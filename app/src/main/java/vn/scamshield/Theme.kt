package vn.scamshield

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bảng màu "Navy tinh tế": nền navy sâu, thẻ slate viền mảnh, điểm nhấn vàng + xanh ngọc. */
object C {
    // Nền
    val Bg = Color(0xFF0A1128)
    val BgHi = Color(0xFF13203F)
    val Bar = Color(0xFF0C1631)
    // Thẻ
    val Card = Color(0xFF16224A)
    val CardHi = Color(0xFF1E2C5C)
    val Field = Color(0xFF101A3A)
    val Stroke = Color(0x26FFFFFF)              // viền trắng 15%
    // Điểm nhấn
    val Gold = Color(0xFFFFC93C)
    val GoldDeep = Color(0xFFF59E0B)
    val Ink = Color(0xFF241A00)                 // chữ trên nền vàng
    val Mint = Color(0xFF34D399)
    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFFA78BFA)
    val Blue = Color(0xFF60A5FA)
    val Rose = Color(0xFFFB7185)
    // Trạng thái
    val Ok = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
    // Chữ
    val Sub = Color(0xFFB9C6E3)
    val Dim = Color(0xFF8291B5)
    val Faint = Color(0xFF5F6E92)
    // Tương thích tên màu cũ (map sang màu mới)
    val Brown get() = Card
    val Tile get() = Mint
    val Label get() = Mint
    val Teal get() = Cyan
    val LoginBg get() = BgHi
    val Blob get() = CardHi

    val goldGrad = listOf(Gold, GoldDeep)
}

/** Co nhẹ khi nhấn — cảm giác bấm mượt cho thẻ, ô danh mục. */
@Composable fun Pressable(onClick: () -> Unit, modifier: Modifier = Modifier, scale: Float = 0.96f, content: @Composable () -> Unit) {
    val itn = remember { MutableInteractionSource() }
    val pressed by itn.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, tween(120))
    Box(modifier.graphicsLayer { scaleX = s; scaleY = s }.clickable(interactionSource = itn, indication = null, onClick = onClick)) { content() }
}

/** Phồng/xẹp nhẹ lặp lại — dùng cho khiên đang hoạt động. */
@Composable fun Modifier.pulse(min: Float = 1f, max: Float = 1.07f, dur: Int = 1100): Modifier {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(min, max, infiniteRepeatable(tween(dur), RepeatMode.Reverse), label = "pulseS")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/** Khiên tròn có quầng sáng, dùng ở trang đăng nhập và thẻ trạng thái. */
@Composable fun ShieldBadge(tint: Color = C.Mint, size: Dp = 56.dp, pulseOn: Boolean = true) {
    val m = if (pulseOn) Modifier.pulse(0.94f, 1.05f, 1400) else Modifier
    Box(m.size(size * 1.45f), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(tint.copy(alpha = 0.32f), Color.Transparent))))
        Box(Modifier.size(size).background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.72f))), CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, tint = C.Bg, modifier = Modifier.size(size * 0.48f))
        }
    }
}

/** Thẻ nội dung chuẩn: nền slate, viền mảnh, bo 22dp. */
@Composable fun CardX(modifier: Modifier = Modifier, bg: Color = C.Card, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(bg, RoundedCornerShape(22.dp)).border(1.dp, C.Stroke, RoundedCornerShape(22.dp)).padding(16.dp),
        Arrangement.spacedBy(10.dp), content = content)

/** Biểu tượng tròn nền màu nhạt — dùng làm đầu đề mục, hàng cài đặt. */
@Composable fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 42.dp, iconSize: Dp = 21.dp) =
    Box(Modifier.size(size).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }

