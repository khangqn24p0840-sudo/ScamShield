package vn.scamshield

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AddPhotoAlternate
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    CUTE("Cute")
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
}

private fun robotMoodFromReply(reply: String): RobotMood {
    val text = reply.lowercase()
    return when {
        listOf("đáng ngờ", "lừa đảo", "nguy hiểm", "rủi ro cao", "giả mạo", "cảnh báo").any(text::contains) ->
            RobotMood.WARNING
        listOf("không thể phân tích", "chưa phân tích được", "đã xảy ra lỗi", "gặp sự cố", "không kết nối").any(text::contains) ->
            RobotMood.SAD
        listOf("không có gì", "rất vui được giúp", "cảm ơn").any(text::contains) ->
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

/**
 * Mascot renderer. The Rive asset is preferred; Compose draws a polished matching robot
 * until the signed .riv file is exported from the Rive Editor/CLI and packaged.
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

@Composable
private fun ComposeRobotMascot(
    modifier: Modifier,
    size: Dp,
    mood: RobotMood
) {
    // Khi ở trạng thái chờ, robot luân phiên các biểu cảm sau mỗi 15 giây.
    var idleExpression by remember { mutableIntStateOf(0) }
    LaunchedEffect(mood) {
        if (mood == RobotMood.IDLE) {
            while (true) {
                delay(15_000L)
                idleExpression = (idleExpression + 1) % 6
            }
        } else {
            idleExpression = 0
        }
    }

    val expression = when (mood) {
        RobotMood.IDLE -> idleExpression
        RobotMood.GREETING -> 0
        RobotMood.ANALYZING -> 6
        RobotMood.WARNING -> 5
        RobotMood.SAD -> 1
        RobotMood.CUTE -> 3
    }

    val motion = rememberInfiniteTransition(label = "scamshield-toy-robot")
    val bob by motion.animateFloat(
        -1.2f, 1.2f,
        infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-bob"
    )
    val wave by motion.animateFloat(
        -5.5f, 5.5f,
        infiniteRepeatable(tween(1450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "robot-wave"
    )
    val blink by motion.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
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

    Box(
        modifier = modifier
            .width(size * 1.28f)
            .height(size * 1.40f)
            .graphicsLayer { translationY = bob.dp.toPx() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier.fillMaxSize().semantics {
                contentDescription = when (expression) {
                    1 -> "Robot ScamShield đang buồn"
                    2 -> "Robot ScamShield đang dỗi"
                    3 -> "Robot ScamShield đang vui"
                    4 -> "Robot ScamShield đang khóc"
                    5 -> "Robot ScamShield ngạc nhiên"
                    6 -> "Robot ScamShield đang phân tích"
                    else -> "Robot ScamShield đang mỉm cười"
                }
            }
        ) {
            val sx = size.width / 100f
            val sy = size.height / 120f
            fun pt(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * sx, y * sy)
            fun box(x: Float, y: Float, w: Float, h: Float) =
                androidx.compose.ui.geometry.Size(w * sx, h * sy)

            val shell = Brush.linearGradient(
                colors = listOf(Color.White, Color(0xFFF4FBFE), Color(0xFFBFDDEB)),
                start = pt(14f, 18f), end = pt(88f, 115f)
            )
            val blue = Brush.linearGradient(
                colors = listOf(Color(0xFFDDF7FF), Color(0xFF9ADDF2), Color(0xFF65BFE0)),
                start = pt(30f, 63f), end = pt(70f, 108f)
            )

            // Bóng đổ mờ, không dùng viền trắng dày quanh toàn bộ robot.
            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color(0x332E82A8), Color(0x142E82A8), Color.Transparent),
                    center = pt(50f, 114f), radius = 33f * sx
                ),
                topLeft = pt(17f, 108f), size = box(66f, 10f)
            )

            // Ăng-ten nhỏ và đèn cyan.
            drawLine(Color(0xFF83C9DE), pt(50f, 13f), pt(50f, 20f), 2.2f * sx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFFE8FBFF), Color(0xFF8CCCE3))),
                topLeft = pt(44f, 17f), size = box(12f, 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * sx)
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFE6FFFF), Color(0xFF80F0F0), Color(0xFF55C5E2))),
                radius = 4.2f * sx, center = pt(50f, 10f)
            )
            drawCircle(Color.White.copy(alpha = 0.85f), 1.2f * sx, pt(48.7f, 8.8f))

            // Tai hai bên đầu, lộ nhẹ sau lớp vỏ.
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFFC7F0FA), Color(0xFF76C3DF))),
                topLeft = pt(8f, 36f), size = box(11f, 15f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * sx)
            )
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF76C3DF), Color(0xFFC7F0FA))),
                topLeft = pt(81f, 36f), size = box(11f, 15f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * sx)
            )

            // Tay trái giơ lên chào; xoay rất nhẹ ở khớp vai.
            val leftArm = androidx.compose.ui.graphics.Path().apply {
                moveTo(30f * sx, 71f * sy)
                cubicTo(25f * sx, 68f * sy, 22f * sx, 65f * sy, 18f * sx, 65f * sy)
                cubicTo(12f * sx, 64f * sy, 8f * sx, 69f * sy, 10f * sx, 74f * sy)
                cubicTo(12f * sx, 80f * sy, 19f * sx, 83f * sy, 24f * sx, 81f * sy)
                cubicTo(28f * sx, 80f * sy, 31f * sx, 76f * sy, 30f * sx, 71f * sy)
                close()
            }
            androidx.compose.ui.graphics.drawscope.withTransform({
                rotate(wave, pivot = pt(29f, 74f))
            }) {
                drawPath(leftArm, shell)
                drawPath(leftArm, Color(0xFFDDEEF4),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(0.75f * sx))
                drawCircle(Color(0xFFFFFFFF), 2.1f * sx, pt(9.5f, 68f))
                drawCircle(Color(0xFFEAF7FC), 1.9f * sx, pt(14f, 65.8f))
                drawCircle(Color(0xFFFFFFFF), 1.8f * sx, pt(18.5f, 68f))
            }

            // Tay phải buông xuống, bàn tay dạng găng tròn.
            val rightArm = androidx.compose.ui.graphics.Path().apply {
                moveTo(71f * sx, 70f * sy)
                cubicTo(77f * sx, 73f * sy, 80f * sx, 82f * sy, 83f * sx, 90f * sy)
                lineTo(88f * sx, 101f * sy)
                cubicTo(89f * sx, 105f * sy, 85f * sx, 107f * sy, 82f * sx, 103f * sy)
                cubicTo(77f * sx, 98f * sy, 73f * sx, 88f * sy, 70f * sx, 80f * sy)
                close()
            }
            drawPath(rightArm, shell)
            drawPath(rightArm, Color(0xFFDDEEF4),
                style = androidx.compose.ui.graphics.drawscope.Stroke(0.75f * sx))
            drawCircle(Color(0xFF9DD9EA), 2.7f * sx, pt(84f, 100f))
            drawCircle(Color.White, 1.4f * sx, pt(84f, 100f))
            drawRoundRect(shell, topLeft = pt(83f, 101f), size = box(5f, 7f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * sx))

            // Thân trắng bo tròn, hơi thuôn ở phần đáy.
            val torso = androidx.compose.ui.graphics.Path().apply {
                moveTo(31f * sx, 64f * sy)
                cubicTo(35f * sx, 61f * sy, 65f * sx, 61f * sy, 69f * sx, 64f * sy)
                cubicTo(76f * sx, 71f * sy, 73f * sx, 90f * sy, 68f * sx, 104f * sy)
                cubicTo(65f * sx, 112f * sy, 60f * sx, 115f * sy, 50f * sx, 115f * sy)
                cubicTo(40f * sx, 115f * sy, 35f * sx, 112f * sy, 32f * sx, 104f * sy)
                cubicTo(27f * sx, 90f * sy, 24f * sx, 71f * sy, 31f * sx, 64f * sy)
                close()
            }
            drawPath(torso, shell)
            drawPath(torso, Color(0xFFD9EBF2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(0.9f * sx))

            // Tấm ngực xanh pastel đặc trưng của robot mẫu.
            val chest = androidx.compose.ui.graphics.Path().apply {
                moveTo(34f * sx, 69f * sy)
                cubicTo(40f * sx, 72f * sy, 60f * sx, 72f * sy, 66f * sx, 69f * sy)
                cubicTo(64f * sx, 80f * sy, 63f * sx, 92f * sy, 60f * sx, 101f * sy)
                cubicTo(58f * sx, 106f * sy, 42f * sx, 106f * sy, 40f * sx, 101f * sy)
                cubicTo(37f * sx, 92f * sy, 36f * sx, 80f * sy, 34f * sx, 69f * sy)
                close()
            }
            drawPath(chest, blue)
            drawPath(chest, Color(0xFFB9E4F3),
                style = androidx.compose.ui.graphics.drawscope.Stroke(0.7f * sx))

            // Khớp vai và viền đế xanh nhạt.
            drawCircle(Brush.linearGradient(listOf(Color(0xFFD5F5FC), Color(0xFF78C6E2))),
                5.2f * sx, pt(29f, 73f))
            drawCircle(Brush.linearGradient(listOf(Color(0xFFD5F5FC), Color(0xFF78C6E2))),
                5.2f * sx, pt(71f, 73f))
            drawCircle(Color.White.copy(alpha = 0.62f), 1.4f * sx, pt(27.5f, 71.5f))
            drawCircle(Color.White.copy(alpha = 0.62f), 1.4f * sx, pt(69.5f, 71.5f))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFC3EFF9), Color(0xFF86CDE5))),
                topLeft = pt(38f, 110f), size = box(24f, 5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * sx)
            )

            // Đầu lớn, vỏ nhựa trắng ngọc trai và mặt kính navy.
            drawRoundRect(
                brush = shell, topLeft = pt(12f, 18f), size = box(76f, 47f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f * sx)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.72f), topLeft = pt(12f, 18f), size = box(76f, 47f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f * sx),
                style = androidx.compose.ui.graphics.drawscope.Stroke(0.8f * sx)
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF263A66), Color(0xFF111D40), Color(0xFF07142F)),
                    start = pt(23f, 22f), end = pt(78f, 62f)
                ),
                topLeft = pt(18f, 23f), size = box(64f, 37f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f * sx)
            )
            drawRoundRect(
                color = Color(0xFF87CFE9).copy(alpha = 0.24f),
                topLeft = pt(18f, 23f), size = box(64f, 37f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f * sx),
                style = androidx.compose.ui.graphics.drawscope.Stroke(0.7f * sx)
            )

            // Ánh phản chiếu mờ trên mặt kính, tạo cảm giác sâu hơn.
            val reflection = androidx.compose.ui.graphics.Path().apply {
                moveTo(23f * sx, 28f * sy)
                cubicTo(36f * sx, 23f * sy, 56f * sx, 23f * sy, 76f * sx, 28f * sy)
                cubicTo(58f * sx, 25f * sy, 39f * sx, 26f * sy, 23f * sx, 31f * sy)
                close()
            }
            drawPath(reflection, Color.White.copy(alpha = 0.06f))

            // Mắt xanh cyan có quầng sáng và nhịp chớp tự nhiên.
            val eyeY = 40f * sy
            val eyeHeight = if (blink < 0.2f) 1.25f * sy
                else if (expression == 5) 7.4f * sy else 6.3f * sy
            val eyeWidth = if (expression == 5) 5.4f * sx else 4.5f * sx
            val lookOffset = if (expression == 6) scan * sx else 0f
            for (eyeX in listOf(39.5f, 60.5f)) {
                val center = pt(eyeX, 40f)
                drawCircle(Color(0xFF55F5F5).copy(alpha = 0.18f), 7.3f * sx, center)
                if (blink < 0.2f) {
                    drawLine(Color(0xFF67F4F2),
                        androidx.compose.ui.geometry.Offset(center.x - 3.2f * sx, center.y),
                        androidx.compose.ui.geometry.Offset(center.x + 3.2f * sx, center.y),
                        1.5f * sx, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                } else {
                    drawOval(
                        Brush.verticalGradient(listOf(Color(0xFFB4FFFF), Color(0xFF4AE6EC), Color(0xFF20BFD5))),
                        topLeft = androidx.compose.ui.geometry.Offset(center.x - eyeWidth / 2f + lookOffset, center.y - eyeHeight / 2f),
                        size = androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight)
                    )
                    drawCircle(Color.White.copy(alpha = 0.7f), 0.75f * sx,
                        androidx.compose.ui.geometry.Offset(center.x - 0.8f * sx + lookOffset, center.y - 1.35f * sy))
                }
            }

            // Miệng vẽ trực tiếp, đổi hình theo cảm xúc thay vì ký tự.
            val mouth = androidx.compose.ui.graphics.Path().apply {
                when (expression) {
                    1, 4 -> {
                        moveTo(43f * sx, 49f * sy)
                        cubicTo(46f * sx, 45f * sy, 54f * sx, 45f * sy, 57f * sx, 49f * sy)
                    }
                    2 -> {
                        moveTo(44f * sx, 48f * sy)
                        cubicTo(48f * sx, 49.2f * sy, 52f * sx, 49.2f * sy, 56f * sx, 48f * sy)
                    }
                    5 -> {
                        moveTo(47f * sx, 46f * sy)
                        cubicTo(47f * sx, 44f * sy, 53f * sx, 44f * sy, 53f * sx, 46f * sy)
                        cubicTo(53f * sx, 51f * sy, 47f * sx, 51f * sy, 47f * sx, 46f * sy)
                        close()
                    }
                    6 -> {
                        moveTo(47f * sx, 49f * sy)
                        cubicTo(49f * sx, 47f * sy, 51f * sx, 47f * sy, 53f * sx, 49f * sy)
                    }
                    else -> {
                        moveTo(42f * sx, 46.5f * sy)
                        cubicTo(46f * sx, 51f * sy, 54f * sx, 51f * sy, 58f * sx, 46.5f * sy)
                    }
                }
            }
            drawPath(
                mouth,
                color = Color(0xFF73F7F0),
                style = if (expression == 5) androidx.compose.ui.graphics.drawscope.Fill
                    else androidx.compose.ui.graphics.drawscope.Stroke(1.7f * sx,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )

            // Má hồng nhẹ khi vui/dễ thương và giọt nước khi buồn.
            if (expression == 3 || expression == 0) {
                drawCircle(Color(0xFFFF9EB8).copy(alpha = 0.30f), 2.0f * sx, pt(29f, 47f))
                drawCircle(Color(0xFFFF9EB8).copy(alpha = 0.30f), 2.0f * sx, pt(71f, 47f))
            }
            if (expression == 4) {
                drawCircle(Color(0xFF6BDDF4), 1.2f * sx, pt(33f, 47f))
                drawCircle(Color(0xFF6BDDF4), 1.2f * sx, pt(67f, 47f))
            }
        }
    }
}

/** Nút chatbot luôn ở góc màn hình, robot chỉ nhún và nghiêng rất nhẹ khi chờ. */
@Composable
private fun FloatingChatbotButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 124.dp, height = 148.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(120.dp).background(
            Brush.radialGradient(listOf(Color(0x5574BFFF), Color(0x1174BFFF), Color.Transparent)), CircleShape))
        Box(Modifier.size(width = 120.dp, height = 144.dp).semantics { contentDescription = "Mở trợ lý AI ScamShield"; role = Role.Button }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            RobotMascot(Modifier, size = 70.dp, mood = RobotMood.IDLE)
        }
    }
}

