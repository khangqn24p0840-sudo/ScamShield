package vn.scamshield

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.Normalizer

private data class ChatLine(
    val fromUser: Boolean,
    val text: String,
    val pending: Boolean = false
)

private val ChatGreen = Color(0xFF2679E7)
private val ChatGreenDeep = Color(0xFF1756B8)
private val ChatGreenDark = Color(0xFF1C3D70)
private val ChatMint = Color(0xFFEBF4FF)
private val ChatCanvas = Color(0xFFF4F8FF)
private val ChatBorder = Color(0xFFD7E4F7)
private val ChatMuted = Color(0xFF6780A5)
private val ChatBubbleUser = Color(0xFFE2EEFF)
private val ChatChip = Color(0xFFEBF3FF)


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

/** Robot trợ lý dạng 3D cách điệu bằng Compose, chuyển động nhẹ nhàng tại chỗ. */
@Composable
fun RobotMascot(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val motion = rememberInfiniteTransition(label = "mascot-idle")
    val bob by motion.animateFloat(-2.2f, 2.2f,
        infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mascot-bob")
    val tilt by motion.animateFloat(-1.5f, 1.5f,
        infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mascot-tilt")
    Box(modifier.size(size * 1.28f).graphicsLayer { translationY = bob.dp.toPx() }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 1.18f).background(
            Brush.radialGradient(listOf(Color(0x667CC8FF), Color(0x1A5AA8FF), Color.Transparent)), CircleShape))
        Box(Modifier.align(Alignment.TopCenter).offset(y = 3.dp).width(3.dp).height(size * 0.18f)
            .background(Color(0xFF4C91E8), RoundedCornerShape(3.dp)))
        Box(Modifier.align(Alignment.TopCenter).offset(y = 1.dp).size(size * 0.105f)
            .background(Brush.linearGradient(listOf(Color(0xFFB9F1FF), Color(0xFF3194FF))), CircleShape)
            .border(1.dp, Color.White, CircleShape))
        Box(Modifier.align(Alignment.BottomCenter).offset(y = (-size * 0.035f))
            .size(width = size * 0.68f, height = size * 0.42f)
            .shadow(5.dp, RoundedCornerShape(size * 0.19f))
            .background(Brush.linearGradient(listOf(Color.White, Color(0xFFBBD9FF))), RoundedCornerShape(size * 0.19f))
            .border(1.2.dp, Color.White, RoundedCornerShape(size * 0.19f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, tint = Color(0xFF2679E7), modifier = Modifier.size(size * 0.22f))
        }
        Box(Modifier.align(Alignment.Center).offset(y = (-size * 0.095f)).graphicsLayer { rotationZ = tilt }
            .size(width = size * 0.98f, height = size * 0.70f)
            .shadow(8.dp, RoundedCornerShape(size * 0.27f))
            .background(Brush.linearGradient(listOf(Color.White, Color(0xFFB8D9FF))), RoundedCornerShape(size * 0.27f))
            .border(1.5.dp, Color.White, RoundedCornerShape(size * 0.27f)), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth().padding(horizontal = size * 0.055f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(size * 0.15f, size * 0.31f)
                    .background(Brush.verticalGradient(listOf(Color(0xFF6DC9FF), Color(0xFF2777D8))), RoundedCornerShape(8.dp)))
                Box(Modifier.weight(1f).padding(horizontal = size * 0.035f)
                    .size(width = size * 0.54f, height = size * 0.51f)
                    .background(Brush.linearGradient(listOf(Color(0xFF102B62), Color(0xFF071A42))), RoundedCornerShape(size * 0.19f))
                    .border(1.dp, Color(0xFF6ACBFF).copy(alpha = 0.55f), RoundedCornerShape(size * 0.19f)),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(size * 0.045f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(size * 0.19f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(size * 0.11f, size * 0.17f).background(Color(0xFF69E5FF), RoundedCornerShape(50)))
                            Box(Modifier.size(size * 0.11f, size * 0.17f).background(Color(0xFF69E5FF), RoundedCornerShape(50)))
                        }
                        Box(Modifier.size(size * 0.16f, size * 0.045f).background(Color(0xFF57DDF8), RoundedCornerShape(50)))
                    }
                }
                Box(Modifier.size(size * 0.15f, size * 0.31f)
                    .background(Brush.verticalGradient(listOf(Color(0xFF6DC9FF), Color(0xFF2777D8))), RoundedCornerShape(8.dp)))
            }
        }
    }
}

