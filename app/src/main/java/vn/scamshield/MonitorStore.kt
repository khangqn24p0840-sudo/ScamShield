package vn.scamshield

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import java.security.MessageDigest

data class MonitoredTarget(
    val id: String,
    val channel: String,
    val identifier: String,
    val createdAt: Long
)

data class MonitoredMessage(
    val id: String,
    val targetId: String,
    val channel: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val appPackage: String = ""
)

/** Lưu riêng trên thiết bị. Không tự đồng bộ nội dung tin nhắn lên máy chủ. */
object MonitorStore {
    private const val MAX_STORED_MESSAGES = 10000

    private class MonitorDb(context: Context) :
        SQLiteOpenHelper(context, "scamshield_message_monitor.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE targets (
                    id TEXT PRIMARY KEY NOT NULL,
                    channel TEXT NOT NULL,
                    identifier TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )"""
            )
            db.execSQL(
                """CREATE TABLE messages (
                    id TEXT PRIMARY KEY NOT NULL,
                    target_id TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    body TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    app_package TEXT NOT NULL DEFAULT ''
                )"""
            )
            db.execSQL("CREATE INDEX idx_monitor_target_time ON messages(target_id, timestamp)")
            db.execSQL("CREATE INDEX idx_monitor_time ON messages(timestamp)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var database: MonitorDb? = null
    private fun db(c: Context): SQLiteDatabase =
        synchronized(this) {
            (database ?: MonitorDb(c.applicationContext).also { database = it }).writableDatabase
        }

    fun targets(c: Context): List<MonitoredTarget> {
        val result = mutableListOf<MonitoredTarget>()
        db(c).rawQuery(
            "SELECT id, channel, identifier, created_at FROM targets ORDER BY created_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonitoredTarget(
                    cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3)
                )
            }
        }
        return result
    }

    @Synchronized
    fun addTarget(c: Context, channel: String, identifier: String): Boolean {
        val value = identifier.trim()
        if (value.isBlank() || channel !in setOf("sms", "social")) return false
        val normalized = if (channel == "sms") Store.digits(value) else value.lowercase()
        if (normalized.isBlank()) return false
        if (targets(c).any {
                it.channel == channel &&
                    (if (channel == "sms") Store.digits(it.identifier) == normalized
                     else it.identifier.equals(value, ignoreCase = true))
            }) return false

        val id = channel + "_" + sha256(normalized).take(24)
        val values = ContentValues().apply {
            put("id", id)
            put("channel", channel)
            put("identifier", value)
            put("created_at", System.currentTimeMillis())
        }
        return db(c).insertWithOnConflict("targets", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized
    fun removeTarget(c: Context, targetId: String) {
        val database = db(c)
        database.beginTransaction()
        try {
            database.delete("messages", "target_id=?", arrayOf(targetId))
            database.delete("targets", "id=?", arrayOf(targetId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun messages(c: Context, targetId: String? = null): List<MonitoredMessage> {
        val result = mutableListOf<MonitoredMessage>()
        val database = db(c)
        val cursor = if (targetId == null) {
            database.rawQuery(
                "SELECT id,target_id,channel,sender,body,timestamp,app_package FROM messages ORDER BY timestamp ASC",
                null
            )
        } else {
            database.rawQuery(
                "SELECT id,target_id,channel,sender,body,timestamp,app_package FROM messages WHERE target_id=? ORDER BY timestamp ASC",
                arrayOf(targetId)
            )
        }
        cursor.use {
            while (it.moveToNext()) {
                result += MonitoredMessage(
                    id = it.getString(0), targetId = it.getString(1),
                    channel = it.getString(2), sender = it.getString(3),
                    body = it.getString(4), timestamp = it.getLong(5),
                    appPackage = it.getString(6)
                )
            }
        }
        return result
    }

    @Synchronized
    private fun saveMessage(c: Context, item: MonitoredMessage): Boolean {
        if (item.body.isBlank()) return false
        val key = sha256(
            item.channel + "|" + item.targetId + "|" + item.timestamp + "|" +
                item.sender + "|" + item.body
        )
        val values = ContentValues().apply {
            put("id", key)
            put("target_id", item.targetId)
            put("channel", item.channel)
            put("sender", item.sender.take(180))
            put("body", item.body.take(4000))
            put("timestamp", item.timestamp)
            put("app_package", item.appPackage.take(120))
        }
        val database = db(c)
        val inserted = database.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        database.execSQL(
            "DELETE FROM messages WHERE id IN (SELECT id FROM messages ORDER BY timestamp DESC LIMIT -1 OFFSET ?)",
            arrayOf(MAX_STORED_MESSAGES)
        )
        return inserted
    }

    fun captureSms(c: Context, sender: String, body: String, timestamp: Long) {
        val normalized = Store.digits(sender)
        val target = targets(c).firstOrNull {
            it.channel == "sms" && normalized.length >= 7 &&
                Store.digits(it.identifier) == normalized
        } ?: return
        saveMessage(c, MonitoredMessage("", target.id, "sms", sender, body, timestamp))
    }

    fun captureSocial(c: Context, sender: String, body: String, packageName: String, timestamp: Long) {
        val s = sender.trim().lowercase()
        if (s.isBlank()) return
        val target = targets(c).firstOrNull {
            it.channel == "social" && (
                s == it.identifier.trim().lowercase() ||
                (it.identifier.trim().length >= 3 && s.contains(it.identifier.trim().lowercase()))
            )
        } ?: return
        saveMessage(c, MonitoredMessage("", target.id, "social", sender, body, timestamp, packageName))
    }

    /** Nhập SMS đến của đúng số đã chọn. Quyền READ_SMS chỉ được hỏi khi người dùng bấm nhập. */
    fun importSmsHistory(c: Context, targetId: String, limit: Int = 10000): Int {
        if (c.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Chưa cấp quyền đọc lịch sử SMS.")
        }
        val target = targets(c).firstOrNull { it.id == targetId && it.channel == "sms" }
            ?: return 0
        val digits = Store.digits(target.identifier)
        if (digits.length < 7) return 0

        var imported = 0
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        c.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.ADDRESS} LIKE ?",
            arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), "%$digits%"),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateCol = cursor.getColumnIndex(Telephony.Sms.DATE)
            while (cursor.moveToNext() && imported < limit) {
                val address = if (addressCol >= 0) cursor.getString(addressCol).orEmpty() else ""
                val body = if (bodyCol >= 0) cursor.getString(bodyCol).orEmpty() else ""
                val timestamp = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                if (Store.digits(address) == digits && body.isNotBlank()) {
                    if (saveMessage(c, MonitoredMessage("", target.id, "sms", address, body, timestamp))) imported++
                }
            }
        }
        return imported
    }

    fun contextualText(c: Context, targetId: String, maxMessages: Int = 30): String =
        messages(c, targetId).takeLast(maxMessages).joinToString("\n\n") { item ->
            val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))
            "[$time] ${item.sender}: ${item.body}"
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
