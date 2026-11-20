package vn.scamshield

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer

private data class ChatLine(
    val fromUser: Boolean,
    val text: String,
    val pending: Boolean = false,
    val imageUri: Uri? = null
)

private object ChatHistoryStore {
    private const val PREFS = "scamshield_chatbot_history"
    private const val KEY_LINES = "lines"
    private const val MAX_LINES = 120

    fun load(c: Context, welcome: String): List<ChatLine> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LINES, null)
            ?: return listOf(ChatLine(fromUser = false, text = welcome))
        return runCatching {
            val array = org.json.JSONArray(raw)
            val restored = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val fromUser = item.optBoolean("fromUser", false)
                val image = item.optBoolean("image", false)
                var text = item.optString("text")
                if (item.optBoolean("pending", false)) {
                    text = "Yêu cầu phân tích trước đó bị gián đoạn. Bạn có thể gửi lại nội dung."
                } else if (image && text.isBlank()) {
                    text = "Ảnh đã được gửi trong phiên trò chuyện trước."
                }
                if (text.isBlank()) null else ChatLine(fromUser = fromUser, text = text)
            }.takeLast(MAX_LINES)
            if (restored.isEmpty()) listOf(ChatLine(fromUser = false, text = welcome)) else restored
        }.getOrElse { listOf(ChatLine(fromUser = false, text = welcome)) }
    }

    fun save(c: Context, lines: List<ChatLine>) {
        val array = org.json.JSONArray()
        lines.takeLast(MAX_LINES).forEach { line ->
            val text = if (line.pending) {
                "Yêu cầu phân tích trước đó bị gián đoạn. Bạn có thể gửi lại nội dung."
            } else line.text
            array.put(
                org.json.JSONObject()
                    .put("fromUser", line.fromUser)
                    .put("text", text.take(5000))
                    .put("image", line.imageUri != null)
                    .put("pending", false)
            )
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LINES, array.toString()).apply()
    }
}

private val ChatGreen = Color(0xFF2679E7)
private val ChatGreenDeep = Color(0xFF1756B8)
private val ChatGreenDark = Color(0xFF1C3D70)
private val ChatMint = Color(0xFFEBF4FF)
private val ChatCanvas = Color(0xFFF4F8FF)
private val ChatBorder = Color(0xFFD7E4F7)
private val ChatMuted = Color(0xFF6780A5)
private val ChatBubbleUser = Color(0xFFE2EEFF)
private val ChatChip = Color(0xFFEBF3FF)

enum class RobotMood(val animationName: String) {
    IDLE("Idle"),
    GREETING("Greeting"),
    ANALYZING("Analyzing"),
    WARNING("Warning"),
    SAD("Sad"),
    CUTE("Cute"),
    JUMPING("Jumping"),
    SITTING("Sitting"),
    THINKING("Thinking")
}

private data class RobotFaceExpression(
    val eyes: String,
    val mouth: String,
    val accent: String,
    val accentColor: Color,
    val description: String
)

private fun RobotMood.fallbackFace() = when (this) {
    RobotMood.IDLE -> RobotFaceExpression("◕  ◕", "ᴗ", "˶  ˶", Color(0xFF8ADFFF), "đang chờ")
    RobotMood.GREETING -> RobotFaceExpression("◕  ◕", "ᴗ", "✦  ✦", Color(0xFFFFA0BC), "vui vẻ chào bạn")
    RobotMood.ANALYZING -> RobotFaceExpression("⌕  ⌕", "…", "·  ·", Color(0xFF73DFFF), "đang phân tích")
    RobotMood.WARNING -> RobotFaceExpression("⊙  ⊙", "o", "!!", Color(0xFFFFC66D), "phát hiện dấu hiệu đáng ngờ")
    RobotMood.SAD -> RobotFaceExpression("•  •", "︵", "﹀", Color(0xFF73C9F5), "đang buồn")
    RobotMood.CUTE -> RobotFaceExpression("✧  ✧", "ω", "♡  ♡", Color(0xFFFF8FB8), "dễ thương")
    RobotMood.JUMPING -> RobotFaceExpression("◠  ◠", "ω", "✨", Color(0xFFB69CFF), "đang nhảy nhảy vui vẻ")
    RobotMood.SITTING -> RobotFaceExpression("◠  ◠", "ᴗ", "˶  ˶", Color(0xFF8ADFFF), "đang ngồi nghỉ")
    RobotMood.THINKING -> RobotFaceExpression("◕  ◔", "~", "...", Color(0xFFB69CFF), "đang suy nghĩ")
}

private fun robotMoodFromReply(reply: String): RobotMood {
    val text = reply.lowercase()
    return when {
        listOf("đáng ngờ", "lừa đảo", "nguy hiểm", "rủi ro cao", "giả mạo", "cảnh báo").any(text::contains) ->
            RobotMood.WARNING
        listOf("không thể phân tích", "chưa phân tích được", "đã xảy ra lỗi", "gặp sự cố", "không kết nối").any(text::contains) ->
            RobotMood.SAD
        listOf("nhảy", "tưng tưng").any(text::contains) ->
            RobotMood.JUMPING
        listOf("ngồi nghỉ", "ngồi xuống").any(text::contains) ->
            RobotMood.SITTING
        listOf("suy nghĩ", "ngẫm").any(text::contains) ->
            RobotMood.THINKING
        listOf("không có gì", "rất vui được giúp", "cảm ơn", "dễ thương").any(text::contains) ->
            RobotMood.CUTE
        else -> RobotMood.GREETING
    }
}

