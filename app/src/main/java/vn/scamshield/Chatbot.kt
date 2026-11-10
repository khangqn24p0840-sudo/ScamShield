package vn.scamshield

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private data class ChatLine(
    val fromUser: Boolean,
    val text: String,
    val pending: Boolean = false
)

private val ChatGreen = Color(0xFF118465)
private val ChatGreenDeep = Color(0xFF0D6D53)
private val ChatGreenDark = Color(0xFF174B3D)
private val ChatMint = Color(0xFFEAF6F0)
private val ChatCanvas = Color(0xFFF4FAF7)
private val ChatBorder = Color(0xFFD6E9DF)
private val ChatMuted = Color(0xFF698579)
private val ChatBubbleUser = Color(0xFFDDF2E8)
private val ChatChip = Color(0xFFE6F4ED)


/** One entry point for the AI assistant. The packaged project keeps its original FloatingBot button. */
@Composable
fun ChatbotLauncher(c: Context, h: Hooks) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (!expanded) {
            FloatingChatbotButton(onClick = { expanded = true }, modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 88.dp))
        }
        ChatbotOverlay(c, h, visible = expanded, onClose = { expanded = false })
    }
}

/** Conversation overlay opened by the existing purple FloatingBot in the packaged ZIP source. */
@Composable
fun ChatbotOverlay(c: Context, h: Hooks, visible: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = visible) { onClose() }
    if (visible) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable { onClose() }
            )
            ChatbotPanel(
                c = c,
                onPickImage = h.pickImage,
                onClose = onClose,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 88.dp)
                    .widthIn(max = 390.dp)
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 520.dp)
                    .fillMaxHeight(0.84f)
            )
        }
    }
}

