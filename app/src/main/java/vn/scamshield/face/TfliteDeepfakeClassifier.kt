package vn.scamshield.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite classifier for ScamShield's face deepfake model.
 *
 * Model contract from deepfake_config.json:
 * input: [1, 224, 224, 3], RGB float32 normalized to [-1, 1]
 * output: [1, 1] probability of Real, or [1, 2] ordered [Fake, Real].
 *
 * The public DeepfakeClassifier interface returns probability of Fake, so
 * this class converts the model's probability-of-Real output to 1 - p(Real).
 */
class TfliteDeepfakeClassifier(
    context: Context,
    assetName: String = "deepfake_model.tflite"
) : DeepfakeClassifier {

    private val interpreter: Interpreter? = loadInterpreter(context, assetName)

    override val isAvailable: Boolean
        get() = interpreter != null

    @Synchronized
    override fun fakeProbability(face: Bitmap): Float? {
        val model = interpreter ?: return null
        if (face.width <= 0 || face.height <= 0) return null

        val scaled = if (face.width == INPUT_SIZE && face.height == INPUT_SIZE) {
            face
        } else {
            Bitmap.createScaledBitmap(face, INPUT_SIZE, INPUT_SIZE, true)
        }

        return try {
            val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            for (pixel in pixels) {
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                input.putFloat(red / 127.5f - 1f)
                input.putFloat(green / 127.5f - 1f)
                input.putFloat(blue / 127.5f - 1f)
            }
            input.rewind()

            val outputSize = model.getOutputTensor(0).shape().last()
            val output = Array(1) { FloatArray(outputSize) }
            model.run(input, output)

            // Config declares probability_of_real; class order is [Fake, Real].
            val probabilityReal = if (outputSize == 1) output[0][0] else output[0][1]
            (1f - probabilityReal.coerceIn(0f, 1f))
        } catch (_: Exception) {
            null
        } finally {
            if (scaled !== face) scaled.recycle()
        }
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
    }

    private fun loadInterpreter(context: Context, assetName: String): Interpreter? {
        var candidate: Interpreter? = null
        return try {
            val modelBytes = context.assets.open(assetName).use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()

            candidate = Interpreter(modelBuffer)
            candidate.allocateTensors()

            val input = candidate.getInputTensor(0)
            require(input.dataType() == DataType.FLOAT32) {
                "Deepfake model input must be float32"
            }
            require(input.shape().contentEquals(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))) {
                "Unexpected deepfake model input shape: ${input.shape().contentToString()}"
            }

            val output = candidate.getOutputTensor(0)
            require(output.dataType() == DataType.FLOAT32) {
                "Deepfake model output must be float32"
            }
            val shape = output.shape()
            require(
                shape.contentEquals(intArrayOf(1, 1)) ||
                    shape.contentEquals(intArrayOf(1, 2))
            ) {
                "Unexpected deepfake model output shape: ${shape.contentToString()}"
            }
            candidate
        } catch (_: Exception) {
            candidate?.close()
            null
        }
    }

    companion object {
        private const val INPUT_SIZE = 224
    }
}