/** One entry point for the AI assistant. The packaged project keeps its original FloatingBot button. */
@Composable
fun ChatbotLauncher(c: Context, h: Hooks) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (!expanded) {
            FloatingChatbotButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 88.dp)
            )
        }
        ChatbotOverlay(c, h, visible = expanded, onClose = { expanded = false })
    }
}

/** Conversation overlay opened by the existing purple FloatingBot in the packaged ZIP source. */
@Composable
fun ChatbotOverlay(c: Context, h: Hooks, visible: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = visible) { onClose() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (visible) {
        Box(Modifier.fillMaxSize().imePadding()) {
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
                    .padding(end = 12.dp, bottom = if (imeVisible) 8.dp else 88.dp)
                    .widthIn(max = 390.dp)
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 520.dp)
                    .fillMaxHeight(if (imeVisible) 0.96f else 0.84f)
            )
        }
    }
}

/**
 * Mascot renderer. The Rive asset is preferred; Compose draws a cute chibi robot
 * with plump mitten paws and Jumping / Sitting / Thinking poses.
 */
@Composable
fun RobotMascot(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    mood: RobotMood = RobotMood.IDLE
) {
    val context = LocalContext.current
    val riveResId = remember(context) {
        context.resources.getIdentifier("scamshield_robot", "raw", context.packageName)
    }

    if (riveResId != 0) {
        AndroidView(
            modifier = modifier.size(width = size * 1.72f, height = size * 2.02f),
            factory = { viewContext ->
                RiveAnimationView(viewContext).also { view ->
                    view.setRiveResource(
                        resId = riveResId,
                        animationName = mood.animationName,
                        autoplay = true
                    )
                    view.tag = mood.name
                }
            },
            update = { view ->
                if (view.tag != mood.name) {
                    view.play(mood.animationName)
                    view.tag = mood.name
                }
            }
        )
    } else {
        ComposeRobotMascot(modifier = modifier, size = size, mood = mood)
    }
}

/** Vẽ cánh tay chibi tròn trịa kèm bàn tay găng mềm mại (thay thế tay que). */
private fun DrawScope.drawChubbyArm(
    px: (Float) -> Float,
    py: (Float) -> Float,
    s: Float,
    shoulderX: Float,
    shoulderY: Float,
    angleDeg: Float,
    side: Int, // -1 = tay trái, +1 = tay phải
    length: Float,
    shellBrush: Brush,
    edgeColor: Color,
    purpleColor: Color
) {
    val pivot = Offset(px(shoulderX), py(shoulderY))
    rotate(degrees = angleDeg, pivot = pivot) {
        drawCircle(Color(0xFF2A244D), 3.6f * s, pivot)
        drawCircle(Color.White.copy(alpha = 0.4f), 1.1f * s, Offset(pivot.x - 1f * s, pivot.y - 1f * s))

        drawRoundRect(
            brush = shellBrush,
            topLeft = Offset(px(shoulderX - 4.3f), py(shoulderY + 0.8f)),
            size = Size(8.6f * s, length * s),
            cornerRadius = CornerRadius(4.3f * s)
        )
        drawRoundRect(
            color = edgeColor,
            topLeft = Offset(px(shoulderX - 4.3f), py(shoulderY + 0.8f)),
            size = Size(8.6f * s, length * s),
            cornerRadius = CornerRadius(4.3f * s),
            style = Stroke(0.9f * s)
        )

        drawRoundRect(
            color = purpleColor,
            topLeft = Offset(px(shoulderX - 4.4f), py(shoulderY + length - 6.8f)),
            size = Size(8.8f * s, 3.2f * s),
            cornerRadius = CornerRadius(1.6f * s)
        )

        val pawCenter = Offset(px(shoulderX), py(shoulderY + length + 1.5f))
        drawCircle(Color.White, 5.2f * s, pawCenter)
        drawCircle(edgeColor, 5.2f * s, pawCenter, style = Stroke(0.9f * s))

        val thumbCenter = Offset(px(shoulderX - side * 4.3f), py(shoulderY + length - 0.8f))
        drawCircle(Color(0xFFF5F2FC), 2.4f * s, thumbCenter)
        drawCircle(edgeColor, 2.4f * s, thumbCenter, style = Stroke(0.85f * s))

        drawOval(
            Color.White.copy(alpha = 0.9f),
            topLeft = Offset(px(shoulderX - 2.9f), py(shoulderY + length - 0.8f)),
            size = Size(3.2f * s, 2.2f * s)
        )
    }
}

