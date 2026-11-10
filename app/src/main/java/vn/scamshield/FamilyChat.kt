package vn.scamshield

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FamilyGreen = Color(0xFF16815F)
private val FamilyGreenDark = Color(0xFF105B45)
private val FamilyMint = Color(0xFFEAF6F0)
private val FamilyCanvas = Color(0xFFF5FAF7)
private val FamilyBorder = Color(0xFFD9EAE1)
private val FamilyText = Color(0xFF173F34)
private val FamilyMuted = Color(0xFF678278)

private data class FamilyRoom(
    val id: String, val name: String, val memberCount: Int,
    val lastMessage: String, val updatedAt: Long, val unread: Int
)
private data class FamilyMessage(
    val id: String, val sender: String, val body: String,
    val createdAt: Long, val mine: Boolean
)

private fun parseFamilyRooms(j: JSONObject?): List<FamilyRoom> {
    val a = j?.optJSONArray("chats") ?: j?.optJSONArray("rooms") ?: j?.optJSONArray("items") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        FamilyRoom(id, o.optString("name", "Gia đình"), o.optInt("member_count", 1),
            o.optString("last_message"), o.optLong("updated_at", o.optLong("last_at")), o.optInt("unread_count"))
    }
}
private fun parseFamilyMessages(j: JSONObject?): List<FamilyMessage> {
    val a = j?.optJSONArray("messages") ?: j?.optJSONArray("items") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val body = o.optString("body", o.optString("text", ""))
        if (body.isBlank()) return@mapNotNull null
        FamilyMessage(o.optString("id", i.toString()), o.optString("sender_name", "Thành viên"),
            body, o.optLong("created_at", o.optLong("ts")), o.optBoolean("mine"))
    }
}
private fun familyError(code: Int, j: JSONObject?): String = when {
    code == -1 -> "Không kết nối được máy chủ. Hãy kiểm tra mạng và máy chủ ScamShield."
    code == 401 || code == 403 -> "Bạn cần đăng nhập lại hoặc không có quyền vào nhóm này."
    code == 404 -> "Máy chủ hiện chưa có API Chat gia đình. Cần cập nhật phần máy chủ để tin nhắn đồng bộ giữa nhiều điện thoại."
    else -> j?.optString("error")?.takeIf { it.isNotBlank() } ?: "Có lỗi xảy ra. Vui lòng thử lại."
}
private fun familyClock(ts: Long): String {
    if (ts <= 0L) return ""
    val ms = if (ts < 10_000_000_000L) ts * 1000L else ts
    return SimpleDateFormat("HH:mm", Locale("vi", "VN")).format(Date(ms))
}

