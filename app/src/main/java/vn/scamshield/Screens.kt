package vn.scamshield

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import vn.scamshield.face.FaceBus

/** Cầu nối giữa giao diện và các hành động cần Activity (xin quyền, mở cài đặt...). */
class Hooks(
    val tick: () -> Int,
    val callOn: () -> Boolean, val smsOn: () -> Boolean,
    val enableCalls: () -> Unit, val enableSms: () -> Unit,
    val toggleListen: () -> Unit,
    val pickImage: () -> Unit,
    val startFace: () -> Unit, val stopFace: () -> Unit,
    val openOverlay: () -> Unit, val openSocial: () -> Unit,
    val aiMsg: () -> String, val setAiMsg: (String) -> Unit
)

fun displayName(c: Context): String {
    val n = Store.str(c, "name")
    if (n.isNotBlank()) return n
    val e = Store.str(c, "email")
    return if (e.isNotBlank()) e.substringBefore("@") else "Khách"
}

@Composable fun ShieldApp(c: Context, h: Hooks) {
    h.tick()
    var loginDone by remember { mutableStateOf(Store.str(c, "token").isNotEmpty() || Store.flag(c, "skip_login")) }
    Box(Modifier.fillMaxSize()) {
        AppBackground()
        if (!loginDone) LoginScreen(c, { loginDone = true }, { Store.putFlag(c, "skip_login", true); loginDone = true })
        else MainShell(c, h) { Store.putFlag(c, "skip_login", false); loginDone = false }
    }
}

// ───────────────────────── Đăng nhập ─────────────────────────

@Composable fun LoginScreen(c: Context, onDone: () -> Unit, onSkip: () -> Unit) {
    var reg by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(Store.str(c, "email")) }
    var pass by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(Store.str(c, "server")) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    val hasDefault = Config.SERVER_URL.isNotBlank()

    fun finish(code: Int, j: JSONObject?) {
        busy = false
        if (j != null && code in 200..299 && j.has("token")) {
            Store.putStr(c, "token", j.getString("token")); Store.putStr(c, "email", email.trim())
            val nm = j.optString("name").ifEmpty { user.trim().ifEmpty { Store.str(c, "name").ifEmpty { email.trim().substringBefore("@") } } }
            Store.putStr(c, "name", nm)
            onDone()
        } else msg = err(j)
    }
    fun submit() {
        if (email.isBlank() || pass.length < 8) { msg = "Nhập email và mật khẩu (từ 8 ký tự)"; return }
        busy = true; msg = if (reg) "Đang đăng ký…" else "Đang đăng nhập…"
        Api.call(c, "POST", if (reg) "/api/register" else "/api/login",
            JSONObject().put("email", email.trim()).put("password", pass).also { if (reg && user.isNotBlank()) it.put("username", user.trim()) }) { code, j -> finish(code, j) }
    }

    Box(Modifier.fillMaxSize()) {
        // Quầng sáng trang trí
        Box(Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-70).dp).size(320.dp)
            .background(Brush.radialGradient(listOf(C.Mint.copy(alpha = 0.16f), Color.Transparent))))
        Box(Modifier.align(Alignment.TopStart).offset(x = (-80).dp, y = 110.dp).size(300.dp)
            .background(Brush.radialGradient(listOf(C.Violet.copy(alpha = 0.14f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomStart).offset(x = (-60).dp, y = 60.dp).size(340.dp)
            .background(Brush.radialGradient(listOf(C.Blue.copy(alpha = 0.10f), Color.Transparent))))

        Column(Modifier.align(Alignment.Center).fillMaxWidth().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            ShieldBadge(C.Gold, 62.dp)
            Spacer(Modifier.height(16.dp))
            Text("ScamShield VN", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text("Khiên chắn chống lừa đảo trong túi bạn", color = C.Sub, fontSize = 13.sp)
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().background(C.Card, RoundedCornerShape(24.dp))
                .border(1.dp, C.Stroke, RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasDefault || showServer)
                    PillField(server, { server = it; Store.putStr(c, "server", it.trim()) }, "Máy chủ https://…", leading = Icons.Rounded.Language)
                if (reg) PillField(user, { user = it }, "Tên người dùng", leading = Icons.Rounded.Person)
                PillField(email, { email = it }, "Email", leading = Icons.Rounded.Email,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                PillField(pass, { pass = it }, "Mật khẩu (từ 8 ký tự)", password = true, leading = Icons.Rounded.Lock)
                GoldButton(if (reg) "Đăng ký" else "Đăng nhập", enabled = !busy, leading = Icons.Rounded.Login) { if (!busy) submit() }
                if (msg.isNotEmpty()) Text(msg, color = C.Warn, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(), lineHeight = 17.sp)
                Text("Quên mật khẩu?", color = C.Dim, fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    .clickable { msg = "Chưa có chức năng đặt lại mật khẩu tự động. Hãy liên hệ người quản trị máy chủ." })
            }
            Spacer(Modifier.height(14.dp))
            TextButton({ onSkip() }) { Text("Dùng không cần tài khoản →", color = C.Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (hasDefault) TextButton({ showServer = !showServer }) {
                Text(if (showServer) "Ẩn máy chủ" else "Máy chủ riêng", color = C.Dim, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            Text(if (reg) "Đã có tài khoản? Đăng nhập ngay" else "Chưa có tài khoản? Đăng ký ngay",
                color = C.Cyan, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { reg = !reg; msg = "" })
        }
    }
}

// ───────────────────────── Khung chính + thanh điều hướng ─────────────────────────

@Composable fun MainShell(c: Context, h: Hooks, onLogin: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf("") }
    var openPost by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = sub.isNotEmpty() || tab != 0) { if (sub.isNotEmpty()) sub = "" else tab = 0 }
    val open: (String) -> Unit = { sub = it }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sub.isNotEmpty()) SubScreen(c, h, sub, { sub = "" }, open)
            else Crossfade(tab, animationSpec = tween(230), label = "tabs") { t ->
                when (t) {
                    0 -> HomeTab(c, h, open, { openPost = it; tab = 4 }, { tab = 4 }) { tab = 5 }
                    1 -> SettingsTab(c, h)
                    2 -> MessageScreen(c, null, open)
                    4 -> CommunityTab(c, onLogin, openPost) { openPost = 0L }
                    else -> AccountTab(c, onLogin, open)
                }
            }
        }
        BottomBar(tab, sub.isNotEmpty()) { tab = it; sub = "" }
    }
        ChatbotLauncher(c, h)
    }

}

