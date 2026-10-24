# ScamShield VN 4.1 — Nhật ký làm mới giao diện

Bản 4.1 làm mới toàn bộ giao diện theo phong cách **"Navy tinh tế"**: nền navy sâu,
thẻ slate viền mảnh, điểm nhấn vàng gradient + xanh ngọc, icon vector Material,
animation mượt và thanh điều hướng nổi dạng pill. **Toàn bộ logic, quyền, service
và API giữ nguyên 100%** — chỉ thay đổi lớp giao diện.

## 1. Thay đổi cấu trúc file

| File | Thay đổi |
|------|----------|
| `app/build.gradle.kts` | Thêm `androidx.compose.material:material-icons-extended` (quản lý phiên bản qua BOM sẵn có). Bump versionCode 6, versionName 4.1 |
| `Theme.kt` | Viết lại: bảng màu mới, bộ component dùng chung (thẻ, nút gradient, ô nhập focus, chip, badge, pulse…) |
| `Screens.kt` | Viết lại: màn đăng nhập, khung chính, thanh điều hướng nổi, trang chủ, tìm kiếm |
| `Features.kt` | Viết lại: khung trang con, thẻ kết quả, nhật ký, tin nhắn, cài đặt, SĐT lừa đảo, Zalo/Messenger, mã độc & link, nghe cuộc gọi, thống kê, khác, tài khoản |
| `Community.kt` | Làm mới giao diện bảng tin (giữ nguyên toàn bộ logic dữ liệu) |
| `Extras.kt` | Làm mới panel khảo sát / AI / video-call giả mạo |
| `MainActivity.kt` | Bổ sung `darkColorScheme` thương hiệu + icon thanh trạng thái màu sáng |

## 2. Design system mới (Theme.kt)

- **Bảng màu `C`**: nền `#0A1128 → #13203F`, thẻ `#16224A` viền trắng 15%, vàng `#FFC93C`,
  xanh ngọc `#34D399`, cyan `#22D3EE` (Cộng đồng), đỏ `#F87171`, vàng `#FBBF24`.
  Các tên màu cũ (Brown/Tile/Label/Teal/Blob/LoginBg) được giữ làm alias để tương thích.
- **`CardX`**: thẻ slate bo 22dp, viền mảnh — thay cho thẻ nâu cũ.
- **`GoldButton`**: gradient vàng ngang, bo 16dp, cao 52dp, hỗ trợ icon đầu dòng.
- **`GhostButton` / `DangerButton`**: nút phụ viền mảnh / nút dừng màu đỏ.
- **`PillField`**: ô nhập tối, icon đầu dòng, **viền sáng vàng khi focus**, con trỏ vàng.
- **`ShieldBadge`**: khiên tròn gradient có quầng sáng radial + hiệu ứng pulse.
- **`IconBadge`, `StatusPill`, `SelChip`, `SectionHeader`, `StatCard`, `TipBanner`, `ScoreBar`**.
- **`Pressable`**: co nhẹ 0.96 khi nhấn. **`Modifier.pulse()`**: phồng/xẹp lặp vô hạn.

## 3. Các màn hình

### Đăng nhập
- 3 quầng sáng radial trang trí (mint/tím/xanh dương) trên nền navy.
- Khiên vàng phát sáng pulse + tên app + slogan.
- Form trong thẻ kính bo 24dp; ô nhập có icon (máy chủ, người dùng, email, khóa).
- Nút "Đăng nhập/Đăng ký" gradient vàng; chuyển qua lại đăng ký/đăng nhập bằng link cyan.

### Thanh điều hướng
- **Thanh nổi pill** bo 33dp, gradient, bóng đổ, nổi trên nội dung.
- **Nút khiên tròn vàng phát sáng** ở giữa cho tab "Kiểm tra", nhô 14dp lên trên.
- Mục được chọn chuyển vàng có animation màu (animateColorAsState).

### Trang chủ
- Header: lời chào cá nhân hóa theo tên + avatar vòng gradient vàng + dòng trạng thái bảo vệ.
- **Thẻ trạng thái hero**: gradient đổi màu theo trạng thái (xanh ngọc khi bảo vệ đầy đủ,
  cam khi còn thiếu, đỏ sẫm khi chưa bật), khiên pulse, 2 chip trạng thái, nút bật từng tầng
  có AnimatedVisibility, link "Xem nhật ký" khi đã bảo vệ đủ.
- **Hàng số liệu nhanh** (mới): Đã chặn / Đã cảnh báo / Số bị chặn.
- Danh mục 8 ô gradient màu riêng từng chủ đề (icon vector, co nhẹ khi nhấn).
- Đề xuất cuộn ngang 4 thẻ gradient; banner mẹo an toàn viền vàng.
- "Cảnh báo nổi bật" từ Cộng đồng (polling 10s như cũ).

