package vn.scamshield.face

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Phân tích "độ thật" của khuôn mặt trong video call theo cửa sổ trượt ~30 giây.
 *
 * LƯU Ý QUAN TRỌNG: các chỉ báo dưới đây là heuristic, ngưỡng là giá trị khởi đầu CHƯA hiệu chỉnh
 * trên dữ liệu thật. Deepfake chất lượng cao có thể qua mặt được, và video nén/ánh sáng yếu có thể
 * gây báo nhầm. Kết quả chỉ là "chỉ báo rủi ro"; biện pháp đáng tin nhất vẫn là thử thách chủ động
 * (quay nghiêng mặt, đưa tay che mặt...) và xác minh qua kênh khác.
 */
class FaceLivenessAnalyzer(private val cfg: Config = Config()) {

    data class Config(
        val windowMs: Long = 30_000,
        val minObserveMs: Long = 15_000,
        val minFps: Double = 6.0,        // dưới mức này không đủ mịn để đếm chớp mắt
        val suspiciousAt: Double = 0.45,
        val highAt: Double = 0.70
    )

    private val window = ArrayList<FaceObservation>()

    @Synchronized
    fun add(o: FaceObservation) {
        window.add(o)
        val cut = o.timestampMs - cfg.windowMs
        while (window.isNotEmpty() && window[0].timestampMs < cut) window.removeAt(0)
    }

    @Synchronized
    fun reset() {
        window.clear()
    }

    @Synchronized
    fun evaluate(): FaceVerdict {
        val faces = window.filter { it.faceFound && it.boxW >= MIN_BOX }
        if (faces.size < 5) {
            return FaceVerdict(FaceLevel.NO_FACE, 0.0, 0f, emptyList(), 0.0, "Chưa thấy khuôn mặt trong video", "")
        }
        val span = faces.last().timestampMs - faces.first().timestampMs
        val fps = if (span > 0) (faces.size - 1) * 1000.0 / span else 0.0
        val progress = (span.toFloat() / cfg.minObserveMs).coerceIn(0f, 1f)
        if (span < cfg.minObserveMs) {
            return FaceVerdict(
                FaceLevel.ANALYZING, 0.0, progress, emptyList(), fps,
                "Đang phân tích khuôn mặt… ${(progress * 100).toInt()}%", ""
            )
        }

        val blink = blinkIndicator(faces, span, fps)
        val jitter = jitterIndicator(faces)
        val blend = blendIndicator(faces)
        val box = boxIndicator(faces)
        val model = modelIndicator(faces)
        val inds = listOfNotNull(blink, jitter, blend, box, model)

        var logit = BIAS
        for (i in inds) logit += (WEIGHTS[i.id] ?: 0.0) * i.score
        if (profilePassed(faces, box, blend)) logit -= 0.6
        val p = 1.0 / (1.0 + exp(-logit))

        val flagged = inds.count { it.score >= 0.4 }
        var level = when {
            p >= cfg.highAt -> FaceLevel.HIGH
            p >= cfg.suspiciousAt -> FaceLevel.SUSPICIOUS
            else -> FaceLevel.OK
        }
        // Không bao giờ kết luận mức cao chỉ dựa trên một chỉ báo
        if (level == FaceLevel.HIGH && flagged < 2) level = FaceLevel.SUSPICIOUS

        val (headline, advice) = when (level) {
            FaceLevel.HIGH -> Pair(
                "Nhiều dấu hiệu nghi giả mạo khuôn mặt",
                "Đừng chuyển tiền, đừng cung cấp OTP hay thông tin cá nhân. Hãy thử:\n" + tips()
            )
            FaceLevel.SUSPICIOUS -> Pair(
                "Có dấu hiệu bất thường, hãy kiểm tra thêm",
                "Hãy thử:\n" + tips()
            )
            else -> Pair(
                "Chưa thấy dấu hiệu bất thường",
                "Không công cụ nào chắc chắn 100%. Nếu bị yêu cầu chuyển tiền hoặc đưa OTP, hãy gọi lại bằng số đã lưu để xác minh."
            )
        }
        return FaceVerdict(level, p, 1f, inds, fps, headline, advice)
    }

    // ------------------------------------------------------------------ chỉ báo

    private fun blinkIndicator(f: List<FaceObservation>, span: Long, fps: Double): FaceIndicator? {
        val eyes = f.filter { it.eyeOpen != null }
        if (fps < cfg.minFps || eyes.size < f.size * 0.7) return null
        var open = true
        var closedAt = 0L
        var blinks = 0
        for (o in eyes) {
            val e = o.eyeOpen ?: continue
            if (open && e < 0.30f) {
                open = false
                closedAt = o.timestampMs
            } else if (!open && e > 0.55f) {
                if (o.timestampMs - closedAt <= 700) blinks++
                open = true
            }
        }
        val perMin = blinks * 60_000.0 / span
        val score = when {
            blinks == 0 && span >= 25_000 -> 0.9
            blinks == 0 -> 0.5
            perMin < 3.0 && span >= 25_000 -> 0.4
            perMin > 50.0 -> 0.4
            else -> 0.0
        }
        return FaceIndicator("blink", "Nhịp chớp mắt", score, "$blinks lần chớp trong ${span / 1000}s")
    }