@Composable
private fun ComposeRobotMascot(
    modifier: Modifier,
    size: Dp,
    mood: RobotMood
) {
    val idleSequence = remember { listOf(0, 3, 9, 8, 7, 0) }
    var idleStep by remember { mutableStateOf(0) }
    LaunchedEffect(mood) {
        if (mood == RobotMood.IDLE) {
            while (true) {
                delay(12_000L)
                idleStep = (idleStep + 1) % idleSequence.size
            }
        } else {
            idleStep = 0
        }
    }

    val expression = when (mood) {
        RobotMood.IDLE -> idleSequence[idleStep]
        RobotMood.GREETING -> 0
        RobotMood.ANALYZING -> 6
        RobotMood.WARNING -> 5
        RobotMood.SAD -> 1
        RobotMood.CUTE -> 3
        RobotMood.JUMPING -> 7
        RobotMood.SITTING -> 8
        RobotMood.THINKING -> 9
    }

    val isJumping = expression == 7
    val isSitting = expression == 8
    val isThinking = expression == 9

    val motion = rememberInfiniteTransition(label = "scamshield-chibi-robot")
    val bob by motion.animateFloat(
        -1.6f, 1.6f,
        infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-bob"
    )
    val jumpPhase by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            animation = keyframes {
                durationMillis = 950
                0f at 0
                0.15f at 170
                1f at 460
                0.15f at 740
                0f at 950
            }
        ),
        label = "robot-jump"
    )
    val sitSway by motion.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-sit"
    )
    val thinkTilt by motion.animateFloat(
        -3f, -6f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-think-tilt"
    )
    val thinkDot by motion.animateFloat(
        0f, -2.6f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-think-dot"
    )
    val wave by motion.animateFloat(
        -6f, 6f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-wave"
    )
    val blink by motion.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3900
                1f at 0
                0.08f at 75
                1f at 155
                1f at 3300
                0.08f at 3375
                1f at 3455
            }
        ),
        label = "robot-blink"
    )
    val scan by motion.animateFloat(
        -1.4f, 1.4f,
        infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-scan"
    )
    val glow by motion.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-glow"
    )
    val antenna by motion.animateFloat(
        -1.6f, 1.6f,
        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-antenna"
    )

    val containerOffsetY = when {
        isJumping -> 2f - jumpPhase * 15f
        isSitting -> 3.5f + sitSway * 0.7f
        else -> bob
    }

    Box(
        modifier = modifier
            .width(size * 1.32f)
            .height(size * 1.44f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier.fillMaxSize().semantics {
                contentDescription = when (expression) {
                    1 -> "Robot ScamShield đang buồn"
                    2 -> "Robot ScamShield đang dỗi"
                    3 -> "Robot ScamShield đang vui dễ thương"
                    4 -> "Robot ScamShield đang khóc"
                    5 -> "Robot ScamShield ngạc nhiên cảnh báo"
                    6 -> "Robot ScamShield đang quét phân tích"
                    7 -> "Robot ScamShield đang nhảy nhảy vui vẻ"
                    8 -> "Robot ScamShield đang ngồi nghỉ"
                    9 -> "Robot ScamShield đang chống cằm suy nghĩ"
                    else -> "Robot ScamShield đang vẫy tay chào"
                }
            }
        ) {
            val vh = 122f
            val s = minOf(this.size.width / 100f, this.size.height / vh)
            val ox = (this.size.width - 100f * s) / 2f
            val oy = (this.size.height - vh * s) / 2f
            fun px(x: Float) = ox + x * s
            fun py(y: Float) = oy + y * s
            fun pt(x: Float, y: Float) = Offset(px(x), py(y))
            fun box(w: Float, h: Float) = Size(w * s, h * s)
            fun rr(r: Float) = CornerRadius(r * s)

            val purple = Color(0xFF7C4DFF)
            val purpleLight = Color(0xFFC8B4FF)
            val purpleDeep = Color(0xFF5B2FD0)
            val ink = Color(0xFF1E193C)
            val edge = Color(0xFFD2CCE8)
            val cyan = Color(0xFF3BD8FF)
            val cyanLight = Color(0xFFB8F4FF)
            val blushPink = Color(0xFFFF8FB8)

            val shell = Brush.linearGradient(
                listOf(Color.White, Color(0xFFF5F2FC), Color(0xFFDFDAF0)),
                start = pt(14f, 12f), end = pt(86f, 74f)
            )
            val bodyShell = Brush.linearGradient(
                listOf(Color.White, Color(0xFFF3EFFB), Color(0xFFDAD4EC)),
                start = pt(28f, 66f), end = pt(70f, 106f)
            )

            val shadowScaleX = if (isJumping) 1.06f - jumpPhase * 0.42f else if (isSitting) 1.12f else 1f
            val shadowAlpha = if (isJumping) 0.28f - jumpPhase * 0.14f else 0.24f
            scale(scaleX = shadowScaleX, scaleY = 1f, pivot = pt(50f, 117.5f)) {
                drawOval(
                    Brush.radialGradient(
                        listOf(Color(0xFF382A78).copy(alpha = shadowAlpha), Color.Transparent),
                        center = pt(50f, if (isSitting) 115.5f else 117.5f),
                        radius = (if (isSitting) 34f else 30f) * s
                    ),
                    topLeft = pt(if (isSitting) 16f else 20f, if (isSitting) 111f else 114f),
                    size = box(if (isSitting) 68f else 60f, if (isSitting) 9f else 7.2f)
                )
            }

            if (isThinking) {
                drawCircle(Color.White, 2.1f * s, pt(76f, 19f + thinkDot * 0.5f))
                drawCircle(purpleLight, 2.1f * s, pt(76f, 19f + thinkDot * 0.5f), style = Stroke(0.9f * s))
                drawCircle(Color.White, 3f * s, pt(81.5f, 13.5f + thinkDot * 0.8f))
                drawCircle(purpleLight, 3f * s, pt(81.5f, 13.5f + thinkDot * 0.8f), style = Stroke(0.9f * s))
                val cloudY = -1f + thinkDot
                drawRoundRect(Color.White, topLeft = pt(81f, cloudY), size = box(18f, 13f), cornerRadius = rr(6.5f))
                drawRoundRect(purple, topLeft = pt(81f, cloudY), size = box(18f, 13f), cornerRadius = rr(6.5f), style = Stroke(1.1f * s))
                for (dx in listOf(86.5f, 90f, 93.5f)) {
                    drawCircle(purple, 1.15f * s, pt(dx, cloudY + 6.5f))
                }
            }

            val stretchX = if (isJumping) 1.04f - jumpPhase * 0.08f else 1f
            val stretchY = if (isJumping) 0.95f + jumpPhase * 0.09f else 1f
            val bodyRotate = if (isSitting) sitSway else 0f

            translate(top = containerOffsetY * s) {
                scale(scaleX = stretchX, scaleY = stretchY, pivot = pt(50f, 114f)) {
                    rotate(degrees = bodyRotate, pivot = pt(50f, 110f)) {
                        when {
                            isSitting -> {
                                for ((idx, fx) in listOf(36.5f, 63.5f).withIndex()) {
                                    val footCenter = pt(fx, 107f)
                                    rotate(degrees = if (idx == 0) -16f else 16f, pivot = footCenter) {
                                        drawOval(Color(0xFFF5F2FC), topLeft = pt(fx - 7.8f, 100.6f), size = box(15.6f, 12.8f))
                                        drawOval(edge, topLeft = pt(fx - 7.8f, 100.6f), size = box(15.6f, 12.8f), style = Stroke(0.9f * s))
                                        drawOval(Color(0xFF9C78FF).copy(alpha = 0.45f), topLeft = pt(fx - 4.4f, 104.3f), size = box(8.8f, 6.8f))
                                        drawCircle(purple.copy(alpha = 0.6f), 1f * s, pt(fx - 2.1f, 104.5f))
                                        drawCircle(purple.copy(alpha = 0.6f), 1f * s, pt(fx, 103.8f))
                                        drawCircle(purple.copy(alpha = 0.6f), 1f * s, pt(fx + 2.1f, 104.5f))
                                    }
                                }
                            }
                            isJumping -> {
                                for ((idx, fx) in listOf(38f, 62f).withIndex()) {
                                    val footPivot = pt(fx, 106f)
                                    rotate(degrees = if (idx == 0) 22f else -22f, pivot = footPivot) {
                                        drawRoundRect(Color(0xFFF3EFFB), topLeft = pt(fx - 7.2f, 101.5f), size = box(14.4f, 10.5f), cornerRadius = rr(5.2f))
                                        drawRoundRect(edge, topLeft = pt(fx - 7.2f, 101.5f), size = box(14.4f, 10.5f), cornerRadius = rr(5.2f), style = Stroke(0.9f * s))
                                        drawRoundRect(purple, topLeft = pt(fx - 5.5f, 108.8f), size = box(11f, 2.2f), cornerRadius = rr(1.1f))
                                    }
                                }
                            }
                            else -> {
                                for (fx in listOf(39f, 61f)) {
                                    drawRoundRect(Color(0xFFF3EFFB), topLeft = pt(fx - 7.5f, 102.5f), size = box(15f, 11f), cornerRadius = rr(5.5f))
                                    drawRoundRect(edge, topLeft = pt(fx - 7.5f, 102.5f), size = box(15f, 11f), cornerRadius = rr(5.5f), style = Stroke(0.9f * s))
                                    drawRoundRect(purple, topLeft = pt(fx - 5.8f, 109.5f), size = box(11.6f, 2.3f), cornerRadius = rr(1.15f))
                                }
                            }
                        }

                        translate(top = if (isSitting) 6f * s else 0f) {
                            drawRoundRect(bodyShell, topLeft = pt(30f, 66f), size = box(40f, 39f), cornerRadius = rr(18f))
                            drawRoundRect(edge, topLeft = pt(30f, 66f), size = box(40f, 39f), cornerRadius = rr(18f), style = Stroke(0.9f * s))

                            drawRoundRect(
                                Brush.horizontalGradient(listOf(purpleDeep, Color(0xFFB69CFF), purpleDeep), startX = px(31.2f), endX = px(68.8f)),
                                topLeft = pt(31.2f, 94.5f), size = box(37.6f, 3.5f), cornerRadius = rr(1.75f)
                            )
                            drawLine(cyan.copy(alpha = 0.25f + 0.45f * glow), pt(34f, 99.6f), pt(66f, 99.6f), 1.1f * s, cap = StrokeCap.Round)

                            val chest = pt(50f, 82f)
                            drawCircle(cyan.copy(alpha = 0.22f * glow), 11f * s, chest)
                            drawCircle(Color(0xFFF3EFFB), 8.2f * s, chest)
                            drawCircle(purple.copy(alpha = 0.88f), 7f * s, chest, style = Stroke(1.4f * s))
                            drawCircle(
                                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.96f), cyanLight, cyan), center = chest, radius = 5.8f * s),
                                5.8f * s, chest
                            )
                            drawCircle(Color.White.copy(alpha = 0.85f), 1.3f * s, pt(48f, 79.8f))

                            val leftAngle = when {
                                isJumping -> 135f
                                isSitting -> -28f
                                else -> 18f
                            }
                            drawChubbyArm(
                                px = ::px, py = ::py, s = s,
                                shoulderX = 30f, shoulderY = if (isSitting) 77f else 76f,
                                angleDeg = leftAngle, side = -1,
                                length = if (isJumping || isSitting) 16f else 17f,
                                shellBrush = shell, edgeColor = edge, purpleColor = purple
                            )

                            val rightAngle = when {
                                isJumping -> -135f
                                isSitting -> 28f
                                isThinking -> 138f
                                else -> -118f + wave
                            }
                            drawChubbyArm(
                                px = ::px, py = ::py, s = s,
                                shoulderX = 70f, shoulderY = if (isSitting || isThinking) 77f else 76f,
                                angleDeg = rightAngle, side = 1,
                                length = when {
                                    isJumping || isSitting -> 16f
                                    isThinking -> 16.5f
                                    else -> 17f
                                },
                                shellBrush = shell, edgeColor = edge, purpleColor = purple
                            )

                            drawRoundRect(ink, topLeft = pt(42.5f, 64f), size = box(15f, 7f), cornerRadius = rr(3.5f))

                            val headPivot = pt(50f, 66f)
                            rotate(degrees = if (isThinking) thinkTilt else 0f, pivot = headPivot) {
                                val tipL = pt(21f + antenna, 5f)
                                val tipR = pt(79f - antenna, 5f)
                                drawLine(Color(0xFFC8C3DB), pt(28f, 16f), tipL, 1.6f * s, cap = StrokeCap.Round)
                                drawLine(Color(0xFFC8C3DB), pt(72f, 16f), tipR, 1.6f * s, cap = StrokeCap.Round)
                                for (tip in listOf(tipL, tipR)) {
                                    drawCircle(Brush.radialGradient(listOf(purpleLight, purple, purpleDeep), center = tip, radius = 3.2f * s), 3.1f * s, tip)
                                    drawCircle(Color.White.copy(alpha = 0.88f), 1f * s, Offset(tip.x - 1f * s, tip.y - 1f * s))
                                }

                                for (cupX in listOf(4f, 84f)) {
                                    drawRoundRect(Color(0xFFF5F2FC), topLeft = pt(cupX, 33f), size = box(12f, 24f), cornerRadius = rr(6f))
                                    drawRoundRect(purple, topLeft = pt(cupX, 33f), size = box(12f, 24f), cornerRadius = rr(6f), style = Stroke(1.6f * s))
                                    drawRoundRect(purple.copy(alpha = 0.35f), topLeft = pt(cupX + 3.5f, 37f), size = box(5f, 16f), cornerRadius = rr(2.5f))
                                }

                                drawRoundRect(shell, topLeft = pt(11f, 13f), size = box(78f, 57f), cornerRadius = rr(28f))
                                drawRoundRect(edge, topLeft = pt(11f, 13f), size = box(78f, 57f), cornerRadius = rr(28f), style = Stroke(0.95f * s))
                                drawOval(Color.White.copy(alpha = 0.9f), topLeft = pt(24f, 15.4f), size = box(24f, 4.8f))

                                drawRoundRect(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF262E54), Color(0xFF0E122B), Color(0xFF050714)),
                                        start = pt(22f, 24f), end = pt(78f, 62f)
                                    ),
                                    topLeft = pt(17f, 23f), size = box(66f, 40f), cornerRadius = rr(19f)
                                )
                                drawRoundRect(
                                    Color(0xFF6E7BFF).copy(alpha = 0.30f),
                                    topLeft = pt(17f, 23f), size = box(66f, 40f), cornerRadius = rr(19f), style = Stroke(0.75f * s)
                                )
                                val reflection = Path().apply {
                                    moveTo(px(26f), py(28.5f))
                                    cubicTo(px(38f), py(23.8f), px(60f), py(23.8f), px(74f), py(28f))
                                    cubicTo(px(60f), py(26.2f), px(40f), py(26.6f), px(26f), py(31.2f))
                                    close()
                                }
                                drawPath(reflection, Color.White.copy(alpha = 0.08f))

                                if (expression == 6) {
                                    val t = (scan + 1.4f) / 2.8f
                                    val y = 27f + t * 32f
                                    drawLine(cyan.copy(alpha = 0.38f), pt(22f, y), pt(78f, y), 1.5f * s, cap = StrokeCap.Round)
                                }

                                val blushAlpha = if (expression == 3 || isJumping || isSitting) 0.45f else 0.2f
                                drawOval(blushPink.copy(alpha = blushAlpha), topLeft = pt(22.1f, 49.4f), size = box(6.8f, 4.2f))
                                drawOval(blushPink.copy(alpha = blushAlpha), topLeft = pt(71.1f, 49.4f), size = box(6.8f, 4.2f))

                                val eyeBrush = Brush.verticalGradient(
                                    listOf(cyanLight, cyan, Color(0xFF22A8F2)),
                                    startY = py(33f), endY = py(49f)
                                )
                                val eyeShiftX = (if (expression == 6) scan * 1.3f else 0f) + (if (isThinking) 2.5f else 0f)
                                val eyeShiftY = if (isThinking) -2f else 0f

                                for (i in 0..1) {
                                    val ex = (if (i == 0) 36f else 64f) + eyeShiftX
                                    val ey = 40.5f + eyeShiftY
                                    drawCircle(
                                        Brush.radialGradient(listOf(cyan.copy(alpha = 0.14f + 0.2f * glow), Color.Transparent), center = pt(ex, ey), radius = 11.5f * s),
                                        11.5f * s, pt(ex, ey)
                                    )
                                    if (blink < 0.2f) {
                                        drawLine(cyan, pt(ex - 4.8f, ey), pt(ex + 4.8f, ey), 2.4f * s, cap = StrokeCap.Round)
                                    } else when {
                                        expression == 3 || isJumping || isSitting -> {
                                            drawArc(
                                                brush = eyeBrush, startAngle = 202f, sweepAngle = 136f, useCenter = false,
                                                topLeft = pt(ex - 5.4f, 39.1f), size = box(10.8f, 10.8f),
                                                style = Stroke(2.8f * s, cap = StrokeCap.Round)
                                            )
                                        }
                                        expression == 5 -> {
                                            drawCircle(eyeBrush, 5.4f * s, pt(ex, ey), style = Stroke(2.5f * s))
                                            drawCircle(Color.White, 1.4f * s, pt(ex + 1.5f, ey - 2f))
                                        }
                                        isThinking -> {
                                            val ry = if (i == 0) 5.8f else 4.2f
                                            val rx = if (i == 0) 5f else 4.8f
                                            drawOval(eyeBrush, topLeft = pt(ex - rx, ey - ry), size = box(rx * 2f, ry * 2f))
                                            drawCircle(Color.White, (if (i == 0) 1.8f else 1.5f) * s, pt(ex - 1.3f, ey - 2f))
                                            if (i == 0) {
                                                drawCircle(Color.White.copy(alpha = 0.85f), 0.9f * s, pt(ex + 1.8f, ey + 1.7f))
                                            }
                                        }
                                        expression == 1 || expression == 4 -> {
                                            drawOval(eyeBrush, topLeft = pt(ex - 5.1f, 36.1f), size = box(10.2f, 10.8f))
                                            drawCircle(Color.White, 1.7f * s, pt(ex - 1.5f, 39.4f))
                                            drawCircle(Color.White.copy(alpha = 0.8f), 0.9f * s, pt(ex + 1.6f, 43.2f))
                                        }
                                        else -> {
                                            drawOval(eyeBrush, topLeft = pt(ex - 5.3f, 34.2f), size = box(10.6f, 12.6f))
                                            drawCircle(Color.White, 2f * s, pt(ex - 1.5f, 37.6f))
                                            drawCircle(Color.White.copy(alpha = 0.9f), 1f * s, pt(ex + 1.9f, 42.5f))
                                        }
                                    }
                                }

                                if (expression == 5) {
                                    drawCircle(cyan, 2.7f * s, pt(50f, 51.8f), style = Stroke(1.9f * s))
                                } else {
                                    val mouth = Path().apply {
                                        fun m(x: Float, y: Float) = moveTo(px(x), py(y))
                                        fun c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
                                            cubicTo(px(x1), py(y1), px(x2), py(y2), px(x3), py(y3))
                                        when {
                                            expression == 1 || expression == 4 -> {
                                                m(45.5f, 53.2f); c(47.5f, 49.8f, 52.5f, 49.8f, 54.5f, 53.2f)
                                            }
                                            expression == 2 -> {
                                                m(45.8f, 51.5f); c(48f, 52.2f, 52f, 52.2f, 54.2f, 51.5f)
                                            }
                                            expression == 3 || isJumping -> {
                                                m(44f, 49.2f); c(45.5f, 53.5f, 48.5f, 53.5f, 50f, 50.8f)
                                                c(51.5f, 53.5f, 54.5f, 53.5f, 56f, 49.2f)
                                            }
                                            expression == 6 -> {
                                                m(47f, 51.5f); c(48.5f, 52.2f, 51.5f, 52.2f, 53f, 51.5f)
                                            }
                                            isThinking -> {
                                                m(47.2f, 52.2f); c(48.8f, 50.8f, 51.2f, 50.8f, 52.8f, 52.2f)
                                            }
                                            else -> {
                                                m(45.2f, 49.8f); c(47.2f, 54f, 52.8f, 54f, 54.8f, 49.8f)
                                            }
                                        }
                                    }
                                    drawPath(mouth, cyan, style = Stroke(2.1f * s, cap = StrokeCap.Round))
                                }

                                if (expression == 4) {
                                    drawOval(cyanLight, topLeft = pt(32.1f, 47.2f), size = box(2.8f, 4.6f))
                                    drawOval(cyanLight, topLeft = pt(65.1f, 47.2f), size = box(2.8f, 4.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Nút chatbot luôn ở góc màn hình. */
@Composable
private fun FloatingChatbotButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 124.dp, height = 148.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(120.dp).background(
                Brush.radialGradient(listOf(Color(0x558B5CF6), Color(0x118B5CF6), Color.Transparent)),
                CircleShape
            )
        )
        Box(
            Modifier.size(width = 120.dp, height = 144.dp)
                .semantics { contentDescription = "Mở trợ lý AI ScamShield"; role = Role.Button }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            RobotMascot(Modifier, size = 70.dp, mood = RobotMood.IDLE)
        }
    }
}

@Composable
private fun ChatbotPanel(c: Context, onPickImage: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val welcome = "Chào bạn! Mình là trợ lý ScamShield. Hãy gửi nội dung tin nhắn, lời thoại hoặc đường link đáng ngờ để mình phân tích dấu hiệu lừa đảo và gợi ý cách xử lý.\n\nKhông gửi OTP, mật khẩu hoặc thông tin cá nhân nhé."
    val lines = remember(c) {
        mutableStateListOf<ChatLine>().apply { addAll(ChatHistoryStore.load(c, welcome)) }
    }
    val listState = rememberLazyListState()
    val historySnapshot = lines.toList()
    LaunchedEffect(historySnapshot) {
        ChatHistoryStore.save(c, historySnapshot)
    }
    DisposableEffect(c) {
        onDispose { ChatHistoryStore.save(c, lines.toList()) }
    }
    var draft by remember { mutableStateOf("") }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var robotMood by remember { mutableStateOf(RobotMood.GREETING) }
    var showTools by remember { mutableStateOf(false) }

    LaunchedEffect(robotMood) {
        val duration = when (robotMood) {
            RobotMood.GREETING -> 2400L
            RobotMood.CUTE -> 2800L
            RobotMood.JUMPING -> 3600L
            RobotMood.THINKING -> 4200L
            RobotMood.SITTING -> 5200L
            RobotMood.WARNING, RobotMood.SAD -> 4200L
            RobotMood.ANALYZING, RobotMood.IDLE -> null
        }
        if (duration != null) {
            delay(duration)
            robotMood = RobotMood.IDLE
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImage = uri
    }

    fun sendImage() {
        val uri = selectedImage ?: return
        if (busy) return
        lines.add(ChatLine(fromUser = true, text = draft.trim().ifBlank { "Đã gửi ảnh để kiểm tra" }, imageUri = uri))
        robotMood = RobotMood.ANALYZING
        draft = ""
        selectedImage = null
        if (Store.str(c, "token").isBlank()) {
            lines.add(ChatLine(fromUser = false, text = "Bạn cần đăng nhập ScamShield để gửi ảnh cho AI phân tích. Ảnh chỉ được tải lên sau khi bạn nhấn Gửi."))
            robotMood = RobotMood.SAD
            return
        }
        busy = true
        lines.add(ChatLine(fromUser = false, text = "Mình đang xem ảnh…", pending = true))
        Thread {
            try {
                val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Không đọc được ảnh")
                if (bytes.size > 5_000_000) throw IllegalArgumentException("Ảnh quá lớn (tối đa 5MB)")
                val mime = c.contentResolver.getType(uri) ?: "image/jpeg"
                val payload = JSONObject().put("mime", mime)
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                Handler(Looper.getMainLooper()).post {
                    Api.call(c, "POST", "/api/analyze/media", payload) { code, result ->
                        val reply = if (code in 200..299 && result != null) fmtMedia(code, result)
                        else "Mình chưa phân tích được ảnh. ${err(result)}"
                        val pendingIndex = lines.indexOfLast { it.pending }
                        if (pendingIndex >= 0) lines[pendingIndex] = ChatLine(fromUser = false, text = reply)
                        else lines.add(ChatLine(fromUser = false, text = reply))
                        robotMood = robotMoodFromReply(reply)
                        busy = false
                    }
                }
            } catch (e: Exception) {
                val error = e.message ?: "Không thể đọc ảnh"
                Handler(Looper.getMainLooper()).post {
                    val pendingIndex = lines.indexOfLast { it.pending }
                    if (pendingIndex >= 0) lines[pendingIndex] = ChatLine(fromUser = false, text = error)
                    else lines.add(ChatLine(fromUser = false, text = error))
                    robotMood = RobotMood.SAD
                    busy = false
                }
            }
        }.start()
    }

    fun sendMessage() {
        if (selectedImage != null) { sendImage(); return }
        val message = draft.trim()
        if (message.isEmpty() || busy) return

        lines.add(ChatLine(fromUser = true, text = message))
        draft = ""

        val chatReply = ChatIntent.localReply(message)
        if (chatReply != null) {
            lines.add(ChatLine(fromUser = false, text = chatReply))
            robotMood = robotMoodFromReply(chatReply)
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
        robotMood = RobotMood.ANALYZING
        lines.add(ChatLine(fromUser = false, text = "Mình đang suy nghĩ…", pending = true))

        // Gửi lịch sử gần nhất để backend/Groq có thể hiểu các câu hỏi nối tiếp.
        // Bỏ dòng người dùng hiện tại vì nó đã được gửi riêng trong trường "message".
        val history = org.json.JSONArray()
        lines.dropLast(2).filterNot { it.pending }.takeLast(10).forEach { line ->
            val historyText = when {
                line.imageUri != null -> line.text.ifBlank { "[Người dùng đã gửi ảnh]" }
                else -> line.text
            }.trim()
            if (historyText.isNotBlank()) {
                history.put(
                    JSONObject()
                        .put("role", if (line.fromUser) "user" else "assistant")
                        .put("text", historyText.take(1200))
                )
            }
        }
        val payload = JSONObject()
            .put("message", message)
            .put("history", history)

        Api.call(c, "POST", "/api/chat", payload) { code, result ->
            val rawReply = result?.optString("reply")?.trim().orEmpty()
            val aiAnswered = result?.optBoolean("ai", false) == true
            val reply = if (code in 200..299 && rawReply.isNotBlank()) {
                if (aiAnswered) rawReply
                else "⚠️ Máy chủ chưa nhận được câu trả lời từ AI; đây là phản hồi dự phòng, không phải kết luận của Groq.\n\n$rawReply"
            } else {
                "Mình chưa kết nối được trợ lý AI. ${err(result)}"
            }
            val pendingIndex = lines.indexOfLast { it.pending }
            if (pendingIndex >= 0) {
                lines[pendingIndex] = ChatLine(fromUser = false, text = reply)
            } else {
                lines.add(ChatLine(fromUser = false, text = reply))
            }
            robotMood = robotMoodFromReply(reply)
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
                .background(Brush.horizontalGradient(listOf(Color(0xFF12346D), Color(0xFF2478C9), Color(0xFF44A8E4))))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                Modifier.size(width = 58.dp, height = 68.dp)
                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                RobotMascot(Modifier, size = 35.dp, mood = robotMood)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Trợ lý ScamShield", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(Color(0xFF8EF0C2), CircleShape))
                    Text("Đang trực tuyến · Bảo vệ bạn mỗi ngày", color = Color(0xFFD8EEFF), fontSize = 10.sp)
                }
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
                        imagePicker.launch("image/*")
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
                onClick = { imagePicker.launch("image/*") },
                enabled = !busy,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Chọn ảnh", tint = ChatGreen)
            }
            IconButton(
                onClick = { sendMessage() },
                enabled = (draft.isNotBlank() || selectedImage != null) && !busy,
                modifier = Modifier.size(46.dp)
                    .background(
                        if ((draft.isNotBlank() || selectedImage != null) && !busy) Brush.linearGradient(listOf(ChatGreen, ChatGreenDeep))
                        else Brush.linearGradient(listOf(ChatBorder, ChatBorder)),
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Rounded.Send,
                    contentDescription = "Gửi nội dung để phân tích",
                    tint = if ((draft.isNotBlank() || selectedImage != null) && !busy) Color.White else ChatMuted,
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
private fun ChatImage(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 3 }
                    BitmapFactory.decodeStream(input, null, options)
                }
            } catch (_: Exception) { null }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Ảnh người dùng gửi",
            modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
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
                .border(1.dp, ChatBorder, RoundedCornerShape(18.dp))
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
            line.imageUri?.let { ChatImage(it) }
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

    fun localReply(raw: String): String? {
        val text = raw.trim()
        val n = norm(text)
        val words = if (n.isEmpty()) emptyList() else n.split(" ")

        if (n.isEmpty()) return pick(
            "Mình đây! 😊 Bạn cần mình giúp kiểm tra tin nhắn hay đường link nào không?",
            "Chào bạn! Bạn cứ dán nội dung đáng ngờ vào đây, mình xem giúp nhé."
        )

        if (urlLike.containsMatchIn(text) || longNumber.containsMatchIn(text)) return null

        toolPrefixes.firstOrNull { n.startsWith(it) }?.let { p ->
            val rest = n.removePrefix(p).trim()
            return if (rest.length < 6) {
                "Bạn dán nội dung cần kiểm tra vào sau câu đó giúp mình nhé, mình xem ngay! 🔍"
            } else null
        }

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

        if (n.contains("nhay") && words.size <= 6) {
            return pick(
                "Yay! Mình đang nhảy tưng tưng đây nè! ✨",
                "Nhảy một cái cho vui nào! Bạn cần mình kiểm tra gì không?"
            )
        }
        if (n.contains("ngoi") && words.size <= 6) {
            return "Mình ngồi nghỉ một chút đây nhé, có tin nhắn lạ cứ gửi mình xem ngay nha!"
        }
        if ((n.contains("suy nghi") || n.contains("ngam")) && words.size <= 6) {
            return "Hmm… để mình suy nghĩ thật kỹ xem nào 🤔"
        }
        if ((n.contains("de thuong") || n.contains("xinh") || n.contains("cute")) && words.size <= 6) {
            return "Hihi cảm ơn bạn nhiều nha! Được khen dễ thương làm mình vui quá 💚"
        }

        val hasKeyword = scamKeywords.any { it in n } || "lua dao" in n || "scam" in n
        if (words.size <= 8 && hasKeyword && (n.contains("khong") || words.contains("ko") || text.endsWith("?")) &&
            !n.contains("http")
        ) {
            return "Bạn dán nguyên văn tin nhắn, lời thoại hoặc đường link vào đây giúp mình nhé, mình sẽ phân tích dấu hiệu lừa đảo cho bạn. Đừng gửi OTP hay mật khẩu nha."
        }

        if (n.length >= 40) return null
        if (hasKeyword && words.size >= 4) return null

        // Nội dung chưa được xử lý bằng câu trả lời nhanh phải đi tới trợ lý AI trên server.
        return null
    }
}
