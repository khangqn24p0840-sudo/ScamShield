package vn.scamshield.face

import android.graphics.Bitmap

/**
 * Điểm cắm cho mô hình học sâu phát hiện deepfake.
 * Khi chưa có model hợp lệ, dùng NoopClassifier và không giả vờ đã có kết quả AI.
 */
interface DeepfakeClassifier {
    val isAvailable: Boolean
    val status: String get() = if (isAvailable) "Model AI đã sẵn sàng" else "Model AI chưa khả dụng"
    /** Xác suất khuôn mặt là giả (0..1), hoặc null nếu không đánh giá được. */
    fun fakeProbability(face: Bitmap): Float?
    /** Giải phóng tài nguyên model nếu adapter có nắm giữ tài nguyên native. */
    fun close() {}
}

object NoopClassifier : DeepfakeClassifier {
    override val isAvailable: Boolean = false
    override fun fakeProbability(face: Bitmap): Float? = null
}
