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
    val createdAt: Long,
    val appPackage: String = ""
)

data class MonitoredMessage(
    val id: String,
    val targetId: String,
    val channel: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val appPackage: String = "",
    val conversationKey: String = "",
    val conversationTitle: String = ""
)

/** Lưu riêng trên thiết bị. Không tự đồng bộ nội dung tin nhắn lên máy chủ. */
object MonitorStore {
    private const val MAX_STORED_MESSAGES = 10000

    private class MonitorDb(context: Context) :
        SQLiteOpenHelper(context, "scamshield_message_monitor.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE targets (
                    id TEXT PRIMARY KEY NOT NULL,
                    channel TEXT NOT NULL,
                    identifier TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    app_package TEXT NOT NULL DEFAULT ''
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
                    app_package TEXT NOT NULL DEFAULT '',
                    conversation_key TEXT NOT NULL DEFAULT '',
                    conversation_title TEXT NOT NULL DEFAULT ''
                )"""
            )
            db.execSQL("CREATE INDEX idx_monitor_target_time ON messages(target_id, timestamp)")
            db.execSQL("CREATE INDEX idx_monitor_conversation_time ON messages(target_id, conversation_key, timestamp)")
            db.execSQL("CREATE INDEX idx_monitor_time ON messages(timestamp)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE targets ADD COLUMN app_package TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE messages ADD COLUMN conversation_key TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN conversation_title TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE messages SET conversation_key = channel || ':' || target_id WHERE conversation_key = ''")
                db.execSQL("CREATE INDEX idx_monitor_conversation_time ON messages(target_id, conversation_key, timestamp)")
            }
        }
    }

    @Volatile private var database: MonitorDb? = null
    private fun db(c: Context): SQLiteDatabase =
        synchronized(this) {
            (database ?: MonitorDb(c.applicationContext).also { database = it }).writableDatabase
        }

    fun targets(c: Context): List<MonitoredTarget> {
        val result = mutableListOf<MonitoredTarget>()
        db(c).rawQuery(
            "SELECT id, channel, identifier, created_at, app_package FROM targets ORDER BY created_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonitoredTarget(
                    cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3), cursor.getString(4)
                )
            }
        }
        return result
    }

    @Synchronized
    fun addTarget(c: Context, channel: String, identifier: String, appPackage: String = ""): Boolean {
        val value = identifier.trim()
        if (value.isBlank() || channel !in setOf("sms", "social")) return false
        if (channel == "social" && appPackage.isNotBlank() && appPackage !in SocialListener.apps) return false
        val normalized = if (channel == "sms") Store.digits(value) else value.lowercase()
        if (normalized.isBlank()) return false
        if (targets(c).any {
                it.channel == channel &&
                    (if (channel == "sms") Store.digits(it.identifier) == normalized
                     else it.identifier.equals(value, ignoreCase = true) &&
                         (it.appPackage == appPackage || it.appPackage.isBlank() || appPackage.isBlank()))
            }) return false

        val identity = if (channel == "social") "$normalized|$appPackage" else normalized
        val id = channel + "_" + sha256(identity).take(24)
        val values = ContentValues().apply {
            put("id", id)
            put("channel", channel)
            put("identifier", value)
            put("created_at", System.currentTimeMillis())
            put("app_package", if (channel == "social") appPackage else "")
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
                "SELECT id,target_id,channel,sender,body,timestamp,app_package,conversation_key,conversation_title FROM messages ORDER BY timestamp ASC",
                null
            )
        } else {
            database.rawQuery(
                "SELECT id,target_id,channel,sender,body,timestamp,app_package,conversation_key,conversation_title FROM messages WHERE target_id=? ORDER BY timestamp ASC",
                arrayOf(targetId)
            )
        }
        cursor.use {
            while (it.moveToNext()) {
                result += MonitoredMessage(
                    id = it.getString(0), targetId = it.getString(1),
                    channel = it.getString(2), sender = it.getString(3),
                    body = it.getString(4), timestamp = it.getLong(5),
                    appPackage = it.getString(6),
                    conversationKey = it.getString(7),
                    conversationTitle = it.getString(8)
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
                item.sender + "|" + item.body + "|" + item.conversationKey
        )
        val values = ContentValues().apply {
            put("id", key)
            put("target_id", item.targetId)
            put("channel", item.channel)
            put("sender", item.sender.take(180))
            put("body", item.body.take(4000))
            put("timestamp", item.timestamp)
            put("app_package", item.appPackage.take(120))
            put("conversation_key", item.conversationKey.take(300))
            put("conversation_title", item.conversationTitle.take(180))
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
        saveMessage(c, MonitoredMessage(
            "", target.id, "sms", sender, body, timestamp,
            conversationKey = "sms|" + normalized, conversationTitle = sender
        ))
    }

    fun captureSocial(
        c: Context,
        sender: String,
        body: String,
        packageName: String,
        timestamp: Long,
        conversationTitle: String = "",
        conversationKey: String = ""
    ) {
        val senderName = sender.trim().lowercase()
        val threadName = conversationTitle.trim().lowercase()
        if (senderName.isBlank() && threadName.isBlank()) return
        val target = targets(c).firstOrNull { candidate ->
            if (candidate.channel != "social" ||
                (candidate.appPackage.isNotBlank() && candidate.appPackage != packageName)
            ) return@firstOrNull false
            val wanted = candidate.identifier.trim().lowercase()
            // Match chính xác tên người gửi hoặc tên cuộc trò chuyện; tránh bắt nhầm tên gần giống.
            wanted == senderName || wanted == threadName
        } ?: return
        val stableKey = conversationKey.trim().ifBlank {
            packageName + "|" + conversationTitle.ifBlank { sender }
        }
        saveMessage(c, MonitoredMessage(
            "", target.id, "social", sender.ifBlank { conversationTitle }, body, timestamp,
            packageName, stableKey, conversationTitle
        ))
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
                    if (saveMessage(c, MonitoredMessage(
                        "", target.id, "sms", address, body, timestamp,
                        conversationKey = "sms|" + digits, conversationTitle = address
                    ))) imported++
                }
            }
        }
        return imported
    }

    fun messageCount(c: Context, targetId: String): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM messages WHERE target_id=?", arrayOf(targetId)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Chỉ tải tin mới nhất thuộc cùng một thread, tránh trộn hai cuộc trò chuyện có cùng tên. */
    fun recentMessages(c: Context, targetId: String, limit: Int = 3): List<MonitoredMessage> {
        val database = db(c)
        val threadKey = database.rawQuery(
            "SELECT conversation_key FROM messages WHERE target_id=? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(targetId)
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return emptyList()
        val result = mutableListOf<MonitoredMessage>()
        database.rawQuery(
            """SELECT id,target_id,channel,sender,body,timestamp,app_package,conversation_key,conversation_title
               FROM messages WHERE target_id=? AND conversation_key=? ORDER BY timestamp DESC LIMIT ?""",
            arrayOf(targetId, threadKey, limit.coerceIn(1, 50).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonitoredMessage(
                    id = cursor.getString(0), targetId = cursor.getString(1),
                    channel = cursor.getString(2), sender = cursor.getString(3),
                    body = cursor.getString(4), timestamp = cursor.getLong(5),
                    appPackage = cursor.getString(6), conversationKey = cursor.getString(7),
                    conversationTitle = cursor.getString(8)
                )
            }
        }
        return result.asReversed()
    }

    /** Transcript ngắn, có thứ tự thời gian, chỉ từ cuộc trò chuyện gần nhất. */
    fun contextualText(c: Context, targetId: String, maxMessages: Int = 10, maxChars: Int = 1700): String {
        val transcript = recentMessages(c, targetId, maxMessages).joinToString("\n") { item ->
            val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))
            "[$time] " + item.sender + ": " + item.body.take(320)
        }
        return if (transcript.length > maxChars) "…\n" + transcript.takeLast(maxChars - 2) else transcript
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
