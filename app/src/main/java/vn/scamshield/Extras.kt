package vn.scamshield
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import vn.scamshield.face.FaceBus
import vn.scamshield.face.FaceLevel

/** Thẻ kính tối chuẩn dùng cho các panel. */
@Composable fun Glass(content: @Composable ColumnScope.() -> Unit) = CardX(content = content)

fun err(j: JSONObject?) = j?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Không kết nối được máy chủ"

fun fmtText(code: Int, j: JSONObject?): String {
    if (code !in 200..299 || j == null) return "Lỗi: " + err(j)
    val r = j.optJSONArray("reasons") ?: JSONArray()
    val rs = (0 until r.length()).joinToString("\n") { "• " + r.getString(it) }
    val lbl = when (j.optString("label")) { "scam" -> "Rất có thể là lừa đảo"; "safe" -> "Có vẻ an toàn"; else -> "Đáng ngờ" }
    return "$lbl (${j.optInt("risk")}/100)\n$rs\n${j.optString("advice")}"
}

fun fmtMedia(code: Int, j: JSONObject?): String {
    if (code !in 200..299 || j == null) return "Lỗi: " + err(j)

    // API mới trả riêng nguy cơ lừa đảo và dấu hiệu ảnh bị chỉnh sửa.
    // Không gắn nhãn "an toàn" khi model Vision chưa thực sự phân tích được.
    if (j.has("scam_score")) {
        val aiAvailable = j.optBoolean("ai", false)
        val score = j.optInt("scam_score", 0).coerceIn(0, 100)
        val reasonsJson = j.optJSONArray("scam_reasons") ?: JSONArray()
        val reasons = (0 until reasonsJson.length()).mapNotNull { index ->
            reasonsJson.optString(index).takeIf { it.isNotBlank() }
        }
        if (!aiAvailable) {
            return buildString {
                append("Chưa có kết quả phân tích rủi ro lừa đảo bằng AI.")
                val note = j.optString("note").trim()
                if (note.isNotBlank()) append("\n").append(note)
                append("\nKhông xem ảnh này là an toàn chỉ vì chưa phân tích được.")
            }
        }
        val verdict = when (j.optString("scam_verdict")) {
            "likely_scam" -> "NGUY CƠ LỪA ĐẢO CAO"
            "suspicious" -> "CÓ DẤU HIỆU ĐÁNG NGỜ"
            else -> "CHƯA THẤY DẤU HIỆU LỪA ĐẢO RÕ RÀNG"
        }
        return buildString {
            append(verdict).append(" (").append(score).append("/100)")
            if (reasons.isNotEmpty()) append("\nDấu hiệu:\n").append(reasons.joinToString("\n") { "• $it" })
            val note = j.optString("note").trim()
            if (note.isNotBlank()) append("\n").append(note)
            val action = j.optString("recommended_action").trim()
            if (action.isNotBlank()) append("\nKhuyến nghị: ").append(action)
            if (j.optString("fake_verdict").isNotBlank()) {
                val integrity = when (j.optString("fake_verdict")) {
                    "likely_fake" -> "Ảnh có dấu hiệu bị chỉnh sửa/ghép."
                    "uncertain" -> "Chưa xác định được tính nguyên vẹn của ảnh."
                    else -> "Không thấy dấu hiệu chỉnh sửa rõ ràng."
                }
                append("\n").append(integrity)
            }
            append("\nLưu ý: không thể xác minh chủ tài khoản ngân hàng hoặc nội dung mã QR chỉ từ hình ảnh.")
        }
    }

    // Backend cũ chỉ trả đánh giá tính nguyên vẹn ảnh, không đánh giá nguy cơ lừa đảo.
    // Không được diễn giải "ảnh có vẻ thật" thành "nội dung chuyển tiền an toàn".
    val integrity = when (j.optString("verdict")) {
        "likely_fake" -> "Có dấu hiệu ảnh bị chỉnh sửa/ghép"
        "uncertain" -> "Chưa xác định được tính nguyên vẹn của ảnh"
        else -> "Chưa thấy dấu hiệu chỉnh sửa rõ ràng"
    }
    return buildString {
        append("Đánh giá ảnh: ").append(integrity)
        append(" (").append((j.optDouble("score") * 100).toInt()).append("%)")
        val note = j.optString("note").trim()
        if (note.isNotBlank()) append("\\n").append(note)
        append("\\nMáy chủ hiện chưa đánh giá nguy cơ lừa đảo trong nội dung ảnh. Không dùng kết quả này để xác nhận QR, tài khoản hoặc yêu cầu chuyển tiền là an toàn.")
    }
}

