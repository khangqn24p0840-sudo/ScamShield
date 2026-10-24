package vn.scamshield
import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

object Notifier {
    @Suppress("DEPRECATION")
    fun show(c: Context, title: String, text: String, blockNum: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("scam", "Cảnh báo lừa đảo", NotificationManager.IMPORTANCE_HIGH))
        val nid = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "scam") else Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        b.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true)
        if (blockNum != null) {
            val ai = Intent(c, ActionReceiver::class.java).putExtra("num", blockNum).putExtra("nid", nid)
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Chặn số này", PendingIntent.getBroadcast(c, nid, ai, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        nm.notify(nid, b.build())
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val n = i.getStringExtra("num") ?: return
        Store.addBlock(c, n)
        c.getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("nid", 0))
        Toast.makeText(c, "Đã chặn $n", Toast.LENGTH_SHORT).show()
    }
}
