package vn.scamshield

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import vn.scamshield.face.FaceBus
import vn.scamshield.face.FaceLivenessAnalyzer
import vn.scamshield.face.FaceObserver
import vn.scamshield.face.TfliteDeepfakeClassifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scans a user-selected video locally. Frames are downscaled before inference
 * and remain in memory only; no video or frame is persisted or uploaded.
 */
object VideoFileScanner {
    private const val MAX_SAMPLES = 120
    private const val TARGET_SAMPLE_FPS = 6.0
    private const val MAX_FRAME_SIDE = 640
    private const val MIN_DURATION_MS = 4_000L
    private const val PROGRESS_INTERVAL_MS = 200L

    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start(context: Context, uri: Uri) {
        if (VideoScanBus.state.running || worker?.isAlive == true) return
        if (FaceBus.running) {
            VideoScanBus.state = VideoScanBus.state.copy(
                status = "Hãy dừng kiểm tra video call trước khi quét video thư viện."
            )
            return
        }

        cancelled.set(false)
        VideoScanBus.state = VideoScanState(running = true, status = "Đang đọc video…")
        worker = Thread({
            scan(context.applicationContext, uri)
        }, "ScamShield-VideoScan").also { it.start() }
    }

    fun cancel() {
        cancelled.set(true)
        publish(VideoScanBus.state.copy(status = "Đang dừng quét…"))
    }

    private fun scan(context: Context, uri: Uri) {
        val retriever = MediaMetadataRetriever()
        var observer: FaceObserver? = null
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            require(durationMs >= MIN_DURATION_MS) {
                "Video quá ngắn hoặc không đọc được thời lượng (tối thiểu 4 giây)."
            }

            val sampleCount = min(
                MAX_SAMPLES,
                max(5, ceil(durationMs / 1_000.0 * TARGET_SAMPLE_FPS).toInt())
            )
            val stepMs = durationMs.toDouble() / sampleCount
            val videoWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val videoHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val classifier = TfliteDeepfakeClassifier(context)
            observer = FaceObserver(context, classifier)
            val analyzer = FaceLivenessAnalyzer(
                FaceLivenessAnalyzer.Config(
                    windowMs = durationMs + 1_000L,
                    minObserveMs = min(15_000L, max(1_000L, durationMs / 3L)),
                    minFps = 6.0
                )
            )
            val modelAvailable = observer.isModelAvailable
            val longVideo = durationMs > MAX_SAMPLES * 1_000L / TARGET_SAMPLE_FPS
            val sampleNotice = if (longVideo) {
                "Video dài: lấy tối đa $MAX_SAMPLES khung hình mẫu trên toàn video."
            } else {
                "Đang phân tích ở tốc độ mẫu tối đa 6 khung hình/giây…"
            }
            publish(VideoScanState(
                running = true,
                status = if (modelAvailable) sampleNotice else
                    "$sampleNotice Model AI chưa khả dụng; chỉ báo cơ bản sẽ được dùng.",
                totalFrames = sampleCount,
                modelAvailable = modelAvailable,
                modelStatus = observer.modelStatus
            ))

            var processed = 0
            var faceFrames = 0
            var lastProgressAt = 0L

            for (index in 0 until sampleCount) {
                if (cancelled.get()) break
                val timeMs = min(durationMs - 1, (index * stepMs).toLong())
                val frame = extractScaledFrame(retriever, timeMs, videoWidth, videoHeight)
                if (cancelled.get()) {
                    frame?.recycle()
                    break
                }

                if (frame != null) {
                    try {
                        val observation = observer.observe(frame, timeMs)
                        if (observation.faceFound) faceFrames++
                        analyzer.add(observation)
                    } finally {
                        frame.recycle()
                    }
                }

                processed++
                val now = SystemClock.uptimeMillis()
                if (processed == 1 || processed == sampleCount ||
                    now - lastProgressAt >= PROGRESS_INTERVAL_MS
                ) {
                    lastProgressAt = now
                    publish(VideoScanBus.state.copy(
                        progress = processed.toFloat() / sampleCount,
                        framesProcessed = processed,
                        faceFrames = faceFrames,
                        status = "Đã phân tích $processed/$sampleCount khung hình"
                    ))
                }
            }

            if (cancelled.get()) {
                publish(VideoScanState(status = "Đã dừng quét video"))
            } else {
                val verdict = analyzer.evaluate()
                val status = when {
                    !modelAvailable -> "Hoàn tất bằng các chỉ báo cơ bản; model AI chưa được nạp."
                    faceFrames < 5 -> "Không đủ khung hình có khuôn mặt để đưa ra đánh giá."
                    longVideo -> "Đã phân tích tối đa $MAX_SAMPLES khung hình mẫu trên toàn video."
                    else -> "Đã hoàn tất phân tích video trên thiết bị."
                }
                publish(VideoScanState(
                    running = false,
                    progress = 1f,
                    status = status,
                    framesProcessed = processed,
                    totalFrames = sampleCount,
                    faceFrames = faceFrames,
                    modelAvailable = modelAvailable,
                    modelStatus = observer.modelStatus,
                    verdict = verdict
                ))
            }
        } catch (e: Exception) {
            publish(VideoScanState(status = e.message ?: "Không thể phân tích video này"))
        } finally {
            try { observer?.close() } catch (_: Exception) { }
            try { retriever.release() } catch (_: Exception) { }
            worker = null
        }
    }

    private fun extractScaledFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        sourceWidth: Int,
        sourceHeight: Int
    ): Bitmap? {
        val timeUs = timeMs * 1_000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            sourceWidth > 0 && sourceHeight > 0
        ) {
            val scale = min(
                1.0,
                MAX_FRAME_SIDE.toDouble() / max(sourceWidth, sourceHeight)
            )
            val targetWidth = max(1, (sourceWidth * scale).roundToInt())
            val targetHeight = max(1, (sourceHeight * scale).roundToInt())
            val scaled = try {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight
                )
            } catch (_: Exception) {
                null
            }
            if (scaled != null) return scaled
            // Some vendor codecs return null for scaled extraction; use the full-frame fallback.
        }

        val original = retriever.getFrameAtTime(
            timeUs,
            MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: return null
        if (max(original.width, original.height) <= MAX_FRAME_SIDE) return original

        val scale = MAX_FRAME_SIDE.toFloat() / max(original.width, original.height)
        val width = max(1, (original.width * scale).roundToInt())
        val height = max(1, (original.height * scale).roundToInt())
        return try {
            Bitmap.createScaledBitmap(original, width, height, true)
        } finally {
            original.recycle()
        }
    }

    private fun publish(state: VideoScanState) {
        main.post { VideoScanBus.state = state }
    }
}
