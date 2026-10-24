package vn.scamshield.face
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** Trạng thái dùng chung giữa service và giao diện. */
object FaceBus {
    var running by mutableStateOf(false)
    var verdict by mutableStateOf<FaceVerdict?>(null)
}

/** Biến một khung hình thành FaceObservation (chỉ giữ số đo, không lưu ảnh). Gọi từ luồng nền. */
class FaceObserver(ctx: Context, private val classifier: DeepfakeClassifier = NoopClassifier) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).build())

    fun observe(b: Bitmap, ts: Long): FaceObservation {
        val faces = try { Tasks.await(detector.process(InputImage.fromBitmap(b, 0))) } catch (_: Exception) { return FaceObservation.none(ts) }
        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return FaceObservation.none(ts)
        val bb = f.boundingBox
        val l = f.leftEyeOpenProbability; val r = f.rightEyeOpenProbability
        val eye = if (l != null && r != null) (l + r) / 2f else null
        val outer = Rect(bb).apply { inset(-(bb.width() * 0.3f).toInt(), -(bb.height() * 0.3f).toInt()) }
        val fake = if (classifier.isAvailable) {
            val c = Rect(bb); if (c.intersect(0, 0, b.width, b.height) && c.width() > 16 && c.height() > 16)
                classifier.fakeProbability(Bitmap.createBitmap(b, c.left, c.top, c.width(), c.height())) else null
        } else null
        return FaceObservation(ts, true, bb.width().toFloat() / b.width, bb.height().toFloat() / b.height,
            f.headEulerAngleY, f.headEulerAngleX, f.headEulerAngleZ, eye,
            lapVar(b, bb, null), lapVar(b, outer, bb), fake)
    }

    /** Phương sai Laplacian (độ nét) trên lưới 64x64 lấy mẫu từ vùng outer, bỏ qua vùng hole. */
    private fun lapVar(b: Bitmap, outer: Rect, hole: Rect?, n: Int = 64): Float {
        val o = Rect(outer); if (!o.intersect(0, 0, b.width, b.height) || o.width() < n || o.height() < n) return 0f
        val g = FloatArray(n * n); val ok = BooleanArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val x = (o.left + (i + 0.5f) * o.width() / n).toInt().coerceIn(0, b.width - 1)
            val y = (o.top + (j + 0.5f) * o.height() / n).toInt().coerceIn(0, b.height - 1)
            ok[j * n + i] = !(hole != null && hole.contains(x, y))
            val p = b.getPixel(x, y)
            g[j * n + i] = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
        }
        var s = 0.0; var s2 = 0.0; var c = 0
        for (j in 1 until n - 1) for (i in 1 until n - 1) {
            val k = j * n + i
            if (!(ok[k] && ok[k - 1] && ok[k + 1] && ok[k - n] && ok[k + n])) continue
            val v = 4 * g[k] - g[k - 1] - g[k + 1] - g[k - n] - g[k + n]
            s += v; s2 += v * v; c++
        }
        if (c < 20) return 0f
        val m = s / c; return (s2 / c - m * m).toFloat()
    }

    fun close() { detector.close() }
}
