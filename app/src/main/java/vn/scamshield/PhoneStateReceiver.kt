package vn.scamshield
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/** Chặn/cảnh báo dự phòng cho Android cũ (và bổ sung cho máy mới). */
class PhoneStateReceiver : BroadcastReceiver() {
    companion object {
        fun perms(): Array<String> {
            val l = mutableListOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 26) l += android.Manifest.permission.ANSWER_PHONE_CALLS
            if (Build.VERSION.SDK_INT < 28) l += android.Manifest.permission.CALL_PHONE
            return l.toTypedArray()
        }
    }
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        if (i.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
        val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return
        if (CallGuard.handle(c, num)) endCall(c)
    }
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun endCall(c: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 28) c.getSystemService(TelecomManager::class.java).endCall()
            else { val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val m = tm.javaClass.getDeclaredMethod("getITelephony"); m.isAccessible = true
                val t = m.invoke(tm); t!!.javaClass.getMethod("endCall").invoke(t) }
        } catch (_: Exception) {}
    }
}
