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
            Text("ScamShield VN", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text("Khiên chắn chống lừa đảo trong túi bạn", color = C.Sub, fontSize = 13.sp)
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
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
}

/** Thanh điều hướng nổi dạng pill: 2 mục | nút Kiểm tra ở giữa | 2 mục. Nhật ký đã chuyển vào Tài khoản. */
@Composable fun BottomBar(tab: Int, inSub: Boolean, onTab: (Int) -> Unit) {
    // (số tab, icon, nhãn) — null là chỗ chừa cho nút Kiểm tra
    val items = listOf(
        Triple(0, Icons.Rounded.Home, "Trang chủ"), Triple(1, Icons.Rounded.Tune, "Cài đặt"), null,
        Triple(4, Icons.Rounded.Forum, "Cộng đồng"), Triple(5, Icons.Rounded.Person, "Tài khoản"))
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp).shadow(18.dp, RoundedCornerShape(33.dp))
            .background(Brush.verticalGradient(listOf(C.CardHi, C.Bar)), RoundedCornerShape(33.dp))
            .border(1.dp, C.Stroke, RoundedCornerShape(33.dp)), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { it ->
                if (it == null) Spacer(Modifier.width(84.dp))
                else {
                    val sel = !inSub && tab == it.first
                    val tint by animateColorAsState(if (sel) C.Gold else C.Dim, tween(220))
                    Column(Modifier.weight(1f).clickable { onTab(it.first) }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(it.second, null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(it.third, fontSize = 10.sp, color = tint, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        val selF = !inSub && tab == 2
        Column(Modifier.align(Alignment.TopCenter).offset(y = (-8).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp)
                // quầng sáng vàng vẽ tay thay cho shadow màu (shadow màu hay bị vẽ thành khối vuông trên một số máy)
                .drawBehind { drawCircle(Brush.radialGradient(listOf(C.Gold.copy(alpha = 0.45f), Color.Transparent), center, size.minDimension * 0.85f), size.minDimension * 0.85f) },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(54.dp).background(Brush.linearGradient(C.goldGrad), CircleShape)
                    .border(3.dp, if (selF) Color.White.copy(alpha = 0.9f) else C.Bg, CircleShape)
                    .clickable { onTab(2) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Shield, null, tint = C.Ink, modifier = Modifier.size(26.dp))
                }
            }
            Text("Kiểm tra", color = C.Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 1.dp))
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
    val callOn = h.callOn(); val smsOn = h.smsOn()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(C.CardHi.copy(alpha = 0.85f), Color.Transparent)),
                RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Xin chào, ${displayName(c)}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(3.dp))
                    Text(if (callOn && smsOn) "Tất cả tầng bảo vệ đang bật" else "Bảo vệ chưa đầy đủ — kiểm tra ngay",
                        color = if (callOn && smsOn) C.Mint else C.Warn, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Avatar(c, toAccount)
            }
            PillField(q, { q = it }, "Tìm cuộc gọi, tin nhắn, số điện thoại…", leading = Icons.Rounded.Search)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (q.isNotBlank()) SearchResults(c, q) else HomeBody(c, h, open, onPost, onFeed)
        }
    }
}