@Composable
fun FamilyChatScreen(c: Context, onBack: () -> Unit) {
    val logged = Store.str(c, "token").isNotBlank()
    var rooms by remember { mutableStateOf<List<FamilyRoom>>(emptyList()) }
    var room by remember { mutableStateOf<FamilyRoom?>(null) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var createOpen by remember { mutableStateOf(false) }
    var joinOpen by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf("") }
    var inviteInput by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var inviteOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    BackHandler(enabled = room != null || createOpen || joinOpen || inviteOpen) {
        when {
            inviteOpen -> inviteOpen = false
            createOpen -> createOpen = false
            joinOpen -> joinOpen = false
            else -> room = null
        }
    }

    fun loadRooms() {
        if (!logged) return
        Api.call(c, "GET", "/api/family/chats", null) { code, json ->
            if (code in 200..299 && json != null) { rooms = parseFamilyRooms(json); error = "" }
            else error = familyError(code, json)
        }
    }

    LaunchedEffect(logged, refresh) {
        if (logged) {
            loadRooms()
            while (true) { delay(15000); loadRooms() }
        }
    }

    if (createOpen) AlertDialog(
        onDismissRequest = { if (!busy) createOpen = false },
        title = { Text("Tạo nhóm gia đình", color = FamilyText) },
        text = {
            OutlinedTextField(roomName, { roomName = it.take(48) }, singleLine = true,
                label = { Text("Tên nhóm, ví dụ: Gia đình mình") }, colors = familyFieldColors())
        },
        confirmButton = {
            TextButton(enabled = roomName.isNotBlank() && !busy, onClick = {
                busy = true
                Api.call(c, "POST", "/api/family/chats", JSONObject().put("name", roomName.trim())) { code, json ->
                    busy = false
                    if (code in 200..299) { roomName = ""; createOpen = false; refresh++ }
                    else error = familyError(code, json)
                }
            }) { Text(if (busy) "Đang tạo…" else "Tạo nhóm", color = FamilyGreen) }
        },
        dismissButton = { TextButton(onClick = { createOpen = false }) { Text("Hủy", color = FamilyMuted) } },
        containerColor = Color.White
    )

    if (joinOpen) AlertDialog(
        onDismissRequest = { if (!busy) joinOpen = false },
        title = { Text("Tham gia nhóm", color = FamilyText) },
        text = {
            OutlinedTextField(inviteInput, { inviteInput = it.uppercase().take(16) }, singleLine = true,
                label = { Text("Mã mời từ người thân") }, colors = familyFieldColors())
        },
        confirmButton = {
            TextButton(enabled = inviteInput.isNotBlank() && !busy, onClick = {
                busy = true
                Api.call(c, "POST", "/api/family/chats/join", JSONObject().put("invite_code", inviteInput.trim())) { code, json ->
                    busy = false
                    if (code in 200..299) { inviteInput = ""; joinOpen = false; refresh++ }
                    else error = familyError(code, json)
                }
            }) { Text(if (busy) "Đang tham gia…" else "Tham gia", color = FamilyGreen) }
        },
        dismissButton = { TextButton(onClick = { joinOpen = false }) { Text("Hủy", color = FamilyMuted) } },
        containerColor = Color.White
    )

    if (inviteOpen) AlertDialog(
        onDismissRequest = { inviteOpen = false },
        title = { Text("Mã mời gia đình", color = FamilyText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gửi mã này cho người thân để họ tham gia nhóm.", color = FamilyMuted, fontSize = 13.sp)
                Text(inviteCode, color = FamilyGreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, letterSpacing = 2.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clip = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Mã mời gia đình", inviteCode))
                Toast.makeText(c, "Đã sao chép mã mời", Toast.LENGTH_SHORT).show()
                inviteOpen = false
            }) {
                Icon(Icons.Rounded.ContentCopy, null, tint = FamilyGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp)); Text("Sao chép", color = FamilyGreen)
            }
        },
        dismissButton = { TextButton(onClick = { inviteOpen = false }) { Text("Đóng", color = FamilyMuted) } },
        containerColor = Color.White
    )

    val selected = room
    if (selected != null) {
        FamilyConversation(c, selected, onBack = { room = null }, onInvite = {
            Api.call(c, "POST", "/api/family/chats/${selected.id}/invite", null) { code, json ->
                if (code in 200..299 && json != null) {
                    inviteCode = json.optString("invite_code", json.optString("code"))
                    if (inviteCode.isNotBlank()) inviteOpen = true else error = "Máy chủ chưa trả về mã mời."
                } else error = familyError(code, json)
            }
        })
    } else {
        Column(
            Modifier.fillMaxSize().background(FamilyCanvas).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(46.dp).background(FamilyMint, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FamilyRestroom, null, tint = FamilyGreen, modifier = Modifier.size(25.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Trò chuyện gia đình", color = FamilyText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Không gian riêng cho người thân", color = FamilyMuted, fontSize = 12.sp)
                }
                IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "Làm mới", tint = FamilyGreen) }
            }

            if (!logged) {
                FamilyInfo("Đăng nhập để sử dụng",
                    "Chat gia đình cần tài khoản để xác định thành viên và đồng bộ tin nhắn giữa các điện thoại. Hãy đăng nhập trong mục Tài khoản trước.")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FamilyAction("Tạo nhóm", Icons.Rounded.Add, Modifier.weight(1f)) { createOpen = true }
                    FamilyAction("Nhập mã mời", Icons.Rounded.GroupAdd, Modifier.weight(1f)) { joinOpen = true }
                }
                if (error.isNotBlank()) FamilyInfo("Chưa kết nối được Chat gia đình", error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nhóm của gia đình", color = FamilyText, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${rooms.size} nhóm", color = FamilyMuted, fontSize = 11.sp)
                }
                if (rooms.isEmpty() && error.isEmpty()) FamilyInfo("Chưa có nhóm nào",
                    "Tạo nhóm gia đình mới hoặc nhập mã mời do người thân gửi cho bạn.")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 18.dp)) {
                    items(rooms, key = { it.id }) { r -> FamilyRoomCard(r) { room = r } }
                }
            }
        }
    }
}

