package vn.scamshield

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Gemini context analysis for the chat-monitor feature only.
 * It is opt-in, debounced, rate-limited, and stores only the structured summary locally.
 */
object ConversationAiMonitor {
    private const val ENABLED_KEY = "chat_monitor_gemini_auto"
    private const val DEBOUNCE_MS = 15_000L
    private const val THREAD_COOLDOWN_MS = 45_000L
    private const val GLOBAL_COOLDOWN_MS = 5_000L
    private const val MESSAGE_LIMIT = 12
    private const val TRANSCRIPT_CHAR_LIMIT = 4_500

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, Runnable>()

    fun isEnabled(c: Context): Boolean = Store.flag(c, ENABLED_KEY)

    fun setEnabled(c: Context, enabled: Boolean) {
        Store.putFlag(c, ENABLED_KEY, enabled)
        if (!enabled) cancelPending()
    }

    @Synchronized
    fun cancelPending() {
        pending.values.forEach(handler::removeCallbacks)
        pending.clear()
    }

    fun schedule(c: Context, targetId: String, conversationKey: String) {
        val app = c.applicationContext
        if (!isEnabled(app) || Store.str(app, "token").isBlank()) return
        val threadId = threadId(targetId, conversationKey)
        val task = Runnable {
            synchronized(this) { pending.remove(threadId) }
            runAutoAnalysis(app, targetId, conversationKey, threadId)
        }
        synchronized(this) {
            pending.remove(threadId)?.let(handler::removeCallbacks)
            pending[threadId] = task
            handler.postDelayed(task, DEBOUNCE_MS)
        }
    }

    private fun runAutoAnalysis(
        c: Context,
        targetId: String,
        conversationKey: String,
        threadId: String
    ) {
        if (!isEnabled(c) || Store.str(c, "token").isBlank()) return

        val now = System.currentTimeMillis()
        val lastThreadRequest = Store.str(c, "monitor_ai_thread_request_$threadId").toLongOrNull() ?: 0L
        val lastGlobalRequest = Store.str(c, "monitor_ai_global_request").toLongOrNull() ?: 0L
        val remaining = maxOf(
            THREAD_COOLDOWN_MS - (now - lastThreadRequest),
            GLOBAL_COOLDOWN_MS - (now - lastGlobalRequest)
        )
        if (remaining > 0L) {
            val task = Runnable {
                synchronized(this) { pending.remove(threadId) }
                runAutoAnalysis(c, targetId, conversationKey, threadId)
            }
            synchronized(this) {
                pending.remove(threadId)?.let(handler::removeCallbacks)
                pending[threadId] = task
                handler.postDelayed(task, remaining)
            }
            return
        }

        val latest = MonitorStore.recentMessages(c, targetId, conversationKey, MESSAGE_LIMIT)
        if (latest.isEmpty()) return
        val lastTimestamp = latest.last().timestamp
        val lastAnalyzed = Store.str(c, "monitor_ai_last_analyzed_$threadId").toLongOrNull() ?: 0L
        if (lastTimestamp <= lastAnalyzed) return

        Store.putStr(c, "monitor_ai_thread_request_$threadId", now.toString())
        Store.putStr(c, "monitor_ai_global_request", now.toString())
        analyzeNow(c, targetId, conversationKey) { result, _ ->
            if (result != null) {
                Store.putStr(c, "monitor_ai_last_analyzed_$threadId", result.lastMessageTimestamp.toString())
            }
        }
    }

