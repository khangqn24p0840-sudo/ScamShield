package vn.scamshield

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Khung trang con: nút quay lại tròn + tiêu đề + nội dung cuộn. */
@Composable fun Page(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.06f), CircleShape)
                .border(1.dp, C.Stroke, CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable fun SubScreen(c: Context, h: Hooks, sub: String, back: () -> Unit, open: (String) -> Unit) {
    when (sub) {
        "all" -> HistoryScreen(c, 0, back)
        "blocked" -> HistoryScreen(c, 2, back)
        "video" -> Page("Video Call Deepfake", back) { FacePanel(h.startFace, h.stopFace) }
        "blocklist" -> BlocklistScreen(c, back)
        "msg" -> MessageScreen(c, back, open)
        "social" -> SocialScreen(c, h, back)
        "malware", "link" -> MalwareScreen(c, back)
        "ai" -> Page("Yêu cầu AI", back) { AiPanel(c, h.aiMsg(), h.pickImage) { h.setAiMsg(it) } }
        "live" -> LiveScreen(h, back)
        "stats" -> StatsScreen(c, back)
        else -> OtherScreen(c, back)
    }
}

/** Kết quả phân tích: màu theo mức độ, có thanh điểm chạy animation. */
@Composable fun ResultCard(r: Result) {
    val col = listOf(C.Mint, C.Warn, C.Bad)[r.level]
    val ic = listOf(Icons.Rounded.CheckCircle, Icons.Rounded.Warning, Icons.Rounded.Error)[r.level]
    val title = listOf("Chưa thấy dấu hiệu lừa đảo", "Đáng ngờ", "Rất có thể là LỪA ĐẢO")[r.level]
    Column(Modifier.fillMaxWidth().background(col.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
        .border(1.dp, col.copy(alpha = 0.40f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(col.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(ic, null, tint = col, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Điểm rủi ro ${r.score}/100", color = C.Sub, fontSize = 12.sp)
            }
        }
        ScoreBar(r.score / 100f, col)
        r.reasons.forEach { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(4.dp).background(col, CircleShape))
            Text(it, fontSize = 13.sp, color = Color.White) } }
        if (r.level == 0) Text("Chưa thấy dấu hiệu không có nghĩa là chắc chắn an toàn. Đừng đọc OTP hay chuyển tiền cho người lạ.",
            fontSize = 12.sp, color = C.Dim, lineHeight = 16.sp)
    }
}

// ───────────────────────── Nhật ký ─────────────────────────

@Composable fun HistoryRow(e: List<String>) {
    val bad = e[1] == "2"
    val col = if (bad) C.Bad else C.Warn
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(C.Card, RoundedCornerShape(18.dp))
        .border(1.dp, C.Stroke, RoundedCornerShape(18.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(col.copy(alpha = 0.85f), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(e[2], fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
            Text(e[3], fontSize = 13.sp, maxLines = 3, color = C.Sub, lineHeight = 17.sp)
            Text(SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date(e[0].toLong())), fontSize = 11.sp, color = C.Dim)
        }
        Icon(if (bad) Icons.Rounded.Block else Icons.Rounded.Warning, null, tint = col.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
    }
}

@Composable fun HistoryScreen(c: Context, initial: Int, back: (() -> Unit)?) {
    var f by remember(initial) { mutableIntStateOf(initial) }
    Page("Nhật ký cảnh báo", back) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tất cả", "Cảnh báo", "Đã chặn").forEachIndexed { i, t -> SelChip(t, f == i) { f = i } }
        }
        val all = Store.history(c)
        val list = when (f) { 1 -> all.filter { it[1] == "1" }; 2 -> all.filter { it[1] == "2" }; else -> all }
        if (list.isEmpty()) Text("Chưa có mục nào.", color = C.Dim, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { list.forEach { HistoryRow(it) } }
    }
}

// ───────────────────────── Tin nhắn ─────────────────────────

@Composable fun MessageScreen(c: Context, back: (() -> Unit)?, open: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<Result?>(null) }
    Page("Kiểm tra tin nhắn", back) {
        Text("Dán nội dung tin nhắn, lời thoại hoặc link nghi ngờ. Phân tích nhanh chạy ngay trong máy, không gửi đi đâu.",
            fontSize = 13.sp, color = C.Sub, lineHeight = 18.sp)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("Dán nội dung nghi ngờ vào đây…") }, minLines = 4)
        GoldButton("Phân tích nhanh (trong máy)", enabled = text.isNotBlank(), leading = Icons.Rounded.Bolt) { res = ScamDetector.analyze(text) }
        res?.let { ResultCard(it) }
        GhostButton("Phân tích sâu bằng AI (cần đăng nhập)", leading = Icons.Rounded.Psychology) { open("ai") }
    }
}

