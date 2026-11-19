package vn.scamshield
import java.text.Normalizer

data class Result(val score: Int, val reasons: List<String>) {
    /** 0 = an toàn, 1 = đáng ngờ, 2 = nguy hiểm */
    val level get() = when { score >= 60 -> 2; score >= 30 -> 1; else -> 0 }
    /** Ngưỡng báo sớm cho cuộc gọi trực tiếp: vàng từ 15, đỏ từ 40 */
    val live get() = when { score >= 40 -> 2; score >= 15 -> 1; else -> 0 }
}

object ScamDetector {
    private val combiningMarks = Regex("\\p{Mn}+")

    fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(combiningMarks, "").replace('đ', 'd')

    /**
     * Conversation-level heuristics for gradual coercion/manipulation.
     * Individual messages may be ordinary; only combinations raise the extra score.
     */
    fun analyzeConversation(text: String): Result {
        val base = analyze(text)
        val normalized = norm(text)
        val trust = Regex("ban la nguoi duy nhat|chi co ban|tin tuong ban|chi tin minh|ban co the tin minh")
            .containsMatchIn(normalized)
        val secrecy = Regex("giu bi mat|dung noi voi ai|khong noi voi ai|khong ke voi ai|dung cho ai biet")
            .containsMatchIn(normalized)
        val urgency = Regex("quyet dinh trong hom nay|trong hom nay|chi con hom nay|mat co hoi|ngay lap tuc|khong con thoi gian")
            .containsMatchIn(normalized)
        val suppressCheck = Regex("dung tu kiem tra|khong can kiem tra|dung hoi ai|cu lam theo|lam theo huong dan|dung can nhac")
            .containsMatchIn(normalized)
        val action = Regex("chuyen tien|gui ma|cung cap otp|nhap ma|dat coc|dong phi")
            .containsMatchIn(normalized)

        val cueCount = listOf(trust, secrecy, urgency, suppressCheck).count { it }
        val manipulationScore = when {
            cueCount == 4 && secrecy && suppressCheck -> 60
            cueCount >= 3 && secrecy && (urgency || suppressCheck) -> 45
            cueCount >= 2 && secrecy && (urgency || suppressCheck || action) -> 30
            else -> 0
        }
        if (manipulationScore == 0) return base

        val reasons = (base.reasons + "Chuỗi hội thoại có dấu hiệu tạo tin tưởng, giữ bí mật, gây áp lực hoặc ngăn kiểm chứng").distinct()
        return Result((base.score + manipulationScore).coerceAtMost(100), reasons)
    }

    private val rules = listOf(
        Triple("cong an|vien kiem sat|toa an|co quan dieu tra|bo cong an|lenh bat|trieu tap", 40, "Giả danh cơ quan chức năng"),
        Triple("tai khoan.*(khoa|dong bang|tam ngung|bi han che)|xac thuc.*tai khoan", 30, "Dọa khóa/đóng băng tài khoản"),
        Triple("ma xac thuc|ma xac minh|mat khau|so the|cvv", 30, "Hỏi thông tin nhạy cảm"),
        Triple("(gui|doc|cung cap|nhap).{0,24}(ma )?otp|otp.{0,24}(gui|doc|cung cap|nhap)", 45, "Yêu cầu gửi / cung cấp mã OTP"),
        Triple("\\botp\\b", 15, "Có nhắc đến mã OTP"),
        Triple("trung thuong|trung giai|nhan qua|qua tang|voucher mien phi", 35, "Lừa trúng thưởng"),
        Triple("viec nhe luong cao|lam nhiem vu|cong tac vien|hoa hong|chot don|tang don|tha tim|like video", 40, "Lừa tuyển việc làm online / nhiệm vụ"),
        Triple("ung tien coc|tien coc|phi nhan viec|dat coc nhan viec|dong phi truoc|viec lam.{0,30}(coc|phi)", 40, "Yêu cầu đặt cọc / đóng phí trước khi nhận việc"),
        Triple("dau tu|loi nhuan|sinh loi|tien ao|usdt|crypto|cam ket lai|forex", 30, "Lừa đầu tư sinh lời"),
        Triple("giai ngan|duyet vay|vay nhanh|lai suat 0|phi ho so", 25, "Lừa vay tiền / thu phí trước"),
        Triple("vneid|dich vu cong|bhxh|tien dien|cuoc vien thong|cuc thue|bien ban phat", 20, "Giả mạo dịch vụ công / tiện ích"),
        Triple("khan cap|ngay lap tuc|trong 24 gio|het han|neu khong se", 15, "Tạo áp lực thời gian"),
        Triple("chuyen khoan|chuyen tien|nap tien|dong phi", 15, "Yêu cầu chuyển tiền"),
        Triple("zalo\\.me|t\\.me/|telegram|lien he zalo|them zalo", 15, "Dẫn sang Zalo/Telegram"),
        Triple("bit\\.ly|tinyurl|cutt\\.ly|rebrand\\.ly|\\.xyz|\\.top|\\.vip|\\.club|\\.icu|\\.cc\\b|http://", 30, "Link rút gọn / tên miền đáng ngờ"),
        Triple("tai khoan an toan|phong toa|xac minh danh tinh|rua tien|duong day|vu an|dieu tra|ma so thue|can cuoc|cccd", 35, "Dọa liên quan vụ án / yêu cầu xác minh danh tính"),
        Triple("sim.*(bi khoa|het han)|thue bao.*(khoa|ngung)", 30, "Dọa khóa SIM"),
        Triple("anydesk|ultraviewer|teamviewer|chia se man hinh|cai ung dung|tai ung dung|cai dat app", 35, "Yêu cầu cài app / chia sẻ màn hình"),
        Triple("buu dien|buu cuc|giao hang|don hang|hoan tien|hoan phi|shipper", 20, "Giả giao hàng / hoàn tiền"),
        Triple("ngan hang|nhan vien|can bo|hop tac|giu bi mat|khong noi voi ai", 15, "Nhắc ngân hàng / yêu cầu giữ bí mật"),
        Triple("https?://|www\\.", 10, "Chứa đường link"),
    )

    // Compile once at first use; notification analysis may run frequently in the background.
    private val compiledRules: List<Triple<Regex, Int, String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        rules.map { Triple(Regex(it.first), it.second, it.third) }
    }

    fun analyze(text: String): Result {
        val t = norm(text)
        var score = 0
        val why = mutableListOf<String>()
        for ((re, pts, label) in compiledRules) if (re.containsMatchIn(t)) { score += pts; why += label }
        // Mô hình học máy TFLite: chỉ CỘNG điểm khi tin chắc là lừa đảo (tối đa +30), không bao giờ trừ điểm của bộ quy tắc
        val p = TextModel.predict(text)
        if (p != null && p >= 0.5f) {
            score += ((p - 0.5f) * 60f).toInt()
            why += "Mô hình học máy (TFLite): ${(p * 100).toInt()}% giống mẫu lừa đảo"
        }
        return Result(score.coerceAtMost(100), why)
    }
}
