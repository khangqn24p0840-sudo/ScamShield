package vn.scamshield
import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import org.json.JSONObject
import vn.scamshield.face.FaceBus

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private var continueSetupAfterRole = false
    private var continueSetupAfterLegacyCallPerms = false
    private val roleL = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        if (continueSetupAfterRole) {
            continueSetupAfterRole = false
            requestSmsPermissions()
        }
    }
    private val permL = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    var aiMsg by mutableStateOf("")
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (bytes.size > 5_000_000) aiMsg = "Ảnh quá lớn (tối đa 5MB)" else {
                aiMsg = "Đang kiểm tra ảnh…"
                Api.call(this, "POST", "/api/analyze/media", JSONObject().put("mime", contentResolver.getType(uri) ?: "image/jpeg")
                    .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))) { code, j -> aiMsg = fmtMedia(code, j) }
            }
        } catch (e: Exception) { aiMsg = "Không đọc được ảnh" }
    }
    private val captureL = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null)
            androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, ScreenFaceService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data))
    }

    private val hooks by lazy {
        Hooks(
            tick = { tick },
            callOn = { callOn() }, smsOn = { smsOn() },
            enableCalls = { requestCallPermission() },
            enableSms = { requestSmsPermissions() },
            enableProtection = { beginProtectionSetup() },
            toggleListen = {
                if (ListenService.running) { ListenService.running = false; stopService(Intent(this, ListenService::class.java)); tick++ }
                else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListen()
                else recL.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            },
            pickImage = { picker.launch("image/*") },
            startFace = { captureL.launch(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent()) },
            stopFace = { stopService(Intent(this, ScreenFaceService::class.java)); FaceBus.running = false },
            openOverlay = { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) },
            openSocial = { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            aiMsg = { aiMsg }, setAiMsg = { aiMsg = it }
        )
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Giao diện sáng: icon hệ thống màu tối trên nền xanh-trắng.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = C.Gold, onPrimary = C.Ink,
                secondary = C.Mint, onSecondary = Color.White,
                tertiary = C.Cyan,
                background = C.Bg, onBackground = C.Text,
                surface = C.Card, onSurface = C.Text,
                surfaceVariant = C.CardHi, onSurfaceVariant = C.Sub,
                outline = C.Stroke, outlineVariant = C.Stroke,
                error = C.Bad, onError = Color.White
            )) { ShieldApp(this@MainActivity, hooks) }
        }
    }
    override fun onResume() { super.onResume(); tick++ }

    private val recL = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++; if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListen()
    }
    private fun startListen() { ListenService.running = true; androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, ListenService::class.java)); tick++ }
    private val callPermL = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (continueSetupAfterLegacyCallPerms) {
            continueSetupAfterLegacyCallPerms = false
            requestSmsPermissions()
        }
    }

    /** Yêu cầu vai trò lọc cuộc gọi hiện đại; Android tự hiện màn hình đồng ý hệ thống. */
    private fun requestCallPermission() {
        if (Build.VERSION.SDK_INT >= 29) {
            if (!roleOn()) roleL.launch(getSystemService(RoleManager::class.java)
                .createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            else tick++
        } else if (!permsOn()) callPermL.launch(PhoneStateReceiver.perms()) else tick++
    }

    /** Xin quyền cuộc gọi trước, sau đó mới xin quyền nhận SMS để không chồng hai hộp thoại. */
    private fun beginProtectionSetup() {
        if (Build.VERSION.SDK_INT >= 29) {
            if (!roleOn()) {
                continueSetupAfterRole = true
                roleL.launch(getSystemService(RoleManager::class.java)
                    .createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            } else requestSmsPermissions()
        } else if (!permsOn()) {
            continueSetupAfterLegacyCallPerms = true
            callPermL.launch(PhoneStateReceiver.perms())
        } else requestSmsPermissions()
    }

    /** Chỉ xin các quyền còn thiếu; Android 13+ xin thêm quyền hiển thị thông báo. */
    private fun requestSmsPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECEIVE_SMS
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) tick++ else permL.launch(missing.toTypedArray())
    }

    private fun roleOn() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    private fun permsOn() = PhoneStateReceiver.perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun callOn() = roleOn() || (Build.VERSION.SDK_INT < 29 && permsOn())
    private fun smsOn() = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