### Các trang con
- `Page`: nút back tròn viền mảnh + tiêu đề lớn; hỗ trợ imePadding cho màn nhập liệu.
- `ResultCard`: icon tròn theo mức độ, **thanh điểm rủi ro chạy animation**, danh sách lý do.
- Nhật ký: hàng có thanh màu bên trái (đỏ=chặn, vàng=cảnh báo) + chip lọc.
- Cài đặt: nhóm thẻ có IconBadge, hàng cài đặt kèm pill Bật/Tắt, switch vàng.
- Mã độc & link: thanh điểm cho từng app đáng ngờ, nút mở cài đặt để gỡ.
- Nghe cuộc gọi: chấm trạng thái đỏ khi đang nghe, nút bắt đầu (vàng) / dừng (đỏ).
- Thống kê: 3 ô số liệu + biểu đồ thanh ngang có animation.
- Tài khoản: thẻ hồ sơ nền sáng hơn, icon Verified, ô nhập tên mới, nút đăng xuất/xóa có icon.

### Cộng đồng
- Thẻ bài đăng viền mảnh, chip chủ đề màu, dot mức độ (thấp=ngọc, tb=vàng, cao=đỏ),
  icon like/bình luận vector, nút FAB "Báo cáo" gradient cam phát sáng.
- Chi tiết bài: nút pill "Hữu ích", "Báo cáo", "Xóa bài"; bong bóng bình luận viền mảnh.

## 4. Animation

- Chuyển tab: `Crossfade` 230ms.
- Nút bật bảo vệ: `AnimatedVisibility` fade + expand.
- Khiên: pulse vô hạn khi hoạt động.
- Nhấn thẻ/ô danh mục: co nhẹ 0.96.
- Thanh điểm (kết quả, thống kê, app quét): chạy tới giá trị 650ms.
- Chọn tab: màu icon/chữ chuyển mượt 220ms.

## 5. Ghi chú kỹ thuật

- Icon: bộ **Material Icons Rounded** qua `material-icons-extended` — đồng nhất mọi máy,
  thay emoji. Đã dùng 53 icon, đều tồn tại trong thư viện phiên bản theo BOM 2024.06.00.
- MaterialTheme dark: primary vàng, secondary xanh ngọc, surface navy — mọi control M3
  (OutlinedTextField, Switch, RadioButton, AlertDialog…) tự ăn theo bảng màu thương hiệu.
- Không đổi: `Hooks`, `Store`, `Api`, `Config`, `ScamDetector`, `Alerter`, các service nền,
  AndroidManifest, quyền. Bản dựng cũ cài đè trực tiếp được (cùng applicationId).

## 6. Cách dựng

1. Mở thư mục `ScamShield` bằng Android Studio (Hedgehog/Koala trở lên).
2. Chờ Gradle sync (lần đầu tải thêm `material-icons-extended`).
3. `Build > Generate Signed APK` hoặc chạy trực tiếp `Run`.

## 4.2 — Chỉnh theo góp ý
- Trang chủ: "Cảnh báo nổi bật" chuyển xuống dưới Danh mục và Đề xuất (Screens.kt).
- Viền cảnh báo đỏ/vàng (Alerter.kt): thay viền đặc dày bằng viền glow mờ dần vào trong, bo góc, nhấp nháy chậm và êm hơn, màu dịu hơn.
- Tin báo (Community.kt): bấm vào mở màn đọc trong app (`NewsReader`), nút "Tìm hiểu thêm" mới mở trang báo gốc.
- Hình nền (Theme.kt `AppBackground`): gradient navy sâu + 3 quầng sáng (tím/xanh ngọc/vàng) trôi chậm + lưới chấm mờ + hai cặp hình khiên mờ. Dùng cho toàn app và màn đọc tin.

## 4.3
- Thanh dưới còn 4 mục + nút Kiểm tra ở giữa (Trang chủ, Cài đặt | Kiểm tra | Cộng đồng, Tài khoản). Bỏ tab Nhật ký khỏi thanh để không chồng lên nút Kiểm tra; Nhật ký nay là một dòng trong tab Tài khoản (mở màn "all").
- Nút Kiểm tra: bỏ shadow màu (bị vẽ thành khối vuông trên một số máy), thay bằng quầng sáng vàng vẽ tay.
- Nền: lưới chấm vẽ bằng drawPoints (bản trước bị vẽ thành đường kẻ ngang).

## 4.4
- Tách bài người dùng và tin báo chí: tab Cộng đồng có 2 mục "Cộng đồng" | "Tin báo chí" (nút đăng bài chỉ hiện ở mục Cộng đồng); Trang chủ có 2 khối riêng, mỗi khối "Xem tất cả" mở đúng mục. Thẻ tin báo viền xanh dương, nhãn TIN BÁO.
- Màn đọc tin hiện "Ý CHÍNH CỦA BÀI" dạng gạch đầu dòng (cần server đã chạy patch_server.py).

## 4.5 — Mô hình học máy TFLite
- Thêm TensorFlow Lite (build.gradle.kts), `TextModel.kt` nạp assets/scam_text.tflite, `ScamApp.kt` khởi tạo sớm, khai báo trong Manifest.
- `ScamDetector.analyze`: bộ quy tắc như cũ + mô hình TFLite chỉ CỘNG tối đa 30 điểm khi p >= 0.5 (không trừ điểm). Thiếu/lỗi mô hình thì chạy như cũ.
- Thư mục `ml/`: tạo mô hình khởi động (không cần TensorFlow), huấn luyện bản chính bằng Keras với CSV của bạn, đặc trưng dùng chung với app.