// ───────────────────────── Cài đặt ─────────────────────────

@Composable private fun SettingRow(icon: ImageVector, tint: Color, title: String, sub: String, on: Boolean) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = C.Dim, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        StatusPill(on)
    }

@Composable fun SettingsTab(c: Context, h: Hooks) {
    h.tick()
    var rev by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val r0 = rev
    Page("Cài đặt bảo vệ", null) {
        val callOn = h.callOn(); val smsOn = h.smsOn()
        val ov = Settings.canDrawOverlays(c); val sc = SocialListener.enabled(c)
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Security, C.Mint)
                Text("Bảo vệ cơ bản", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            SettingRow(Icons.Rounded.Call, C.Mint, "Chặn cuộc gọi", "Tự động lọc số lừa đảo khi có cuộc gọi tới", callOn)
            SettingRow(Icons.Rounded.Sms, C.Warn, "Cảnh báo tin nhắn", "Quét SMS lừa đảo ngay trong máy", smsOn)
            AnimatedVisibilityV(callOn, smsOn, h)
        }
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Tune, C.Cyan)
                Text("Cảnh báo nâng cao", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            SettingRow(Icons.Rounded.CropFree, C.Violet,
                "Viền cảnh báo màn hình", "Rung + viền vàng/đỏ phủ toàn màn hình khi phát hiện", ov)
            SettingRow(Icons.Rounded.Forum, C.Blue, "Quét Zalo, Messenger…", "Đọc thông báo mạng xã hội để phát hiện lừa đảo", sc)
            AnimatedVisibilityV2(ov, sc, h)
        }
        CardX {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chặn mọi số nước ngoài", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tự động chặn đầu số khác +84", color = C.Dim, fontSize = 11.5.sp)
                }
                Switch(Store.blockForeign(c), { Store.setBlockForeign(c, it); rev++ })
            }
        }
        CardX {
            Text("Thử cảnh báo", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Thử vàng", Modifier.weight(1f), leading = Icons.Rounded.Warning) { Alerter.fire(c, 1) }
                GhostButton("Thử đỏ", Modifier.weight(1f), leading = Icons.Rounded.Error) { Alerter.fire(c, 2) }
            }
        }
        Text("ScamShield VN • phiên bản " + c.packageManager.getPackageInfo(c.packageName, 0).versionName,
            fontSize = 12.sp, color = C.Faint, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun AnimatedVisibilityV(callOn: Boolean, smsOn: Boolean, h: Hooks) {
    androidx.compose.animation.AnimatedVisibility(visible = !callOn || !smsOn,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
            androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
            androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!callOn) GoldButton("Bật chặn cuộc gọi") { h.enableCalls() }
            if (!smsOn) GoldButton("Bật cảnh báo tin nhắn") { h.enableSms() }
        }
    }
}

@Composable private fun AnimatedVisibilityV2(ov: Boolean, sc: Boolean, h: Hooks) {
    androidx.compose.animation.AnimatedVisibility(visible = !ov || !sc,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
            androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
            androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ov) GoldButton("Bật viền cảnh báo màn hình") { h.openOverlay() }
            if (!sc) GoldButton("Bật quét thông báo mạng xã hội") { h.openSocial() }
        }
    }
}

// ───────────────────────── Số lừa đảo ─────────────────────────

