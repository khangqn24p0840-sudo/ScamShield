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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageMonitorScreen(c: Context, openSocialSettings: () -> Unit, back: () -> Unit) {
    var channel by remember { mutableStateOf("sms") }
    var identifier by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf<Pair<String, Result>?>(null) }
    var importTargetId by remember { mutableStateOf("") }

    val targets = remember(refresh) { MonitorStore.targets(c) }
    val messages = remember(refresh) { MonitorStore.messages(c) }
    val smsPermission = remember(c, refresh) {
        c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
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
                    FilterChip(selected = channel == "social", onClick = { channel = "social" }, label = { Text("Mạng xã hội") },
                        leadingIcon = { Icon(Icons.Rounded.Forum, null) })
                }
                OutlinedTextField(
                    value = identifier, onValueChange = { identifier = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (channel == "sms") "Số điện thoại người gửi" else "Tên người gửi / tài khoản hiển thị") },
                    placeholder = { Text(if (channel == "sms") "Ví dụ: 09xxxxxxxx" else "Ví dụ: Nguyen Van A") }
                )
                Button(
                    onClick = {
                        val ok = MonitorStore.addTarget(c, channel, identifier)
                        message = if (ok) "Đã thêm đối tượng theo dõi. Dữ liệu mới sẽ được lưu cục bộ trên máy."
                            else "Không thêm được: hãy kiểm tra nội dung hoặc đối tượng đã tồn tại."
                        identifier = ""
                        analysis = null
                        refresh++
                    },
                    enabled = identifier.trim().isNotEmpty(),
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
                Text("• Zalo/Messenger: chỉ nhận nội dung thông báo mới sau khi bạn bật quyền Truy cập thông báo. Android không cung cấp cho ứng dụng này toàn bộ lịch sử chat cũ bên trong các ứng dụng đó.",
                    color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                Text("• Nội dung theo dõi lưu trên thiết bị; không tự gửi lên máy chủ. Phân tích dưới đây chạy bằng bộ dò cục bộ.",
                    color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp)
                if (!SocialListener.enabled(c)) {
                    OutlinedButton(onClick = openSocialSettings, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.NotificationsActive, null); Spacer(Modifier.width(7.dp))
                        Text("Mở cài đặt quyền thông báo")
                    }
                }
            }
        }

        Text("Đối tượng đang theo dõi", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        if (targets.isEmpty()) Text("Chưa có số điện thoại hoặc tài khoản nào.", color = C.Dim, fontSize = 13.sp)
        targets.forEach { target ->
            val targetMessages = messages.filter { it.targetId == target.id }
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
                            Text(if (target.channel == "sms") "SMS" else "Thông báo mạng xã hội",
                                color = C.Sub, fontSize = 11.sp)
                        }
                        Text("${targetMessages.size} tin", color = C.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                                    "Đã phân tích chuỗi ${targetMessages.size} tin gần đây ngay trên thiết bị."
                            },
                            enabled = targetMessages.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Rounded.Psychology, null); Spacer(Modifier.width(5.dp)); Text("Phân tích ngữ cảnh") }
                        OutlinedButton(onClick = {
                            MonitorStore.removeTarget(c, target.id)
                            if (analysis?.first == target.id) analysis = null
                            message = "Đã xóa đối tượng và dữ liệu tin nhắn liên quan khỏi bộ theo dõi."
                            refresh++
                        }) { Icon(Icons.Rounded.Delete, null); Text("Xóa") }
                    }
                    targetMessages.takeLast(3).asReversed().forEach { item ->
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
                }
            }
        }

        if (message.isNotBlank()) Text(message, color = C.Blue, fontSize = 12.sp, lineHeight = 17.sp)
    }
}
