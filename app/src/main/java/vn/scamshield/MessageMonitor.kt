package vn.scamshield

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
    var autoGeminiEnabled by remember { mutableStateOf(ConversationAiMonitor.isEnabled(c)) }
    var askAutoGeminiConsent by remember { mutableStateOf(false) }
    var importTargetId by remember { mutableStateOf("") }
    var socialAppPackage by remember { mutableStateOf("") }
    var appExpanded by remember { mutableStateOf(false) }
    var askNotificationAccess by remember { mutableStateOf(false) }
    var screenMonitorEnabled by remember { mutableStateOf(ChatAccessibilityService.isMonitoringEnabled(c)) }
    var askScreenAccessConsent by remember { mutableStateOf(false) }
    var journalTarget by remember { mutableStateOf<MonitoredTarget?>(null) }

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
    val accessibilityServiceEnabled = remember(refresh) { ChatAccessibilityService.isServiceEnabled(c) }
    // Chỉ lấy COUNT và ba tin gần nhất của mỗi mục; không tải toàn bộ bảng 10.000 tin mỗi lần refresh.
    val targetMessageCounts = remember(refresh, targets) {
        targets.associate { it.id to MonitorStore.messageCount(c, it.id) }
    }
    val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val journalMessages = remember(journalTarget?.id, refresh) {
        journalTarget?.let { MonitorStore.messages(c, it.id).takeLast(500) }.orEmpty()
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        message = if (granted) {
            "Đã cấp quyền hiển thị cảnh báo của ScamShield."
        } else {
            "Chưa được cấp quyền hiển thị thông báo; ScamShield không thể hiện cảnh báo trên thanh thông báo."
        }
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

    Page("Theo dõi tin nhắn", back) {
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4DD)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chưa cấp quyền hiển thị cảnh báo", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Android đang chặn ScamShield gửi thông báo cảnh báo. Quyền Truy cập thông báo để đọc tin nhắn và quyền Hiển thị thông báo là hai quyền riêng biệt.",
                        color = C.Sub, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Button(
                        onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cấp quyền hiển thị cảnh báo") }
                }
            }
        }
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
                        "Chọn đúng ứng dụng nhận tin nhắn: thông báo từ Zalo phải theo dõi bằng Zalo, không phải Facebook. Tên người gửi hoặc tên nhóm cũng phải khớp chính xác.",
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
                        ChatAccessibilityService.refreshPackageFilter(c)
                        message = if (ok) "Đã thêm đối tượng theo dõi. Dữ liệu mới sẽ được lưu cục bộ trên máy."
                            else "Không thêm được: hãy kiểm tra nội dung hoặc đối tượng đã tồn tại."
                        identifier = ""
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
                Text("• Mỗi tin nhắn mới trong hồ sơ theo dõi được ScamShield tự đánh giá ngay trên thiết bị và lưu kết quả vào nhật ký. Cảnh báo cũng tự động xuất hiện khi phát hiện dấu hiệu. Nếu bật phân tích ngữ cảnh tự động, tối đa 12 tin gần nhất của đúng cuộc trò chuyện có thể được gửi tới máy chủ để AI hiểu thêm ngữ cảnh.",
                    color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ScamShield tự động hiểu ngữ cảnh", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Tự động phân tích ngữ cảnh mới cho các cuộc chat đang theo dõi; có thể tắt bất cứ lúc nào.", color = C.Sub, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Switch(
                        checked = autoGeminiEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                ConversationAiMonitor.setEnabled(c, false)
                                autoGeminiEnabled = false
                                message = "Đã tắt ScamShield tự động hiểu ngữ cảnh."
                            } else if (Store.str(c, "token").isBlank()) {
                                message = "Bạn cần đăng nhập ScamShield trước khi bật phân tích ngữ cảnh tự động."
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

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, C.Stroke, RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Theo dõi nội dung chat đang mở", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Khi bạn mở đúng cuộc trò chuyện đã thêm, ScamShield có thể đọc văn bản bong bóng tin đến đang hiển thị qua Trợ năng Android. Không chụp ảnh màn hình, không đọc ô đang nhập hoặc trường mật khẩu. Chỉ văn bản khớp ứng dụng và tiêu đề chat đã chọn mới được lưu.",
                    color = C.Sub, fontSize = 11.sp, lineHeight = 16.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Bật đọc màn hình khi đang chat", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            when {
                                screenMonitorEnabled && accessibilityServiceEnabled -> "Đang bật và sẵn sàng theo dõi chat đã chọn."
                                screenMonitorEnabled -> "Đã bật trong ScamShield; cần bật dịch vụ Trợ năng ở Android."
                                else -> "Đang tắt. Không đọc nội dung màn hình."
                            },
                            color = C.Sub, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = screenMonitorEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                ChatAccessibilityService.setMonitoringEnabled(c, false)
                                screenMonitorEnabled = false
                                message = "Đã tạm dừng đọc nội dung chat trên màn hình."
                            } else if (targets.none { it.channel == "social" && it.appPackage.isNotBlank() }) {
                                message = "Hãy thêm ít nhất một người hoặc cuộc trò chuyện mạng xã hội trước."
                            } else if (!ChatAccessibilityService.hasConsent(c)) {
                                askScreenAccessConsent = true
                            } else {
                                ChatAccessibilityService.setMonitoringEnabled(c, true)
                                screenMonitorEnabled = true
                                if (!ChatAccessibilityService.isServiceEnabled(c)) {
                                    c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                } else {
                                    message = "Đã bật đọc màn hình cho các cuộc trò chuyện đã chọn."
                                }
                            }
                        }
                    )
                }
                if (!accessibilityServiceEnabled) {
                    OutlinedButton(
                        onClick = { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Accessibility, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Mở cài đặt Trợ năng Android")
                    }
                } else {
                    Text("Quyền Trợ năng Android đã bật. Có thể tắt quyền này trong Cài đặt Android hoặc tắt công tắc phía trên để dừng thu thập.", color = C.Mint, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Text(
                    "Lưu ý: một số phiên bản Zalo/Messenger không công bố đầy đủ cấu trúc tin nhắn. Khi không xác định chắc cuộc trò chuyện hoặc chiều tin nhắn, ScamShield sẽ bỏ qua thay vì tự đoán. Trong chat nhóm, bản đầu có thể chưa xác định chính xác tên từng thành viên gửi tin.",
                    color = C.Sub, fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }

        Text("Đối tượng đang theo dõi", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        if (targets.isEmpty()) Text("Chưa có số điện thoại hoặc tài khoản nào.", color = C.Dim, fontSize = 13.sp)
        targets.forEach { target ->
            val targetMessageCount = targetMessageCounts[target.id] ?: 0
            Card(
                onClick = { journalTarget = target },
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
                    if (target.channel == "social" && targetMessageCount == 0) {
                        Text(
                            "Chưa nhận được tin nhắn khớp. Kiểm tra lại ứng dụng đã chọn, tên người gửi/nhóm và quyền Truy cập thông báo.",
                            color = C.Sub, fontSize = 11.sp, lineHeight = 15.sp
                        )
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
                    OutlinedButton(onClick = {
                        MonitorStore.removeTarget(c, target.id)
                        ChatAccessibilityService.refreshPackageFilter(c)
                        journalTarget = null
                        message = "Đã xóa đối tượng và dữ liệu tin nhắn liên quan khỏi bộ theo dõi."
                        refresh++
                    }) {
                        Icon(Icons.Rounded.Delete, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Xóa đối tượng")
                    }
                    Text(
                        "Chạm vào thẻ này để xem nhật ký tin nhắn và kết quả đánh giá từng tin.",
                        color = C.Sub, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
            }
        }

        if (message.isNotBlank()) Text(message, color = C.Blue, fontSize = 12.sp, lineHeight = 17.sp)

        if (journalTarget != null) {
            val selectedJournalTarget = journalTarget!!
            AlertDialog(
                onDismissRequest = { journalTarget = null },
                title = { Text("Nhật ký tin nhắn • ${selectedJournalTarget.identifier}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Lịch sử sắp xếp theo thời gian từ cũ đến mới. Tin đọc từ màn hình dùng thời điểm ScamShield quan sát được, không nhất thiết là giờ gửi ban đầu. Mỗi tin được đánh giá trên thiết bị; tối đa 500 tin gần nhất. Nhãn An toàn không phải bảo đảm tuyệt đối.",
                            color = C.Sub, fontSize = 11.sp
                        )
                        if (journalMessages.isEmpty()) {
                            Text("Chưa có tin nhắn được lưu.", color = C.Sub, fontSize = 13.sp)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                itemsIndexed(journalMessages) { _, item ->
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .background(Color(0xFFF4F8FF), RoundedCornerShape(11.dp))
                                            .padding(9.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            "${item.sender} • " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.timestamp)),
                                            color = C.Blue, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            when (item.channel) { "screen" -> "Nguồn: màn hình (ghi nhận lúc đọc)"; "social" -> "Nguồn: thông báo"; else -> "Nguồn: SMS" },
                                            color = C.Sub, fontSize = 9.sp
                                        )
                                        Text(item.body, color = C.Text, fontSize = 12.sp, lineHeight = 17.sp)
                                        val (verdict, verdictColor) = when (item.riskLevel) {
                                            "DANGER" -> "NGUY HIỂM" to Color(0xFFC62828)
                                            "SUSPICIOUS" -> "ĐÁNG NGỜ" to Color(0xFFB77900)
                                            "SAFE" -> "AN TOÀN" to Color(0xFF23834D)
                                            else -> "CHƯA ĐÁNH GIÁ" to C.Sub
                                        }
                                        Text(
                                            "$verdict • ${item.riskScore}/100",
                                            color = verdictColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (item.riskReasons.isNotEmpty()) {
                                            Text(
                                                item.riskReasons.joinToString(" • "),
                                                color = C.Sub,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { journalTarget = null }) { Text("Đóng") }
                }
            )
        }

        if (askScreenAccessConsent) {
            AlertDialog(
                onDismissRequest = { askScreenAccessConsent = false },
                title = { Text("Cho phép đọc nội dung chat đang hiển thị?") },
                text = {
                    Text(
                        "ScamShield sẽ dùng Trợ năng Android để nhận biết ứng dụng chat đang mở và đọc văn bản của các bong bóng tin đến đang hiển thị, nhưng chỉ khi tiêu đề khớp đúng người/cuộc trò chuyện bạn đã thêm theo dõi. Ứng dụng bỏ qua ô nhập, trường mật khẩu và không chụp ảnh màn hình. Văn bản được lưu cục bộ để chấm điểm và tạo nhật ký. " +
                            "Nội dung chỉ được gửi lên máy chủ để Gemini phân tích nếu bạn bật công tắc ScamShield tự động hiểu ngữ cảnh riêng. Cấu trúc giao diện của từng ứng dụng có thể khiến một số tin bị bỏ sót. Bạn có thể dừng thu thập bằng công tắc này bất cứ lúc nào."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askScreenAccessConsent = false
                        ChatAccessibilityService.setConsent(c, true)
                        ChatAccessibilityService.setMonitoringEnabled(c, true)
                        screenMonitorEnabled = true
                        c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        message = "Hãy tìm ScamShield – Theo dõi chat trong danh sách Trợ năng và tự bật dịch vụ."
                    }) { Text("Đồng ý và mở Cài đặt") }
                },
                dismissButton = {
                    TextButton(onClick = { askScreenAccessConsent = false }) { Text("Không đồng ý") }
                }
            )
        }

        if (askAutoGeminiConsent) {
            AlertDialog(
                onDismissRequest = { askAutoGeminiConsent = false },
                title = { Text("Bật ScamShield tự động hiểu ngữ cảnh?") },
                text = {
                    Text(
                        "Sau khi có nội dung mới trong một cuộc trò chuyện bạn đã thêm theo dõi, bao gồm nội dung đọc từ thông báo hoặc bong bóng tin đến đang hiển thị trên màn hình khi bạn bật Trợ năng, ScamShield sẽ đợi khoảng 15 giây rồi gửi tối đa 12 tin gần nhất của đúng cuộc trò chuyện đó qua máy chủ tới Google Gemini. " +
                            "Nếu server tạm tắt hoặc mất kết nối, yêu cầu sẽ được giữ trên thiết bị và tự gửi lại khi server hoạt động trở lại; bạn có thể xem trạng thái đang chờ trong mục này. " +
                            "ScamShield không gửi toàn bộ lịch sử; một số mã OTP, mật khẩu, số điện thoại và dãy số dài sẽ được ẩn trước khi gửi. " +
                            "Nội dung có thể vẫn chứa thông tin riêng tư, và việc phân tích sẽ sử dụng hạn mức Gemini. " +
                            "Bạn có thể tắt tính năng này bất cứ lúc nào; thao tác đó sẽ hủy các yêu cầu đang chờ."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askAutoGeminiConsent = false
                        ConversationAiMonitor.setEnabled(c, true)
                        autoGeminiEnabled = true
                        message = "Đã bật. ScamShield AI sẽ phân tích khi có tin nhắn mới trong các cuộc trò chuyện đang theo dõi."
                    }) { Text("Đồng ý bật") }
                },
                dismissButton = {
                    TextButton(onClick = { askAutoGeminiConsent = false }) { Text("Hủy") }
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
