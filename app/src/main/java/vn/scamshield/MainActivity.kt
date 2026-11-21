package vn.scamshield
import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
            if (roleOn()) requestSmsPermissions()
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
    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyzeVideo(uri)
    }

    /** Lấy tối đa 8 khung hình đại diện; chỉ gửi các khung đã nén, không gửi toàn bộ video hay âm thanh. */
    private fun analyzeVideo(uri: android.net.Uri) {
        aiMsg = "Đang đọc video…"
        Thread {
            var retriever: android.media.MediaMetadataRetriever? = null
            try {
                retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IllegalArgumentException("Không xác định được thời lượng video")
                if (durationMs !in 1_000L..30_000L) {
                    throw IllegalArgumentException("Chỉ hỗ trợ video dài từ 1 đến 30 giây")
                }
                val frameCount = minOf(8, maxOf(3, (durationMs / 500L).toInt()))
                val frames = org.json.JSONArray()
                var totalBytes = 0
                for (index in 0 until frameCount) {
                    val timestampMs = ((index + 0.5) * durationMs / frameCount).toLong()
                        .coerceIn(0L, durationMs - 1L)
                    val original = retriever.getFrameAtTime(
                        timestampMs * 1_000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: continue
                    try {
                        val maxSide = 640
                        val scale = minOf(1.0, maxSide.toDouble() / maxOf(original.width, original.height))
                        val width = maxOf(1, (original.width * scale).toInt())
                        val height = maxOf(1, (original.height * scale).toInt())
                        val frame = if (width != original.width || height != original.height)
                            android.graphics.Bitmap.createScaledBitmap(original, width, height, true) else original
                        try {
                            val output = java.io.ByteArrayOutputStream()
                            var quality = 82
                            var bytes: ByteArray
                            do {
                                output.reset()
                                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)
                                bytes = output.toByteArray()
                                quality -= 8
                            } while (bytes.size > 600 * 1024 && quality >= 58)
                            if (bytes.isEmpty() || bytes.size > 800 * 1024) {
                                throw IllegalArgumentException("Một khung hình vượt giới hạn dung lượng")
                            }
                            totalBytes += bytes.size
                            if (totalBytes > 5 * 1024 * 1024) {
                                throw IllegalArgumentException("Video tạo ra quá nhiều dữ liệu để phân tích")
                            }
                            frames.put(JSONObject()
                                .put("timestamp_ms", timestampMs)
                                .put("mime", "image/jpeg")
                                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                        } finally {
                            if (frame !== original) frame.recycle()
                        }
                    } finally {
                        original.recycle()
                    }
                }
                if (frames.length() < 3) throw IllegalArgumentException("Không trích xuất đủ khung hình từ video")
                val payload = JSONObject().put("duration_ms", durationMs).put("frames", frames)
                runOnUiThread {
                    aiMsg = "Đã lấy ${frames.length()} khung hình. Đang gửi AI phân tích…"
                    Api.call(this, "POST", "/api/analyze/video", payload) { code, result ->
                        aiMsg = fmtVideo(code, result)
                    }
                }
            } catch (e: Exception) {
                val message = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Không đọc được video"
                runOnUiThread { aiMsg = "Không thể phân tích video: $message" }
            } finally {
                try { retriever?.release() } catch (_: Exception) { }
            }
        }.start()
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
            pickVideo = { videoPicker.launch("video/*") },
            startFace = { captureL.launch(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent()) },
            stopFace = { stopService(Intent(this, ScreenFaceService::class.java)); FaceBus.running = false },
            openOverlay = { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) },
            openSocial = { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            aiMsg = { aiMsg }, setAiMsg = { aiMsg = it }
        )
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Cho Compose nhận vùng hiển thị còn lại khi bàn phím bật, tránh che nội dung chat.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
        if (roleAvailable()) {
            if (!roleOn()) roleL.launch(getSystemService(RoleManager::class.java)
                .createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            else tick++
        } else if (!permsOn()) callPermL.launch(PhoneStateReceiver.perms()) else tick++
    }

    /** Xin quyền cuộc gọi trước, sau đó mới xin quyền nhận SMS để không chồng hai hộp thoại. */
    private fun beginProtectionSetup() {
        if (roleAvailable()) {
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

    private fun roleAvailable() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
    private fun roleOn() = roleAvailable() &&
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    private fun permsOn() = PhoneStateReceiver.perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun callOn() = if (roleAvailable()) roleOn() else permsOn()
    private fun smsOn() = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
