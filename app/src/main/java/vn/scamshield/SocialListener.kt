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
        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sbn.shortcutId.orEmpty()
        } else ""
        val conversationIdentity = shortcutId.ifBlank {
            conversationTitle.ifBlank { title }
        }
        if (conversationIdentity.isBlank()) return
        val conversationKey = sbn.packageName + "|" + conversationIdentity.trim().lowercase()

        val captured = mutableListOf<IncomingMessage>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val current = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            )
            val historic = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
            )
            (historic + current).forEach { item ->
                val body = item.text?.toString()?.trim().orEmpty()
                if (body.isBlank()) return@forEach
                val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    item.senderPerson?.name?.toString() ?: "Bạn"
                } else {
                    @Suppress("DEPRECATION")
                    item.sender?.toString() ?: "Bạn"
                }
                captured += IncomingMessage(sender, body, item.timestamp)
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
                captured += IncomingMessage(title.ifBlank { conversationTitle }, body, sbn.postTime)
            }
        }

        captured.distinctBy { it.timestamp.toString() + "|" + it.sender + "|" + it.body }
            .forEach { item ->
                val eventKey = conversationKey + "|" + item.timestamp + "|" +
                    item.sender + "|" + item.body.hashCode()
                if (!markIfNew(eventKey)) return@forEach

                // Chỉ lưu cục bộ nếu khớp đúng tên người gửi hoặc tên cuộc trò chuyện đã chọn.
                MonitorStore.captureSocial(
                    this,
                    item.sender,
                    item.body,
                    sbn.packageName,
                    item.timestamp,
                    conversationTitle.ifBlank { title },
                    conversationKey
                )

                // Cảnh báo tức thời vẫn chạy cục bộ; Gemini chỉ được gọi khi người dùng chủ động yêu cầu.
                val risk = ScamDetector.analyze(item.body)
                if (risk.level < 1) return@forEach
                val alertTitle = (if (risk.level == 2) "🚨 Tin nhắn có dấu hiệu lừa đảo trên "
                    else "⚠️ Tin nhắn đáng ngờ trên ") + app
                Store.log(
                    this,
                    if (risk.level == 2) 2 else 1,
                    alertTitle + " từ " + item.sender,
                    item.body
                )
                Notifier.show(
                    this,
                    alertTitle,
                    "Từ " + item.sender + ": " + risk.reasons.joinToString(", ") +
                        ". Không bấm link, không chuyển tiền, không cung cấp OTP."
                )
                Alerter.fire(this, risk.level)
            }
    }
}
