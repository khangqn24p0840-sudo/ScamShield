package vn.scamshield.face

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
 
/**
 * TFLite adapter driven by the repository's deepfake_config.json.
 *
 * The model is optional at runtime: a missing or incompatible model disables ML scoring
 * without crashing the rest of ScamShield. The configured output contract is interpreted
 * explicitly; no implicit assumption that a scalar output means "fake" is made.
 */
class TfliteDeepfakeClassifier(context: Context) : DeepfakeClassifier {

    private data class RuntimeModel(
        val interpreter: Interpreter,
        val inputSize: Int,
        val inputType: DataType,
        val outputCount: Int,
        val normalization: String,
        val outputContract: String,
        val fakeClassIndex: Int
    )

    private data class LoadResult(val runtime: RuntimeModel?, val error: String?)

    private val loaded = load(context.applicationContext)
    private val runtime: RuntimeModel? = loaded.runtime
    override val status: String = if (runtime != null) {
        "Model Deepfake đã sẵn sàng"
    } else {
        "Không thể nạp model Deepfake: ${loaded.error ?: "không rõ nguyên nhân"}"
    }
    override val isAvailable: Boolean get() = runtime != null

    private fun load(context: Context): LoadResult {
        return try {
            val config = context.assets.open(CONFIG_FILE).bufferedReader().use { JSONObject(it.readText()) }
            val inputSize = config.optInt("input_size", 224)
            require(inputSize in 32..1024) { "Invalid input_size" }
            val normalization = config.optString("normalization", "[-1, 1]")
            require(normalization in setOf("[-1, 1]", "[0, 1]", "0..255")) { "Unsupported normalization" }
            val outputContract = config.optString("output", "")
            require(outputContract in setOf("probability_of_real", "probability_of_fake")) {
                "Unsupported output contract"
            }

            val names = config.optJSONArray("class_names")
            val fakeIndex = if (names != null) {
                (0 until names.length()).firstOrNull {
                    names.optString(it).equals("fake", ignoreCase = true)
                } ?: 0
            } else 0

            val descriptor = context.assets.openFd(MODEL_FILE)
            val mapped = FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }.also { descriptor.close() }
            val options = Interpreter.Options().setNumThreads(2)
            val interpreter = Interpreter(mapped, options)
            try {
                val input = interpreter.getInputTensor(0)
                val shape = input.shape()
                require(shape.size == 4 && shape[0] == 1 && shape[1] == inputSize &&
                    shape[2] == inputSize && shape[3] == 3) { "Model input must be [1,size,size,3]" }
                require(input.dataType() == DataType.FLOAT32 || input.dataType() == DataType.UINT8) {
                    "Unsupported input tensor type"
                }
                val output = interpreter.getOutputTensor(0)
                val outputCount = output.shape().fold(1) { acc, value -> acc * value }
                require(outputCount in 1..2) { "Model output must contain one or two values" }
                require(output.dataType() == DataType.FLOAT32) { "Unsupported output tensor type" }
                if (outputCount == 2) require(fakeIndex in 0..1) { "Invalid fake class index" }
                LoadResult(
                    RuntimeModel(
                        interpreter, inputSize, input.dataType(), outputCount,
                        normalization, outputContract, fakeIndex
                    ),
                    null
                )
            } catch (e: Exception) {
                interpreter.close()
                throw e
            }
        } catch (e: Exception) {
            LoadResult(null, e.message?.take(160) ?: e.javaClass.simpleName)
        }
    }

    @Synchronized
    override fun fakeProbability(face: Bitmap): Float? {
        val model = runtime ?: return null
        if (face.width < 16 || face.height < 16) return null
        return try {
            val scaled = Bitmap.createScaledBitmap(face, model.inputSize, model.inputSize, true)
            try {
                val channels = model.inputSize * model.inputSize * 3
                val bytesPerChannel = if (model.inputType == DataType.FLOAT32) 4 else 1
                val input = ByteBuffer.allocateDirect(channels * bytesPerChannel)
                    .order(ByteOrder.nativeOrder())
                val pixels = IntArray(model.inputSize * model.inputSize)
                scaled.getPixels(pixels, 0, model.inputSize, 0, 0, model.inputSize, model.inputSize)
                for (pixel in pixels) {
                    putChannel(input, (pixel shr 16) and 0xFF, model)
                    putChannel(input, (pixel shr 8) and 0xFF, model)
                    putChannel(input, pixel and 0xFF, model)
                }
                input.rewind()
                val output = Array(1) { FloatArray(model.outputCount) }
                model.interpreter.run(input, output)
                val raw = output[0]
                if (raw.any { !it.isFinite() }) return null
                val fake = if (model.outputCount == 1) {
                    val value = raw[0]
                    if (value !in 0f..1f) return null
                    if (model.outputContract == "probability_of_real") 1f - value else value
                } else {
                    val value = raw[model.fakeClassIndex]
                    if (value !in 0f..1f) return null
                    value
                }
                fake.coerceIn(0f, 1f)
            } finally {
                if (scaled !== face) scaled.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun putChannel(buffer: ByteBuffer, value: Int, model: RuntimeModel) {
        if (model.inputType == DataType.UINT8) {
            buffer.put(value.toByte())
        } else {
            val normalized = when (model.normalization) {
                "[-1, 1]" -> value / 127.5f - 1f
                "[0, 1]" -> value / 255f
                else -> value.toFloat()
            }
            buffer.putFloat(normalized)
        }
    }

    override fun close() {
        runtime?.interpreter?.close()
    }

    companion object {
        const val MODEL_FILE = "deepfake_model.tflite"
        const val CONFIG_FILE = "deepfake_config.json"
    }
}
