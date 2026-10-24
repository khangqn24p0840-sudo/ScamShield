package vn.scamshield

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── Dữ liệu & tiện ích ─────────────────────────

/** Một cảnh báo trên bảng tin cộng đồng. */
/** Mục mà tab Cộng đồng mở đầu tiên ("post" hoặc "news"); Trang chủ đặt giá trị này trước khi chuyển tab. */
var feedStartSection by mutableStateOf("post")

data class Post(
    val id: Long, val title: String, val body: String, val topic: String, val severity: String, val official: Boolean,
    val author: String, val ts: Long, val likes: Int, val comments: Int, val liked: Boolean, val mine: Boolean,
    val kind: String = "post", val link: String = ""   // kind = "news": tin từ báo (author là tên báo, link là bài gốc)
)

/** Một bình luận công khai dưới cảnh báo. */
data class Comment(val id: Long, val author: String, val body: String, val ts: Long, val mine: Boolean)

val TOPICS = listOf("ngan-hang" to "Ngân hàng", "mang-xa-hoi" to "Mạng xã hội", "dien-thoai" to "Điện thoại", "viec-lam" to "Việc làm", "khac" to "Khác")
private val SEVERITIES = listOf("low" to "Thấp", "medium" to "Trung bình", "high" to "Cao")

private val FeedCyan = C.Cyan
private val FeedCard = C.Card
private val FeedBorder = C.Stroke
private val FeedChipBg = Color.White.copy(alpha = 0.06f)

fun topicLabel(k: String) = TOPICS.firstOrNull { it.first == k }?.second ?: "Khác"
private fun sevLabel(k: String) = SEVERITIES.firstOrNull { it.first == k }?.second ?: "Trung bình"
private fun sevColor(k: String): Color = when (k) { "high" -> C.Bad; "low" -> C.Mint; else -> C.Warn }
private fun topicColor(k: String): Color = when (k) {
    "ngan-hang" -> Color(0xFFF87171); "mang-xa-hoi" -> Color(0xFFA78BFA); "dien-thoai" -> Color(0xFFFBBF24)
    "viec-lam" -> Color(0xFF34D399); else -> Color(0xFF94A3B8)
}

fun timeAgo(ts: Long): String {
    val s = System.currentTimeMillis() / 1000 - ts
    return when {
        s < 60 -> "Vừa xong"
        s < 3600 -> "${s / 60} phút trước"
        s < 86400 -> "${s / 3600} giờ trước"
        s < 7 * 86400 -> "${s / 86400} ngày trước"
        else -> SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date(ts * 1000))
    }
}

fun parsePost(o: JSONObject?): Post? = o?.let {
    Post(it.optLong("id"), it.optString("title"), it.optString("body"), it.optString("topic"), it.optString("severity"), it.optBoolean("official"),
        it.optString("author"), it.optLong("ts"), it.optInt("likes"), it.optInt("comments"), it.optBoolean("liked"), it.optBoolean("mine"), it.optString("kind", "post"), it.optString("link"))
}

fun parsePosts(j: JSONObject?): List<Post> {
    val a = j?.optJSONArray("items") ?: j?.optJSONArray("posts") ?: return emptyList()
    return (0 until a.length()).mapNotNull { parsePost(a.optJSONObject(it)) }
}

private fun parseComments(j: JSONObject?): List<Comment> {
    val a = j?.optJSONArray("comments") ?: return emptyList()
    return (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Comment(o.optLong("id"), o.optString("author"), o.optString("body"), o.optLong("ts"), o.optBoolean("mine"))
    }
}

/** Thông báo lỗi dễ hiểu cho người dùng. */
fun apiMsg(code: Int, j: JSONObject?): String = when {
    code == -1 -> "Không kết nối được máy chủ. Hãy kiểm tra mạng, server và ngrok."
    code == 404 && j?.optString("error") == "Không tìm thấy" ->
        "Máy chủ chưa được cập nhật bản mới (thiếu mục Cộng đồng). Hãy thay file server mới và chạy lại server."
    else -> err(j)
}

/** Mở bài gốc của báo trong trình duyệt (chỉ http/https). */
fun openUrl(c: Context, url: String) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

