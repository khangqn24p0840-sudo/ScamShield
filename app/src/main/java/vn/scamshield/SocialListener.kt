package vn.scamshield
import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/** Đọc thông báo của Zalo, Messenger, Telegram... để cảnh báo tin nhắn lừa đảo. */
class SocialListener : NotificationListenerService() {
    companion object {
        val apps = mapOf("com.zing.zalo" to "Zalo", "com.facebook.orca" to "Messenger", "com.facebook.mlite" to "Messenger Lite",
            "com.facebook.katana" to "Facebook", "org.telegram.messenger" to "Telegram", "com.viber.voip" to "Viber",
            "com.instagram.android" to "Instagram", "com.whatsapp" to "WhatsApp", "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok", "com.google.android.apps.messaging" to "Tin nhắn Google")
        fun enabled(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    }
    private var last = ""
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in Habit.bankApps) {
            val x = sbn.notification.extras ?: return
            Habit.onText(this, (x.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString() + " " + (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ""))
            return
        }
        val app = apps[sbn.packageName] ?: return
        val e = sbn.notification.extras ?: return
        val body = listOfNotNull(e.getCharSequence(Notification.EXTRA_TEXT), e.getCharSequence(Notification.EXTRA_BIG_TEXT))
            .joinToString(" ") + " " + (e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ") ?: "")
        if (body.isBlank()) return
        val sender = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val r = ScamDetector.analyze(body)
        val key = sbn.packageName + sender + body.hashCode()
        if (r.level < 1 || key == last) return
        last = key
        val t = (if (r.level == 2) "🚨 Tin nhắn lừa đảo trên " else "⚠️ Tin nhắn đáng ngờ trên ") + app
        Store.log(this, if (r.level == 2) 2 else 1, "$t từ $sender", body)
        Notifier.show(this, t, "Từ $sender: " + r.reasons.joinToString(", ") + ". Không bấm link, không chuyển tiền, không đọc OTP.")
        Alerter.fire(this, r.level)
    }
}
