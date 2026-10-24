package vn.scamshield.face

enum class FaceLevel { NO_FACE, ANALYZING, OK, SUSPICIOUS, HIGH }

/** Số đo của MỘT khung hình video. Chỉ chứa số liệu, không lưu ảnh. */
data class FaceObservation(
    val timestampMs: Long,
    val faceFound: Boolean,
    val boxW: Float = 0f,          // chiều rộng khung mặt / chiều rộng khung hình (0..1)
    val boxH: Float = 0f,
    val yaw: Float = 0f,           // độ, quay trái/phải
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val eyeOpen: Float? = null,    // 0..1, trung bình hai mắt; null nếu không đo được
    val faceSharp: Float = 0f,     // phương sai Laplacian vùng mặt
    val bgSharp: Float = 0f,       // phương sai Laplacian vùng viền quanh mặt
    val modelFakeProb: Float? = null // xác suất giả từ mô hình học sâu (nếu có)
) {
    companion object {
        fun none(ts: Long) = FaceObservation(ts, false)
    }
}

data class FaceIndicator(
    val id: String,
    val label: String,
    val score: Double,     // 0..1, càng cao càng bất thường
    val detail: String
)

data class FaceVerdict(
    val level: FaceLevel,
    val probability: Double,
    val progress: Float,            // 0..1: đã thu thập đủ dữ liệu chưa
    val indicators: List<FaceIndicator>,
    val fps: Double,
    val headline: String,
    val advice: String
)
