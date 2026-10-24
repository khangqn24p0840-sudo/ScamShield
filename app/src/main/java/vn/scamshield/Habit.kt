package vn.scamshield
import android.content.Context
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

/** Phân tích thói quen NGAY TRONG MÁY. Số tiền, nội dung, số điện thoại không bao giờ gửi lên máy chủ. */
object Habit {
    // Tên gói app ngân hàng/ví: hãy kiểm tra lại trên Google Play (đuôi ?id=) vì có thể thay đổi.
    val bankApps = setOf("com.VCB", "com.mbmobile", "vn.com.techcombank.bb.app", "com.vnpay.bidv", "com.vietinbank.ipay",
        "com.vnpay.Agribank3g", "com.mservice.momotransfer", "vn.com.vng.zalopay", "com.tpb.mb.gprsandroid")
    private val amt = Regex("([-+])?\\s?(\\d{1,3}(?:[.,]\\d{3})+|\\d{4,})\\s?(vnd|d)\\b")
    private val creditCue = Regex("\\b(tang|nhan|cong|credit)\\b")
    private val debitCue = Regex("giam|chuyen khoan|chuyen tien|thanh toan|rut tien|debit")

    fun debit(text: String): Long? {
        val t = ScamDetector.norm(text)
        val m = amt.find(t) ?: return null
        val sign = m.groupValues[1]
        if (sign == "+" || (sign != "-" && creditCue.containsMatchIn(t))) return null
        if (sign != "-" && !debitCue.containsMatchIn(t)) return null
        return m.groupValues[2].replace(Regex("[.,]"), "").toLongOrNull()
    }

    /** Gọi với nội dung SMS/thông báo ngân hàng. Cảnh báo nếu khoản chi bất thường so với thói quen. */
    fun onText(c: Context, text: String) {
        val a = debit(text) ?: return
        val now = System.currentTimeMillis()
        val arr = JSONArray(Store.str(c, "debits").ifEmpty { "[]" })
        val prev = (0 until arr.length()).map { arr.getJSONArray(it).let { r -> r.getLong(0) to r.getLong(1) } }
        val amounts = prev.map { it.second }.sorted()
        val limit = if (amounts.size >= 3) maxOf(amounts[amounts.size / 2] * 4, 3_000_000L) else 10_000_000L
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val why = when {
            a >= limit -> "Số tiền lớn hơn nhiều so với thói quen của bạn"
            hour in 0..4 && a >= 2_000_000L -> "Chuyển tiền lớn vào ban đêm"
            prev.count { now - it.first < 15 * 60_000L } >= 3 -> "Nhiều giao dịch liên tiếp trong 15 phút"
            else -> null
        }
        val n = JSONArray().put(JSONArray().put(now).put(a))
        for (i in 0 until minOf(arr.length(), 49)) n.put(arr.get(i))
        Store.putStr(c, "debits", n.toString())
        if (why != null) {
            val money = String.format(Locale("vi", "VN"), "%,d", a)
            Store.log(c, 2, "Giao dịch bất thường", "$money đ – $why")
            Notifier.show(c, "🚨 Giao dịch bất thường: $money đ", "$why. Nếu có người đang hướng dẫn bạn chuyển tiền qua điện thoại hay tin nhắn, hãy DỪNG LẠI và gọi người thân hoặc ngân hàng bằng số trên thẻ.")
            Alerter.fire(c, 2)
        }
    }

    /** true đúng lúc có số lạ thứ 5 nhắn tin trong vòng 1 giờ (dấu hiệu chiến dịch hàng loạt). */
    fun senderBurst(c: Context, from: String): Boolean {
        if (Store.digits(from).length < 7 || CallGuard.known(c, from)) return false
        val now = System.currentTimeMillis()
        val arr = JSONArray(Store.str(c, "senders").ifEmpty { "[]" })
        val keep = mutableListOf<Pair<Long, String>>()
        for (i in 0 until arr.length()) { val r = arr.getJSONArray(i); if (now - r.getLong(0) < 3_600_000L) keep.add(r.getLong(0) to r.getString(1)) }
        val added = keep.none { it.second == from }
        if (added) keep.add(now to from)
        Store.putStr(c, "senders", JSONArray(keep.map { JSONArray().put(it.first).put(it.second) }).toString())
        return added && keep.map { it.second }.distinct().size == 5
    }
}
