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
import kotlin.math.roundToInt

/**
 * TFLite adapter driven by deepfake_config.json.
 * Input/output buffers are reused between frames to reduce per-frame allocations.
 */
class TfliteDeepfakeClassifier(context: Context) : DeepfakeClassifier {

    private data class RuntimeModel(
        val interpreter: Interpreter,
        val inputSize: Int,
        val inputType: DataType,
        val normalization: String,
        val outputContract: String,
        val fakeClassIndex: Int,
        val inputScale: Float,
        val inputZeroPoint: Int,
        val inputBuffer: ByteBuffer,
        val pixels: IntArray,
        val output: Array<FloatArray>
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
        var interpreter: Interpreter? = null
        var descriptor: android.content.res.AssetFileDescriptor? = null
        return try {
            val config = context.assets.open(CONFIG_FILE).bufferedReader().use {
                JSONObject(it.readText())
            }
            val inputSize = config.optInt("input_size", 224)
            require(inputSize in 32..1024) { "Invalid input_size" }
            require(config.optInt("input_channels", 3) == 3) { "Only RGB input is supported" }
            require(config.optString("input_format", "RGB").equals("RGB", true)) {
                "Only RGB input is supported"
            }

            val normalization = config.optString("normalization", "[-1, 1]")
            require(normalization in setOf("[-1, 1]", "[0, 1]", "0..255")) {
                "Unsupported normalization"
            }
            val outputContract = config.optString("output", "")
            require(outputContract in setOf("probability_of_real", "probability_of_fake")) {
                "Unsupported output contract"
            }

            val names = config.optJSONArray("class_names")
            val fakeIndices = if (names == null) emptyList() else {
                (0 until names.length()).filter {
                    names.optString(it).equals("fake", ignoreCase = true)
                }
            }

            descriptor = context.assets.openFd(MODEL_FILE)
            val mapped = FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
            descriptor.close()
            descriptor = null

            interpreter = Interpreter(mapped, Interpreter.Options().setNumThreads(2))
            val input = interpreter.getInputTensor(0)
            val shape = input.shape()
            require(
                shape.size == 4 && shape[0] == 1 &&
                    shape[1] == inputSize && shape[2] == inputSize && shape[3] == 3
            ) { "Model input must be [1,size,size,3]" }

            val inputType = input.dataType()
            require(inputType == DataType.FLOAT32 || inputType == DataType.UINT8) {
                "Unsupported input tensor type"
            }
            val quant = input.quantizationParams()
            val inputScale = quant.scale
            val inputZeroPoint = quant.zeroPoint
            if (inputType == DataType.UINT8) {
                require(inputScale > 0f && inputScale.isFinite()) {
                    "Invalid UINT8 input quantization scale"
                }
            }

            val outputTensor = interpreter.getOutputTensor(0)
            val outputCount = outputTensor.shape().fold(1) { acc, value -> acc * value }
            require(outputCount in 1..2) { "Model output must contain one or two values" }
            require(outputTensor.dataType() == DataType.FLOAT32) {
                "Unsupported output tensor type"
            }
            val fakeIndex = if (outputCount == 2) {
                require(names != null && names.length() == 2 && fakeIndices.size == 1) {
                    "Two-value output requires exactly one Fake class in class_names"
                }
                fakeIndices.single()
            } else {
                0
            }

            val channelCount = inputSize * inputSize * 3
            val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
            val inputBuffer = ByteBuffer.allocateDirect(channelCount * bytesPerChannel)
                .order(ByteOrder.nativeOrder())

            val loadedModel = RuntimeModel(
                interpreter = interpreter,
                inputSize = inputSize,
                inputType = inputType,
                normalization = normalization,
                outputContract = outputContract,
                fakeClassIndex = fakeIndex,
                inputScale = inputScale,
                inputZeroPoint = inputZeroPoint,
                inputBuffer = inputBuffer,
                pixels = IntArray(inputSize * inputSize),
                output = Array(1) { FloatArray(outputCount) }
            )
            interpreter = null
            LoadResult(loadedModel, null)
        } catch (e: Exception) {
            try { descriptor?.close() } catch (_: Exception) { }
            try { interpreter?.close() } catch (_: Exception) { }
            LoadResult(null, e.message?.take(160) ?: e.javaClass.simpleName)
        }
    }

    @Synchronized
    override fun fakeProbability(face: Bitmap): Float? {
        val model = runtime ?: return null
        if (face.width < 16 || face.height < 16) return null

        return try {
            val scaled = if (face.width == model.inputSize && face.height == model.inputSize) {
                face
            } else {
                Bitmap.createScaledBitmap(face, model.inputSize, model.inputSize, true)
            }

            try {
                model.inputBuffer.clear()
                scaled.getPixels(
                    model.pixels, 0, model.inputSize,
                    0, 0, model.inputSize, model.inputSize
                )
                for (pixel in model.pixels) {
                    putChannel(model.inputBuffer, (pixel shr 16) and 0xFF, model)
                    putChannel(model.inputBuffer, (pixel shr 8) and 0xFF, model)
                    putChannel(model.inputBuffer, pixel and 0xFF, model)
                }
                model.inputBuffer.rewind()
                model.output[0].fill(0f)
                model.interpreter.run(model.inputBuffer, model.output)

                val raw = model.output[0]
                if (raw.any { !it.isFinite() || it !in 0f..1f }) return null

                val fake = if (raw.size == 1) {
                    if (model.outputContract == "probability_of_real") 1f - raw[0] else raw[0]
                } else {
                    raw[model.fakeClassIndex]
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
        val normalized = when (model.normalization) {
            "[-1, 1]" -> value / 127.5f - 1f
            "[0, 1]" -> value / 255f
            else -> value.toFloat()
        }
        if (model.inputType == DataType.UINT8) {
            val quantized = (normalized / model.inputScale + model.inputZeroPoint).roundToInt()
            buffer.put(quantized.coerceIn(0, 255).toByte())
        } else {
            buffer.putFloat(normalized)
        }
    }

    @Synchronized
    override fun close() {
        try { runtime?.interpreter?.close() } catch (_: Exception) { }
    }

    companion object {
        const val MODEL_FILE = "deepfake_model.tflite"
        const val CONFIG_FILE = "deepfake_config.json"
    }
}
