package vn.scamshield

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private data class BankOption(
    val name: String,
    val shortName: String,
    val code: String,
    val bin: String,
    val lookupSupported: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankLookupScreen(c: Context, back: () -> Unit) {
    var banks by remember { mutableStateOf<List<BankOption>>(emptyList()) }
    var selected by remember { mutableStateOf<BankOption?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }
    var loadingBanks by remember { mutableStateOf(false) }
    var lookingUp by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Đang tải danh sách ngân hàng…") }
    var result by remember { mutableStateOf<JSONObject?>(null) }

    fun loadBanks() {
        loadingBanks = true
        message = "Đang tải danh sách ngân hàng…"
        Api.call(c, "GET", "/api/bank/list", null) { code, data ->
            loadingBanks = false
            val list = mutableListOf<BankOption>()
            val a = data?.optJSONArray("data")
            if (code in 200..299 && a != null) {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val bin = o.optString("bin")
                    if (bin.length == 6 && bin.all(Char::isDigit)) {
                        list += BankOption(
                            name = o.optString("name"),
                            shortName = o.optString("shortName").ifBlank { o.optString("name") },
                            code = o.optString("code"),
                            bin = bin,
                            lookupSupported = o.optInt("lookupSupported", 0) == 1
                        )
                    }
                }
            }
            banks = list.sortedBy { it.shortName.lowercase() }
            if (selected == null) selected = banks.firstOrNull()
            message = if (banks.isEmpty()) err(data) else "Đã tải ${banks.size} ngân hàng từ danh sách VietQR."
        }
    }

    LaunchedEffect(Unit) { loadBanks() }

    Page("Tra cứu tài khoản ngân hàng", back) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountBalance, null, tint = C.Blue)
                    Spacer(Modifier.width(8.dp))
                    Text("Chọn ngân hàng", color = C.Text, fontWeight = FontWeight.Bold)
                }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.let { "${it.shortName} • BIN ${it.bin}" } ?: if (loadingBanks) "Đang tải…" else "Chọn ngân hàng",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        banks.forEach { bank ->
                            DropdownMenuItem(
                                text = { Text("${bank.shortName} • ${bank.bin}") },
                                onClick = { selected = bank; expanded = false; result = null }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it.filter(Char::isDigit).take(30); result = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Số tài khoản ngân hàng") },
                    placeholder = { Text("Nhập số tài khoản cần đối chiếu") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(
                    onClick = {
                        val bank = selected ?: return@Button
                        if (!bank.lookupSupported) {
                            message = "Ngân hàng này hiện không công bố hỗ trợ tra cứu tên tài khoản qua VietQR."
                        } else if (account.length !in 6..30) {
                            message = "Hãy nhập số tài khoản gồm 6–30 chữ số."
                        } else {
                            lookingUp = true
                            result = null
                            message = "Đang gửi yêu cầu tra cứu tới server…"
                            Api.call(c, "POST", "/api/bank/lookup",
                                JSONObject().put("bin", bank.bin).put("accountNumber", account)) { code, data ->
                                lookingUp = false
                                if (code in 200..299 && data?.optBoolean("ok") == true) {
                                    result = data
                                    message = "Đã nhận kết quả từ VietQR."
                                } else message = err(data)
                            }
                        }
                    },
                    enabled = !lookingUp && selected != null && account.length in 6..30,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Search, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (lookingUp) "Đang tra cứu…" else "Tra tên chủ tài khoản")
                }
                Row {
                    OutlinedButton(onClick = { loadBanks() }, enabled = !loadingBanks) {
                        Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(5.dp)); Text("Làm mới danh sách")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("BIN: ${selected?.bin ?: "—"}", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 13.dp))
                }
            }
        }

        result?.let { data ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF7F0)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFBCE6CC), RoundedCornerShape(18.dp))
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tên tài khoản ngân hàng", color = C.Sub, fontSize = 12.sp)
                    Text(data.optString("accountName"), color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Số tài khoản: ${data.optString("accountNumberMasked")}", color = C.Sub, fontSize = 12.sp)
                }
            }
        }

        Text(message, color = C.Blue, fontSize = 12.sp, lineHeight = 17.sp)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6DF)),
            shape = RoundedCornerShape(17.dp)
        ) {
            Text(
                "Khi bạn nhấn Tra tên chủ tài khoản, BIN và số tài khoản sẽ được gửi qua máy chủ ScamShield tới VietQR để tra cứu. ScamShield không lưu số tài khoản hoặc kết quả. Tên khớp chỉ giúp đối chiếu người nhận, KHÔNG chứng minh họ đáng tin cậy hay tài khoản chưa từng bị dùng lừa đảo. Không chuyển tiền chỉ dựa trên kết quả này.",
                modifier = Modifier.padding(13.dp), color = C.Text, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }
}