@Composable private fun HomeBody(c: Context, h: Hooks, open: (String) -> Unit, onPost: (Long) -> Unit, onFeed: () -> Unit) {
    StatusCard(h, open)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(Store.count(c, "blocked"), "Đã chặn", Icons.Rounded.Block, C.Bad, Modifier.weight(1f))
        StatCard(Store.count(c, "warned"), "Đã cảnh báo", Icons.Rounded.Campaign, C.Warn, Modifier.weight(1f))
        StatCard(Store.blocklist(c).size, "Số bị chặn", Icons.Rounded.PhoneMissed, C.Mint, Modifier.weight(1f))
    }
    SectionHeader("Danh mục")
    val cats = listOf(
        Cat(Icons.Rounded.Shield, "Tất cả", C.Mint, "all"),
        Cat(Icons.Rounded.Videocam, "Video Deepfake", C.Violet, "video"),
        Cat(Icons.Rounded.PhoneMissed, "SĐT Lừa Đảo", C.Bad, "blocklist"),
        Cat(Icons.Rounded.Sms, "Tin Nhắn", C.Warn, "msg"),
        Cat(Icons.Rounded.Forum, "Zalo, Messenger", C.Cyan, "social"),
        Cat(Icons.Rounded.BugReport, "Mã Độc & Link", C.Rose, "malware"),
        Cat(Icons.Rounded.SmartToy, "Yêu Cầu AI", C.Blue, "ai"),
        Cat(Icons.Rounded.MoreHoriz, "Khác", C.Dim, "other"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cats.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { ct -> CatTile(Modifier.weight(1f), ct.icon, ct.label, ct.tint) { open(ct.key) } }
            }
        }
    }
    SectionHeader("Đề xuất")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RecCard(Icons.Rounded.GraphicEq, "Nghe cuộc gọi qua loa ngoài",
            if (ListenService.running) "Đang nghe" else "Đã chặn ${Store.count(c, "blocked")}",
            listOf(Color(0xFF7F1D1D), Color(0xFF450A0A))) { open("live") }
        RecCard(Icons.Rounded.Videocam, "Kiểm tra Deepfake", if (FaceBus.running) "Đang chạy" else "Video call",
            listOf(Color(0xFF4338CA), Color(0xFF1E1B4B))) { open("video") }
        RecCard(Icons.Rounded.Analytics, "Thống kê cảnh báo", "${Store.count(c, "warned") + Store.count(c, "blocked")} lần",
            listOf(Color(0xFF047857), Color(0xFF022C22))) { open("stats") }
        RecCard(Icons.Rounded.Link, "Phân tích link", "Mã độc & link", listOf(Color(0xFF1D4ED8), Color(0xFF172554))) { open("link") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Cảnh báo từ cộng đồng", "Xem tất cả") { feedStartSection = "post"; onFeed() }
        FeedPreview(c, "post", { feedStartSection = "post"; onFeed() }, onPost)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Tin báo chí", "Xem tất cả") { feedStartSection = "news"; onFeed() }
        FeedPreview(c, "news", { feedStartSection = "news"; onFeed() }, onPost)
    }
    TipBanner("Ngân hàng, công an không bao giờ yêu cầu OTP hay chuyển tiền qua điện thoại.")
}

/** Thẻ trạng thái bảo vệ: gradient đổi màu theo mức độ + khiên nhấp nháy. */
@Composable private fun StatusCard(h: Hooks, open: (String) -> Unit) {
    val callOn = h.callOn(); val smsOn = h.smsOn(); val prot = callOn && smsOn
    val grad = when {
        prot -> listOf(Color(0xFF0E8A66), Color(0xFF0A4A3A))
        callOn || smsOn -> listOf(Color(0xFFB45309), Color(0xFF7C2D12))
        else -> listOf(Color(0xFF9F1239), Color(0xFF4C0519))
    }
    Column(Modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(24.dp))
        .background(Brush.verticalGradient(grad), RoundedCornerShape(24.dp))
        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShieldBadge(if (prot) C.Mint else C.Warn, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (prot) "Bạn đang được bảo vệ" else "Bảo vệ chưa đầy đủ",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(if (prot) "ScamShield đang lọc cuộc gọi và tin nhắn lừa đảo cho bạn."
                    else "Bật đủ 2 tầng để khiên hoạt động hiệu quả nhất.",
                    color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip2("Chặn cuộc gọi", callOn, Modifier.weight(1f))
            StatusChip2("Cảnh báo SMS", smsOn, Modifier.weight(1f))
        }
        AnimatedVisibility(visible = !callOn || !smsOn,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!callOn) GoldButton("Bật chặn cuộc gọi") { h.enableCalls() }
                if (!smsOn) GoldButton("Bật cảnh báo tin nhắn") { h.enableSms() }
            }
        }
        if (prot) Box(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .clickable { open("blocked") }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("Xem nhật ký cảnh báo  →", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun StatusChip2(label: String, on: Boolean, modifier: Modifier = Modifier) =
    Row(modifier.background(Color.White.copy(alpha = 0.13f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
            tint = if (on) C.Mint else C.Warn, modifier = Modifier.size(15.dp))
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
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
