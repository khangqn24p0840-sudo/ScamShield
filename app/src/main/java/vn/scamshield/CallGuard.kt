package vn.scamshield
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

object CallGuard {
    private var last = ""; private var at = 0L

    fun known(c: Context, num: String): Boolean {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            c.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)),
                arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }
    }

    /** Trả về true nếu cần chặn cuộc gọi này. */
    fun handle(c: Context, num: String): Boolean {
        if (Store.digits(num).length < 3) return false
        val foreign = num.startsWith("+") && !num.startsWith("+84")
        val block = Store.isBlocked(c, num) || (foreign && Store.blockForeign(c))
        val now = System.currentTimeMillis()
        if (num == last && now - at < 8000) return block
        last = num; at = now
        if (block) {
            Store.log(c, 2, "Đã chặn cuộc gọi", num)
            Notifier.show(c, "🛡️ Đã chặn cuộc gọi lừa đảo", "Số $num nằm trong danh sách chặn.")
        } else if (foreign || !known(c, num)) {
            Store.markCalled(c, num)
            Store.log(c, 1, if (foreign) "Số nước ngoài gọi đến" else "Số lạ gọi đến", num)
            Notifier.show(c, "⚠️ Số lạ đang gọi" + if (foreign) " (nước ngoài)" else "",
                "Số lạ $num. Công an, ngân hàng không bao giờ đòi OTP hay chuyển tiền qua điện thoại.", num)
        }
        return block
    }
}
