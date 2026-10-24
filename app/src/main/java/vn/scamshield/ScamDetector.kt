package vn.scamshield
import java.text.Normalizer

data class Result(val score: Int, val reasons: List<String>) {
    /** 0 = an toàn, 1 = đáng ngờ, 2 = nguy hiểm */
    val level get() = when { score >= 60 -> 2; score >= 30 -> 1; else -> 0 }
    /** Ngưỡng báo sớm cho cuộc gọi trực tiếp: vàng từ 15, đỏ từ 40 */
    val live get() = when { score >= 40 -> 2; score >= 15 -> 1; else -> 0 }
}

object ScamDetector {
    fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')

    private val rules = listOf(
        Triple("cong an|vien kiem sat|toa an|co quan dieu tra|bo cong an|lenh bat|trieu tap", 40, "Giả danh cơ quan chức năng"),
        Triple("tai khoan.*(khoa|dong bang|tam ngung|bi han che)|xac thuc.*tai khoan", 30, "Dọa khóa/đóng băng tài khoản"),
        Triple("\\botp\\b|ma xac thuc|ma xac minh|mat khau|so the|cvv", 30, "Hỏi mã OTP / thông tin nhạy cảm"),
        Triple("trung thuong|trung giai|nhan qua|qua tang|voucher mien phi", 35, "Lừa trúng thưởng"),
        Triple("viec nhe luong cao|lam nhiem vu|cong tac vien|hoa hong|chot don|tang don|tha tim|like video", 40, "Lừa tuyển việc làm online / nhiệm vụ"),
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

    fun analyze(text: String): Result {
        val t = norm(text)
        var score = 0
        val why = mutableListOf<String>()
        for ((re, pts, label) in rules) if (Regex(re).containsMatchIn(t)) { score += pts; why += label }
        // Mô hình học máy TFLite: chỉ CỘNG điểm khi tin chắc là lừa đảo (tối đa +30), không bao giờ trừ điểm của bộ quy tắc
        val p = TextModel.predict(text)
        if (p != null && p >= 0.5f) {
            score += ((p - 0.5f) * 60f).toInt()
            why += "Mô hình học máy (TFLite): ${(p * 100).toInt()}% giống mẫu lừa đảo"
        }
        return Result(score.coerceAtMost(100), why)
    }
}
