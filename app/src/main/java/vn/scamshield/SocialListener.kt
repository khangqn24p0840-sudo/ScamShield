package vn.scamshield

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.util.LinkedHashMap

/** Đọc phần nội dung ứng dụng nhắn tin công bố trong thông báo; không truy cập kho chat riêng của ứng dụng. */
class SocialListener : NotificationListenerService() {
    companion object {
        val apps = mapOf(
            "com.zing.zalo" to "Zalo",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.facebook.katana" to "Facebook",
            "org.telegram.messenger" to "Telegram",
            "com.viber.voip" to "Viber",
            "com.instagram.android" to "Instagram",
            "com.whatsapp" to "WhatsApp",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.google.android.apps.messaging" to "Tin nhắn Google"
        )

        fun enabled(c: Context) =
            NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    }

    private data class IncomingMessage(
        val sender: String,
        val body: String,
        val timestamp: Long
    )

    // Một thông báo MessagingStyle có thể được đăng lại nhiều lần với cả lịch sử cũ.
    // Cache có giới hạn ngăn phân tích/cảnh báo trùng mà không giữ nội dung tin nhắn trong RAM lâu dài.
    private val recentlySeen = LinkedHashMap<String, Long>()
    // Chỉ giữ ngữ cảnh gần đây trong RAM để ghép các tin liên tiếp cùng một cuộc trò chuyện.
    private val recentConversationContext = LinkedHashMap<String, MutableList<IncomingMessage>>()

    @Synchronized
    private fun appendConversationContext(key: String, item: IncomingMessage) {
        if (item.sender == "Bạn" || item.body.isBlank()) return
        val now = System.currentTimeMillis()
        val messages = recentConversationContext.getOrPut(key) { mutableListOf() }
        messages.removeAll { now - it.timestamp > 5 * 60 * 1000L }
        if (messages.none { it.timestamp == item.timestamp && it.sender == item.sender && it.body == item.body }) {
            messages += item
        }
        messages.sortBy { it.timestamp }
        while (messages.size > 8) messages.removeAt(0)
        while (recentConversationContext.size > 80) {
            val iterator = recentConversationContext.entries.iterator()
            if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
        }
    }

    @Synchronized
    private fun conversationContext(key: String, fallback: String): String {
        val now = System.currentTimeMillis()
        val messages = recentConversationContext[key] ?: return fallback
        messages.removeAll { now - it.timestamp > 5 * 60 * 1000L }
        return messages.joinToString("\n") { "${it.sender}: ${it.body}" }
            .takeLast(3500)
            .ifBlank { fallback }
    }

    @Synchronized
    private fun markIfNew(key: String): Boolean {
        val now = System.currentTimeMillis()
        val iterator = recentlySeen.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > 10 * 60 * 1000L) iterator.remove()
        }
        if (recentlySeen.containsKey(key)) return false
        recentlySeen[key] = now
        while (recentlySeen.size > 512) {
            val oldest = recentlySeen.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            } else break
        }
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return

        if (sbn.packageName in Habit.bankApps) {
            val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: "").toString()
            if (text.isNotBlank()) Habit.onText(this, text)
            return
        }

        val app = apps[sbn.packageName] ?: return
        // Bỏ thông báo tổng hợp của nhóm; từng thông báo con mới chứa tin nhắn cụ thể.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val conversationTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        // shortcutId belongs to Notification (API 26+), not StatusBarNotification.
        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sbn.notification.shortcutId.orEmpty()
        } else ""
        val conversationIdentity = shortcutId.ifBlank {
            conversationTitle.ifBlank { title }
        }
        if (conversationIdentity.isBlank()) return
        val conversationKey = sbn.packageName + "|" + conversationIdentity.trim().lowercase()

        val captured = mutableListOf<IncomingMessage>()
        var newestIncoming: IncomingMessage? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val current = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            )
            val historic = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
            )
            historic.forEach { item ->
                val body = item.text?.toString()?.trim().orEmpty()
                if (body.isNotBlank()) {
                    captured += IncomingMessage(item.senderPerson?.name?.toString()?.takeIf { it.isNotBlank() } ?: title.ifBlank { conversationTitle }.ifBlank { "Người gửi" }, body, item.timestamp)
                }
            }
            current.forEach { item ->
                val body = item.text?.toString()?.trim().orEmpty()
                if (body.isNotBlank()) {
                    val entry = IncomingMessage(item.senderPerson?.name?.toString()?.takeIf { it.isNotBlank() } ?: title.ifBlank { conversationTitle }.ifBlank { "Người gửi" }, body, item.timestamp)
                    captured += entry
                    // Ưu tiên tin mới nhất từ phía đối thoại; không để tin do chính người dùng gửi
                    // làm mất cảnh báo cho tin đến trong cùng một payload MessagingStyle.
                    if (entry.sender != "Bạn" &&
                        (newestIncoming == null || entry.timestamp > newestIncoming!!.timestamp)
                    ) {
                        newestIncoming = entry
                    }
                }
            }
        }

        // Fallback cho ứng dụng không dùng MessagingStyle hoặc Android cũ.
        if (captured.isEmpty()) {
            val body = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                        ?.joinToString(" ")
                    ?: ""
                ).toString().trim()
            if (body.isNotBlank()) {
                val fallback = IncomingMessage(title.ifBlank { conversationTitle }, body, sbn.postTime)
                captured += fallback
                newestIncoming = fallback
            }
        }

        val uniqueMessages = captured.distinctBy {
            it.timestamp.toString() + "|" + it.sender + "|" + it.body
        }
        // Lưu lịch sử đúng đối tượng đã chọn; cảnh báo cục bộ vẫn hoạt động với mọi ứng dụng được hỗ trợ.
        var newestIncomingIsNew = false
        uniqueMessages.forEach { item ->
            val eventKey = conversationKey + "|" + item.timestamp + "|" +
                item.sender + "|" + item.body.hashCode()
            if (!markIfNew(eventKey)) return@forEach
            if (item == newestIncoming && item.sender != "Bạn") newestIncomingIsNew = true
            appendConversationContext(conversationKey, item)

            val targetId = MonitorStore.captureSocial(
                this,
                item.sender,
                item.body,
                sbn.packageName,
                item.timestamp,
                conversationTitle.ifBlank { title },
                conversationKey
            )
            // Chỉ lập lịch AI cho đúng thread đã theo dõi và khi người dùng đã bật đồng ý.
            if (targetId != null && item.sender != "Bạn") {
                ConversationAiMonitor.schedule(this, targetId, conversationKey)
            }
        }

        // Ghép tối đa 8 tin mới nhất trong 5 phút của đúng cuộc trò chuyện để không bỏ sót dấu hiệu chia nhỏ.
        val newest = newestIncoming
        if (newestIncomingIsNew && newest != null && newest.sender != "Bạn") {
            val contextText = conversationContext(conversationKey, newest.body)
            val risk = ScamDetector.analyzeConversation(contextText)
            if (risk.level >= 1) {
                val alertTitle = (if (risk.level == 2) "🚨 Tin nhắn có dấu hiệu lừa đảo trên "
                    else "⚠️ Tin nhắn đáng ngờ trên ") + app
                Store.log(
                    this,
                    if (risk.level == 2) 2 else 1,
                    alertTitle + " từ " + newest.sender,
                    newest.body
                )
                Notifier.show(
                    this,
                    alertTitle,
                    "Từ " + newest.sender + ": " + risk.reasons.joinToString(", ") +
                        ". Không bấm link, không chuyển tiền, không cung cấp OTP."
                )
                Alerter.fire(this, risk.level)
            }
        }
    }
}