/** Thanh điều hướng nổi dạng pill: 2 mục | nút Kiểm tra ở giữa | 2 mục. Nhật ký đã chuyển vào Tài khoản. */
@Composable fun BottomBar(tab: Int, inSub: Boolean, onTab: (Int) -> Unit) {
    // (số tab, icon, nhãn) — null là chỗ chừa cho nút Kiểm tra
    val items = listOf(
        Triple(0, Icons.Rounded.Home, "Trang chủ"), Triple(1, Icons.Rounded.Tune, "Cài đặt"), null,
        Triple(4, Icons.Rounded.Forum, "Cộng đồng"), Triple(5, Icons.Rounded.Person, "Tài khoản"))
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp).shadow(18.dp, RoundedCornerShape(33.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFF9FCFF), Color(0xFFE4EEFC))), RoundedCornerShape(33.dp))
            .border(1.dp, Color.White, RoundedCornerShape(33.dp)), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { it ->
                if (it == null) Spacer(Modifier.width(84.dp))
                else {
                    val sel = !inSub && tab == it.first
                    val tint by animateColorAsState(if (sel) Color(0xFF236FE0) else Color(0xFF687A98), tween(220))
                    Column(Modifier.weight(1f).clickable { onTab(it.first) }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(it.second, null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(it.third, fontSize = 10.sp, color = tint, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).offset(y = (-8).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp)
                // quầng sáng vàng vẽ tay thay cho shadow màu (shadow màu hay bị vẽ thành khối vuông trên một số máy)
                .drawBehind { drawCircle(Brush.radialGradient(listOf(C.Gold.copy(alpha = 0.45f), Color.Transparent), center, size.minDimension * 0.85f), size.minDimension * 0.85f) },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(54.dp).background(Brush.linearGradient(listOf(Color(0xFF398BFF), Color(0xFF1459C8))), CircleShape)
                    .border(3.dp, Color.White, CircleShape)
                    .clickable { onTab(2) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            Text("Kiểm tra", color = Color(0xFF236FE0), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

// ───────────────────────── Trang chủ ─────────────────────────

private data class Cat(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val tint: Color, val key: String)

@Composable fun Avatar(c: Context, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).background(Brush.linearGradient(C.goldGrad), CircleShape).padding(2.dp)
        .background(C.Bg, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(displayName(c).take(1).uppercase(), color = C.Gold, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable fun HomeTab(c: Context, h: Hooks, open: (String) -> Unit, onPost: (Long) -> Unit, onFeed: () -> Unit, toAccount: () -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE7F1FF), Color(0xFFD7E7FF), Color(0xFFF0F6FF)))).verticalScroll(rememberScrollState())) {
        HomeBrandHeader(c, toAccount)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(17.dp),
                placeholder = { Text("Tìm cuộc gọi, tin nhắn, số điện thoại…", color = Color(0xFF8195B4), fontSize = 12.5.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color(0xFF397FD4)) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF18365F), fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF18365F), unfocusedTextColor = Color(0xFF18365F),
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF438CE8), unfocusedBorderColor = Color(0xFFBCD1EF),
                    cursorColor = Color(0xFF236FE0)
                )
            )
            if (q.isNotBlank()) Box(Modifier.fillMaxWidth().background(Color(0xFF0E1B3B), RoundedCornerShape(20.dp)).padding(12.dp)) { SearchResults(c, q) } else HomeBody(c, h, open, onPost, onFeed)
        }
    }
}