    /** Explicit user-triggered analysis also uses the monitor-only Gemini endpoint. */
    fun analyzeNow(
        c: Context,
        targetId: String,
        conversationKey: String,
        onComplete: (ConversationAnalysisResult?, String?) -> Unit
    ) {
        if (Store.str(c, "token").isBlank()) {
            onComplete(null, "Bạn cần đăng nhập ScamShield để dùng Gemini.")
            return
        }

        val messages = MonitorStore.recentMessages(c, targetId, conversationKey, MESSAGE_LIMIT)
        if (messages.isEmpty()) {
            onComplete(null, "Cuộc trò chuyện chưa có nội dung để phân tích.")
            return
        }

        val payloadMessages = JSONArray()
        messages.forEach { item ->
            val safeText = redactSensitiveText(item.body).take(600)
            if (safeText.isNotBlank()) {
                payloadMessages.put(
                    JSONObject()
                        .put("sender", item.sender.take(100))
                        .put("text", safeText)
                        .put("timestamp", item.timestamp)
                )
            }
        }
        if (payloadMessages.length() == 0) {
            onComplete(null, "Không có nội dung phù hợp để phân tích.")
            return
        }

        Api.call(
            c,
            "POST",
            "/api/monitor/conversation",
            JSONObject().put("messages", payloadMessages)
        ) { code, response ->
            if (code !in 200..299 || response == null) {
                onComplete(
                    null,
                    response?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "Gemini chưa phân tích được cuộc trò chuyện. Hãy thử lại sau."
                )
                return@call
            }

            val evidenceJson = response.optJSONArray("evidence") ?: JSONArray()
            val evidence = (0 until evidenceJson.length()).mapNotNull { index ->
                evidenceJson.optString(index).takeIf { it.isNotBlank() }?.take(180)
            }.take(5)
            val score = response.optInt("risk_score", 0).coerceIn(0, 100)
            val level = when {
                score >= 70 -> "HIGH"
                score >= 35 -> "MEDIUM"
                else -> "LOW"
            }
            val result = ConversationAnalysisResult(
                targetId = targetId,
                conversationKey = conversationKey,
                riskScore = score,
                riskLevel = level,
                summary = response.optString("summary").take(600),
                evidence = evidence,
                recommendedAction = response.optString("recommended_action").take(500),
                confidence = response.optString("confidence", "LOW").uppercase().take(12),
                analyzedAt = System.currentTimeMillis(),
                messageCount = messages.size,
                lastMessageTimestamp = messages.last().timestamp
            )

            val previousScore = MonitorStore.saveConversationAnalysis(c, result)
            notifyOnEscalation(c, result, previousScore)
            onComplete(result, null)
        }
    }

    private fun notifyOnEscalation(
        c: Context,
        result: ConversationAnalysisResult,
        previousScore: Int?
    ) {
        val crossedHigh = result.riskScore >= 70 && (previousScore == null || previousScore < 70)
        val crossedMedium = result.riskScore in 35..69 && (previousScore == null || previousScore < 35)
        if (!crossedHigh && !crossedMedium) return

        val title = if (crossedHigh) {
            "🚨 Gemini phát hiện diễn biến chat rủi ro cao"
        } else {
            "⚠️ Gemini phát hiện dấu hiệu đáng ngờ trong chat"
        }
        val details = buildString {
            append(result.summary.ifBlank { "Cuộc trò chuyện có dấu hiệu cần kiểm tra." })
            if (result.evidence.isNotEmpty()) append(" Bằng chứng: ").append(result.evidence.joinToString("; "))
            if (result.recommendedAction.isNotBlank()) append(" Khuyến nghị: ").append(result.recommendedAction)
        }.take(900)
        Notifier.show(c, title, details)
        Store.log(c, if (crossedHigh) 2 else 1, title, result.summary)
    }

    private fun redactSensitiveText(input: String): String {
        var text = input
        text = text.replace(
            Regex("(?i)(otp|mã\\s*xác\\s*thực|mã\\s*bảo\\s*mật|verification\\s*code)\\s*[:=\\-]?\\s*\\d{4,8}"),
            "\\$1 [đã ẩn]"
        )
        text = text.replace(
            Regex("(?i)(mật\\s*khẩu|password|passcode)\\s*[:=]\\s*[^\\s,;]+"),
            "\\$1: [đã ẩn]"
        )
        text = text.replace(Regex("(?<!\\d)(?:\\+?84|0)\\d{8,10}(?!\\d)"), "[số điện thoại đã ẩn]")
        text = text.replace(Regex("(?<!\\d)\\d{9,19}(?!\\d)"), "[dãy số đã ẩn]")
        return text.take(TRANSCRIPT_CHAR_LIMIT)
    }

    private fun threadId(targetId: String, conversationKey: String): String {
        val raw = "$targetId|$conversationKey"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
