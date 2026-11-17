package vn.scamshield

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MessageMonitorScreen(c: Context, openSocialSettings: () -> Unit, back: () -> Unit) {
    var channel by remember { mutableStateOf("sms") }
    var identifier by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf<Pair<String, Result>?>(null) }
    var pendingGeminiTarget by remember { mutableStateOf<MonitoredTarget?>(null) }
    var geminiBusyTarget by remember { mutableStateOf<String?>(null) }
    var autoGeminiEnabled by remember { mutableStateOf(ConversationAiMonitor.isEnabled(c)) }
    var askAutoGeminiConsent by remember { mutableStateOf(false) }
    var importTargetId by remember { mutableStateOf("") }
    var socialAppPackage by remember { mutableStateOf("") }
    var appExpanded by remember { mutableStateOf(false) }
    var askNotificationAccess by remember { mutableStateOf(false) }

    val installedSocialApps = remember(c) {
        SocialListener.apps.entries.mapNotNull { (pkg, _) ->
            runCatching {
                val info = c.packageManager.getApplicationInfo(pkg, 0)
                pkg to c.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    LaunchedEffect(installedSocialApps) {
        if (socialAppPackage.isBlank() || installedSocialApps.none { it.first == socialAppPackage }) {
            socialAppPackage = installedSocialApps.firstOrNull()?.first.orEmpty()
        }
    }

    val targets = remember(refresh) { MonitorStore.targets(c) }
    // Chỉ lấy COUNT và ba tin gần nhất của mỗi mục; không tải toàn bộ bảng 10.000 tin mỗi lần refresh.
    val targetMessageCounts = remember(refresh, targets) {
        targets.associate { it.id to MonitorStore.messageCount(c, it.id) }
    }
    val targetRecentMessages = remember(refresh, targets) {
        targets.associate { it.id to MonitorStore.recentMessages(c, it.id, 3) }
    }
    val targetConversationAnalyses = remember(refresh, targets) {
        targets.associate { it.id to MonitorStore.latestConversationAnalysis(c, it.id) }
    }
    val smsPermission = remember(c, refresh) {
        c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val receiveSmsPermission = remember(c, refresh) {
        c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }

    val receivePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        message = if (granted) "Đã cấp quyền nhận SMS mới từ số đang theo dõi."
            else "Bạn chưa cấp quyền nhận SMS mới; lịch sử cũ vẫn có thể nhập riêng nếu có READ_SMS."
        refresh++
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            refresh++
        }
    }

    val importPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val targetId = importTargetId
        if (granted && targetId.isNotBlank()) {
            try {
                val count = MonitorStore.importSmsHistory(c, targetId)
                message = "Đã nhập $count SMS lịch sử mới vào bộ theo dõi trên máy."
            } catch (e: Exception) {
                message = "Không thể nhập lịch sử SMS: ${e.message ?: "lỗi quyền truy cập"}"
            }
            refresh++
        } else if (!granted) {
            message = "Bạn chưa cấp quyền đọc lịch sử SMS. ScamShield không thể nhập dữ liệu cũ."
        }
    }

    fun requestGeminiAnalysis(target: MonitoredTarget) {
        if (Store.str(c, "token").isBlank()) {
            message = "Bạn cần đăng nhập ScamShield trước khi sử dụng phân tích Gemini."
            return
        }
        val conversationKey = MonitorStore.latestConversationKey(c, target.id)
        if (conversationKey.isNullOrBlank()) {
            message = "Cuộc trò chuyện này chưa có nội dung để phân tích."
            return
        }
        geminiBusyTarget = target.id
        message = "Đang gửi ngữ cảnh của cuộc trò chuyện đã chọn cho Gemini…"
        ConversationAiMonitor.analyzeNow(c, target.id, conversationKey) { result, error ->
            geminiBusyTarget = null
            if (result != null) {
                message = "Gemini đã cập nhật đánh giá ngữ cảnh cho cuộc trò chuyện này."
                refresh++
            } else {
                message = error ?: "Gemini chưa phân tích được. Hãy thử lại sau."
            }
        }
    }

    Page("Theo dõi tin nhắn", back) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Chọn nguồn cần theo dõi", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = channel == "sms", onClick = { channel = "sms" }, label = { Text("SMS") },
                        leadingIcon = { Icon(Icons.Rounded.Sms, null) })
                    FilterChip(selected = channel == "social", onClick = {
                        channel = "social"
                        if (!SocialListener.enabled(c)) askNotificationAccess = true
                    }, label = { Text("Mạng xã hội") },
                        leadingIcon = { Icon(Icons.Rounded.Forum, null) })
                }
                if (channel == "social") {
                    Text("Chọn ứng dụng", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    ExposedDropdownMenuBox(expanded = appExpanded, onExpandedChange = { appExpanded = it }) {
                        OutlinedTextField(
                            value = installedSocialApps.firstOrNull { it.first == socialAppPackage }?.second
                                ?: if (installedSocialApps.isEmpty()) "Không tìm thấy ứng dụng hỗ trợ" else "Chọn ứng dụng",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = appExpanded) },
                            enabled = installedSocialApps.isNotEmpty(),
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(expanded = appExpanded, onDismissRequest = { appExpanded = false }) {
                            installedSocialApps.forEach { (pkg, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    socialAppPackage = pkg
                                    appExpanded = false
                                })
                            }
                        }
                    }
                    Text(
                        "Chọn ứng dụng đã cài trên điện thoại. Tin nhắn chỉ được ghép với hồ sơ của ứng dụng này.",
                        color = C.Sub, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                OutlinedTextField(
                    value = identifier, onValueChange = { identifier = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (channel == "sms") "Số điện thoại người gửi" else "Tên người gửi hiển thị trong thông báo") },
                    placeholder = { Text(if (channel == "sms") "Ví dụ: 09xxxxxxxx" else "Ví dụ: Nguyen Van A") }
                )
                if (channel == "social") {
                    Text(
                        "Nhập chính xác tên người gửi hoặc tiêu đề nhóm/cuộc trò chuyện đang hiện trên thông báo. Android không cho ScamShield xem danh sách tài khoản đăng nhập hay toàn bộ lịch sử chat riêng.",
                        color = C.Sub, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Button(
                    onClick = {
                        val ok = MonitorStore.addTarget(c, channel, identifier, if (channel == "social") socialAppPackage else "")
                        message = if (ok) "Đã thêm đối tượng theo dõi. Dữ liệu mới sẽ được lưu cục bộ trên máy."
                            else "Không thêm được: hãy kiểm tra nội dung hoặc đối tượng đã tồn tại."
                        identifier = ""
                        analysis = null
                        refresh++
                    },
                    enabled = identifier.trim().isNotEmpty() &&
                        (channel != "social" || socialAppPackage.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(7.dp)); Text("Thêm đối tượng theo dõi") }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFD6E6FA), RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Quyền truy cập và giới hạn", color = C.Text, fontWeight = FontWeight.Bold)
                Text("• SMS: nhập lịch sử tin nhắn đến của số bạn đã thêm, sau khi cấp quyền đọc SMS. Bộ theo dõi giữ tối đa 10.000 tin nhắn gần nhất trên thiết bị.",
                    color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                Text("• Mạng xã hội: chỉ đọc nội dung được hiển thị trong thông báo mới sau khi bạn tự bật quyền Truy cập thông báo. Android không cho ScamShield xem danh sách tài khoản đăng nhập hoặc toàn bộ lịch sử chat cũ trong Zalo/Messenger.",
                    color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                Text("• Bạn chọn ứng dụng và nhập chính xác tên người gửi hoặc tiêu đề nhóm/cuộc trò chuyện. Chỉ tin khớp ứng dụng và tên đã chọn mới được lưu vào hồ sơ theo dõi.",
                    color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                Text("• Tin nhắn được lưu trên thiết bị. Mặc định Gemini không nhận nội dung chat; bạn có thể bật phân tích ngữ cảnh tự động bên dưới. Khi bật, Gemini chỉ nhận tối đa 12 tin gần nhất của đúng cuộc trò chuyện đã theo dõi sau khi có tin mới và qua thời gian chờ.",
                    color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Gemini tự động hiểu ngữ cảnh", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Chỉ áp dụng cho các cuộc chat đang theo dõi; có thể tắt bất cứ lúc nào.", color = C.Sub, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Switch(
                        checked = autoGeminiEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                ConversationAiMonitor.setEnabled(c, false)
                                autoGeminiEnabled = false
                                message = "Đã tắt Gemini tự động phân tích ngữ cảnh."
                            } else if (Store.str(c, "token").isBlank()) {
                                message = "Bạn cần đăng nhập ScamShield trước khi bật Gemini tự động."
                            } else {
                                askAutoGeminiConsent = true
                            }
                        }
                    )
                }
                if (targets.any { it.channel == "sms" } && !receiveSmsPermission) {
                    OutlinedButton(
                        onClick = { receivePermission.launch(Manifest.permission.RECEIVE_SMS) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Sms, null); Spacer(Modifier.width(7.dp))
                        Text("Cho phép nhận SMS mới từ số đã chọn")
                    }
                } else if (targets.any { it.channel == "sms" }) {
                    Text("Đã cấp quyền nhận SMS mới trên thiết bị.", color = C.Mint, fontSize = 11.sp)
                }
                if (!SocialListener.enabled(c)) {
                    OutlinedButton(onClick = openSocialSettings, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.NotificationsActive, null); Spacer(Modifier.width(7.dp))
                        Text("Cấp quyền truy cập thông báo")
                    }
                }
            }
        }

        Text("Đối tượng đang theo dõi", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        if (targets.isEmpty()) Text("Chưa có số điện thoại hoặc tài khoản nào.", color = C.Dim, fontSize = 13.sp)
        targets.forEach { target ->
            val targetMessageCount = targetMessageCounts[target.id] ?: 0
            val recentMessages = targetRecentMessages[target.id].orEmpty()
            val conversationAnalysis = targetConversationAnalyses[target.id]
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, C.Stroke, RoundedCornerShape(17.dp))
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (target.channel == "sms") Icons.Rounded.Sms else Icons.Rounded.Forum,
                            null, tint = C.Blue)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(target.identifier, color = C.Text, fontWeight = FontWeight.Bold)
                            Text(
                                if (target.channel == "sms") "SMS"
                                else "Ứng dụng: " + (SocialListener.apps[target.appPackage] ?: "Mạng xã hội"),
                                color = C.Sub, fontSize = 11.sp
                            )
                        }
                        Text("$targetMessageCount tin", color = C.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (target.channel == "sms") {
                        OutlinedButton(
                            onClick = {
                                importTargetId = target.id
                                if (c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                                    try {
                                        val count = MonitorStore.importSmsHistory(c, target.id)
                                        message = "Đã nhập $count SMS lịch sử mới."
                                    } catch (e: Exception) { message = "Không nhập được SMS: ${e.message}" }
                                    refresh++
                                } else importPermission.launch(Manifest.permission.READ_SMS)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.History, null); Spacer(Modifier.width(7.dp))
                            Text(if (smsPermission) "Nhập lịch sử SMS cũ" else "Cấp quyền và nhập lịch sử SMS")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val transcript = MonitorStore.contextualText(c, target.id, 30)
                                analysis = target.id to ScamDetector.analyze(transcript)
                                message = if (transcript.isBlank()) "Chưa có tin nhắn để phân tích." else
                                    "Đã phân tích tối đa 30 tin gần nhất ngay trên thiết bị."
                            },
                            enabled = targetMessageCount > 0,
                            modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Rounded.Psychology, null); Spacer(Modifier.width(5.dp)); Text("Phân tích ngữ cảnh") }
                        OutlinedButton(onClick = {
                            MonitorStore.removeTarget(c, target.id)
                            if (analysis?.first == target.id) analysis = null
                            message = "Đã xóa đối tượng và dữ liệu tin nhắn liên quan khỏi bộ theo dõi."
                            refresh++
                        }) { Icon(Icons.Rounded.Delete, null); Text("Xóa") }
                    }
                    OutlinedButton(
                        onClick = { pendingGeminiTarget = target },
                        enabled = targetMessageCount > 0 && geminiBusyTarget != target.id,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (geminiBusyTarget == target.id) "Đang phân tích bằng Gemini…" else "Phân tích hội thoại bằng Gemini")
                    }
                    recentMessages.asReversed().forEach { item ->
                        Column(
                            Modifier.fillMaxWidth().background(Color(0xFFF6F9FE), RoundedCornerShape(12.dp)).padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("${item.sender} • " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(item.timestamp)),
                                color = C.Blue, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.body, color = C.Text, fontSize = 12.sp, maxLines = 3)
                        }
                    }
                    analysis?.takeIf { it.first == target.id }?.second?.let { ResultCard(it) }
                    conversationAnalysis?.let { result ->
                        val riskColor = when (result.riskLevel) {
                            "HIGH" -> Color(0xFFFFE8E8)
                            "MEDIUM" -> Color(0xFFFFF4DD)
                            else -> Color(0xFFEAF7EF)
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = riskColor),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Gemini • ${result.riskLevel} • ${result.riskScore}/100",
                                    color = C.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp
                                )
                                Text(result.summary, color = C.Text, fontSize = 12.sp, lineHeight = 18.sp)
                                result.evidence.forEach { evidence ->
                                    Text("• $evidence", color = C.Sub, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                                if (result.recommendedAction.isNotBlank()) {
                                    Text("Khuyến nghị: ${result.recommendedAction}", color = C.Text, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                                Text(
                                    "Độ tin cậy: ${result.confidence.lowercase()} • " +
                                        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(result.analyzedAt)),
                                    color = C.Sub, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        if (message.isNotBlank()) Text(message, color = C.Blue, fontSize = 12.sp, lineHeight = 17.sp)

        if (askAutoGeminiConsent) {
            AlertDialog(
                onDismissRequest = { askAutoGeminiConsent = false },
                title = { Text("Bật Gemini tự động cho Theo dõi chat?") },
                text = {
                    Text(
                        "Sau khi có tin nhắn mới trong một cuộc trò chuyện bạn đã thêm theo dõi, ScamShield sẽ đợi khoảng 15 giây rồi gửi tối đa 12 tin gần nhất của đúng cuộc trò chuyện đó qua máy chủ tới Google Gemini. " +
                            "ScamShield không gửi toàn bộ lịch sử; một số mã OTP, mật khẩu, số điện thoại và dãy số dài sẽ được ẩn trước khi gửi. " +
                            "Nội dung có thể vẫn chứa thông tin riêng tư, và việc phân tích sẽ sử dụng hạn mức Gemini. " +
                            "Bạn có thể tắt tính năng này bất cứ lúc nào."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askAutoGeminiConsent = false
                        ConversationAiMonitor.setEnabled(c, true)
                        autoGeminiEnabled = true
                        message = "Đã bật. Gemini sẽ phân tích khi có tin nhắn mới trong các cuộc trò chuyện đang theo dõi."
                    }) { Text("Đồng ý bật") }
                },
                dismissButton = {
                    TextButton(onClick = { askAutoGeminiConsent = false }) { Text("Hủy") }
                }
            )
        }

        if (pendingGeminiTarget != null) {
            val selectedTarget = pendingGeminiTarget!!
            AlertDialog(
                onDismissRequest = { pendingGeminiTarget = null },
                title = { Text("Gửi nội dung cuộc trò chuyện cho Gemini?") },
                text = {
                    Text(
                        "ScamShield sẽ gửi tối đa 12 tin gần nhất của thread mới nhất đã theo dõi trong mục " +
                            selectedTarget.identifier + " lên máy chủ ScamShield để Gemini phân tích. Một số dữ liệu nhạy cảm dễ nhận diện sẽ được ẩn trước khi gửi. " +
                            "Đây không phải thao tác tự động. Hãy hủy nếu nội dung có OTP, mật khẩu, thông tin ngân hàng " +
                            "hoặc dữ liệu riêng tư của người khác."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingGeminiTarget = null
                        requestGeminiAnalysis(selectedTarget)
                    }) { Text("Đồng ý gửi và phân tích") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingGeminiTarget = null }) { Text("Hủy") }
                }
            )
        }

        if (askNotificationAccess) {
            AlertDialog(
                onDismissRequest = { askNotificationAccess = false },
                title = { Text("Cho phép ScamShield đọc thông báo?") },
                text = {
                    Text(
                        "Để theo dõi tin nhắn mới từ ứng dụng bạn chọn, ScamShield cần quyền Truy cập thông báo của Android. " +
                            "Đây là quyền ở cấp dịch vụ và có thể cho phép ScamShield nhận thông báo từ nhiều ứng dụng. " +
                            "Hồ sơ theo dõi chỉ lưu tin nhắn khớp với ứng dụng và tên người gửi bạn chọn; tính năng cảnh báo lừa đảo riêng có thể phân tích thông báo từ các ứng dụng xã hội được hỗ trợ để cảnh báo ngay trên máy. " +
                            "Nội dung không tự gửi lên máy chủ. Bạn có thể tắt quyền này bất cứ lúc nào trong Cài đặt."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askNotificationAccess = false
                        openSocialSettings()
                    }) { Text("Mở cài đặt") }
                },
                dismissButton = {
                    TextButton(onClick = { askNotificationAccess = false }) { Text("Để sau") }
                }
            )
        }
    }
}