@Composable fun BlocklistScreen(c: Context, back: () -> Unit) {
    var rev by remember { mutableIntStateOf(0) }
    var num by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val r0 = rev
    Page("SĐT lừa đảo", back) {
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.PhoneMissed, C.Bad)
                Text("Danh sách chặn (${Store.blocklist(c).size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillField(num, { num = it }, "Số điện thoại", Modifier.weight(1f), leading = Icons.Rounded.Phone,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Button({ Store.addBlock(c, num); num = ""; rev++ }, Modifier.height(52.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Gold, contentColor = C.Ink)) {
                    Text("Chặn", fontWeight = FontWeight.Bold) }
            }
            val bl = Store.blocklist(c).toList()
            if (bl.isEmpty()) Text("Chưa có số nào trong danh sách.", color = C.Dim, fontSize = 13.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bl.forEach { n ->
                    Row(Modifier.fillMaxWidth().background(C.Field, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Phone, null, tint = C.Dim, modifier = Modifier.size(15.dp))
                            Text(n, color = Color.White, fontSize = 14.sp)
                        }
                        TextButton({ Store.removeBlock(c, n); rev++ }) { Text("Bỏ chặn", color = C.Rose, fontSize = 12.sp) }
                    }
                }
            }
        }
        CardX {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chặn mọi số nước ngoài", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tự động chặn đầu số khác +84", color = C.Dim, fontSize = 11.5.sp)
                }
                Switch(Store.blockForeign(c), { Store.setBlockForeign(c, it); rev++ })
            }
        }
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Group, C.Cyan)
                Text("Danh sách cộng đồng", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            Text("Tải các số lừa đảo do máy chủ của bạn tổng hợp (cần đăng nhập).", fontSize = 12.sp, color = C.Sub)
            GhostButton("Tải danh sách số lừa đảo cộng đồng", leading = Icons.Rounded.Download) { syncCommunity(c) { m -> msg = m; rev++ } }
            if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = C.Warn)
        }
    }
}

// ───────────────────────── Zalo / Messenger ─────────────────────────

@Composable fun SocialScreen(c: Context, h: Hooks, back: () -> Unit) {
    h.tick()
    Page("Chat Zalo, Messenger…", back) {
        val sc = SocialListener.enabled(c); val ov = Settings.canDrawOverlays(c)
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Forum, C.Cyan)
                Text("Quét thông báo mạng xã hội", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            Text("App đọc thông báo tin nhắn đến từ Zalo, Messenger… ngay trong máy, phát hiện nội dung lừa đảo và cảnh báo bạn. Không lưu và không gửi nội dung đi.",
                fontSize = 13.sp, color = C.Sub, lineHeight = 18.sp)
            SettingRow(Icons.Rounded.Notifications, C.Cyan, "Quét thông báo", "Đọc thông báo tin nhắn đến", sc)
            SettingRow(Icons.Rounded.CropFree, C.Violet, "Viền cảnh báo", "Rung + viền màu khi phát hiện", ov)
            if (!sc) GoldButton("Bật quét thông báo mạng xã hội") { h.openSocial() }
            if (!ov) GoldButton("Bật viền cảnh báo màn hình") { h.openOverlay() }
        }
        CardX {
            Text("Thử cảnh báo", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Thử vàng", Modifier.weight(1f), leading = Icons.Rounded.Warning) { Alerter.fire(c, 1) }
                GhostButton("Thử đỏ", Modifier.weight(1f), leading = Icons.Rounded.Error) { Alerter.fire(c, 2) }
            }
        }
    }
}

// ───────────────────────── Mã độc & link ─────────────────────────

class AppRisk(val name: String, val pkg: String, val score: Int, val why: List<String>)