@Composable private fun HomeBrandHeader(c: Context, toAccount: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xFFEAF4FF), Color(0xFFBBD8FF), Color(0xFFDCEBFF))),
                RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Color(0xFF2F8BFF), Color(0xFF1454C5))), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(29.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("ScamShield", color = Color(0xFF102A58), fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Text("Cùng bạn nhận diện và phòng tránh lừa đảo", color = Color(0xFF45618E), fontSize = 11.5.sp)
            }
            Box(Modifier.size(42.dp).background(Color.White.copy(alpha = 0.82f), CircleShape)
                .border(1.dp, Color.White, CircleShape).clickable(onClick = toAccount), contentAlignment = Alignment.Center) {
                Text(displayName(c).take(1).uppercase(), color = Color(0xFF1558C8), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Xin chào, " + displayName(c) + "!", color = Color(0xFF163466), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("👋", fontSize = 17.sp)
        }
        Text("Hôm nay bạn muốn kiểm tra điều gì?", color = Color(0xFF45618E), fontSize = 12.5.sp)
    }
}

@Composable private fun HomeBody(c: Context, h: Hooks, open: (String) -> Unit, onPost: (Long) -> Unit, onFeed: () -> Unit) {
    var featurePage by remember { mutableIntStateOf(0) }
    StatusCard(h, open)
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        HomeStatCard(Store.count(c, "blocked"), "Đã chặn", Icons.Rounded.Block, Color(0xFFE35D67), Modifier.weight(1f))
        HomeStatCard(Store.count(c, "warned"), "Đã cảnh báo", Icons.Rounded.Campaign, Color(0xFFE1A129), Modifier.weight(1f))
        HomeStatCard(Store.blocklist(c).size, "Số bị chặn", Icons.Rounded.PhoneMissed, Color(0xFF299B83), Modifier.weight(1f))
    }
    HomeSectionHeader("Tính năng nổi bật")
    val cats = listOf(
        Cat(Icons.Rounded.Shield, "Tất cả kiểm tra", Color(0xFF2477E8), "all"),
        Cat(Icons.Rounded.Videocam, "Video Deepfake", C.Violet, "video"),
        Cat(Icons.Rounded.PhoneMissed, "SĐT lừa đảo", C.Bad, "blocklist"),
        Cat(Icons.Rounded.Sms, "Tin nhắn", C.Warn, "msg"),
        Cat(Icons.Rounded.Forum, "Zalo, Messenger", C.Cyan, "social"),
        Cat(Icons.Rounded.BugReport, "Mã độc & Link", C.Rose, "malware"),
        Cat(Icons.Rounded.SmartToy, "Yêu cầu AI", C.Blue, "ai"),
        Cat(Icons.Rounded.FamilyRestroom, "Chat gia đình", C.Mint, "family"),
        Cat(Icons.Rounded.MoreHoriz, "Khác", C.Dim, "other"))
    val featurePages = cats.chunked(6)
    val currentPage = featurePage.coerceIn(0, featurePages.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        featurePages[currentPage].chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { ct -> HomeCategoryTile(Modifier.weight(1f), ct.icon, ct.label, ct.tint) { open(ct.key) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (featurePages.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                featurePages.indices.forEach { index ->
                    Box(Modifier.padding(horizontal = 4.dp).size(if (index == currentPage) 8.dp else 6.dp)
                        .background(if (index == currentPage) Color(0xFF3986F6) else C.Dim.copy(alpha = 0.45f), CircleShape)
                        .clickable { featurePage = index })
                }
            }
        }
    }
    AssistantWelcomeCard(open)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSectionHeader("Tin tức & Báo chí", "Xem tất cả") { feedStartSection = "news"; onFeed() }
        Text("Các tin cảnh báo và thông tin phòng tránh được cập nhật từ nguồn báo chí.", color = Color(0xFF5A7196), fontSize = 11.5.sp, lineHeight = 16.sp)
        FeedPreview(c, "news", { feedStartSection = "news"; onFeed() }, onPost)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeSectionHeader("Cảnh báo từ cộng đồng", "Xem tất cả") { feedStartSection = "post"; onFeed() }
        FeedPreview(c, "post", { feedStartSection = "post"; onFeed() }, onPost)
    }
    HomeTipBanner("Ngân hàng, công an không bao giờ yêu cầu OTP hay chuyển tiền qua điện thoại.")
}