@Composable
private fun ChatbotPanel(c: Context, onPickImage: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val welcome = "Chào bạn! Mình là trợ lý ScamShield. Hãy gửi nội dung tin nhắn, lời thoại hoặc đường link đáng ngờ để mình phân tích dấu hiệu lừa đảo và gợi ý cách xử lý.\n\nKhông gửi OTP, mật khẩu hoặc thông tin cá nhân nhé."
    val lines = remember { mutableStateListOf(ChatLine(fromUser = false, text = welcome)) }
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var robotMood by remember { mutableStateOf(RobotMood.GREETING) }
    var showTools by remember { mutableStateOf(false) }

    LaunchedEffect(robotMood) {
        val duration = when (robotMood) {
            RobotMood.GREETING -> 2200L
            RobotMood.CUTE -> 2600L
            RobotMood.WARNING, RobotMood.SAD -> 4200L
            RobotMood.ANALYZING -> null
            RobotMood.IDLE -> null
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

        // Chào hỏi / xã giao: trả lời tự nhiên ngay trên máy, không cần đăng nhập, không chấm điểm.
        val chatReply = ChatIntent.localReply(message)
        if (chatReply != null) {
            lines.add(ChatLine(fromUser = false, text = chatReply))
            robotMood = if (chatReply.contains("cảm ơn", ignoreCase = true)) RobotMood.CUTE else RobotMood.GREETING
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
