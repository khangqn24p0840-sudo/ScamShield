package vn.scamshield
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Nghe giọng đối phương qua loa ngoài, đổi sang chữ và phát cảnh báo nếu có dấu hiệu lừa đảo. */
class ListenService : Service() {
    companion object { @Volatile var running = false }
    private var sr: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private var buf = ""; private var best = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("listen", "Đang nghe cuộc gọi", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "listen") else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("🎙️ ScamShield đang nghe cuộc gọi")
            .setContentText("Hãy bật loa ngoài. Sẽ cảnh báo nếu có dấu hiệu lừa đảo.").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(7, n)
        running = true; buf = ""; best = 0
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Notifier.show(this, "Không nghe được", "Máy chưa có dịch vụ nhận diện giọng nói (cần app Google).")
            stopSelf(); return START_NOT_STICKY
        }
        h.post { listen() }
        return START_NOT_STICKY
    }

    private fun listen() {
        if (!running) return
        val r = sr ?: SpeechRecognizer.createSpeechRecognizer(this).also { s ->
            sr = s
            s.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { feed(b, false); again(300) }
                override fun onPartialResults(b: Bundle?) { feed(b, true) }
                override fun onError(e: Int) { if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) stopSelf() else again(600) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(x: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    private fun again(ms: Long) = h.postDelayed({ if (running) { sr?.cancel(); listen() } }, ms)

    private fun feed(b: Bundle?, partial: Boolean) {
        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        val all = (buf + " " + t).takeLast(700)
        if (!partial) buf = all
        val r = ScamDetector.analyze(all)
        if (r.live >= 1 && r.live > best) {
            best = r.live; Alerter.fire(this, r.live)
            val title = if (r.live == 2) "🚨 CẢNH BÁO: cuộc gọi có dấu hiệu LỪA ĐẢO" else "⚠️ Cuộc gọi đáng ngờ"
            Notifier.show(this, title, r.reasons.joinToString(", ") + ". Hãy cúp máy, không đọc OTP, không chuyển tiền!")
            Store.log(this, if (r.live == 2) 2 else 1, "Cuộc gọi đang diễn ra", r.reasons.joinToString(", "))
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pat = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pat, -1)) else v.vibrate(pat, -1)
    }

    override fun onDestroy() { running = false; Overlay.hide(this); h.removeCallbacksAndMessages(null); sr?.destroy(); sr = null; super.onDestroy() }
}
