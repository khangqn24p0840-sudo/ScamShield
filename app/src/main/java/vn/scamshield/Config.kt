package vn.scamshield

/**
 * Địa chỉ HTTPS máy chủ ScamShield được đóng gói sẵn trong ứng dụng.
 * Khi đổi máy chủ hoặc domain tunnel, cập nhật SERVER_URL và build lại APK.
 * Không yêu cầu người dùng nhập địa chỉ trong giao diện.
 */
object Config {
    const val SERVER_URL = "https://reveler-bladder-lunar.ngrok-free.dev"
}