@Composable
private fun FamilyConversation(c: Context, room: FamilyRoom, onBack: () -> Unit, onInvite: () -> Unit) {
    var messages by remember(room.id) { mutableStateOf<List<FamilyMessage>>(emptyList()) }
    var draft by remember(room.id) { mutableStateOf("") }
    var error by remember(room.id) { mutableStateOf("") }
    var refresh by remember(room.id) { mutableIntStateOf(0) }
    var sending by remember(room.id) { mutableStateOf(false) }
    var loading by remember(room.id) { mutableStateOf(true) }
    val listState = rememberLazyListState()

    BackHandler { onBack() }

    fun loadMessages() {
        Api.call(c, "GET", "/api/family/chats/${room.id}/messages?limit=50", null) { code, json ->
            loading = false
            if (code in 200..299 && json != null) { messages = parseFamilyMessages(json); error = "" }
            else error = familyError(code, json)
        }
    }

    LaunchedEffect(room.id, refresh) {
        loadMessages()
        while (true) { delay(6000); loadMessages() }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(FamilyCanvas)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Quay lại", tint = FamilyGreen) }
            Column(Modifier.weight(1f)) {
                Text(room.name, color = FamilyText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${room.memberCount} thành viên", color = FamilyMuted, fontSize = 11.sp)
            }
            IconButton(onClick = onInvite) { Icon(Icons.Rounded.GroupAdd, "Mời người thân", tint = FamilyGreen) }
            IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "Làm mới", tint = FamilyGreen) }
        }
        HorizontalDivider(color = FamilyBorder)
        if (error.isNotBlank()) FamilyInfo("Chưa thể đồng bộ tin nhắn", error, Modifier.padding(12.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (loading && messages.isEmpty()) item { Text("Đang tải tin nhắn…", color = FamilyMuted, fontSize = 12.sp) }
            items(messages, key = { it.id }) { m -> FamilyBubble(m) }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { if (it.length <= 2000) draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nhắn cho gia đình…", color = FamilyMuted, fontSize = 13.sp) },
                maxLines = 4, textStyle = TextStyle(color = FamilyText, fontSize = 14.sp),
                shape = RoundedCornerShape(20.dp), colors = familyFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val text = draft.trim()
                    if (text.isNotBlank() && !sending) {
                        sending = true
                        Api.call(c, "POST", "/api/family/chats/${room.id}/messages", JSONObject().put("body", text)) { code, json ->
                            sending = false
                            if (code in 200..299) { draft = ""; refresh++ } else error = familyError(code, json)
                        }
                    }
                })
            )
            IconButton(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    val text = draft.trim()
                    if (text.isNotBlank()) {
                        sending = true
                        Api.call(c, "POST", "/api/family/chats/${room.id}/messages", JSONObject().put("body", text)) { code, json ->
                            sending = false
                            if (code in 200..299) { draft = ""; refresh++ } else error = familyError(code, json)
                        }
                    }
                },
                modifier = Modifier.size(46.dp).background(if (draft.isNotBlank() && !sending) FamilyGreen else FamilyBorder, CircleShape)
            ) { Icon(Icons.Rounded.Send, "Gửi tin nhắn", tint = Color.White) }
        }
    }
}

@Composable
private fun FamilyRoomCard(room: FamilyRoom, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp))
            .border(1.dp, FamilyBorder, RoundedCornerShape(18.dp)).clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(48.dp).background(FamilyMint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.FamilyRestroom, null, tint = FamilyGreen, modifier = Modifier.size(25.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(room.name, color = FamilyText, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(room.lastMessage.ifBlank { "${room.memberCount} thành viên" }, color = FamilyMuted, fontSize = 12.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(familyClock(room.updatedAt), color = FamilyMuted, fontSize = 10.sp)
            if (room.unread > 0) Box(Modifier.background(FamilyGreen, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp)) {
                Text(room.unread.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FamilyBubble(m: FamilyMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).background(
                if (m.mine) Color(0xFFDDF2E8) else Color.White,
                RoundedCornerShape(17.dp)
            ).border(1.dp, FamilyBorder, RoundedCornerShape(17.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (!m.mine) Text(m.sender, color = FamilyGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(m.body, color = FamilyText, fontSize = 13.sp, lineHeight = 18.sp)
            if (m.createdAt > 0L) Text(familyClock(m.createdAt), color = FamilyMuted, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 3.dp))
        }
    }
}

@Composable
private fun FamilyAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                         modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier.height(48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = FamilyGreen, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun FamilyInfo(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp))
        .border(1.dp, FamilyBorder, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = FamilyText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(body, color = FamilyMuted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun familyFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = FamilyText, unfocusedTextColor = FamilyText,
    cursorColor = FamilyGreen, focusedBorderColor = FamilyGreen, unfocusedBorderColor = FamilyBorder,
    focusedLabelColor = FamilyGreen, unfocusedLabelColor = FamilyMuted,
    focusedPlaceholderColor = FamilyMuted, unfocusedPlaceholderColor = FamilyMuted,
    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
)
