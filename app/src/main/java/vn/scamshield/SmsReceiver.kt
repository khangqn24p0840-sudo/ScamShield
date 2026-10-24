package vn.scamshield
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        msgs.groupBy { it.originatingAddress ?: "?" }.forEach { (from, parts) ->
            val body = parts.joinToString("") { it.messageBody ?: "" }
            Habit.onText(c, body)
            if (Habit.senderBurst(c, from)) {
                Store.log(c, 1, "Nhiều số lạ nhắn tin", "5 số lạ trong 1 giờ")
                Notifier.show(c, "⚠️ Nhiều số lạ nhắn tin liên tiếp", "5 số lạ nhắn trong 1 giờ – dấu hiệu chiến dịch lừa đảo hàng loạt. Đừng bấm link.")
            }
            val r = ScamDetector.analyze(body)
            val recent = Store.calledRecently(c, from) && r.score > 0   // số lạ vừa gọi rồi nhắn tin có link/từ khóa lạ
            if (r.level >= 1 || recent || Store.isBlocked(c, from)) {
                val high = r.level == 2 || recent
                val t = when { recent -> "🚨 Số lạ vừa gọi đã nhắn tin đáng ngờ"; r.level == 2 -> "🚨 Tin nhắn có dấu hiệu LỪA ĐẢO"; else -> "⚠️ Tin nhắn đáng ngờ" }
                Store.log(c, if (high) 2 else 1, "$t từ $from", body)
                val advice = if (recent) " Số này vừa gọi cho bạn – nên CHẶN ngay, không bấm link." else " Không bấm link, không cung cấp OTP."
                Notifier.show(c, t, "Từ $from: " + r.reasons.joinToString(", ") + "." + advice, from.takeIf { Store.digits(it).length >= 7 })
                Alerter.fire(c, if (high) 2 else 1)
            }
        }
    }
}
