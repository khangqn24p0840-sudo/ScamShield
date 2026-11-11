package vn.scamshield

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

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

/**
 * Lưu cục bộ các mục tiêu và tin nhắn người dùng chủ động chọn theo dõi.
 * Không đồng bộ nội dung tin nhắn lên máy chủ.
 */
object MonitorStore {
    private const val PREFS = "message_monitor"
    private const val TARGETS = "targets"
    private const val MESSAGES = "messages"
    private const val MAX_MESSAGES = 300

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun targets(c: Context): List<MonitoredTarget> {
        val a = JSONArray(prefs(c).getString(TARGETS, "[]"))
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val channel = o.optString("channel")
            val identifier = o.optString("identifier")
            if (id.isBlank() || channel.isBlank() || identifier.isBlank()) null
            else MonitoredTarget(id, channel, identifier, o.optLong("createdAt"))
        }
    }

    @Synchronized
    fun addTarget(c: Context, channel: String, identifier: String): Boolean {
        val value = identifier.trim()
        if (value.isBlank()) return false
        val normalized = if (channel == "sms") Store.digits(value) else value.lowercase().trim()
        if (normalized.isBlank()) return false
        if (targets(c).any {
                it.channel == channel &&
                    (if (channel == "sms") Store.digits(it.identifier) == normalized
                     else it.identifier.equals(value, ignoreCase = true))
            }) return false
        val id = channel + "_" + normalized.hashCode().toUInt().toString(16)
        val a = JSONArray()
        targets(c).forEach { a.put(JSONObject().put("id", it.id).put("channel", it.channel)
            .put("identifier", it.identifier).put("createdAt", it.createdAt)) }
        a.put(JSONObject().put("id", id).put("channel", channel).put("identifier", value)
            .put("createdAt", System.currentTimeMillis()))
        prefs(c).edit().putString(TARGETS, a.toString()).apply()
        return true
    }

    @Synchronized
    fun removeTarget(c: Context, targetId: String) {
        val remaining = JSONArray()
        targets(c).filterNot { it.id == targetId }.forEach {
            remaining.put(JSONObject().put("id", it.id).put("channel", it.channel)
                .put("identifier", it.identifier).put("createdAt", it.createdAt))
        }
        val keptMessages = JSONArray()
        messages(c).filterNot { it.targetId == targetId }.forEach { keptMessages.put(it.toJson()) }
        prefs(c).edit().putString(TARGETS, remaining.toString())
            .putString(MESSAGES, keptMessages.toString()).apply()
    }

    @Synchronized
    fun messages(c: Context, targetId: String? = null): List<MonitoredMessage> {
        val a = JSONArray(prefs(c).getString(MESSAGES, "[]"))
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val item = MonitoredMessage(
                id = o.optString("id"), targetId = o.optString("targetId"),
                channel = o.optString("channel"), sender = o.optString("sender"),
                body = o.optString("body"), timestamp = o.optLong("timestamp"),
                appPackage = o.optString("appPackage")
            )
            if (item.id.isBlank() || item.body.isBlank() || (targetId != null && item.targetId != targetId)) null else item
        }.sortedBy { it.timestamp }
    }

    @Synchronized
    private fun saveMessage(c: Context, item: MonitoredMessage): Boolean {
        if (item.body.isBlank()) return false
        val old = messages(c)
        val key = item.channel + "|" + item.targetId + "|" + item.timestamp + "|" + item.body.hashCode()
        if (old.any { it.id == key }) return false
        val next = (old + item.copy(id = key, body = item.body.take(4000))).takeLast(MAX_MESSAGES)
        val a = JSONArray()
        next.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString(MESSAGES, a.toString()).apply()
        return true
    }

    fun captureSms(c: Context, sender: String, body: String, timestamp: Long) {
        val target = targets(c).firstOrNull {
            it.channel == "sms" && Store.digits(it.identifier).length >= 7 &&
                Store.digits(it.identifier) == Store.digits(sender)
        } ?: return
        saveMessage(c, MonitoredMessage("", target.id, "sms", sender, body, timestamp))
    }

    fun captureSocial(c: Context, sender: String, body: String, packageName: String, timestamp: Long, appName: String) {
        val s = sender.trim().lowercase()
        val app = appName.trim().lowercase()
        val target = targets(c).firstOrNull {
            it.channel == "social" && (
                s == it.identifier.trim().lowercase() ||
                (it.identifier.length >= 3 && s.contains(it.identifier.trim().lowercase())) ||
                (it.identifier.length >= 3 && app == it.identifier.trim().lowercase())
            )
        } ?: return
        saveMessage(c, MonitoredMessage("", target.id, "social", sender, body, timestamp, packageName))
    }

    /** Nhập lịch sử SMS của đúng số đã chọn; không đọc SMS của các số khác vào bộ theo dõi. */
    fun importSmsHistory(c: Context, targetId: String, limit: Int = 300): Int {
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
            "${Telephony.Sms.ADDRESS} LIKE ?",
            arrayOf("%$digits%"),
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

    fun contextualText(c: Context, targetId: String, maxMessages: Int = 20): String =
        messages(c, targetId).takeLast(maxMessages).joinToString("\n\n") { item ->
            val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))
            "[$time] ${item.sender}: ${item.body}"
        }

    private fun MonitoredMessage.toJson() = JSONObject()
        .put("id", id).put("targetId", targetId).put("channel", channel)
        .put("sender", sender).put("body", body).put("timestamp", timestamp)
        .put("appPackage", appPackage)
}
