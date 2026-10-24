package vn.scamshield

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Mô hình học máy TFLite đọc tin nhắn/lời thoại và trả xác suất "giống lừa đảo" (0..1).
 * File mô hình: assets/scam_text.tflite. Thiếu file hoặc nạp lỗi -> predict() trả null, app dùng bộ quy tắc như cũ.
 * Cách tính đặc trưng PHẢI giống ml/features.py.
 */
object TextModel {
    private const val DIM = 4096
    private const val ASSET = "scam_text.tflite"
    private val TOKEN = Regex("[a-z0-9]+")
    private var appCtx: Context? = null
    private var interp: Interpreter? = null
    private var tried = false

    /** Trạng thái để kiểm tra khi gỡ lỗi: "chưa nạp" | "đã nạp" | "lỗi: ..." */
    @Volatile var status = "chưa nạp"
        private set

    fun init(c: Context) { appCtx = c.applicationContext }

    @Synchronized private fun load(): Interpreter? {
        if (tried) return interp
        val c = appCtx ?: return null
        tried = true
        try {
            val bytes = c.assets.open(ASSET).use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes); buf.rewind()
            interp = Interpreter(buf, Interpreter.Options().setNumThreads(1))
            status = "đã nạp"
        } catch (e: Throwable) {
            interp = null; status = "lỗi: ${e.message}"
        }
        return interp
    }

    private fun hash(s: String): Int {
        var h = 0x811C9DC5L
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xFF); h = (h * 0x01000193L) and 0xFFFFFFFFL }
        return (h % DIM).toInt()
    }

    fun features(text: String): FloatArray {
        val words = TOKEN.findAll(ScamDetector.norm(text.take(2000))).map { it.value }.toList()
        val counts = HashMap<Int, Int>()
        fun add(f: String) { val i = hash(f); counts[i] = (counts[i] ?: 0) + 1 }
        for (i in words.indices) {
            add("u:" + words[i])
            if (i + 1 < words.size) add("b:" + words[i] + " " + words[i + 1])
        }
        val x = FloatArray(DIM)
        var ss = 0.0
        for ((i, c) in counts) { val v = ln(1.0 + c).toFloat(); x[i] = v; ss += v.toDouble() * v }
        val n = sqrt(ss).toFloat()
        if (n > 0f) for (i in counts.keys) x[i] /= n
        return x
    }

    /** Xác suất lừa đảo 0..1, hoặc null nếu chưa có mô hình / lỗi. */
    @Synchronized fun predict(text: String): Float? {
        if (text.isBlank()) return null
        val m = load() ?: return null
        return try {
            val out = Array(1) { FloatArray(1) }
            m.run(arrayOf(features(text)), out)
            out[0][0]
        } catch (e: Throwable) { status = "lỗi: ${e.message}"; null }
    }
}
