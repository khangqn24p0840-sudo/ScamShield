package vn.scamshield
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import vn.scamshield.face.*

/** Quan sát màn hình (sau khi người dùng đồng ý) để phân tích khuôn mặt trong video call. Không lưu hình. */
class ScreenFaceService : Service() {
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ht: HandlerThread? = null
    private var observer: FaceObserver? = null
    private val analyzer = FaceLivenessAnalyzer()
    private val gate = FaceAlertGate()
    private var lastFrame = 0L; private var lastEval = 0L; private var startAt = 0L

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (proj != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("face", "Kiểm tra khuôn mặt", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "face") else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("🎭 ScamShield đang kiểm tra khuôn mặt video call")
            .setContentText("Chỉ xử lý trong máy, không lưu hình. Mở app để dừng.").setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(8, n)

        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        proj = p
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(Looper.getMainLooper()))
        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2; val h = dm.heightPixels / 2
        ht = HandlerThread("face").also { it.start() }
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2); reader = r
        vd = p.createVirtualDisplay("scamshield-face", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        observer = FaceObserver(this)
        startAt = SystemClock.elapsedRealtime(); FaceBus.verdict = null; FaceBus.running = true
        r.setOnImageAvailableListener({ handle(it) }, Handler(ht!!.looper))
        return START_NOT_STICKY
    }

    private fun handle(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < 110) return
            lastFrame = now
            if (now - startAt > 20 * 60_000L) { Handler(Looper.getMainLooper()).post { stopSelf() }; return }   // tự dừng sau 20 phút
            val pl = img.planes[0]
            val pad = pl.rowStride - pl.pixelStride * img.width
            val wide = Bitmap.createBitmap(img.width + pad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(pl.buffer)
            val bmp = if (wide.width != img.width) Bitmap.createBitmap(wide, 0, 0, img.width, img.height) else wide
            analyzer.add(observer!!.observe(bmp, now))
            if (now - lastEval >= 1000) {
                lastEval = now
                val v = analyzer.evaluate(); FaceBus.verdict = v
                if (gate.shouldAlert(v, now)) alert(v)
            }
        } catch (_: Exception) { } finally { img.close() }
    }

    private fun alert(v: FaceVerdict) {
        val lv = if (v.level == FaceLevel.HIGH) 2 else 1
        Alerter.fire(this, lv)
        Notifier.show(this, if (lv == 2) "🚨 Nghi giả mạo khuôn mặt trong video call" else "⚠️ Khuôn mặt có dấu hiệu bất thường", v.headline + ".\n" + v.advice)
        Store.log(this, lv, "Khuôn mặt video call nghi giả mạo", v.headline)
    }

    override fun onDestroy() {
        FaceBus.running = false
        reader?.setOnImageAvailableListener(null, null)
        vd?.release(); proj?.stop(); ht?.quitSafely(); observer?.close()
        super.onDestroy()
    }
}