/** Quét ứng dụng đã cài (không phải app hệ thống) và chấm điểm theo các quyền/hành vi hay bị lạm dụng. Chỉ là chỉ báo. */
@Suppress("DEPRECATION")
fun scanApps(c: Context): List<AppRisk> {
    val pm = c.packageManager
    val acc = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").lowercase()
    val out = ArrayList<AppRisk>()
    for (p in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
        val ai = p.applicationInfo ?: continue
        if ((ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue
        if (p.packageName == c.packageName) continue
        val perms = p.requestedPermissions?.toSet() ?: emptySet()
        var s = 0; val why = ArrayList<String>()
        if (acc.contains(p.packageName.lowercase() + "/")) { s += 40; why += "Đang bật dịch vụ Trợ năng (có thể đọc và điều khiển màn hình)" }
        if ("android.permission.SYSTEM_ALERT_WINDOW" in perms) { s += 15; why += "Được vẽ đè lên ứng dụng khác" }
        if ("android.permission.READ_SMS" in perms || "android.permission.RECEIVE_SMS" in perms) { s += 20; why += "Đọc/nhận tin nhắn SMS (có thể lấy OTP)" }
        if ("android.permission.REQUEST_INSTALL_PACKAGES" in perms) { s += 10; why += "Có thể cài thêm ứng dụng khác" }
        val installer = try { pm.getInstallerPackageName(p.packageName) } catch (_: Exception) { null }
        if (installer == null) { s += 10; why += "Không cài từ cửa hàng ứng dụng" }
        if (s >= 30) out += AppRisk(pm.getApplicationLabel(ai).toString(), p.packageName, s.coerceAtMost(100), why)
    }
    return out.sortedByDescending { it.score }.take(15)
}

/** Chấm điểm một đường link dựa trên dấu hiệu bề ngoài (không truy cập link). */
fun linkCheck(u: String): Result {
    val s = u.trim().lowercase()
    val host = s.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore("?").substringBefore(":")
    var score = 0; val why = ArrayList<String>()
    if (s.startsWith("http://")) { score += 25; why += "Không dùng HTTPS" }
    if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) { score += 40; why += "Dùng địa chỉ IP thay vì tên miền" }
    if (host.startsWith("xn--") || host.contains(".xn--")) { score += 35; why += "Tên miền chứa ký tự dễ giả mạo (punycode)" }
    if (Regex("^(bit\\.ly|tinyurl\\.com|cutt\\.ly|rebrand\\.ly|t\\.co|is\\.gd|s\\.id|goo\\.gl)$").matches(host)) { score += 25; why += "Link rút gọn, không biết đích đến" }
    if (Regex("\\.(xyz|top|vip|club|icu|cc|click|shop|site|online|live)$").containsMatchIn(host)) { score += 20; why += "Đuôi tên miền hay bị lạm dụng" }
    if (host.count { it == '.' } >= 3) { score += 15; why += "Nhiều tầng tên miền phụ" }
    if (host.contains("-") && Regex("vietcombank|vcb|techcombank|mbbank|bidv|vietinbank|agribank|tpbank|momo|zalopay|vneid|bocongan|dichvucong").containsMatchIn(host)) {
        score += 35; why += "Tên miền nhái tên ngân hàng/cơ quan nhà nước" }
    if (s.contains(".apk")) { score += 40; why += "Link tải file cài đặt .apk" }
    val base = ScamDetector.analyze(u)
    score += base.score / 2; why += base.reasons
    return Result(score.coerceAtMost(100), why.distinct())
}

@Composable fun MalwareScreen(c: Context, back: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var lr by remember { mutableStateOf<Result?>(null) }
    var apps by remember { mutableStateOf<List<AppRisk>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    Page("Mã độc & link", back) {
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Link, C.Blue)
                Text("Kiểm tra đường link", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            Text("Dán link nhận được qua tin nhắn/Zalo. App chỉ xem dấu hiệu bề ngoài, không mở link.", fontSize = 12.sp, color = C.Sub)
            OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), placeholder = { Text("https://…") }, singleLine = true)
            GoldButton("Kiểm tra link", enabled = link.isNotBlank(), leading = Icons.Rounded.Link) { lr = linkCheck(link) }
        }
        lr?.let { ResultCard(it) }
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.BugReport, C.Rose)
                Text("Quét ứng dụng đáng ngờ", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            Text("Liệt kê các ứng dụng bạn đã cài có quyền hay bị mã độc lạm dụng (Trợ năng, đọc SMS, vẽ đè màn hình…). Đây chỉ là chỉ báo: app ngân hàng, ví điện tử, trình quản lý cũng có thể xin các quyền này.",
                fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
            GoldButton(if (scanning) "Đang quét…" else "Quét ứng dụng", enabled = !scanning, leading = Icons.Rounded.Search) {
                scanning = true
                Thread { val r = scanApps(c); Handler(Looper.getMainLooper()).post { apps = r; scanning = false } }.start()
            }
        }
        apps?.let { list ->
            if (list.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, tint = C.Mint, modifier = Modifier.size(18.dp))
                Text("Không thấy ứng dụng nào có tổ hợp quyền đáng ngờ.", color = C.Mint, fontSize = 13.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { a ->
                    val col = if (a.score >= 60) C.Bad else C.Warn
                    CardX {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text(a.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${a.score}/100", fontWeight = FontWeight.Bold, color = col, fontSize = 14.sp)
                        }
                        ScoreBar(a.score / 100f, col)
                        Text(a.pkg, fontSize = 11.sp, color = C.Dim)
                        a.why.forEach { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(4.dp).background(col, CircleShape))
                            Text(it, fontSize = 13.sp, color = C.Sub) } }
                        GhostButton("Mở cài đặt ứng dụng (gỡ nếu không dùng)") {
                            c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Nghe cuộc gọi ─────────────────────────

@Composable fun LiveScreen(h: Hooks, back: () -> Unit) {
    h.tick()
    Page("Nghe cuộc gọi", back) {
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Mic, if (ListenService.running) C.Bad else C.Mint)
                Column {
                    Text("Nghe qua loa ngoài", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Text("Phân tích lời thoại khi bạn bắt máy bằng loa ngoài", color = C.Dim, fontSize = 11.5.sp)
                }
            }
            Text("Khi số lạ gọi tới: bắt máy, bật loa ngoài rồi bấm bắt đầu. App phân tích lời nói và cảnh báo nếu có dấu hiệu lừa đảo.",
                fontSize = 13.sp, color = C.Sub, lineHeight = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).background(if (ListenService.running) C.Bad else C.Faint, CircleShape))
                Text(if (ListenService.running) "Đang nghe — phân tích lời thoại trực tiếp" else "Chưa bật",
                    fontSize = 13.sp, color = if (ListenService.running) C.Bad else C.Sub, fontWeight = FontWeight.SemiBold)
            }
            if (ListenService.running) DangerButton("Dừng nghe", leading = Icons.Rounded.Stop) { h.toggleListen() }
            else GoldButton("Bắt đầu nghe", leading = Icons.Rounded.Mic) { h.toggleListen() }
        }
        TipBanner("Nếu người gọi yêu cầu cài ứng dụng, nhập OTP hoặc chuyển tiền — hãy dừng lại và cúp máy ngay.")
    }
}

