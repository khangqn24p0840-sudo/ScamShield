package vn.scamshield

import android.app.Application

/** Khởi tạo sớm để mọi nơi gọi ScamDetector (tin nhắn, cuộc gọi, thông báo) đều dùng được mô hình TFLite. */
class ScamApp : Application() {
    override fun onCreate() { super.onCreate(); TextModel.init(this) }
}