fun fmtVideo(code: Int, j: JSONObject?): String {
    if (code !in 200..299 || j == null) return "Chưa phân tích được video: " + err(j)
    val frames = j.optInt("frames_analyzed", 0)
    val confidence = j.optString("confidence", "LOW").uppercase()
    val note = j.optString("note").trim()
    val verdict = when (j.optString("verdict")) {
        "likely_fake" -> "NGHI NGỜ VIDEO GIẢ MẠO"
        "suspicious" -> "VIDEO CÓ DẤU HIỆU ĐÁNG NGỜ"
        "no_obvious_signs" -> "CHƯA THẤY DẤU HIỆU GIẢ MẠO RÕ RÀNG"
        else -> "CHƯA THỂ XÁC MINH VIDEO"
    }
    return buildString {
        append(verdict)
        if (frames > 0) append('\n').append("Đã đối chiếu ").append(frames).append(" khung hình")
        append('\n').append("Độ tin cậy đánh giá: ").append(confidence)
        if (note.isNotBlank()) append('\n').append("Nhận xét: ").append(note)
        append('\n').append("Không thể xác nhận người trong video là thật chỉ dựa vào kết quả này. Với cuộc gọi liên quan tiền bạc hoặc OTP, hãy xác minh lại bằng số liên hệ đã lưu.")
    }
}

@Composable fun SurveyPanel(c: Context) {
    val qs = listOf("Nhóm tuổi của bạn" to listOf("Dưới 18", "18–30", "31–50", "Trên 50"),
        "Bạn nhận cuộc gọi từ số lạ thường xuyên không?" to listOf("Hiếm", "Thỉnh thoảng", "Hằng ngày"),
        "Bạn từng bị lừa chưa?" to listOf("Chưa", "Suýt bị", "Đã mất tiền"),
        "Kênh bạn dễ bị tiếp cận nhất" to listOf("Cuộc gọi", "SMS", "Zalo/Messenger", "Mạng xã hội khác"),
        "Mức tự tin nhận biết lừa đảo" to listOf("Thấp", "Vừa", "Cao"))
    val sel = remember { mutableStateListOf("", "", "", "", "") }
    var msg by remember { mutableStateOf("") }
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.FactCheck, C.Cyan)
            Text("Khảo sát nhanh (5 câu)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.Text)
        }
        qs.forEachIndexed { i, (q, opts) ->
            Text(q, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            opts.forEach { o -> Row(Modifier.fillMaxWidth().clickable { sel[i] = o }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sel[i] == o, { sel[i] = o }); Text(o, fontSize = 14.sp, color = C.Sub) } }
        }
        GoldButton("Gửi khảo sát", leading = Icons.Rounded.Send, enabled = sel.all { it.isNotEmpty() }) {
            Api.call(c, "POST", "/api/survey", JSONObject().put("answers", JSONArray(sel.toList()))) { code, j ->
                msg = if (code in 200..299) "Cảm ơn bạn đã tham gia!" else err(j) + " (cần đăng nhập trước)" }
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = C.Warn)
    }
}

@Composable fun AiPanel(c: Context, aiMsg: String, onPick: () -> Unit, onMsg: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.Psychology, C.Violet)
            Text("Phân tích bằng AI", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.Text)
        }
        Text("Cần đăng nhập. Chỉ nội dung bạn bấm phân tích mới được gửi lên máy chủ của bạn để AI đánh giá.", fontSize = 12.sp, color = C.Sub, lineHeight = 16.sp)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("Dán tin nhắn / lời thoại nghi ngờ…") }, minLines = 3)
        GoldButton("Phân tích nội dung bằng AI", enabled = text.isNotBlank(), leading = Icons.Rounded.Bolt) {
            onMsg("Đang phân tích…"); Api.call(c, "POST", "/api/analyze/text", JSONObject().put("text", text)) { code, j -> onMsg(fmtText(code, j)) }
        }
        GhostButton("Kiểm tra ảnh có bị làm giả (qua máy chủ)", leading = Icons.Rounded.Image, onClick = onPick)
        if (aiMsg.isNotEmpty()) Text(aiMsg, fontSize = 14.sp, color = C.Text, lineHeight = 19.sp)
    }
}