@Composable
private fun FloatingChatbotButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(62.dp)
            .shadow(18.dp, CircleShape)
            .background(Color(0xFF776BFA), CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.96f), CircleShape)
            .clickable(onClick = onClick)
            .pulse(0.98f, 1.035f, 1700),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.SmartToy,
            contentDescription = "Mở trợ lý AI ScamShield",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-2).dp)
                .size(21.dp)
                .background(Color.White, CircleShape)
                .border(1.dp, Color(0xFFFFC86A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFFFA51F), modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun ChatbotPanel(c: Context, onPickImage: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val welcome = "Chào bạn! Mình là trợ lý ScamShield. Hãy gửi nội dung tin nhắn, lời thoại hoặc đường link đáng ngờ để mình phân tích dấu hiệu lừa đảo và gợi ý cách xử lý.\n\nKhông gửi OTP, mật khẩu hoặc thông tin cá nhân nhé."
    val lines = remember { mutableStateListOf(ChatLine(fromUser = false, text = welcome)) }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }

    fun sendMessage() {
        val message = draft.trim()
        if (message.isEmpty() || busy) return

        lines.add(ChatLine(fromUser = true, text = message))
        draft = ""

        if (Store.str(c, "token").isBlank()) {
            lines.add(
                ChatLine(
                    fromUser = false,
                    text = "Bạn cần đăng nhập tài khoản ScamShield để sử dụng phân tích AI. Hãy mở mục Tài khoản, đăng nhập hoặc đăng ký rồi quay lại đây nhé."
                )
            )
            return
        }

        busy = true
        lines.add(ChatLine(fromUser = false, text = "Mình đang phân tích nội dung…", pending = true))
        Api.call(c, "POST", "/api/analyze/text", JSONObject().put("text", message)) { code, result ->
            val reply = if (code in 200..299 && result != null) {
                fmtText(code, result)
            } else {
                "Mình chưa phân tích được nội dung này. ${err(result)}"
            }
            val pendingIndex = lines.indexOfLast { it.pending }
            if (pendingIndex >= 0) {
                lines[pendingIndex] = ChatLine(fromUser = false, text = reply)
            } else {
                lines.add(ChatLine(fromUser = false, text = reply))
            }
            busy = false
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        modifier = modifier
            .shadow(24.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White)
            .border(1.dp, ChatBorder, RoundedCornerShape(26.dp))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(ChatGreenDeep, ChatGreen)))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                Modifier.size(42.dp)
                    .background(Brush.linearGradient(listOf(ChatGreen, ChatGreenDeep)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.SmartToy, null, tint = Color.White, modifier = Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Trợ lý AI ScamShield", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Hỗ trợ nhận diện lừa đảo", color = Color(0xFFD9F6E8), fontSize = 11.sp)
            }
            IconButton(onClick = { showTools = !showTools }) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = "Mở tính năng AI", tint = Color.White)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Đóng chatbot", tint = Color.White)
            }
        }

        HorizontalDivider(color = ChatBorder)

        if (showTools) {
            Column(
                Modifier.fillMaxWidth()
                    .background(ChatMint)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = ChatGreen, modifier = Modifier.size(16.dp))
                    Text("TÍNH NĂNG AI", color = ChatGreenDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ChatToolChip("Phân tích tin nhắn") {
                        draft = "Hãy phân tích tin nhắn sau và chỉ ra dấu hiệu lừa đảo: "
                        showTools = false
                    }
                    ChatToolChip("Kiểm tra đường link") {
                        draft = "Hãy đánh giá đường link sau có dấu hiệu lừa đảo hay không: "
                        showTools = false
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ChatToolChip("Dấu hiệu cuộc gọi") {
                        draft = "Hãy giúp tôi nhận diện các dấu hiệu lừa đảo trong cuộc gọi có nội dung: "
                        showTools = false
                    }
                    ChatToolChip("Kiểm tra ảnh") {
                        showTools = false
                        onPickImage()
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(ChatCanvas)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(lines) { _, line -> ChatBubble(line) }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 4000) draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Dán tin nhắn hoặc đường link…", color = ChatMuted, fontSize = 12.sp) },
                maxLines = 3,
                textStyle = TextStyle(color = ChatGreenDark, fontSize = 13.sp, lineHeight = 18.sp),
                shape = RoundedCornerShape(17.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ChatGreenDark,
                    unfocusedTextColor = ChatGreenDark,
                    disabledTextColor = ChatMuted,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    disabledContainerColor = ChatCanvas,
                    cursorColor = ChatGreen,
                    focusedBorderColor = ChatGreen,
                    unfocusedBorderColor = ChatBorder,
                    focusedPlaceholderColor = ChatMuted,
                    unfocusedPlaceholderColor = ChatMuted
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendMessage() })
            )
            IconButton(
                onClick = { sendMessage() },
                enabled = draft.isNotBlank() && !busy,
                modifier = Modifier.size(46.dp)
                    .background(
                        if (draft.isNotBlank() && !busy) Brush.linearGradient(listOf(ChatGreen, ChatGreenDeep))
                        else Brush.linearGradient(listOf(ChatBorder, ChatBorder)),
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Rounded.Send,
                    contentDescription = "Gửi nội dung để phân tích",
                    tint = if (draft.isNotBlank() && !busy) Color.White else ChatMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            "Nội dung gửi sẽ được chuyển lên máy chủ để phân tích.",
            color = ChatMuted,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
        )
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = 310.dp)
                .background(
                    if (line.fromUser) ChatBubbleUser else Color.White,
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (line.fromUser) 18.dp else 5.dp,
                        bottomEnd = if (line.fromUser) 5.dp else 18.dp
                    )
                )
                .border(
                    1.dp,
                    ChatBorder,
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (!line.fromUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(Icons.Rounded.SmartToy, null, tint = ChatGreen, modifier = Modifier.size(13.dp))
                    Text("SCAMSHIELD AI", color = ChatGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                line.text,
                color = ChatGreenDark,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }
    }
}


@Composable
private fun ChatToolChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ChatChip, RoundedCornerShape(12.dp))
            .border(1.dp, ChatBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        color = ChatGreenDark,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold
    )
}
