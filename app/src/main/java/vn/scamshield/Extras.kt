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
    val v = when (j.optString("verdict")) { "likely_fake" -> "Nhiều khả năng ảnh bị làm giả"; "uncertain" -> "Chưa chắc chắn"; else -> "Nhiều khả năng ảnh thật" }
    return "$v (${(j.optDouble("score") * 100).toInt()}%)\n${j.optString("note")}"
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
            Text("Khảo sát nhanh (5 câu)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        }
        qs.forEachIndexed { i, (q, opts) ->
            Text(q, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
            Text("Phân tích bằng AI", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        }
        Text("Cần đăng nhập. Chỉ nội dung bạn bấm phân tích mới được gửi lên máy chủ của bạn để AI đánh giá.", fontSize = 12.sp, color = C.Sub, lineHeight = 16.sp)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("Dán tin nhắn / lời thoại nghi ngờ…") }, minLines = 3)
        GoldButton("Phân tích nội dung bằng AI", enabled = text.isNotBlank(), leading = Icons.Rounded.Bolt) {
            onMsg("Đang phân tích…"); Api.call(c, "POST", "/api/analyze/text", JSONObject().put("text", text)) { code, j -> onMsg(fmtText(code, j)) }
        }
        GhostButton("Kiểm tra ảnh có bị làm giả (qua máy chủ)", leading = Icons.Rounded.Image, onClick = onPick)
        if (aiMsg.isNotEmpty()) Text(aiMsg, fontSize = 14.sp, color = Color.White, lineHeight = 19.sp)
    }
}

@Composable fun FacePanel(onStart: () -> Unit, onStop: () -> Unit) {
    val v = FaceBus.verdict
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.Videocam, C.Cyan)
            Column {
                Text("Kiểm tra video call giả mạo", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Tính năng thử nghiệm", color = C.Dim, fontSize = 11.sp)
            }
        }
        Text("Khi đang video call, bật chế độ này: app quan sát màn hình, đo các chỉ báo trên khuôn mặt và cảnh báo nếu nghi giả mạo. Chỉ xử lý trong máy, không lưu hình. Kết quả chỉ là chỉ báo, không phải kết luận.",
            fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        if (FaceBus.running) DangerButton("Dừng kiểm tra", leading = Icons.Rounded.Stop, onClick = onStop)
        else GoldButton("Bắt đầu kiểm tra video call", leading = Icons.Rounded.PlayArrow, onClick = onStart)
        if (FaceBus.running && v != null) {
            val col = when (v.level) { FaceLevel.HIGH -> C.Bad; FaceLevel.SUSPICIOUS -> C.Warn; FaceLevel.OK -> C.Mint; else -> Color.White }
            Text(v.headline, color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (v.progress < 1f) {
                Text("Tiến độ thu thập: ${(v.progress * 100).toInt()}%", fontSize = 12.sp, color = C.Sub)
                ScoreBar(v.progress, C.Cyan)
            } else {
                Text("Điểm rủi ro: ${(v.probability * 100).toInt()}% • ${"%.0f".format(v.fps)} khung/giây", fontSize = 12.sp, color = C.Sub)
                ScoreBar(v.probability.toFloat(), col)
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
