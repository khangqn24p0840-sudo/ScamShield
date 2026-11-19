package vn.scamshield

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Gửi phân tích hội thoại đã được xếp hàng trên thiết bị.
 * WorkManager giữ tác vụ qua việc đóng app/khởi động lại máy và chờ có mạng.
 */
class ConversationAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val targetId = inputData.getString(KEY_TARGET_ID).orEmpty()
        val conversationKey = inputData.getString(KEY_CONVERSATION_KEY).orEmpty()
        if (targetId.isBlank() || conversationKey.isBlank()) return Result.success()

        return try {
            when (ConversationAiMonitor.processQueued(applicationContext, targetId, conversationKey)) {
                ConversationAiMonitor.QueueResult.DONE -> Result.success()
                ConversationAiMonitor.QueueResult.RETRY -> {
                    ConversationAiMonitor.enqueueRetry(applicationContext, targetId, conversationKey)
                    Result.success()
                }
            }
        } catch (_: Exception) {
            ConversationAiMonitor.enqueueRetry(applicationContext, targetId, conversationKey)
            Result.success()
        }
    }

    companion object {
        const val KEY_TARGET_ID = "target_id"
        const val KEY_CONVERSATION_KEY = "conversation_key"
    }
}
