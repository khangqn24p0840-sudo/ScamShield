package vn.scamshield
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    /** Địa chỉ máy chủ đang dùng: người dùng tự đặt (mục Nâng cao) thì ưu tiên, không thì dùng mặc định trong Config. */
    fun server(c: Context): String {
        val custom = Store.str(c, "server").trim().trimEnd('/')
        return if (custom.isNotEmpty()) custom else Config.SERVER_URL.trim().trimEnd('/')
    }

    /** Gọi máy chủ ScamShield (chỉ HTTPS). cb chạy trên luồng chính. */
    fun call(c: Context, method: String, path: String, body: JSONObject?, cb: (Int, JSONObject?) -> Unit) {
        val base = Api.server(c); val token = Store.str(c, "token")
        Thread {
            var code = -1; var out: JSONObject? = null
            try {
                if (!base.startsWith("https://")) throw Exception("Địa chỉ máy chủ phải bắt đầu bằng https://")
                val con = URL(base + path).openConnection() as HttpURLConnection
                con.requestMethod = method; con.connectTimeout = 10000; con.readTimeout = 60000
                con.setRequestProperty("Content-Type", "application/json")
                con.setRequestProperty("ngrok-skip-browser-warning", "1")   // bỏ trang cảnh báo của ngrok (gói miễn phí)
                con.setRequestProperty("User-Agent", "ScamShieldVN-App")
                if (token.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) { con.doOutput = true; con.outputStream.use { it.write(body.toString().toByteArray()) } }
                code = con.responseCode
                val s = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText()
                out = s?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            } catch (e: Exception) { out = JSONObject().put("error", e.message ?: "Không kết nối được máy chủ") }
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