/** Chỉ cập nhật khi màn hình đang mở (tránh gọi máy chủ liên tục khi app chạy nền). */
@Composable private fun rememberIsResumed(): () -> Boolean {
    val owner = LocalLifecycleOwner.current
    return { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
}

// ───────────────────────── Thành phần giao diện ─────────────────────────

@Composable private fun Tag(text: String, col: Color) =
    Box(Modifier.background(col.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).border(BorderStroke(1.dp, col.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = col, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }

@Composable private fun FeedChip(label: String, on: Boolean, onClick: () -> Unit) =
    Box(Modifier.background(if (on) FeedCyan else FeedChipBg, RoundedCornerShape(20.dp))
        .border(1.dp, if (on) Color.Transparent else C.Stroke, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(label, fontSize = 13.5.sp, color = if (on) Color(0xFF042F2E) else C.Sub, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1) }

@Composable private fun AuthorDot(name: String) {
    val pal = listOf(Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFFFBBF24), Color(0xFF34D399), Color(0xFFF87171), Color(0xFF60A5FA))
    val col = pal[(name.hashCode() and 0x7fffffff) % pal.size]
    Box(Modifier.size(32.dp).background(col.copy(alpha = 0.2f), CircleShape).border(BorderStroke(1.dp, col), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = col, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/** Thẻ cảnh báo: nhãn chủ đề, thời gian, tiêu đề, tóm tắt, mức độ, lượt thích, bình luận. */
@Composable fun PostCard(p: Post, onClick: () -> Unit) {
    val news = p.kind == "news"
    val edge = if (news) Color(0x5538BDF8) else FeedBorder   // tin báo chí: viền xanh dương để khác bài người dùng
    Column(Modifier.fillMaxWidth().background(FeedCard, RoundedCornerShape(20.dp)).border(BorderStroke(1.dp, edge), RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (news) Tag("TIN BÁO", Color(0xFF38BDF8))
                Tag(topicLabel(p.topic).uppercase(), topicColor(p.topic))
                if (p.official) Tag("CHÍNH THỨC", FeedCyan)
            }
            Text(timeAgo(p.ts), color = C.Dim, fontSize = 12.sp)
        }
        Text(p.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (p.body.isNotBlank()) Text(if (news) p.body.replace("•", "").replace("\n", " ").trim() else p.body, color = C.Sub, fontSize = 14.sp, lineHeight = 20.sp,
            maxLines = if (news) 3 else 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(1.dp).background(FeedBorder))
        if (news) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("${p.author}", color = C.Dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Đọc tiếp", color = FeedCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ChevronRight, null, tint = FeedCyan, modifier = Modifier.size(16.dp))
            }
        } else Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(sevColor(p.severity), CircleShape))
                    Text("Mức độ: ${sevLabel(p.severity)}", color = C.Dim, fontSize = 12.sp, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.ThumbUp, null, tint = C.Dim, modifier = Modifier.size(13.dp))
                    Text("${p.likes}", color = C.Dim, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.ChatBubble, null, tint = C.Dim, modifier = Modifier.size(13.dp))
                    Text("${p.comments}", color = C.Dim, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chi tiết", color = FeedCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ChevronRight, null, tint = FeedCyan, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ───────────────────────── Đọc tin báo ngay trong app ─────────────────────────

/** Màn đọc tin báo: hiện phần trích trong app; chỉ khi bấm "Tìm hiểu thêm" mới mở trang báo gốc. */
@Composable fun NewsReader(c: Context, p: Post, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) { AppBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.06f), CircleShape).border(1.dp, C.Stroke, CircleShape)
                    .clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                Text("Tin báo chí", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.fillMaxWidth().background(FeedCard, RoundedCornerShape(20.dp)).border(BorderStroke(1.dp, FeedBorder), RoundedCornerShape(20.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag("TIN BÁO", Color(0xFF38BDF8))
                            Tag(topicLabel(p.topic).uppercase(), topicColor(p.topic))
                        }
                        Text(timeAgo(p.ts), color = C.Dim, fontSize = 12.sp)
                    }
                    Text(p.title, color = Color.White, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Nguồn: ${p.author}", color = FeedCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(FeedBorder))
                    val pts = p.body.split("\n").map { it.trim().removePrefix("•").trim() }.filter { it.isNotBlank() }
                    if (pts.size > 1) {
                        Text("Ý CHÍNH CỦA BÀI", color = C.Gold, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        pts.forEach { t ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.padding(top = 9.dp).size(7.dp).background(C.Gold, CircleShape))
                                Text(t, color = Color(0xFFDDE5F7), fontSize = 15.5.sp, lineHeight = 24.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    } else if (p.body.isNotBlank()) Text(p.body, color = Color(0xFFDDE5F7), fontSize = 15.5.sp, lineHeight = 24.sp)
                    else Text("Bài này chưa có phần trích. Bấm \"Tìm hiểu thêm\" để đọc trên trang báo.", color = C.Sub, fontSize = 14.sp)
                    Text("Đây là phần tóm tắt, không phải toàn bộ bài viết. Bấm \"Tìm hiểu thêm\" để đọc bài đầy đủ trên trang báo.", color = C.Dim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                GoldButton("Tìm hiểu thêm", Modifier.fillMaxWidth(), enabled = p.link.startsWith("http"), leading = Icons.Rounded.OpenInNew) { openUrl(c, p.link) }
            }
        }
        }
    }
}

// ───────────────────────── Xem trước trên Trang chủ ─────────────────────────

/** Cảnh báo nổi bật trên Trang chủ: 3 cảnh báo mới nhất, tự cập nhật khi có bài mới. Xem được không cần đăng nhập. */
@Composable fun FeedPreview(c: Context, kind: String, onSeeAll: () -> Unit, onPost: (Long) -> Unit) {
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var rev by remember { mutableStateOf("") }
    var offline by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Post?>(null) }
    val resumed = rememberIsResumed()
    LaunchedEffect(Store.str(c, "token").isNotEmpty(), kind) {
        rev = ""
        while (true) {
            if (resumed()) Api.call(c, "GET", "/api/feed?limit=2&kind=$kind&rev=$rev", null) { code, j ->
                if (code in 200..299 && j != null) {
                    offline = false
                    if (!j.optBoolean("unchanged")) { posts = parsePosts(j); rev = j.optString("rev") }
                } else offline = true
            }
            delay(10000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (posts.isEmpty()) Box(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(18.dp))
            .border(1.dp, C.Stroke, RoundedCornerShape(18.dp)).padding(16.dp)) {
            Text(if (offline) "Chưa tải được dữ liệu (mất kết nối máy chủ)." else if (kind == "news") "Chưa có tin báo chí nào. Máy chủ sẽ tự tải tin mới định kỳ." else "Chưa có cảnh báo nào từ cộng đồng. Hãy là người đầu tiên chia sẻ!",
                color = C.Dim, fontSize = 13.sp)
        }
        posts.forEach { p -> PostCard(p) { if (p.kind == "news") reading = p else onPost(p.id) } }
    }
    reading?.let { NewsReader(c, it) { reading = null } }
}

// ───────────────────────── Tab Cộng đồng ─────────────────────────

@Composable fun CommunityTab(c: Context, onLogin: () -> Unit, openPost: Long, onOpened: () -> Unit) {
    val logged = Store.str(c, "token").isNotEmpty()
    var detail by remember { mutableLongStateOf(0L) }
    var composing by remember { mutableStateOf(false) }
    LaunchedEffect(openPost) { if (openPost > 0L) { detail = openPost; onOpened() } }
    BackHandler(enabled = composing || detail > 0L) { if (composing) composing = false else detail = 0L }
    when {
        composing -> ComposePost(c, { composing = false }, { composing = false })
        detail > 0L -> PostDetail(c, detail, logged, onLogin) { detail = 0L }
        else -> FeedList(c, logged, onLogin, { detail = it }) { composing = true }
    }
}

@Composable private fun FeedList(c: Context, logged: Boolean, onLogin: () -> Unit, onOpen: (Long) -> Unit, onCompose: () -> Unit) {
    var section by remember { mutableStateOf(feedStartSection) }   // "post" = bài người dùng, "news" = tin báo chí
    var topic by remember { mutableStateOf("") }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var rev by remember { mutableStateOf("") }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var needLogin by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf<Post?>(null) }
    reading?.let { NewsReader(c, it) { reading = null } }
    val resumed = rememberIsResumed()

    // Cập nhật liên tục: cứ 8 giây hỏi máy chủ "có gì mới không"; không đổi thì máy chủ trả lời rất gọn.
    LaunchedEffect(section, topic, logged, retry) {
        posts = emptyList(); rev = ""
        while (true) {
            if (resumed()) Api.call(c, "GET", "/api/feed?limit=20&kind=$section&topic=$topic&rev=$rev", null) { code, j ->
                if (code in 200..299 && j != null) {
                    msg = ""
                    if (!j.optBoolean("unchanged")) { posts = parsePosts(j); rev = j.optString("rev"); hasMore = posts.size >= 20 }
                } else msg = apiMsg(code, j)
            }
            delay(8000)
        }
    }

    fun loadMore() {
        val last = posts.lastOrNull() ?: return
        loadingMore = true
        Api.call(c, "GET", "/api/feed?limit=20&kind=$section&topic=$topic&before_ts=${last.ts}", null) { code, j ->
            loadingMore = false
            if (code in 200..299 && j != null) {
                val older = parsePosts(j)
                val have = posts.map { it.kind + it.id }.toSet()
                val fresh = older.filter { (it.kind + it.id) !in have }   // trang sau có thể lặp lại mục cuối của trang trước
                posts = posts + fresh
                hasMore = fresh.isNotEmpty() && older.size >= 20
            } else msg = apiMsg(code, j)
        }
    }

    if (needLogin) AlertDialog(onDismissRequest = { needLogin = false },
        title = { Text("Cần đăng nhập") }, text = { Text("Hãy đăng nhập để đăng cảnh báo, bình luận và bày tỏ quan điểm.") },
        confirmButton = { TextButton({ needLogin = false; onLogin() }) { Text("Đăng nhập") } },
        dismissButton = { TextButton({ needLogin = false }) { Text("Để sau") } })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color(0xFFFB923C), modifier = Modifier.size(24.dp))
                Text(if (section == "news") "Tin Báo Chí" else "Cảnh Báo Cộng Đồng", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).background(if (msg.isEmpty()) C.Mint else C.Bad, CircleShape))
                    Text(if (msg.isEmpty()) "Trực tiếp" else "Mất kết nối", color = C.Dim, fontSize = 12.sp)
                }
            }
            // Hai mục tách bạch: bài người dùng đăng và tin từ báo
            Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("post" to "Cộng đồng", "news" to "Tin báo chí").forEach { (k, l) ->
                    val on = section == k
                    Box(Modifier.weight(1f).background(if (on) (if (k == "news") Color(0xFF38BDF8) else Color(0xFFFB923C)) else Color.Transparent, RoundedCornerShape(11.dp))
                        .clickable { if (!on) { section = k; feedStartSection = k; topic = "" } }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(l, color = if (on) Color(0xFF0B1230) else C.Sub, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
            }
            Text(if (section == "news") "Tin do các báo đăng, được tóm tắt ý chính. Bấm vào để đọc trong app." else "Bài do người dùng ScamShield chia sẻ, không phải tin báo chí.", color = C.Dim, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedChip("Tất cả", topic == "") { topic = "" }
                TOPICS.forEach { (k, l) -> FeedChip(l, topic == k) { topic = k } }
            }
            if (msg.isNotEmpty()) {
                Text(msg, color = C.Warn, fontSize = 13.sp)
                TextButton({ retry++ }) { Text("Thử lại", color = FeedCyan) }
            }
            if (posts.isEmpty() && msg.isEmpty()) Text(if (section == "news") "Chưa có tin báo chí nào. Máy chủ tự tải tin mới định kỳ." else "Chưa có cảnh báo nào trong mục này. Hãy là người đầu tiên chia sẻ!", color = C.Sub, fontSize = 14.sp)
            posts.forEach { p -> PostCard(p) { if (p.kind == "news") reading = p else onOpen(p.id) } }
            if (hasMore) TextButton({ if (!loadingMore) loadMore() }, Modifier.fillMaxWidth()) { Text(if (loadingMore) "Đang tải…" else "Xem thêm cảnh báo cũ hơn", color = FeedCyan) }
            Spacer(Modifier.height(96.dp))
        }
        if (section == "post") Column(Modifier.align(Alignment.BottomEnd).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(58.dp).shadow(16.dp, CircleShape, ambientColor = Color(0x66FB923C), spotColor = Color(0x66FB923C))
                .background(Brush.verticalGradient(listOf(Color(0xFFFB923C), Color(0xFFEA580C))), CircleShape)
                .clickable { if (logged) onCompose() else needLogin = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
            Text("Báo cáo", color = Color(0xFFFB923C), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

// ───────────────────────── Đăng cảnh báo ─────────────────────────

@Composable private fun ComposePost(c: Context, onBack: () -> Unit, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("dien-thoai") }
    var sev by remember { mutableStateOf("medium") }
    var sending by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun send() {
        sending = true; msg = "Đang đăng…"
        Api.call(c, "POST", "/api/posts", JSONObject().put("title", title.trim()).put("body", body.trim()).put("topic", topic).put("severity", sev)) { code, j ->
            sending = false
            if (code in 200..299) onDone() else msg = apiMsg(code, j)
        }
    }

    Page("Đăng cảnh báo", onBack) {
        Text("Chủ đề", color = C.Dim, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { TOPICS.forEach { (k, l) -> FeedChip(l, topic == k) { topic = k } } }
        Text("Mức độ nguy hiểm", color = C.Dim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SEVERITIES.forEach { (k, l) -> FeedChip(l, sev == k) { sev = k } } }
        OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tiêu đề (tối thiểu 3 ký tự)") })
        OutlinedTextField(body, { body = it.take(1000) }, Modifier.fillMaxWidth(), minLines = 5,
            label = { Text("Nội dung") }, placeholder = { Text("Chuyện gì xảy ra? Số điện thoại, nội dung tin nhắn, cách nhận biết…") })
        Text("${body.length}/1000 • Bài đăng hiển thị công khai dưới tên hiển thị của bạn. Không đăng OTP, số thẻ, mật khẩu hay thông tin cá nhân của người khác.", fontSize = 11.sp, color = C.Sub)
        if (msg.isNotEmpty()) Text(msg, color = C.Warn, fontSize = 13.sp)
        GoldButton("Đăng cảnh báo", enabled = title.trim().length >= 3 && body.trim().length >= 5 && !sending, leading = Icons.Rounded.Campaign) { send() }
    }
}

// ───────────────────────── Chi tiết + bình luận công khai ─────────────────────────

@Composable private fun PostDetail(c: Context, id: Long, logged: Boolean, onLogin: () -> Unit, onBack: () -> Unit) {
    var post by remember { mutableStateOf<Post?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val resumed = rememberIsResumed()

    // Bình luận mới của người khác hiện sau tối đa 5 giây.
    LaunchedEffect(id, logged, refresh) {
        while (true) {
            if (resumed()) Api.call(c, "GET", "/api/posts/$id", null) { code, j ->
                if (code in 200..299 && j != null) { post = parsePost(j.optJSONObject("post")); comments = parseComments(j); msg = "" }
                else if (code == 404 && post != null) { post = null; msg = "Bài viết không còn tồn tại hoặc đã bị ẩn." }
                else msg = apiMsg(code, j)
            }
            delay(5000)
        }
    }

    fun need(): Boolean { if (!logged) onLogin(); return !logged }
    fun like() { if (need()) return
        Api.call(c, "POST", "/api/posts/$id/like", null) { code, j ->
            if (code in 200..299 && j != null) post = post?.copy(liked = j.optBoolean("liked"), likes = j.optInt("likes")) else msg = apiMsg(code, j) } }
    fun reportPost() { if (need()) return
        Api.call(c, "POST", "/api/posts/$id/report", null) { code, j -> msg = if (code in 200..299) "Đã gửi báo cáo, cảm ơn bạn" else apiMsg(code, j) } }
    fun deletePost() = Api.call(c, "DELETE", "/api/posts/$id", null) { code, j -> if (code in 200..299) onBack() else msg = apiMsg(code, j) }
    fun sendComment() {
        sending = true
        Api.call(c, "POST", "/api/posts/$id/comments", JSONObject().put("body", draft.trim())) { code, j ->
            sending = false
            if (code in 200..299) { draft = ""; msg = ""; refresh++ } else msg = apiMsg(code, j)
        }
    }
    fun reportComment(cid: Long) = Api.call(c, "POST", "/api/comments/$cid/report", null) { code, j -> msg = if (code in 200..299) "Đã gửi báo cáo bình luận, cảm ơn bạn" else apiMsg(code, j) }
    fun deleteComment(cid: Long) = Api.call(c, "DELETE", "/api/comments/$cid", null) { code, j -> if (code in 200..299) refresh++ else msg = apiMsg(code, j) }

    confirm?.let { (text, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Xác nhận") }, text = { Text(text) },
            confirmButton = { TextButton({ confirm = null; action() }) { Text("Xóa") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Hủy") } })
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.06f), CircleShape).border(1.dp, C.Stroke, CircleShape)
                    .clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text("Chi tiết cảnh báo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (msg.isNotEmpty()) Text(msg, color = C.Warn, fontSize = 13.sp)
            val p = post
            if (p == null) {
                if (msg.isEmpty()) Text("Đang tải…", color = C.Sub)
            } else {
                Column(Modifier.fillMaxWidth().background(FeedCard, RoundedCornerShape(20.dp)).border(BorderStroke(1.dp, FeedBorder), RoundedCornerShape(20.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag(topicLabel(p.topic).uppercase(), topicColor(p.topic))
                            if (p.official) Tag("CHÍNH THỨC", FeedCyan)
                        }
                        Text(timeAgo(p.ts), color = C.Dim, fontSize = 12.sp)
                    }
                    Text(p.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(p.body, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuthorDot(p.author)
                        Column { Text(p.author, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text("Mức độ: ${sevLabel(p.severity)}", color = C.Dim, fontSize = 12.sp) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(FeedBorder))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.background(if (p.liked) C.Mint.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                            .border(1.dp, if (p.liked) C.Mint.copy(alpha = 0.4f) else C.Stroke, RoundedCornerShape(14.dp))
                            .clickable { like() }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.ThumbUp, null, tint = if (p.liked) C.Mint else C.Sub, modifier = Modifier.size(15.dp))
                            Text("Hữu ích ${p.likes}", fontSize = 12.5.sp, color = if (p.liked) C.Mint else C.Sub, fontWeight = FontWeight.SemiBold)
                        }
                        if (p.mine) Row(Modifier.background(C.Bad.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                            .clickable { confirm = "Xóa vĩnh viễn bài viết và các bình luận của nó?" to { deletePost() } }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Delete, null, tint = C.Bad, modifier = Modifier.size(15.dp))
                            Text("Xóa bài", fontSize = 12.5.sp, color = C.Bad, fontWeight = FontWeight.SemiBold)
                        } else Row(Modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                            .clickable { reportPost() }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Flag, null, tint = C.Sub, modifier = Modifier.size(15.dp))
                            Text("Báo cáo", fontSize = 12.5.sp, color = C.Sub, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("Bình luận (${comments.size})", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (comments.isEmpty()) Text("Chưa có bình luận. Hãy là người đầu tiên!", color = C.Sub, fontSize = 13.sp)
                comments.forEach { cm ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuthorDot(cm.author)
                        Column(Modifier.weight(1f).background(FeedCard, RoundedCornerShape(14.dp)).border(BorderStroke(1.dp, FeedBorder), RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(cm.author + (if (cm.mine) " (bạn)" else ""), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(timeAgo(cm.ts), color = C.Dim, fontSize = 11.sp)
                            }
                            Text(cm.body, color = C.Sub, fontSize = 14.sp, lineHeight = 20.sp)
                            if (cm.mine) Text("Xóa", color = C.Bad, fontSize = 12.sp, modifier = Modifier.clickable { confirm = "Xóa bình luận này?" to { deleteComment(cm.id) } })
                            else Text("Báo cáo", color = C.Dim, fontSize = 12.sp, modifier = Modifier.clickable { if (!need()) reportComment(cm.id) })
                        }
                    }
                }
            }
        }
        if (post != null) Box(Modifier.fillMaxWidth().background(C.Bar).padding(10.dp)) {
            if (logged) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it.take(500) }, Modifier.weight(1f), maxLines = 3, placeholder = { Text("Viết bình luận công khai…") })
                Button({ if (!sending) sendComment() }, enabled = draft.isNotBlank() && !sending, shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FeedCyan, contentColor = Color(0xFF042F2E))) { Text(if (sending) "…" else "Gửi", fontWeight = FontWeight.Bold) }
            } else Button(onLogin, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FeedCyan, contentColor = Color(0xFF042F2E))) { Text("Đăng nhập để bình luận", fontWeight = FontWeight.Bold) }
        }
    }
}
