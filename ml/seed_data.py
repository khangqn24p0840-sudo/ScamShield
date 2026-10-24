"""
DỮ LIỆU MẪU TỔNG HỢP (do máy sinh từ khuôn câu) chỉ để chạy thử quy trình.
Không dùng số liệu đo trên tập này để báo cáo độ chính xác ngoài đời thực.
Muốn có kết quả đáng tin: tự thu tập tin nhắn thật, gắn nhãn vào file CSV (cột content,label) rồi chạy với --csv.
"""
import random

BANKS = ["Vietcombank", "BIDV", "Techcombank", "MB Bank", "Agribank", "VPBank", "ACB", "Sacombank"]
URLS = ["bit.ly/3xYz12", "vcb-xacthuc.top/login", "tinyurl.com/nh-update", "bidv-online.xyz", "cutt.ly/khoatk", "mbbank-vn.vip/ht"]
MONEY = ["2.000.000", "5 triệu", "15.000.000", "500.000", "20 triệu", "10.000.000"]
ORGS = ["Công an quận 1", "Viện kiểm sát nhân dân", "Cơ quan điều tra Bộ Công an", "Công an phường", "Tòa án nhân dân"]
CARRIERS = ["Viettel", "Vinaphone", "Mobifone"]
STORES = ["Shopee", "Lazada", "Tiki", "Giao Hàng Nhanh", "Viettel Post"]
NAMES = ["Lan", "Hùng", "Mai", "Tuấn", "Hà", "Minh", "Linh", "Nam"]
CODES = ["482913", "770215", "159364", "903318", "224571"]
TIMES = ["8h tối nay", "9h sáng mai", "chiều thứ bảy", "7h sáng", "cuối tuần này"]

# nhãn 1 = lừa đảo
SCAM = [
    "[{b}] Tài khoản của quý khách có giao dịch bất thường và sẽ bị khóa trong 24 giờ. Xác thực ngay tại {u}",
    "Quý khách vui lòng cung cấp mã OTP vừa gửi để hủy giao dịch nhầm {m}. Tuyệt đối không nói với ai",
    "Đây là {o}. Bạn liên quan đến một vụ án rửa tiền. Hãy chuyển tiền vào tài khoản an toàn để phục vụ điều tra và giữ bí mật",
    "Chúc mừng bạn đã trúng thưởng {m} từ chương trình tri ân. Nhấn {u} để nhận thưởng, hết hạn trong hôm nay",
    "Tuyển cộng tác viên làm nhiệm vụ thả tim, chốt đơn online, hoa hồng {m} mỗi ngày. Liên hệ Zalo ngay để nhận việc nhẹ lương cao",
    "Đầu tư tiền ảo USDT cam kết lợi nhuận 30% mỗi tuần, rút vốn bất cứ lúc nào. Tham gia nhóm Telegram để được hướng dẫn",
    "Hỗ trợ vay nhanh giải ngân trong 15 phút, lãi suất 0%, không cần thế chấp. Nộp phí hồ sơ {m} trước để duyệt vay",
    "[{c}] Thuê bao của quý khách sắp bị khóa do chưa chuẩn hóa thông tin. Truy cập {u} để xác minh ngay",
    "Bạn có đơn hàng {s} đang bị hoàn về. Vui lòng cài ứng dụng tại {u} và chia sẻ màn hình để nhân viên hỗ trợ nhận lại tiền",
    "Cục thuế thông báo bạn nợ thuế quá hạn, nộp phạt {m} qua {u} trong hôm nay nếu không sẽ bị xử lý",
    "Vneid của bạn bị đình chỉ. Hãy xác minh danh tính, cung cấp số CCCD và mật khẩu tại {u} để khôi phục",
    "Mẹ ơi con đổi số mới rồi, mẹ chuyển giúp con {m} gấp vào số tài khoản này, con đang cần, đừng nói với ba",
    "[{b}] Thẻ tín dụng của bạn đã được duyệt hạn mức {m}. Cung cấp số thẻ và mã CVV để kích hoạt ngay",
    "Cảnh báo: tiền điện tháng này chưa thanh toán, sẽ cắt điện sau 2 giờ. Thanh toán ngay tại {u}",
    "Anh/chị bị khiếu nại vì vi phạm giao thông, biên bản phạt {m}. Nộp phạt online tại {u} để tránh bị truy thu",
    "Bạn được chọn làm cộng tác viên của {s}, hoàn tiền {m} sau mỗi đơn. Nạp tiền đặt cọc trước để nhận nhiệm vụ đầu tiên",
    "Ngân hàng {b} thông báo: cài ngay ứng dụng bảo mật tại {u}, nếu không tài khoản sẽ bị phong tỏa vĩnh viễn",
    "Tôi là nhân viên {b}, tài khoản bạn bị lộ thông tin. Hãy đọc mã xác thực gửi về điện thoại để tôi khóa giúp, đừng cúp máy",
    "Bạn nhận được voucher quà tặng miễn phí trị giá {m}. Bấm {u} nhập thông tin thẻ để nhận quà",
    "Đây là {o}, yêu cầu bạn có mặt làm việc. Nếu không hợp tác chúng tôi sẽ ra lệnh bắt, hãy xác minh danh tính qua Zalo ngay",
    "Kính gửi khách hàng, tài khoản {b} của bạn đã được nâng cấp, đăng nhập {u} trong vòng 1 giờ để tránh bị đóng băng",
    "Làm việc tại nhà lương {m}/ngày chỉ cần điện thoại, nhắn Telegram ngay để được hướng dẫn, ưu tiên người cần việc gấp",
]