    private fun jitterIndicator(f: List<FaceObservation>): FaceIndicator? {
        if (f.size < 12) return null
        var sum = 0.0
        var n = 0
        for (i in 1 until f.size - 1) {
            val a = f[i - 1]; val b = f[i]; val c = f[i + 1]
            if (c.timestampMs - a.timestampMs > 500) continue
            val dy = (c.yaw - 2 * b.yaw + a.yaw).toDouble()
            val dp = (c.pitch - 2 * b.pitch + a.pitch).toDouble()
            val dr = (c.roll - 2 * b.roll + a.roll).toDouble()
            sum += (dy * dy + dp * dp + dr * dr) / 3.0
            n++
        }
        if (n < 8) return null
        val hf = sqrt(sum / n)
        val score = ((hf - 2.5) / 5.0).coerceIn(0.0, 1.0)
        return FaceIndicator("jitter", "Khuôn mặt rung giật bất thường", score, "dao động ${"%.1f".format(hf)}°")
    }

    private fun blendIndicator(f: List<FaceObservation>): FaceIndicator? {
        val lr = f.filter { it.bgSharp > 1f && it.faceSharp > 1f }
            .map { ln(it.faceSharp / it.bgSharp.toDouble()) }
        if (lr.size < 12) return null
        val mean = lr.average()
        val sd = sqrt(lr.sumOf { (it - mean) * (it - mean) } / lr.size)
        val score = ((sd - 0.45) / 0.9).coerceIn(0.0, 1.0)
        return FaceIndicator("blend", "Độ nét mặt và nền lệch nhau", score, "biến thiên ${"%.2f".format(sd)}")
    }

    private fun boxIndicator(f: List<FaceObservation>): FaceIndicator? {
        if (f.size < 12) return null
        var jumps = 0
        var n = 0
        for (i in 1 until f.size) {
            val a = f[i - 1]; val b = f[i]
            if (b.timestampMs - a.timestampMs > 400) continue
            val sa = sqrt((a.boxW * a.boxH).toDouble())
            val sb = sqrt((b.boxW * b.boxH).toDouble())
            if (sa <= 0.0) continue
            n++
            if (abs(sb - sa) / sa > 0.12) jumps++
        }
        if (n < 8) return null
        val frac = jumps.toDouble() / n
        val score = ((frac - 0.08) / 0.30).coerceIn(0.0, 1.0)
        return FaceIndicator("box", "Khung mặt nhảy bất thường", score, "${(frac * 100).toInt()}% khung hình")
    }

    private fun modelIndicator(f: List<FaceObservation>): FaceIndicator? {
        val m = f.takeLast(40).mapNotNull { it.modelFakeProb }
        if (m.size < 5) return null
        val mean = m.average()
        val score = ((mean - 0.35) / 0.45).coerceIn(0.0, 1.0)
        return FaceIndicator("model", "Mô hình AI đánh giá", score, "xác suất giả ${(mean * 100).toInt()}%")
    }

    /** Mặt quay nghiêng mạnh mà vẫn ổn định -> bằng chứng nghiêng về "người thật". */
    private fun profilePassed(f: List<FaceObservation>, box: FaceIndicator?, blend: FaceIndicator?): Boolean {
        val profileFrames = f.count { abs(it.yaw) >= 40f }
        return profileFrames >= 4 && (box?.score ?: 0.0) < 0.3 && (blend?.score ?: 0.0) < 0.4
    }

    private fun tips() = CHALLENGES.joinToString("\n") { "• $it" }

    companion object {
        private const val MIN_BOX = 0.06f
        private const val BIAS = -2.2
        private val WEIGHTS = mapOf(
            "blink" to 2.0, "jitter" to 0.8, "blend" to 1.0, "box" to 0.8, "model" to 3.5
        )
        val CHALLENGES = listOf(
            "Yêu cầu họ quay mặt nghiêng hẳn sang trái/phải",
            "Yêu cầu đưa bàn tay quét ngang trước mặt",
            "Yêu cầu đứng lên, lùi xa camera hoặc bật thêm đèn",
            "Hỏi một điều chỉ người thật mới biết (không phải thông tin có trên mạng)",
            "Cúp máy và gọi lại bằng số đã lưu trong danh bạ"
        )
    }
}

/** Chống báo động liên tục: chỉ cảnh báo khi trạng thái xấu kéo dài, và có thời gian nghỉ giữa hai lần. */
class FaceAlertGate(
    private val cooldownMs: Long = 60_000,
    private val suspiciousHoldMs: Long = 10_000
) {
    private var highStreak = 0
    private var suspiciousSince = -1L
    private var lastAlert = Long.MIN_VALUE / 2

    fun shouldAlert(v: FaceVerdict, now: Long): Boolean {
        highStreak = if (v.level == FaceLevel.HIGH) highStreak + 1 else 0
        if (v.level == FaceLevel.SUSPICIOUS) {
            if (suspiciousSince < 0) suspiciousSince = now
        } else {
            suspiciousSince = -1L
        }
        val sustained = highStreak >= 2 || (suspiciousSince >= 0 && now - suspiciousSince >= suspiciousHoldMs)
        if (sustained && now - lastAlert >= cooldownMs) {
            lastAlert = now
            return true
        }
        return false
    }
}
