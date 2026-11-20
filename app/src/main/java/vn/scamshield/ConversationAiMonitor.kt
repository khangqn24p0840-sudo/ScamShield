package vn.scamshield

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Phân tích ngữ cảnh cho tính năng Theo dõi chat.
 * Chỉ chạy sau khi người dùng bật đồng ý; tin nhắn và hàng đợi được giữ trên thiết bị.
 */
object ConversationAiMonitor {
    private const val ENABLED_KEY = "chat_monitor_gemini_auto"
    private const val DEBOUNCE_MS = 15_000L
    private const val THREAD_COOLDOWN_MS = 45_000L
    private const val GLOBAL_COOLDOWN_MS = 5_000L
    private const val MESSAGE_LIMIT = 12
    private const val TRANSCRIPT_CHAR_LIMIT = 4_500
    private const val WORK_TAG = "scamshield-conversation-ai"
    private const val RETRY_DELAY_SECONDS = 25L
    private const val PENDING_PREFIX = "monitor_ai_pending_"
    private const val ERROR_PREFIX = "monitor_ai_pending_error_"

    enum class QueueResult { DONE, RETRY }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, Runnable>()

    fun isEnabled(c: Context): Boolean = Store.flag(c, ENABLED_KEY)

    fun setEnabled(c: Context, enabled: Boolean) {
        Store.putFlag(c, ENABLED_KEY, enabled)
        // Đồng bộ trạng thái đồng ý để trang quản trị biết tài khoản có cho phép theo dõi chat hay không.
        if (Store.str(c, "token").isNotBlank()) {
            Api.call(c, "POST", "/api/monitor/consent", JSONObject().put("enabled", enabled)) { _, _ -> }
        }
        if (!enabled) {
            cancelPending()
            WorkManager.getInstance(c.applicationContext).cancelAllWorkByTag(WORK_TAG)
            MonitorStore.targets(c).forEach { target ->
                MonitorStore.latestConversationKey(c, target.id)?.let { key ->
                    setPending(c, target.id, key, false)
                }
            }
        }
    }

    @Synchronized
    fun cancelPending() {
        pending.values.forEach(handler::removeCallbacks)
        pending.clear()
    }

    fun isPending(c: Context, targetId: String, conversationKey: String): Boolean =
        Store.flag(c, PENDING_PREFIX + threadId(targetId, conversationKey))

    fun pendingError(c: Context, targetId: String, conversationKey: String): String =
        Store.str(c, ERROR_PREFIX + threadId(targetId, conversationKey))

    fun schedule(c: Context, targetId: String, conversationKey: String) {
        val app = c.applicationContext
        if (!isEnabled(app) || Store.str(app, "token").isBlank()) return
        val id = threadId(targetId, conversationKey)
        val task = Runnable {
            synchronized(this) { pending.remove(id) }
            runAutoAnalysis(app, targetId, conversationKey)
        }
        synchronized(this) {
            pending.remove(id)?.let(handler::removeCallbacks)
            pending[id] = task
            handler.postDelayed(task, DEBOUNCE_MS)
        }
    }