@Composable fun FacePanel(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPickVideo: () -> Unit,
    videoMessage: String,
    onVideoMessage: (String) -> Unit
) {
    val v = FaceBus.verdict
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.Videocam, C.Cyan)
            Column {
                Text("Kiểm tra video deepfake", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.Text)
                Text("Phân tích video có sẵn hoặc theo dõi video call", color = C.Dim, fontSize = 11.sp)
            }
        }
        Text("Video được lấy tối đa 8 khung hình đại diện để AI đối chiếu. Chỉ các khung hình đã nén được gửi lên máy chủ; không gửi âm thanh hay toàn bộ video. Kết quả là chỉ báo, không phải xác minh danh tính.",
            fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        GoldButton("Chọn video từ thư viện (1–30 giây)", leading = Icons.Rounded.VideoLibrary) {
            onVideoMessage("")
            onPickVideo()
        }
        if (videoMessage.isNotBlank()) {
            Text(videoMessage, color = C.Text, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Divider(color = C.Stroke)
        Text("Phân tích trực tiếp trong cuộc gọi", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("Bật chế độ này khi đang video call. ScamShield quan sát màn hình sau khi bạn đồng ý chia sẻ, rồi phân tích các chỉ báo khuôn mặt trong máy.",
            fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        if (FaceBus.running) DangerButton("Dừng kiểm tra", leading = Icons.Rounded.Stop, onClick = onStop)
        else GoldButton("Bắt đầu kiểm tra video call", leading = Icons.Rounded.PlayArrow, onClick = onStart)
        if (FaceBus.running && v != null) {
            val col = when (v.level) {
                FaceLevel.HIGH -> C.Bad
                FaceLevel.SUSPICIOUS, FaceLevel.UNVERIFIED -> C.Warn
                FaceLevel.OK -> C.Mint
                else -> C.Dim
            }
            Text(v.headline, color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            when (v.level) {
                FaceLevel.ANALYZING -> {
                    Text("Đang thu thập dữ liệu — chưa có kết luận an toàn (${(v.progress * 100).toInt()}%)", fontSize = 12.sp, color = C.Sub)
                    ScoreBar(v.progress, C.Cyan)
                }
                FaceLevel.UNVERIFIED -> {
                    Text("Không đủ cơ sở xác nhận người trong cuộc gọi là thật. Bản hiện tại chưa có bộ phân loại deepfake chuyên dụng đang hoạt động.",
                        fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
                }
                FaceLevel.NO_FACE -> Text("Không thấy khuôn mặt đủ rõ để đánh giá. Đây không phải kết quả an toàn.", fontSize = 12.sp, color = C.Sub)
                else -> {
                    Text("Điểm chỉ báo rủi ro: ${(v.probability * 100).toInt()}% • ${"%.0f".format(v.fps)} khung/giây",
                        fontSize = 12.sp, color = C.Sub)
                    ScoreBar(v.probability.toFloat(), col)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                v.indicators.forEach { ind ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(4.dp).background(col, CircleShape))
                        Text("${ind.label}: ${ind.detail}", fontSize = 12.sp, color = C.Sub)
                    }
                }
            }
            if (v.advice.isNotEmpty()) Text(v.advice, fontSize = 12.sp, color = C.Dim, lineHeight = 16.sp)
        } else if (FaceBus.running) {
            Text("ScamShield đang quan sát màn hình. Chưa có đủ dữ liệu để đánh giá — không xem cuộc gọi là an toàn.",
                fontSize = 12.sp, color = C.Warn, lineHeight = 17.sp)
        }
    }
}

/** Tải danh sách số lừa đảo cộng đồng từ máy chủ (app tự thêm dấu + trước mỗi số). */
fun syncCommunity(c: Context, onMsg: (String) -> Unit) {
    onMsg("Đang tải…")
    Api.call(c, "GET", "/api/blocklist", null) { code, j ->
        if (code == 200 && j != null) {
            val a = j.optJSONArray("numbers") ?: JSONArray()
            for (i in 0 until a.length()) Store.addBlock(c, "+" + a.getString(i))
            onMsg("Đã thêm ${a.length()} số lừa đảo từ cộng đồng")
        } else onMsg(err(j))
    }
}