/** Nút chính vàng gradient. */
@Composable fun GoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Button(onClick, modifier.fillMaxWidth().height(52.dp), enabled = enabled, shape = shape, contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = C.Ink,
            disabledContainerColor = Color.Transparent, disabledContentColor = C.Ink.copy(alpha = 0.55f))) {
        Box(Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.45f).background(Brush.horizontalGradient(C.goldGrad), shape), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (leading != null) Icon(leading, null, modifier = Modifier.size(20.dp))
                Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

/** Nút phụ viền mảnh nền trong suốt. */
@Composable fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier.fillMaxWidth().height(48.dp), enabled = enabled, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (enabled) C.Stroke else C.Stroke.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = C.Dim)) {
        if (leading != null) { Icon(leading, null, Modifier.size(18.dp), tint = C.Gold); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }

/** Nút dừng/hành động nguy (đỏ). */
@Composable fun DangerButton(text: String, modifier: Modifier = Modifier, leading: ImageVector? = null, onClick: () -> Unit) =
    Button(onClick, modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.Bad, contentColor = Color(0xFF3B0A0A))) {
        if (leading != null) { Icon(leading, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }

/** Ô nhập tối, viền sáng khi focus, icon đầu dòng. */
@Composable fun PillField(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    password: Boolean = false, leading: ImageVector? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val itn = remember { MutableInteractionSource() }
    val focused by itn.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(value, onChange, modifier, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        keyboardOptions = keyboardOptions,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(C.Gold),
        interactionSource = itn,
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().height(52.dp).background(C.Field, shape)
                .border(1.dp, if (focused) C.Gold.copy(alpha = 0.7f) else C.Stroke, shape).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (leading != null) Icon(leading, null, tint = if (focused) C.Gold else C.Dim, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = C.Dim, fontSize = 14.sp, maxLines = 1)
                    inner()
                }
            }
        })
}

/** Tiêu đề khối + nút hành động "Xem tất cả". */
@Composable fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (actionLabel != null && onAction != null)
            Text(actionLabel, color = C.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAction))
    }