@Composable private fun HomeSectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color(0xFF18365F), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        if (actionLabel != null && onAction != null) Text(actionLabel, color = Color(0xFF236FE0), fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onAction))
    }
}

@Composable private fun HomeStatCard(value: Int, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier) {
    Column(modifier.height(96.dp).background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(17.dp))
        .border(1.dp, Color.White, RoundedCornerShape(17.dp)).padding(horizontal = 5.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(31.dp).background(tint.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Text(value.toString(), color = Color(0xFF18365F), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Color(0xFF647B9E), fontSize = 9.5.sp, maxLines = 1)
    }
}

@Composable private fun HomeTipBanner(text: String) {
    Row(Modifier.fillMaxWidth().background(Color(0xFFFFF6DF), RoundedCornerShape(17.dp))
        .border(1.dp, Color(0xFFFFE3A6), RoundedCornerShape(17.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.Lightbulb, null, tint = Color(0xFFC58A18), modifier = Modifier.size(25.dp))
        Text(text, color = Color(0xFF77571B), fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

@Composable private fun HomeCategoryTile(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Pressable(onClick, modifier) {
        Column(
            Modifier.fillMaxWidth().height(105.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFFF8FBFF), Color(0xFFDDEBFF))), RoundedCornerShape(19.dp))
                .border(1.dp, Color.White.copy(alpha = 0.92f), RoundedCornerShape(19.dp))
                .padding(horizontal = 5.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(Modifier.size(42.dp).background(tint.copy(alpha = 0.13f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
            }
            Text(label, color = Color(0xFF233B62), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
        }
    }
}

@Composable private fun AssistantWelcomeCard(open: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFFEAF4FF), Color(0xFFD3E5FF))), RoundedCornerShape(22.dp))
            .border(1.dp, Color.White, RoundedCornerShape(22.dp)).padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Xin chào! Mình là ScamShield 😊", color = Color(0xFF17396F), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text("Mình có thể giúp bạn kiểm tra nội dung đáng ngờ và chia sẻ mẹo an toàn.", color = Color(0xFF49658D), fontSize = 11.5.sp, lineHeight = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HomeActionChip("Kiểm tra ngay", Icons.Rounded.Shield, Color(0xFF236FE0)) { open("all") }
                HomeActionChip("Mẹo an toàn", Icons.Rounded.Lightbulb, Color(0xFFCB8B12)) { open("other") }
            }
        }
        RobotMascot(Modifier.width(82.dp).height(96.dp), size = 72.dp)
    }
}

