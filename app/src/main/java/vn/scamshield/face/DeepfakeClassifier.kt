package vn.scamshield.face

import android.graphics.Bitmap

/**
 * Interface for a deep-learning face classifier.
 * @return probability that the supplied face is Fake (0..1), or null if unavailable.
 */
interface DeepfakeClassifier {
    val isAvailable: Boolean
    fun fakeProbability(face: Bitmap): Float?

    /** Release resources owned by an implementation. */
    fun close() {}
}

object NoopClassifier : DeepfakeClassifier {
    override val isAvailable: Boolean = false
    override fun fakeProbability(face: Bitmap): Float? = null
}
