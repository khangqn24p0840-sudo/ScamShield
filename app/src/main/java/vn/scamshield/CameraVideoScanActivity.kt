package vn.scamshield

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import vn.scamshield.face.FaceLivenessAnalyzer
import vn.scamshield.face.FaceObserver
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Quét video đã chọn hoặc camera trực tiếp.
 * Hiện tại dùng ML Kit face detection + chỉ báo hình ảnh/chuyển động; chưa phải mô hình Deepfake đã huấn luyện.
 */
class CameraVideoScanActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var preview: PreviewView? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val analyzer = FaceLivenessAnalyzer(FaceLivenessAnalyzer.Config(minObserveMs = 5_000))
    private var observer: FaceObserver? = null
    private var lastFrameAt = 0L
    private var closed = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Bạn chưa cấp quyền camera. Không thể quét trực tiếp."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(android.graphics.Color.rgb(12, 18, 32))
        }
        val title = TextView(this).apply {
            text = "ScamShield • Quét video Deepfake"
            textSize = 21f
            setTextColor(android.graphics.Color.WHITE)
        }
        status = TextView(this).apply {
            text = "Đang chuẩn bị phân tích…"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 18, 0, 18)
        }
        root.addView(title)
        preview = PreviewView(this).also {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(status)
        root.addView(Button(this).apply { text = "Đóng"; setOnClickListener { finish() } })
        setContentView(root)

        observer = FaceObserver(this)
        val uri = intent.data
        if (uri != null) {
            preview?.visibility = android.view.View.GONE
            Thread({ scanVideo(uri) }, "video-scan").start()
        } else if (intent.getBooleanExtra("camera", false)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            status.text = "Không có video được chọn."
        }
    }

    private fun startCamera() {
        status.text = "Camera đang hoạt động. Giữ khuôn mặt trong khung hình khoảng 5–10 giây."
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview!!.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt >= 350) {
                        lastFrameAt = now
                        val bitmap = image.toBitmapNv21()
                        analyzer.add(observer!!.observe(bitmap, now))
                        val verdict = analyzer.evaluate()
                        runOnUiThread {
                            status.text = verdict.headline + "\n" +
                                "Điểm chỉ báo: " + (verdict.probability * 100).toInt() + "%\n" +
                                verdict.advice
                        }
                        bitmap.recycle()
                    }
                } catch (_: Exception) {
                    runOnUiThread { status.text = "Không phân tích được khung hình camera." }
                } finally { image.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
            } catch (e: Exception) {
                status.text = "Không mở được camera: " + (e.message ?: "lỗi không xác định")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scanVideo(uri: android.net.Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                runOnUiThread { status.text = "Không đọc được video này." }
                return
            }
            var t = 0L
            var lastHeadline = "Đang phân tích video…"
            while (t < durationMs && !closed) {
                val frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    analyzer.add(observer!!.observe(frame, t))
                    val v = analyzer.evaluate()
                    lastHeadline = v.headline
                    runOnUiThread {
                        status.text = "Đang quét: ${(t * 100 / max(1L, durationMs))}%\n" +
                            v.headline + "\nĐiểm chỉ báo: " + (v.probability * 100).toInt() + "%\n" + v.advice
                    }
                    frame.recycle()
                }
                t += 500L
            }
            runOnUiThread { status.text = "Đã quét xong video.\n$lastHeadline\n" +
                "Kết quả chỉ là chỉ báo thử nghiệm, không xác nhận video thật hay giả." }
        } catch (_: Exception) {
            runOnUiThread { status.text = "Không thể đọc hoặc phân tích video này." }
        } finally { retriever.release() }
    }

    private fun ImageProxy.toBitmapNv21(): Bitmap {
        val w = width
        val h = height
        val y = planes[0].buffer
        val u = planes[1].buffer
        val v = planes[2].buffer
        val nv21 = ByteArray(w * h + w * h / 2)
        y.rewind()
        val yRow = planes[0].rowStride
        for (row in 0 until h) {
            val rowStart = row * yRow
            for (col in 0 until w) nv21[row * w + col] = y.get(rowStart + col)
        }
        val uvRow = planes[1].rowStride
        val uvPixel = planes[1].pixelStride
        var out = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val ui = row * uvRow + col * uvPixel
                val vi = row * planes[2].rowStride + col * planes[2].pixelStride
                nv21[out++] = v.get(vi)
                nv21[out++] = u.get(ui)
            }
        }
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 75, jpeg)
        return android.graphics.BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size())
    }

    override fun onDestroy() {
        closed = true
        executor.shutdown()
        observer?.close()
        super.onDestroy()
    }
}
