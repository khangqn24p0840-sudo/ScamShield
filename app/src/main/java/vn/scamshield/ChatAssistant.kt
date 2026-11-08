package vn.scamshield

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private data class ChatLine(val role: String, val text: String)

/** Nút AI nổi dùng chung ở lớp ShieldApp nên luôn hiện khi chuyển tab/trang con. */
@Composable
fun FloatingChatAssistant(c: Context, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<ChatLine>() }
    val scroll = androidx.compose.foundation.rememberScrollState()

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        if (expanded) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 88.dp)
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .heightIn(maxHeight = 560.dp),
                shape = RoundedCornerShape(24.dp),
                color = C.Card,
                tonalElevation = 10.dp,
                shadowElevation = 18.dp
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = C.Gold.copy(alpha = 0.16f)) {
                            Icon(Icons.Rounded.SmartToy, null, tint = C.Gold, modifier = Modifier.padding(9.dp).size(22.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ScamShield AI", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Trợ lý AI đa năng", color = C.Sub, fontSize = 11.sp)
                        }
                        IconButton(onClick = { expanded = false }) {
                            Icon(Icons.Rounded.Close, "Đóng", tint = C.Sub)
                        }
                    }
                    HorizontalDivider(color = C.Stroke)
                    if (lines.isEmpty()) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Xin chào! Tôi có thể giúp gì cho bạn?", color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Bạn có thể hỏi về học tập, công nghệ hoặc nhờ giải thích cảnh báo lừa đảo.",
                                color = C.Sub, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            itemsIndexed(lines) { _, line ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement =
                                    if (line.role == "user") Arrangement.End else Arrangement.Start) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (line.role == "user") C.Gold.copy(alpha = 0.18f) else C.CardHi,
                                        modifier = Modifier.widthIn(max = 290.dp)
                                    ) {
                                        Text(line.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                            color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (loading) {
                        Text("AI đang trả lời…", color = C.Sub, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(2500) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Nhập câu hỏi…", fontSize = 13.sp) },
                            maxLines = 3,
                            shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (draft.isNotBlank() && !loading) sendChat(lines, draft, c) { loading = it; if (!it) draft = "" }
                            })
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            enabled = draft.isNotBlank() && !loading,
                            onClick = { sendChat(lines, draft, c) { loading = it; if (!it) draft = "" } }
                        ) {
                            Icon(Icons.Rounded.Send, "Gửi", tint = if (draft.isNotBlank() && !loading) C.Gold else C.Dim)
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 88.dp).size(54.dp),
            containerColor = C.Gold,
            contentColor = C.Ink
        ) {
            if (expanded) Icon(Icons.Rounded.Close, "Đóng trợ lý")
            else Icon(Icons.Rounded.SmartToy, "Mở trợ lý AI")
        }
    }
}

private fun sendChat(lines: MutableList<ChatLine>, prompt: String, c: Context, setLoading: (Boolean) -> Unit) {
    val text = prompt.trim()
    if (text.isEmpty()) return
    lines.add(ChatLine("user", text))
    setLoading(true)
    val history = JSONArray()
    lines.takeLast(12).forEach { line ->
        history.put(JSONObject().put("role", line.role).put("content", line.text))
    }
    Api.call(c, "POST", "/api/chat", JSONObject().put("messages", history)) { code, json ->
        val answer = if (code in 200..299) json?.optString("reply").orEmpty()
            else json?.optString("error").orEmpty().ifBlank { "Không thể kết nối trợ lý AI (HTTP $code)." }
        lines.add(ChatLine("assistant", answer.ifBlank { "AI chưa trả về nội dung. Bạn thử lại nhé." }))
        setLoading(false)
    }
}
