package vn.scamshield

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import vn.scamshield.face.FaceLivenessAnalyzer
import vn.scamshield.face.FaceObserver
import vn.scamshield.face.TfliteDeepfakeClassifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Samples a user-selected video locally and feeds the same FaceObserver + analyzer
 * used by live screen monitoring. Frames are processed one at a time and never saved.
 */
object VideoFileScanner {
    private const val MAX_SAMPLES = 120
    private const val MIN_DURATION_MS = 4_000L
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start(context: Context, uri: Uri) {
        if (VideoScanBus.state.running) return
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
            require(durationMs >= MIN_DURATION_MS) { "Video quá ngắn hoặc không đọc được thời lượng (tối thiểu 4 giây)." }

            val samples = min(
                MAX_SAMPLES,
                max(5, ceil(durationMs / 1_000.0).toInt())
            )
            val step = durationMs.toDouble() / samples
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
            publish(VideoScanState(
                running = true,
                status = if (modelAvailable) "Đang phân tích bằng AI trên thiết bị…" else
                    "Đang quét chỉ báo cơ bản; model AI chưa khả dụng",
                totalFrames = samples,
                modelAvailable = modelAvailable,
                modelStatus = observer.modelStatus
            ))

            var processed = 0
            var faceFrames = 0
            for (index in 0 until samples) {
                if (cancelled.get()) break
                val timeMs = min(durationMs - 1, (index * step).toLong())
                val frame = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
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
                publish(VideoScanBus.state.copy(
                    progress = processed.toFloat() / samples,
                    framesProcessed = processed,
                    faceFrames = faceFrames,
                    status = "Đã phân tích $processed/$samples khung hình"
                ))
            }

            if (cancelled.get()) {
                publish(VideoScanState(status = "Đã dừng quét video"))
            } else {
                val verdict = analyzer.evaluate()
                val status = when {
                    !modelAvailable -> "Hoàn tất bằng các chỉ báo cơ bản; model AI chưa được nạp."
                    faceFrames < 5 -> "Không đủ khung hình có khuôn mặt để đưa ra đánh giá."
                    else -> "Đã hoàn tất phân tích video trên thiết bị."
                }
                publish(VideoScanState(
                    running = false,
                    progress = 1f,
                    status = status,
                    framesProcessed = processed,
                    totalFrames = samples,
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

    private fun publish(state: VideoScanState) {
        main.post { VideoScanBus.state = state }
    }
}