/** Nút chatbot luôn ở góc màn hình, robot chỉ nhún và nghiêng rất nhẹ khi chờ. */
@Composable
private fun FloatingChatbotButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(84.dp).background(
            Brush.radialGradient(listOf(Color(0x5574BFFF), Color(0x1174BFFF), Color.Transparent)), CircleShape))
        Box(Modifier.size(78.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            RobotMascot(Modifier, size = 62.dp)
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

        // Chào hỏi / xã giao: trả lời tự nhiên ngay trên máy, không cần đăng nhập, không chấm điểm.
        val chatReply = ChatIntent.localReply(message)
        if (chatReply != null) {
            lines.add(ChatLine(fromUser = false, text = chatReply))
            return
        }

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
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                RobotMascot(Modifier, size = 35.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Trợ lý ScamShield", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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

/**
 * Phân loại tin nhắn người dùng gõ vào chatbot.
 * - Xã giao (chào, cảm ơn, hỏi bot là ai...) -> trả lời tự nhiên ngay trên máy, KHÔNG gửi lên server.
 * - Nội dung cần kiểm tra (link, số điện thoại/tài khoản, tin nhắn dài, có từ khóa lừa đảo) -> trả null để gọi phân tích AI.
 */
internal object ChatIntent {

    private val diacritics = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")
    private val urlLike = Regex(
        "(https?://|www\\.|\\b[a-z0-9-]+\\.(com|vn|net|org|info|xyz|top|click|club|online|site|link|cc|me|co|app|shop|icu|live|vip)\\b)",
        RegexOption.IGNORE_CASE
    )
    private val longNumber = Regex("\\d[\\d .\\-]{6,}\\d")

    private val toolPrefixes = listOf(
        "hay phan tich tin nhan sau",
        "hay danh gia duong link sau",
        "hay giup toi nhan dien cac dau hieu lua dao trong cuoc goi"
    )

    private val scamKeywords = listOf(
        "otp", "chuyen khoan", "so tai khoan", "ngan hang", "trung thuong", "nhan qua", "cong an", "vien kiem sat",
        "toa an", "khoa tai khoan", "xac minh", "mat khau", "dat coc", "dau tu", "viec nhe luong cao", "cong tac vien",
        "vay tien", "giai ngan", "hoan tien", "tien dien", "shipper", "buu dien", "thue bao", "mat sim", "click",
        "bam vao", "nhap ma", "ma xac thuc", "can cuoc", "cccd", "lenh bat", "truy na"
    )

    private fun norm(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .replace('đ', 'd')
        return d.replace(nonWord, " ").replace(spaces, " ").trim()
    }

    private fun pick(vararg options: String) = options.random()

    private val greetWords = setOf("hi", "hii", "hello", "helo", "hey", "alo", "chao", "yo", "hallo", "hola", "e", "oi")

    /** @return câu trả lời xã giao, hoặc null nếu nên gọi phân tích AI. */
    fun localReply(raw: String): String? {
        val text = raw.trim()
        val n = norm(text)
        val words = if (n.isEmpty()) emptyList() else n.split(" ")

        // Chỉ có emoji/ký hiệu
        if (n.isEmpty()) return pick(
            "Mình đây! 😊 Bạn cần mình giúp kiểm tra tin nhắn hay đường link nào không?",
            "Chào bạn! Bạn cứ dán nội dung đáng ngờ vào đây, mình xem giúp nhé."
        )

        // Link / số dài => chắc chắn là nội dung cần kiểm tra
        if (urlLike.containsMatchIn(text) || longNumber.containsMatchIn(text)) return null

        // Các nút gợi ý (tool chip) đã điền sẵn câu mở đầu
        toolPrefixes.firstOrNull { n.startsWith(it) }?.let { p ->
            val rest = n.removePrefix(p).trim()
            return if (rest.length < 6) {
                "Bạn dán nội dung cần kiểm tra vào sau câu đó giúp mình nhé, mình xem ngay! 🔍"
            } else null
        }

        // ---- Xã giao ----
        val first = words.first()
        if (words.size <= 5 && (first in greetWords || n.startsWith("xin chao"))) {
            return pick(
                "Chào bạn! 👋 Mình là trợ lý ScamShield. Bạn cần mình kiểm tra tin nhắn, cuộc gọi hay đường link nào không?",
                "Hello bạn! Hôm nay có tin nhắn hay cuộc gọi nào làm bạn thấy lấn cấn không? Cứ gửi mình xem giúp nhé.",
                "Xin chào! Mình sẵn sàng hỗ trợ bạn rồi. Bạn muốn hỏi gì về lừa đảo hay muốn mình kiểm tra nội dung nào?"
            )
        }
        if (n.startsWith("chao buoi") || n.startsWith("good morning") || n.startsWith("good evening")) {
            return "Chào bạn! Chúc bạn một ngày an toàn nhé 😊 Bạn cần mình giúp gì không?"
        }
        if (words.size <= 6 && ("cam on" in n || n.startsWith("thanks") || n.startsWith("thank") || n == "tks" || n == "ty")) {
            return pick(
                "Không có gì đâu bạn! Có gì đáng ngờ cứ gửi mình nhé 💚",
                "Rất vui được giúp bạn! Cẩn thận với tin nhắn lạ nha."
            )
        }
        if (words.size <= 4 && (n.contains("tam biet") || n == "bye" || n.startsWith("bye ") || n == "bb" || n.contains("hen gap lai"))) {
            return "Tạm biệt bạn! Nhớ là đừng bao giờ chia sẻ OTP cho ai nhé. Hẹn gặp lại 👋"
        }
        if (n.contains("ban la ai") || n.contains("ten ban la gi") || n.contains("may la ai") || n.contains("who are you") ||
            n.contains("ban ten gi")
        ) {
            return "Mình là trợ lý AI của ScamShield 🤖 — chuyên giúp bạn nhận diện tin nhắn, cuộc gọi, đường link và ảnh có dấu hiệu lừa đảo, rồi gợi ý cách xử lý."
        }
        if (n.contains("ban lam duoc gi") || n.contains("ban giup duoc gi") || n.contains("giup gi") ||
            n.contains("lam duoc gi") || n.contains("huong dan") || n == "help" || n.contains("cach dung") || n.contains("su dung the nao")
        ) {
            return "Mình có thể giúp bạn:\n• Phân tích tin nhắn/lời thoại nghi lừa đảo\n• Đánh giá đường link\n• Nhận diện dấu hiệu qua cuộc gọi\n• Kiểm tra ảnh có bị làm giả không (bấm biểu tượng ✨ ở trên)\n\nBạn chỉ cần dán nội dung vào đây là được."
        }
        if (n.contains("khoe khong") || n.contains("khoe ko") || n.contains("how are you") || n.contains("dang lam gi") ||
            n.contains("an com chua")
        ) {
            return pick(
                "Mình khỏe, cảm ơn bạn đã hỏi! 😄 Còn bạn thì sao, có tin nhắn lạ nào cần mình xem không?",
                "Mình ổn lắm, lúc nào cũng sẵn sàng canh chừng giúp bạn đây! Bạn cần hỗ trợ gì nào?"
            )
        }
        if (words.size <= 2 && n in setOf("ok", "oke", "okie", "uh", "u", "da", "vang", "um", "uhm", "duoc", "roi", "hay", "tot", "good")) {
            return pick("Ok bạn! Cần gì cứ nhắn mình nhé 😊", "Vâng! Có gì cần kiểm tra bạn cứ gửi nhé.")
        }
        if (n.contains("xin loi") && words.size <= 5) {
            return "Bạn đừng ngại, cứ hỏi thoải mái nhé! 😊"
        }

        // Hỏi chung chung về lừa đảo nhưng chưa gửi nội dung
        val hasKeyword = scamKeywords.any { it in n } || "lua dao" in n || "scam" in n
        if (words.size <= 8 && hasKeyword && (n.contains("khong") || words.contains("ko") || text.endsWith("?")) &&
            !n.contains("http")
        ) {
            return "Bạn dán nguyên văn tin nhắn, lời thoại hoặc đường link vào đây giúp mình nhé, mình sẽ phân tích dấu hiệu lừa đảo cho bạn. Đừng gửi OTP hay mật khẩu nha."
        }

        // ---- Nội dung cần phân tích ----
        if (n.length >= 40) return null
        if (hasKeyword && words.size >= 4) return null

        // Câu ngắn không rõ ý: trả lời thân thiện thay vì chấm điểm
        return pick(
            "Mình chưa rõ ý bạn lắm 😅 Nếu bạn có tin nhắn, lời thoại hay đường link đáng ngờ thì dán vào đây, mình sẽ phân tích giúp nhé.",
            "Bạn nói rõ hơn một chút được không? Hoặc cứ gửi nội dung đáng ngờ, mình sẽ kiểm tra ngay."
        )
    }
}
