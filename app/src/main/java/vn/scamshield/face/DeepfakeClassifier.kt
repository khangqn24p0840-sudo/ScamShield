package vn.scamshield.face

import android.graphics.Bitmap

/**
 * Điểm cắm cho mô hình học sâu phát hiện deepfake (TFLite, ONNX...).
 * Chưa có mô hình thì dùng NoopClassifier, analyzer vẫn chạy bằng các chỉ báo heuristic.
 * Xem optional/TfliteDeepfakeClassifier.kt.txt để bật bản TFLite.
 */
interface DeepfakeClassifier {
    val isAvailable: Boolean

    /** @return xác suất khuôn mặt là giả (0..1), hoặc null nếu không đánh giá được. */
    fun fakeProbability(face: Bitmap): Float?
}

object NoopClassifier : DeepfakeClassifier {
    override val isAvailable: Boolean = false
    override fun fakeProbability(face: Bitmap): Float? = null
}
