package vn.scamshield
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    /** Địa chỉ máy chủ được cố định trong mã nguồn; người dùng không cần cấu hình trong app. */
    fun server(c: Context): String = Config.SERVER_URL.trim().trimEnd('/')

    /** Gọi máy chủ ScamShield (chỉ HTTPS). cb chạy trên luồng chính. */
    fun call(c: Context, method: String, path: String, body: JSONObject?, cb: (Int, JSONObject?) -> Unit) {
        val base = Api.server(c); val token = Store.str(c, "token")
        Thread {
            var code = -1
            var out: JSONObject? = null
            var connection: HttpURLConnection? = null
            try {
                if (!base.startsWith("https://")) throw Exception("Địa chỉ máy chủ phải bắt đầu bằng https://")
                val con = URL(base + path).openConnection() as HttpURLConnection
                connection = con
                con.requestMethod = method
                con.connectTimeout = 10000
                con.readTimeout = 60000
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                con.setRequestProperty("Accept", "application/json")
                con.setRequestProperty("ngrok-skip-browser-warning", "1")
                con.setRequestProperty("User-Agent", "ScamShieldVN-App")
                if (token.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    con.doOutput = true
                    con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                code = con.responseCode
                val stream = if (code in 200..299) con.inputStream else con.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                out = response?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            } catch (e: Exception) {
                out = JSONObject().put("error", e.message ?: "Không kết nối được máy chủ")
            } finally {
                connection?.disconnect()
            }
            Handler(Looper.getMainLooper()).post { cb(code, out) }
        }.start()
    }
}

/** Gửi thống kê ẩn danh (chỉ loại + mức) khi người dùng đã đăng nhập và đồng ý. */
object Sync {
    fun kindOf(t: String): String { val s = t.lowercase(); return when {
        "đang diễn ra" in s -> "voice"; "khuôn mặt" in s -> "face"; "giao dịch" in s || "nhiều số lạ" in s -> "habit"
        " trên " in s -> "social"; "nhắn tin" in s || "tin nhắn" in s -> "sms"; else -> "call" } }
    fun push(c: Context, level: Int, title: String) {
        if (!Store.flag(c, "consent") || Store.str(c, "token").isEmpty()) return
        Api.call(c, "POST", "/api/events", JSONObject().put("kind", kindOf(title)).put("level", if (level >= 2) 2 else 1)) { _, _ -> }
    }
}
