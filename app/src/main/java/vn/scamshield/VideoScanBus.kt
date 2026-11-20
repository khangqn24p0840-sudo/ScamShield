package vn.scamshield

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vn.scamshield.face.FaceVerdict

data class VideoScanState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val framesProcessed: Int = 0,
    val totalFrames: Int = 0,
    val faceFrames: Int = 0,
    val modelAvailable: Boolean = false,
    val modelStatus: String = "",
    val verdict: FaceVerdict? = null
)

/** UI state for a user-initiated local video scan. No video or frame is persisted. */
object VideoScanBus {
    var state by mutableStateOf(VideoScanState())
}