// ───────────────────────── Thống kê ─────────────────────────

@Composable fun StatsScreen(c: Context, back: () -> Unit) {
    Page("Thống kê cảnh báo", back) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(Store.count(c, "blocked"), "Đã chặn", Icons.Rounded.Block, C.Bad, Modifier.weight(1f))
            StatCard(Store.count(c, "warned"), "Đã cảnh báo", Icons.Rounded.Campaign, C.Warn, Modifier.weight(1f))
            StatCard(Store.blocklist(c).size, "Số bị chặn", Icons.Rounded.PhoneMissed, C.Mint, Modifier.weight(1f))
        }
        val kinds = Store.history(c).groupingBy { Sync.kindOf(it[2]) }.eachCount()
        val names = mapOf("call" to "Cuộc gọi", "voice" to "Nghe qua loa ngoài", "sms" to "Tin nhắn SMS", "social" to "Zalo / Messenger", "face" to "Video call giả mạo", "habit" to "Giao dịch / nhiều số lạ")
        CardX {
            Text("Theo loại (trong 100 mục gần nhất)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
            if (kinds.isEmpty()) Text("Chưa có dữ liệu.", color = C.Dim, fontSize = 13.sp)
            val mx = (kinds.values.maxOrNull() ?: 1).coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                kinds.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(names[k] ?: k, color = Color.White, fontSize = 13.sp)
                        Text("$v", color = C.Sub, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    ScoreBar(v.toFloat() / mx, C.Mint)
                }
            }
        }
    }
}

// ───────────────────────── Khác ─────────────────────────