@Composable private fun HomeActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(Modifier.background(Color.White.copy(alpha = 0.84f), RoundedCornerShape(13.dp))
        .border(1.dp, Color.White, RoundedCornerShape(13.dp)).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(label, color = Color(0xFF254875), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

object Protect {
    private var version by mutableIntStateOf(0)
    fun on(c: Context): Boolean { val v = version; if (v < 0) return false; return Store.flag(c, "protect_on") }
    fun set(c: Context, v: Boolean) { Store.putFlag(c, "protect_on", v); version++ }
}

@Composable private fun StatusCard(h: Hooks, open: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val protect = Protect.on(ctx)
    val callOn = h.callOn(); val smsOn = h.smsOn()
    val prot = protect && callOn && smsOn
    val borderTint = if (prot) Color(0xFF78D7B0) else Color(0xFFB7D2F5)
    Column(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(23.dp))
        .background(Brush.linearGradient(listOf(Color(0xFFF9FCFF), Color(0xFFDDEBFF))), RoundedCornerShape(23.dp))
        .border(1.dp, borderTint, RoundedCornerShape(23.dp)).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(58.dp).background(Brush.radialGradient(listOf(Color(0xFF8BC8FF), Color(0xFF2E78E4))), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).background(if (prot) Color(0xFF16A36B) else Color(0xFFE2A126), CircleShape))
                    Text(if (prot) "ĐANG AN TOÀN" else if (!protect) "CHƯA BẬT BẢO VỆ" else "CẦN HOÀN TẤT CÀI ĐẶT",
                        color = if (prot) Color(0xFF138257) else Color(0xFF9B6712), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                }
                Text(when { prot -> "Bạn đang được bảo vệ"; !protect -> "Bảo vệ đang tắt"; else -> "Bảo vệ chưa đầy đủ" },
                    color = Color(0xFF17345F), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(when {
                    prot -> "ScamShield đang lọc cuộc gọi và tin nhắn lừa đảo cho bạn."
                    !protect -> "Bật bảo vệ để ScamShield hỗ trợ lọc cuộc gọi và tin nhắn."
                    else -> "Cấp đủ quyền để các lớp bảo vệ hoạt động hiệu quả hơn."
                }, color = Color(0xFF526B91), fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF5B78A3), modifier = Modifier.size(21.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip2("Chặn cuộc gọi", protect && callOn, Modifier.weight(1f))
            StatusChip2("Cảnh báo SMS", protect && smsOn, Modifier.weight(1f))
        }
        if (!protect) GoldButton("Bật bảo vệ") {
            Protect.set(ctx, true)
            if (!callOn) h.enableCalls() else if (!smsOn) h.enableSms()
        }
        AnimatedVisibility(visible = protect && (!callOn || !smsOn),
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!callOn) GoldButton("Bật chặn cuộc gọi") { h.enableCalls() }
                if (!smsOn) GoldButton("Bật cảnh báo tin nhắn") { h.enableSms() }
            }
        }
        if (prot) {
            Box(Modifier.fillMaxWidth().background(Color(0xFFE5F5EF), RoundedCornerShape(13.dp))
                .clickable { open("blocked") }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Xem nhật ký cảnh báo  →", color = Color(0xFF167A59), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            Text("Tắt bảo vệ", color = Color(0xFF6981A2), fontSize = 11.5.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { Protect.set(ctx, false) }.padding(vertical = 4.dp))
        }
    }
}

@Composable private fun StatusChip2(label: String, on: Boolean, modifier: Modifier = Modifier) =
    Row(modifier.background(if (on) Color(0xFFE2F6EC) else Color(0xFFFFF2D9), RoundedCornerShape(13.dp))
        .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
            tint = if (on) Color(0xFF159768) else Color(0xFFCB8B19), modifier = Modifier.size(14.dp))
        Text(label, color = Color(0xFF385578), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }

@Composable private fun SearchResults(c: Context, q: String) {
    val n = ScamDetector.norm(q.trim())
    val qd = q.filter { it.isDigit() }
    val nums = Store.blocklist(c).filter { it.contains(q.trim()) || (qd.length >= 3 && it.filter { ch -> ch.isDigit() }.contains(qd)) }
    val hist = Store.history(c).filter { ScamDetector.norm(it[2] + " " + it[3]).contains(n) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (q.trim().length >= 12) { SectionHeader("Phân tích nhanh nội dung đang nhập"); ResultCard(ScamDetector.analyze(q)) }
        if (nums.isNotEmpty()) {
            SectionHeader("Số đã chặn (${nums.size})")
            CardX { nums.forEach { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Block, null, tint = C.Bad, modifier = Modifier.size(16.dp))
                Text(it, color = Color.White, fontSize = 14.sp) } } }
        }
        if (hist.isNotEmpty()) {
            SectionHeader("Trong nhật ký (${hist.size})")
            hist.take(20).forEach { HistoryRow(it) }
        }
        if (nums.isEmpty() && hist.isEmpty() && q.trim().length < 12) Text("Không tìm thấy kết quả khớp.", color = C.Sub)
    }
}