# nhãn 0 = bình thường (gồm cả tin thật trông giống lừa đảo: OTP thật, thông báo ngân hàng thật...)
BENIGN = [
    "Mã OTP của bạn là {k}, có hiệu lực trong 5 phút. Tuyệt đối không chia sẻ mã này cho bất kỳ ai, kể cả nhân viên {b}",
    "[{b}] TK 0123456789 vừa nhận +{m} VND lúc 14:05. Nội dung: {n} chuyển tiền ăn trưa",
    "[{b}] Giao dịch rút tiền tại ATM thành công, số dư khả dụng đã được cập nhật. Cảm ơn quý khách đã sử dụng dịch vụ",
    "Đơn hàng của bạn từ {s} đã được giao thành công. Cảm ơn bạn đã mua sắm, đừng quên đánh giá sản phẩm nhé",
    "{s}: shipper sẽ giao đơn hàng của bạn vào {t}. Vui lòng giữ điện thoại và chuẩn bị tiền mặt nếu thanh toán khi nhận",
    "[{c}] Quý khách đã đăng ký thành công gói data 5G, hiệu lực 30 ngày. Soạn TD gửi 191 để kiểm tra dung lượng",
    "Chào {n}, tối nay {t} mình đi ăn lẩu nhé, mấy đứa trong nhóm đều đi được hết rồi",
    "Mẹ nhớ mua giúp con ít rau và trứng nhé, con về lúc 6h chiều, tối nay cả nhà nấu cơm ở nhà",
    "Nhắc lịch họp nhóm đồ án vào {t} tại phòng B203, các bạn nhớ mang laptop và chuẩn bị phần báo cáo của mình",
    "Điện lực thông báo lịch ngừng cấp điện để bảo trì lưới điện khu vực của bạn vào {t}, mong quý khách thông cảm",
    "Bạn có một cuộc gọi nhỡ từ {n}. Hãy gọi lại khi tiện nhé",
    "Cảm ơn bạn đã đặt bàn tại nhà hàng, bàn của bạn được giữ đến {t}. Hẹn gặp bạn sớm",
    "Thầy nhắc cả lớp nộp bài tập tuần này trước {t} qua email của lớp, bài nộp muộn sẽ bị trừ điểm",
    "Chương trình khuyến mãi cuối tuần: giảm 20% cho đơn từ 300.000đ tại cửa hàng, áp dụng đến hết {t}",
    "Hóa đơn tiền internet tháng này của bạn là 220.000đ, hạn thanh toán trước ngày 25. Thanh toán tại quầy hoặc ứng dụng chính thức",
    "Alo {n} ơi, cuối tuần này về quê không con? Ba mẹ nhớ con lắm, về sớm ăn cơm nhé",
    "Lịch khám của bạn tại phòng khám đã được xác nhận vào {t}. Vui lòng mang theo thẻ bảo hiểm y tế",
    "[{b}] Thẻ của bạn vừa được dùng thanh toán {m} tại siêu thị. Nếu không phải bạn, hãy gọi số tổng đài in mặt sau thẻ",
    "{n} ơi gửi mình file báo cáo hôm qua với, mình cần nộp cho sếp trước chiều nay",
    "Mã xác thực đăng nhập của bạn là {k}. Nếu không phải bạn yêu cầu, hãy bỏ qua tin nhắn này",
    "Chúc mừng sinh nhật {n}! Chúc bạn tuổi mới thật nhiều niềm vui, sức khỏe và thành công trong công việc",
    "Xe buýt tuyến 01 sẽ đổi lộ trình từ ngày mai do sửa đường, quý khách xem chi tiết trên bảng tin của bến xe",
    "Tin nhắn từ thư viện: sách bạn mượn sẽ đến hạn trả vào {t}, bạn có thể gia hạn trực tuyến trên trang chính thức của trường",
    "Anh ơi em gửi anh báo giá lắp điều hòa rồi nhé, nếu ổn anh nhắn em hẹn lịch qua lắp {t}",
]

VERB_PAD = ["", " ", " Cảm ơn.", " Trân trọng.", " Xin cảm ơn quý khách."]


def _fill(t, rnd):
    return t.format(b=rnd.choice(BANKS), u=rnd.choice(URLS), m=rnd.choice(MONEY), o=rnd.choice(ORGS), c=rnd.choice(CARRIERS),
                    s=rnd.choice(STORES), n=rnd.choice(NAMES), k=rnd.choice(CODES), t=rnd.choice(TIMES)) + rnd.choice(VERB_PAD)


def generate(per_template=14, seed=7):
    """Trả về (texts, labels, group_ids). group_id = khuôn câu gốc, để chia tập kiểm tra theo khuôn (tránh rò rỉ)."""
    rnd = random.Random(seed)
    texts, labels, groups = [], [], []
    for label, templates in ((1, SCAM), (0, BENIGN)):
        for gi, t in enumerate(templates):
            for _ in range(per_template):
                texts.append(_fill(t, rnd))
                labels.append(label)
                groups.append(f"{label}-{gi}")
    return texts, labels, groups
