package vn.scamshield.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional TFLite adapter for ml/deepfake/train_deepfake.py.
 * Contract: float32 [1,224,224,3] RGB pixels 0..255 -> [1,1] sigmoid fake probability.
 */
class TfliteDeepfakeClassifier(context: Context) : DeepfakeClassifier, Closeable {
    private var interpreter: Interpreter? = null
    override val isAvailable: Boolean
        get() = interpreter != null

    init {
        try {
            val model = context.assets.open("deepfake_face.tflite").use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
            buffer.put(model)
            buffer.rewind()
            val candidate = Interpreter(buffer, Interpreter.Options().setNumThreads(2))
            val input = candidate.getInputTensor(0)
            val output = candidate.getOutputTensor(0)
            require(input.dataType() == DataType.FLOAT32)
            require(input.shape().contentEquals(intArrayOf(1, 224, 224, 3)))
            require(output.dataType() == DataType.FLOAT32)
            require(output.shape().contentEquals(intArrayOf(1, 1)))
            interpreter = candidate
        } catch (_: Exception) {
            interpreter?.close()
            interpreter = null
        }
    }

    @Synchronized
    override fun fakeProbability(face: Bitmap): Float? {
        val model = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(face, 224, 224, true)
            val pixels = IntArray(224 * 224)
            resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            if (resized !== face) resized.recycle()
            val input = ByteBuffer.allocateDirect(1 * 224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16) and 0xff).toFloat())
                input.putFloat(((pixel shr 8) and 0xff).toFloat())
                input.putFloat((pixel and 0xff).toFloat())
            }
            input.rewind()
            val output = Array(1) { FloatArray(1) }
            model.run(input, output)
            output[0][0].takeIf { it.isFinite() && it in 0f..1f }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