@Composable fun OtherScreen(c: Context, back: () -> Unit) {
    Page("Khác", back) {
        SurveyPanel(c)
        CardX {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Lightbulb, C.Gold)
                Text("Mẹo an toàn", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
            listOf("Ngân hàng, công an không bao giờ yêu cầu OTP hay chuyển tiền qua điện thoại.",
                "Không bấm link lạ, không cài app theo hướng dẫn của người lạ.",
                "Nghi ngờ thì cúp máy và gọi lại bằng số đã lưu hoặc số in trên thẻ.").forEach {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(5.dp).background(C.Gold, CircleShape))
                    Text(it, fontSize = 13.sp, color = C.Sub, lineHeight = 17.sp)
                }
            }
        }
        Text("ScamShield VN • phiên bản " + c.packageManager.getPackageInfo(c.packageName, 0).versionName,
            fontSize = 12.sp, color = C.Faint, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ───────────────────────── Tài khoản ─────────────────────────

@Composable fun AccountTab(c: Context, onLogin: () -> Unit, open: (String) -> Unit) {
    var logged by remember { mutableStateOf(Store.str(c, "token").isNotEmpty()) }
    var consent by remember { mutableStateOf(Store.flag(c, "consent")) }
    var msg by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(Store.str(c, "server")) }
    var advanced by remember { mutableStateOf(false) }
    var askDel by remember { mutableStateOf(false) }
    var nameEdit by remember { mutableStateOf(Store.str(c, "name")) }
    val hasDefault = Config.SERVER_URL.isNotBlank()
    Page("Tài khoản", null) {
        CardX(bg = C.CardHi) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(c) {}
                Column(Modifier.weight(1f)) {
                    Text(displayName(c), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(if (logged) Store.str(c, "email") else "Chưa đăng nhập", color = C.Sub, fontSize = 12.5.sp)
                }
                if (logged) IconBadge(Icons.Rounded.Verified, C.Mint, 34.dp, 17.dp)
            }
        }
        CardX {
            Row(Modifier.fillMaxWidth().clickable { open("all") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(Icons.Rounded.History, C.Gold, 42.dp, 21.dp)
                Column(Modifier.weight(1f)) {
                    Text("Nhật ký", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Cuộc gọi, tin nhắn và cảnh báo đã xử lý", color = C.Sub, fontSize = 12.5.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = C.Dim)
            }
        }
        if (!logged) CardX {
            Text("Đăng nhập để dùng phân tích bằng AI và danh sách số lừa đảo cộng đồng. Chặn cuộc gọi và cảnh báo tin nhắn vẫn hoạt động đầy đủ khi không có tài khoản.",
                fontSize = 13.sp, color = C.Sub, lineHeight = 18.sp)
            GoldButton("Đăng nhập / Đăng ký", leading = Icons.Rounded.Login) { onLogin() }
        } else CardX {
            Text("Tên hiển thị trong Cộng đồng", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
            PillField(nameEdit, { nameEdit = it.take(24) }, "Tên hiển thị (2–24 ký tự)", leading = Icons.Rounded.Person)
            GhostButton("Lưu tên hiển thị", enabled = nameEdit.trim().length >= 2, leading = Icons.Rounded.Save) {
                Api.call(c, "POST", "/api/profile", JSONObject().put("name", nameEdit.trim())) { code, j ->
                    if (code in 200..299) { Store.putStr(c, "name", nameEdit.trim()); msg = "Đã lưu tên hiển thị" } else msg = err(j) } }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Gửi thống kê ẩn danh (chỉ loại và mức cảnh báo; KHÔNG gửi nội dung, số điện thoại, số tiền)",
                    Modifier.weight(1f), fontSize = 12.5.sp, color = C.Sub, lineHeight = 16.sp)
                Switch(consent, { consent = it; Store.putFlag(c, "consent", it); Api.call(c, "POST", "/api/consent", JSONObject().put("analytics", it)) { _, _ -> } })
            }
            GhostButton("Tải danh sách số lừa đảo cộng đồng", leading = Icons.Rounded.Download) { syncCommunity(c) { m -> msg = m } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Đăng xuất", Modifier.weight(1f), leading = Icons.Rounded.Logout) { Store.putStr(c, "token", ""); logged = false; msg = "Đã đăng xuất" }
                GhostButton(if (askDel) "Bấm lại để xóa" else "Xóa tài khoản", Modifier.weight(1f), leading = Icons.Rounded.Delete) {
                    if (!askDel) { askDel = true; msg = "Xóa tài khoản sẽ xóa toàn bộ dữ liệu của bạn trên máy chủ. Bấm lại để xác nhận." }
                    else Api.call(c, "DELETE", "/api/me", null) { code, j ->
                        askDel = false
                        if (code == 200) { Store.putStr(c, "token", ""); logged = false; msg = "Đã xóa tài khoản và dữ liệu" } else msg = err(j) }
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = C.Warn)
        if (!hasDefault || advanced)
            PillField(server, { server = it; Store.putStr(c, "server", it.trim()) },
                if (hasDefault) "Máy chủ riêng (để trống = dùng mặc định)" else "Địa chỉ máy chủ (https://...)", leading = Icons.Rounded.Language)
        if (hasDefault) TextButton({ advanced = !advanced }) { Text(if (advanced) "Ẩn nâng cao" else "Nâng cao", fontSize = 12.sp, color = C.Dim) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = C.Dim, modifier = Modifier.size(15.dp))
            Text("Nội dung tin nhắn, số tiền và danh bạ chỉ được xử lý trong máy.", fontSize = 12.sp, color = C.Dim)
        }
    }
}