/** Chip lọc (Tất cả / Cảnh báo / Đã chặn…). */
@Composable fun SelChip(label: String, on: Boolean, onClick: () -> Unit) =
    Box(Modifier.background(if (on) C.Gold else Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
        .border(1.dp, if (on) Color.Transparent else C.Stroke, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, color = if (on) C.Ink else C.Sub, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }

/** Chip trạng thái Bật/Tắt. */
@Composable fun StatusPill(on: Boolean, onText: String = "Bật", offText: String = "Tắt") =
    Box(Modifier.background((if (on) C.Mint else C.Faint).copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(if (on) onText else offText, color = if (on) C.Mint else C.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }

/** Ô danh mục: nền gradient màu nhạt + icon màu, co nhẹ khi nhấn. */
@Composable fun CatTile(modifier: Modifier = Modifier, icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Pressable(onClick, modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)
                .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.30f), tint.copy(alpha = 0.10f))), RoundedCornerShape(18.dp))
                .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(label, color = C.Sub, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Thẻ đề xuất cuộn ngang: gradient đậm + bong bóng icon + nhãn trạng thái. */
@Composable fun RecCard(icon: ImageVector, title: String, badge: String, grad: List<Color>, onClick: () -> Unit) {
    Pressable(onClick) {
        Column(Modifier.width(148.dp)) {
            Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(grad), RoundedCornerShape(20.dp))
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(56.dp).background(Color.White.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 2, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Box(Modifier.align(Alignment.CenterHorizontally).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(badge, color = C.Sub, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** Ô số liệu nhanh: icon + số đếm + nhãn. */
@Composable fun StatCard(value: Int, label: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) =
    Column(modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
        .border(1.dp, C.Stroke, RoundedCornerShape(18.dp)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IconBadge(icon, tint, 36.dp, 18.dp)
        Text("$value", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = C.Dim, fontSize = 11.sp, maxLines = 1)
    }

/** Banner mẹo an toàn viền vàng. */
@Composable fun TipBanner(text: String) =
    Row(Modifier.fillMaxWidth().background(C.Gold.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
        .border(1.dp, C.Gold.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(Icons.Rounded.Lightbulb, C.Gold, 38.dp, 19.dp)
        Text(text, color = C.Sub, fontSize = 12.5.sp, lineHeight = 17.sp)
    }

/** Thanh điểm rủi ro có animation chạy tới giá trị. */
@Composable fun ScoreBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(650))
    Box(modifier.fillMaxWidth().height(8.dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(4.dp))) {
        if (f > 0.005f) Box(Modifier.fillMaxHeight().fillMaxWidth(f)
            .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.72f))), RoundedCornerShape(4.dp)))
    }
}


// ───────────────────────── Hình nền ─────────────────────────

private fun shieldPath(cx: Float, cy: Float, w: Float, h: Float) = Path().apply {
    moveTo(cx, cy - h / 2)
    lineTo(cx + w / 2, cy - h / 2 + h * 0.13f)
    lineTo(cx + w / 2, cy)
    cubicTo(cx + w / 2, cy + h * 0.28f, cx + w * 0.2f, cy + h * 0.42f, cx, cy + h / 2)
    cubicTo(cx - w * 0.2f, cy + h * 0.42f, cx - w / 2, cy + h * 0.28f, cx - w / 2, cy)
    lineTo(cx - w / 2, cy - h / 2 + h * 0.13f)
    close()
}

/** Nền app: gradient navy + quầng sáng màu trôi chậm + lưới chấm mờ + hình khiên mờ. Lớp tĩnh và lớp động tách riêng cho nhẹ máy. */
@Composable fun AppBackground() {
    Box(Modifier.fillMaxSize()) {
        // Lớp tĩnh (chỉ vẽ một lần): gradient, lưới chấm, khiên
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF15245A), C.Bg, Color(0xFF060B1C))))
            val gap = 26.dp.toPx()
            val pts = ArrayList<androidx.compose.ui.geometry.Offset>()
            var yy = gap / 2
            while (yy < size.height) { var xx = gap / 2; while (xx < size.width) { pts.add(androidx.compose.ui.geometry.Offset(xx, yy)); xx += gap }; yy += gap }
            drawPoints(pts, androidx.compose.ui.graphics.PointMode.Points, Color.White.copy(alpha = 0.07f), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
            val line = Stroke(width = 1.6.dp.toPx())
            drawPath(shieldPath(size.width * 0.86f, size.height * 0.20f, 190.dp.toPx(), 230.dp.toPx()), Color.White.copy(alpha = 0.06f), style = line)
            drawPath(shieldPath(size.width * 0.86f, size.height * 0.20f, 140.dp.toPx(), 170.dp.toPx()), C.Gold.copy(alpha = 0.07f), style = line)
            drawPath(shieldPath(size.width * 0.06f, size.height * 0.70f, 240.dp.toPx(), 290.dp.toPx()), Color.White.copy(alpha = 0.045f), style = line)
            drawPath(shieldPath(size.width * 0.06f, size.height * 0.70f, 170.dp.toPx(), 205.dp.toPx()), C.Mint.copy(alpha = 0.06f), style = line)
        }
        // Lớp động: 3 quầng sáng màu trôi rất chậm
        val tr = rememberInfiniteTransition(label = "bg")
        val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
        Canvas(Modifier.fillMaxSize()) {
            fun glow(col: Color, a: Float, cx: Float, cy: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(col.copy(alpha = a), Color.Transparent), androidx.compose.ui.geometry.Offset(cx, cy), r),
                r, androidx.compose.ui.geometry.Offset(cx, cy))
            val w = size.width; val h = size.height
            glow(Color(0xFF7C5CFF), 0.26f, w * (0.85f - 0.25f * p), h * (0.08f + 0.06f * p), 340.dp.toPx())
            glow(C.Mint, 0.17f, w * (0.05f + 0.20f * p), h * (0.45f - 0.08f * p), 300.dp.toPx())
            glow(C.Gold, 0.11f, w * (0.80f - 0.18f * p), h * (0.92f - 0.06f * p), 320.dp.toPx())
        }
    }
}
