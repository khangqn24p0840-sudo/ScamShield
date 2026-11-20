package vn.scamshield

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
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
    val conversationTitle: String = "",
    val riskScore: Int = 0,
    val riskLevel: String = "UNKNOWN",
    val riskReasons: List<String> = emptyList()
)

data class ConversationAnalysisResult(
    val targetId: String,
    val conversationKey: String,
    val riskScore: Int,
    val riskLevel: String,
    val summary: String,
    val evidence: List<String>,
    val recommendedAction: String,
    val confidence: String,
    val analyzedAt: Long,
    val messageCount: Int,
    val lastMessageTimestamp: Long
)

/** Lưu nội dung gốc trên thiết bị; chỉ đồng bộ tối đa 12 tin gần nhất khi người dùng bật đồng ý phân tích ngữ cảnh. */
object MonitorStore {
    private const val MAX_STORED_MESSAGES = 10000

    private class MonitorDb(context: Context) :
        SQLiteOpenHelper(context, "scamshield_message_monitor.db", null, 6) {
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
                    conversation_title TEXT NOT NULL DEFAULT '',
                    risk_score INTEGER NOT NULL DEFAULT 0,
                    risk_level TEXT NOT NULL DEFAULT 'UNKNOWN',
                    risk_reasons TEXT NOT NULL DEFAULT '[]'
                )"""
            )
            db.execSQL("CREATE INDEX idx_monitor_target_time ON messages(target_id, timestamp)")
            db.execSQL("CREATE INDEX idx_monitor_conversation_time ON messages(target_id, conversation_key, timestamp)")
            db.execSQL("CREATE INDEX idx_monitor_time ON messages(timestamp)")
            createConversationAnalysisTable(db)
            createConversationMemoryTable(db)
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
            if (oldVersion < 4) createConversationAnalysisTable(db)
            if (oldVersion < 5) createConversationMemoryTable(db)
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE messages ADD COLUMN risk_score INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN risk_level TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE messages ADD COLUMN risk_reasons TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun createConversationMemoryTable(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS conversation_memory (
                    target_id TEXT NOT NULL,
                    conversation_key TEXT NOT NULL,
                    memory_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_message_timestamp INTEGER NOT NULL,
                    PRIMARY KEY(target_id, conversation_key)
                )"""
            )
        }

        private fun createConversationAnalysisTable(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS conversation_analysis (
                    target_id TEXT NOT NULL,
                    conversation_key TEXT NOT NULL,
                    risk_score INTEGER NOT NULL,
                    risk_level TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    evidence TEXT NOT NULL,
                    recommended_action TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    analyzed_at INTEGER NOT NULL,
                    message_count INTEGER NOT NULL,
                    last_message_timestamp INTEGER NOT NULL,
                    PRIMARY KEY(target_id, conversation_key)
                )"""
            )
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
        val inserted = db(c).insertWithOnConflict("targets", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) ChatAccessibilityService.refreshPackageFilter(c)
        return inserted
    }

    @Synchronized
    fun removeTarget(c: Context, targetId: String) {
        // Hủy mọi lần gửi nền thuộc đối tượng trước khi xóa dữ liệu cục bộ.
        ConversationAiMonitor.cancelTarget(c, targetId)
        val database = db(c)
        database.beginTransaction()
        try {
            database.delete("messages", "target_id=?", arrayOf(targetId))
            database.delete("conversation_analysis", "target_id=?", arrayOf(targetId))
            database.delete("conversation_memory", "target_id=?", arrayOf(targetId))
            database.delete("targets", "id=?", arrayOf(targetId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        ChatAccessibilityService.refreshPackageFilter(c)
    }

    private fun assessUnknownMessages(database: SQLiteDatabase, targetId: String, limit: Int = 500) {
        val pending = mutableListOf<Pair<String, String>>()
        database.rawQuery(
            "SELECT id,body FROM messages WHERE target_id=? AND risk_level='UNKNOWN' ORDER BY timestamp DESC LIMIT ?",
            arrayOf(targetId, limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) pending += cursor.getString(0) to cursor.getString(1)
        }
        pending.forEach { (id, body) ->
            val assessment = ScamDetector.analyze(body)
            val values = ContentValues().apply {
                put("risk_score", assessment.score.coerceIn(0, 100))
                put("risk_level", when (assessment.level) {
                    2 -> "DANGER"
                    1 -> "SUSPICIOUS"
                    else -> "SAFE"
                })
                put("risk_reasons", JSONArray(assessment.reasons.take(8)).toString())
            }
            database.update("messages", values, "id=?", arrayOf(id))
        }
    }

    fun messages(c: Context, targetId: String? = null): List<MonitoredMessage> {
        val result = mutableListOf<MonitoredMessage>()
        val database = db(c)
        if (targetId != null) assessUnknownMessages(database, targetId)
        val cursor = if (targetId == null) {
            database.rawQuery(
                "SELECT id,target_id,channel,sender,body,timestamp,app_package,conversation_key,conversation_title,risk_score,risk_level,risk_reasons FROM messages ORDER BY timestamp ASC",
                null
            )
        } else {
            database.rawQuery(
                "SELECT id,target_id,channel,sender,body,timestamp,app_package,conversation_key,conversation_title,risk_score,risk_level,risk_reasons FROM messages WHERE target_id=? ORDER BY timestamp ASC",
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
                    conversationTitle = it.getString(8),
                    riskScore = it.getInt(9),
                    riskLevel = it.getString(10),
                    riskReasons = runCatching { JSONArray(it.getString(11)) }.getOrElse { JSONArray() }
                        .let { array -> (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) } }
                )
            }
        }
        return result
    }

    @Synchronized
    private fun saveMessage(c: Context, item: MonitoredMessage, assess: Boolean = true): Boolean {
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
            // Đánh giá ngay trên thiết bị và lưu cùng tin nhắn; không tải nội dung lên server tại đây.
            val assessment = if (assess) ScamDetector.analyze(item.body) else null
            put("risk_score", assessment?.score?.coerceIn(0, 100) ?: 0)
            put("risk_level", when (assessment?.level) {
                2 -> "DANGER"
                1 -> "SUSPICIOUS"
                0 -> "SAFE"
                else -> "UNKNOWN"
            })
            put("risk_reasons", JSONArray(assessment?.reasons?.take(8) ?: emptyList<String>()).toString())
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

    private fun hasRecentDuplicate(
        c: Context,
        targetId: String,
        conversationKey: String,
        body: String,
        withinMs: Long = 120_000L
    ): Boolean {
        val since = System.currentTimeMillis() - withinMs
        return db(c).rawQuery(
            "SELECT 1 FROM messages WHERE target_id=? AND conversation_key=? AND body=? AND timestamp>=? LIMIT 1",
            arrayOf(targetId, conversationKey, body, since.toString())
        ).use { it.moveToFirst() }
    }

    fun captureSms(c: Context, sender: String, body: String, timestamp: Long): Boolean {
        val normalized = Store.digits(sender)
        val target = targets(c).firstOrNull {
            it.channel == "sms" && normalized.length >= 7 &&
                Store.digits(it.identifier) == normalized
        } ?: return false
        val conversationKey = "sms|" + normalized
        val inserted = saveMessage(c, MonitoredMessage(
            "", target.id, "sms", sender, body, timestamp,
            conversationKey = conversationKey, conversationTitle = sender
        ))
        if (inserted) ConversationAiMonitor.schedule(c, target.id, conversationKey)
        return true
    }

    fun captureSocial(
        c: Context,
        sender: String,
        body: String,
        packageName: String,
        timestamp: Long,
        conversationTitle: String = "",
        conversationKey: String = ""
    ): String? {
        val senderName = sender.trim().lowercase()
        val threadName = conversationTitle.trim().lowercase()
        if (senderName.isBlank() && threadName.isBlank()) return null
        val target = targets(c).firstOrNull { candidate ->
            if (candidate.channel != "social" ||
                (candidate.appPackage.isNotBlank() && candidate.appPackage != packageName)
            ) return@firstOrNull false
            val wanted = candidate.identifier.trim().lowercase()
            // Match chính xác tên người gửi hoặc tên cuộc trò chuyện; tránh bắt nhầm tên gần giống.
            wanted == senderName || wanted == threadName
        } ?: return null
        val stableKey = conversationKey.trim().ifBlank {
            packageName + "|" + conversationTitle.ifBlank { sender }
        }
        if (hasRecentDuplicate(c, target.id, stableKey, body)) return null
        val inserted = saveMessage(c, MonitoredMessage(
            "", target.id, "social", sender.ifBlank { conversationTitle }, body, timestamp,
            packageName, stableKey, conversationTitle
        ))
        return if (inserted) target.id else null
    }

    /** Lưu tin đã đọc từ bong bóng chat đang hiển thị, chỉ cho target social + package khớp. */
    fun captureScreenSocial(
        c: Context,
        targetId: String,
        body: String,
        packageName: String,
        timestamp: Long,
        conversationTitle: String,
        conversationKey: String
    ): Boolean {
        val target = targets(c).firstOrNull {
            it.id == targetId && it.channel == "social" && it.appPackage == packageName
        } ?: return false
        if (hasRecentDuplicate(c, target.id, conversationKey, body)) return false
        val inserted = saveMessage(c, MonitoredMessage(
            "", target.id, "screen", "Tin đến · ${target.identifier}", body, timestamp,
            packageName, conversationKey, conversationTitle
        ))
        if (inserted) ConversationAiMonitor.schedule(c, target.id, conversationKey)
        return inserted
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
                    ), assess = imported < 500)) imported++
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
            """SELECT id,target_id,channel,sender,body,timestamp,app_package,conversation_key,conversation_title,risk_score,risk_level,risk_reasons
               FROM messages WHERE target_id=? AND conversation_key=? ORDER BY timestamp DESC LIMIT ?""",
            arrayOf(targetId, threadKey, limit.coerceIn(1, 50).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonitoredMessage(
                    id = cursor.getString(0), targetId = cursor.getString(1),
                    channel = cursor.getString(2), sender = cursor.getString(3),
                    body = cursor.getString(4), timestamp = cursor.getLong(5),
                    appPackage = cursor.getString(6), conversationKey = cursor.getString(7),
                    conversationTitle = cursor.getString(8),
                    riskScore = cursor.getInt(9),
                    riskLevel = cursor.getString(10),
                    riskReasons = runCatching { JSONArray(cursor.getString(11)) }.getOrElse { JSONArray() }
                        .let { array -> (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) } }
                )
            }
        }
        return result.asReversed()
    }

    /** Transcript ngắn, có thứ tự thời gian, chỉ từ cuộc trò chuyện gần nhất. */
    fun contextualText(c: Context, targetId: String, maxMessages: Int = 10, maxChars: Int = 1700): String {
        val key = latestConversationKey(c, targetId) ?: return ""
        return contextualText(c, targetId, key, maxMessages, maxChars)
    }

    fun latestConversationKey(c: Context, targetId: String): String? =
        db(c).rawQuery(
            "SELECT conversation_key FROM messages WHERE target_id=? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(targetId)
        ).use { if (it.moveToFirst()) it.getString(0).takeIf(String::isNotBlank) else null }

    /** Các thread đã ghi nhận của một đối tượng, dùng để xóa trạng thái hàng đợi khi bỏ theo dõi. */
    fun conversationKeys(c: Context, targetId: String): List<String> {
        val result = mutableListOf<String>()
        db(c).rawQuery(
            "SELECT DISTINCT conversation_key FROM messages WHERE target_id=?",
            arrayOf(targetId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        return result
    }

    /** Trả về transcript của một thread xác định; không trộn các chat khác cùng người gửi. */
    fun contextualText(
        c: Context,
        targetId: String,
        conversationKey: String,
        maxMessages: Int = 12,
        maxChars: Int = 2200
    ): String {
        val transcript = recentMessages(c, targetId, conversationKey, maxMessages).joinToString("\n") { item ->
            val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))
            "[$time] " + item.sender + ": " + item.body.take(320)
        }
        return if (transcript.length > maxChars) "…\n" + transcript.takeLast(maxChars - 2) else transcript
    }

    fun recentMessages(
        c: Context,
        targetId: String,
        conversationKey: String,
        limit: Int = 12
    ): List<MonitoredMessage> {
        val result = mutableListOf<MonitoredMessage>()
        db(c).rawQuery(
            """SELECT id,target_id,channel,sender,body,timestamp,app_package,conversation_key,conversation_title,risk_score,risk_level,risk_reasons
               FROM messages
               WHERE target_id=? AND conversation_key=?
               ORDER BY timestamp DESC LIMIT ?""",
            arrayOf(targetId, conversationKey, limit.coerceIn(1, 50).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonitoredMessage(
                    id = cursor.getString(0), targetId = cursor.getString(1),
                    channel = cursor.getString(2), sender = cursor.getString(3),
                    body = cursor.getString(4), timestamp = cursor.getLong(5),
                    appPackage = cursor.getString(6), conversationKey = cursor.getString(7),
                    conversationTitle = cursor.getString(8),
                    riskScore = cursor.getInt(9),
                    riskLevel = cursor.getString(10),
                    riskReasons = runCatching { JSONArray(cursor.getString(11)) }.getOrElse { JSONArray() }
                        .let { array -> (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) } }
                )
            }
        }
        return result.asReversed()
    }

    /** Bộ nhớ ngữ cảnh được lưu trên thiết bị, phân tách theo đúng target và thread. */
    fun conversationMemory(c: Context, targetId: String, conversationKey: String): JSONObject? =
        db(c).rawQuery(
            "SELECT memory_json FROM conversation_memory WHERE target_id=? AND conversation_key=?",
            arrayOf(targetId, conversationKey)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else runCatching { JSONObject(cursor.getString(0)) }.getOrNull()
        }

    fun saveConversationMemory(
        c: Context,
        targetId: String,
        conversationKey: String,
        memory: JSONObject,
        lastMessageTimestamp: Long
    ) {
        val bounded = JSONObject().apply {
            put("summary", memory.optString("summary").take(1200))
            put("key_facts", boundedMemoryArray(memory.optJSONArray("key_facts"), 8, 180))
            put("behavior_patterns", boundedMemoryArray(memory.optJSONArray("behavior_patterns"), 8, 180))
            put("open_questions", boundedMemoryArray(memory.optJSONArray("open_questions"), 5, 160))
        }
        val values = ContentValues().apply {
            put("target_id", targetId)
            put("conversation_key", conversationKey)
            put("memory_json", bounded.toString())
            put("updated_at", System.currentTimeMillis())
            put("last_message_timestamp", lastMessageTimestamp)
        }
        db(c).insertWithOnConflict(
            "conversation_memory", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun boundedMemoryArray(source: JSONArray?, limit: Int, maxChars: Int): JSONArray {
        val result = JSONArray()
        if (source == null) return result
        for (index in 0 until minOf(source.length(), limit)) {
            val value = source.optString(index).trim().take(maxChars)
            if (value.isNotBlank()) result.put(value)
        }
        return result
    }

    fun saveConversationAnalysis(
        c: Context,
        result: ConversationAnalysisResult
    ): Int? {
        val database = db(c)
        val previousScore = database.rawQuery(
            "SELECT risk_score FROM conversation_analysis WHERE target_id=? AND conversation_key=?",
            arrayOf(result.targetId, result.conversationKey)
        ).use { if (it.moveToFirst()) it.getInt(0) else null }
        val values = ContentValues().apply {
            put("target_id", result.targetId)
            put("conversation_key", result.conversationKey)
            put("risk_score", result.riskScore.coerceIn(0, 100))
            put("risk_level", result.riskLevel.take(12))
            put("summary", result.summary.take(600))
            put("evidence", JSONArray(result.evidence.take(5)).toString())
            put("recommended_action", result.recommendedAction.take(500))
            put("confidence", result.confidence.take(12))
            put("analyzed_at", result.analyzedAt)
            put("message_count", result.messageCount.coerceIn(0, 10000))
            put("last_message_timestamp", result.lastMessageTimestamp)
        }
        database.insertWithOnConflict(
            "conversation_analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        return previousScore
    }

    fun conversationAnalysis(
        c: Context,
        targetId: String,
        conversationKey: String
    ): ConversationAnalysisResult? =
        db(c).rawQuery(
            """SELECT risk_score,risk_level,summary,evidence,recommended_action,confidence,
                      analyzed_at,message_count,last_message_timestamp
               FROM conversation_analysis WHERE target_id=? AND conversation_key=?""",
            arrayOf(targetId, conversationKey)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val evidenceJson = runCatching { JSONArray(cursor.getString(3)) }.getOrElse { JSONArray() }
            ConversationAnalysisResult(
                targetId = targetId,
                conversationKey = conversationKey,
                riskScore = cursor.getInt(0),
                riskLevel = cursor.getString(1),
                summary = cursor.getString(2),
                evidence = (0 until evidenceJson.length()).mapNotNull { i ->
                    evidenceJson.optString(i).takeIf { it.isNotBlank() }
                },
                recommendedAction = cursor.getString(4),
                confidence = cursor.getString(5),
                analyzedAt = cursor.getLong(6),
                messageCount = cursor.getInt(7),
                lastMessageTimestamp = cursor.getLong(8)
            )
        }

    fun latestConversationAnalysis(c: Context, targetId: String): ConversationAnalysisResult? {
        val key = latestConversationKey(c, targetId) ?: return null
        return conversationAnalysis(c, targetId, key)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