    private fun runAutoAnalysis(c: Context, targetId: String, conversationKey: String) {
        if (!isEnabled(c) || Store.str(c, "token").isBlank()) return
        if (MonitorStore.targets(c).none { it.id == targetId }) return

        val now = System.currentTimeMillis()
        val threadId = threadId(targetId, conversationKey)
        val lastThreadRequest = Store.str(c, "monitor_ai_thread_request_$threadId").toLongOrNull() ?: 0L
        val lastGlobalRequest = Store.str(c, "monitor_ai_global_request").toLongOrNull() ?: 0L
        val remaining = maxOf(
            THREAD_COOLDOWN_MS - (now - lastThreadRequest),
            GLOBAL_COOLDOWN_MS - (now - lastGlobalRequest)
        )
        if (remaining > 0L) {
            val task = Runnable {
                synchronized(this) { pending.remove(threadId) }
                runAutoAnalysis(c, targetId, conversationKey)
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

        // Chỉ đánh dấu thời điểm yêu cầu; mốc đã phân tích chỉ được cập nhật sau khi server trả kết quả hợp lệ.
        Store.putStr(c, "monitor_ai_thread_request_$threadId", now.toString())
        Store.putStr(c, "monitor_ai_global_request", now.toString())
        enqueue(c, targetId, conversationKey)
    }

    /** Hàng đợi có thể được gọi sau lần gửi thủ công thất bại, nhưng chỉ khi người dùng đã bật tự động. */
    fun queueForRetryIfEnabled(c: Context, targetId: String, conversationKey: String) {
        if (!isEnabled(c) || Store.str(c, "token").isBlank()) return
        if (MonitorStore.targets(c).none { it.id == targetId }) return
        enqueue(c.applicationContext, targetId, conversationKey)
    }

    private fun enqueue(c: Context, targetId: String, conversationKey: String) {
        setPending(c, targetId, conversationKey, true)
        val request = buildRequest(c, targetId, conversationKey, 0L)
        WorkManager.getInstance(c).enqueueUniqueWork(
            uniqueWorkName(targetId, conversationKey),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Worker gọi lại chính nó sau một khoảng cố định khi backend chưa hoạt động. */
    fun enqueueRetry(c: Context, targetId: String, conversationKey: String) {
        if (!isEnabled(c) || Store.str(c, "token").isBlank()) {
            setPending(c, targetId, conversationKey, false, "Cần bật lại tính năng và đăng nhập để gửi phân tích.")
            return
        }
        if (MonitorStore.targets(c).none { it.id == targetId }) {
            setPending(c, targetId, conversationKey, false)
            return
        }
        val request = buildRequest(c, targetId, conversationKey, RETRY_DELAY_SECONDS)
        WorkManager.getInstance(c).enqueueUniqueWork(
            uniqueWorkName(targetId, conversationKey),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun buildRequest(
        c: Context,
        targetId: String,
        conversationKey: String,
        delaySeconds: Long
    ): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        return OneTimeWorkRequestBuilder<ConversationAnalysisWorker>()
            .setInputData(
                workDataOf(
                    ConversationAnalysisWorker.KEY_TARGET_ID to targetId,
                    ConversationAnalysisWorker.KEY_CONVERSATION_KEY to conversationKey
                )
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag(targetWorkTag(targetId))
            .apply {
                if (delaySeconds > 0L) setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            }
            .build()
    }

    fun cancelTarget(c: Context, targetId: String) {
        WorkManager.getInstance(c.applicationContext).cancelAllWorkByTag(targetWorkTag(targetId))
        MonitorStore.conversationKeys(c, targetId).forEach { key ->
            setPending(c, targetId, key, false)
        }
    }

    /** Xử lý đồng bộ bên trong Worker; không chặn luồng giao diện. */
    fun processQueued(c: Context, targetId: String, conversationKey: String): QueueResult {
        if (!isEnabled(c) || Store.str(c, "token").isBlank()) {
            setPending(c, targetId, conversationKey, false, "Cần đăng nhập và bật Theo dõi chat để tiếp tục.")
            return QueueResult.DONE
        }
        if (MonitorStore.targets(c).none { it.id == targetId }) {
            setPending(c, targetId, conversationKey, false)
            return QueueResult.DONE
        }

        val messages = MonitorStore.recentMessages(c, targetId, conversationKey, MESSAGE_LIMIT)
        if (messages.isEmpty()) {
            setPending(c, targetId, conversationKey, false)
            return QueueResult.DONE
        }

        val id = threadId(targetId, conversationKey)
        val lastTimestamp = messages.last().timestamp
        val lastAnalyzed = Store.str(c, "monitor_ai_last_analyzed_$id").toLongOrNull() ?: 0L
        if (lastTimestamp <= lastAnalyzed) {
            setPending(c, targetId, conversationKey, false)
            return QueueResult.DONE
        }

        val payload = buildPayload(c, targetId, conversationKey, messages)
        if (payload == null) {
            setPending(c, targetId, conversationKey, false, "Không có nội dung phù hợp để phân tích.")
            return QueueResult.DONE
        }

        val (code, response) = Api.callBlocking(
            c, "POST", "/api/monitor/conversation", payload
        )
        if (code !in 200..299 || response == null) {
            val error = response?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "Chưa kết nối được máy chủ ScamShield."
            val retryable = isRetryable(code) || (code in 200..299 && response == null)
            setPending(c, targetId, conversationKey, retryable, error)
            return if (retryable) QueueResult.RETRY else QueueResult.DONE
        }
        if (!response.has("risk_score") || !response.has("summary")) {
            setPending(c, targetId, conversationKey, true, "Máy chủ chưa trả về kết quả phân tích hợp lệ.")
            return QueueResult.RETRY
        }

        val result = persistResponse(c, targetId, conversationKey, messages, response)
        if (result == null) {
            setPending(c, targetId, conversationKey, true, "Phản hồi phân tích chưa đầy đủ; sẽ thử lại.")
            return QueueResult.RETRY
        }

        Store.putStr(c, "monitor_ai_last_analyzed_$id", result.lastMessageTimestamp.toString())
        setPending(c, targetId, conversationKey, false)
        return QueueResult.DONE
    }

    /** Phân tích do người dùng bấm nút; nếu lỗi mạng và đã bật tự động thì xếp hàng gửi lại. */
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
        val payload = buildPayload(c, targetId, conversationKey, messages)
        if (payload == null) {
            onComplete(null, "Không có nội dung phù hợp để phân tích.")
            return
        }

        Api.call(c, "POST", "/api/monitor/conversation", payload) { code, response ->
            if (code !in 200..299 || response == null) {
                val error = response?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Gemini chưa phân tích được cuộc trò chuyện. Hãy thử lại sau."
                if (isRetryable(code) && isEnabled(c)) {
                    queueForRetryIfEnabled(c, targetId, conversationKey)
                    onComplete(null, "Máy chủ đang tạm thời không phản hồi. Đã lưu yêu cầu trên thiết bị và sẽ tự gửi lại khi máy chủ hoạt động.")
                } else {
                    onComplete(null, error)
                }
                return@call
            }

            val result = persistResponse(c, targetId, conversationKey, messages, response)
            if (result == null) {
                if (isEnabled(c)) {
                    queueForRetryIfEnabled(c, targetId, conversationKey)
                    onComplete(null, "Máy chủ chưa trả kết quả hợp lệ. Yêu cầu đã được xếp hàng để tự thử lại.")
                } else {
                    onComplete(null, "Máy chủ chưa trả về kết quả phân tích hợp lệ.")
                }
                return@call
            }
            val id = threadId(targetId, conversationKey)
            Store.putStr(c, "monitor_ai_last_analyzed_$id", result.lastMessageTimestamp.toString())
            setPending(c, targetId, conversationKey, false)
            onComplete(result, null)
        }
    }

    private fun buildPayload(
        c: Context,
        targetId: String,
        conversationKey: String,
        messages: List<MonitoredMessage>
    ): JSONObject? {
        val payloadMessages = JSONArray()
        messages.forEach { item ->
            val safeText = redactSensitiveText(item.body).take(480)
            if (safeText.isNotBlank()) {
                payloadMessages.put(
                    JSONObject()
                        .put("sender", redactSensitiveText(item.sender).take(100))
                        .put("text", safeText)
                        .put("timestamp", item.timestamp)
                        .put("source", item.channel)
                )
            }
        }
        if (payloadMessages.length() == 0) return null
        val previousMemory = MonitorStore.conversationMemory(c, targetId, conversationKey) ?: JSONObject()
        val latest = messages.lastOrNull()
        return JSONObject()
            .put("target_id", targetId)
            .put("conversation_key", conversationKey)
            .put("conversation_title", latest?.conversationTitle.orEmpty())
            .put("app_package", latest?.appPackage.orEmpty())
            .put("messages", payloadMessages)
            .put("memory", previousMemory)
    }

    private fun persistResponse(
        c: Context,
        targetId: String,
        conversationKey: String,
        messages: List<MonitoredMessage>,
        response: JSONObject
    ): ConversationAnalysisResult? {
        if (!response.has("risk_score") || !response.has("summary")) return null
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
        response.optJSONObject("conversation_memory")?.let { memory ->
            MonitorStore.saveConversationMemory(
                c, targetId, conversationKey, memory, result.lastMessageTimestamp
            )
        }
        val previousScore = MonitorStore.saveConversationAnalysis(c, result)
        notifyOnEscalation(c, result, previousScore)
        return result
    }

    private fun setPending(
        c: Context,
        targetId: String,
        conversationKey: String,
        value: Boolean,
        error: String = ""
    ) {
        val id = threadId(targetId, conversationKey)
        Store.putFlag(c, PENDING_PREFIX + id, value)
        Store.putStr(c, ERROR_PREFIX + id, error.take(300))
    }

    private fun isRetryable(code: Int): Boolean =
        code == -1 || code == 408 || code == 425 || code == 429 || code in 500..599

    private fun uniqueWorkName(targetId: String, conversationKey: String): String =
        "monitor-ai-" + threadId(targetId, conversationKey)

    private fun targetWorkTag(targetId: String): String =
        "monitor-ai-target-" + sha256(targetId).take(24)

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
        text = Regex("(?i)(otp|mã\\s*xác\\s*thực|mã\\s*bảo\\s*mật|verification\\s*code)\\s*[:=\\-]?\\s*\\d{4,8}")
            .replace(text) { match -> match.groupValues[1] + " [đã ẩn]" }
        text = Regex("(?i)(mật\\s*khẩu|password|passcode)\\s*[:=]\\s*[^\\s,;]+")
            .replace(text) { match -> match.groupValues[1] + ": [đã ẩn]" }
        text = text.replace(Regex("(?<!\\d)(?:\\+?84|0)\\d{8,10}(?!\\d)"), "[số điện thoại đã ẩn]")
        text = text.replace(Regex("(?<!\\d)\\d{9,19}(?!\\d)"), "[dãy số đã ẩn]")
        return text.take(TRANSCRIPT_CHAR_LIMIT)
    }

    private fun threadId(targetId: String, conversationKey: String): String =
        sha256("$targetId|$conversationKey").take(24)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
