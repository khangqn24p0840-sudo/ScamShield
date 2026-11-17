# Theo dõi hội thoại theo ngữ cảnh

## Luồng xử lý

1. `SocialListener` chỉ đọc phần nội dung ứng dụng nhắn tin đưa vào thông báo Android. Nó không truy cập cơ sở dữ liệu riêng, màn hình chat hay toàn bộ lịch sử của Zalo/Messenger.
2. Trên Android 11 trở lên, nếu thông báo dùng `Notification.MessagingStyle`, ứng dụng tách các tin có người gửi và dấu thời gian. Android cũ hoặc ứng dụng không dùng kiểu thông báo này sẽ dùng nội dung văn bản thông báo làm phương án dự phòng.
3. `MonitorStore` lưu tin đã chọn trong SQLite cục bộ. Mỗi tin có `conversation_key` để truy vấn chuỗi gần nhất của cùng một cuộc trò chuyện. Nâng cấp schema v2 lên v3 được thực hiện tự động.
4. Bộ quy tắc và TFLite vẫn phân tích cục bộ khi thông báo đến. Gemini không được gọi tự động ở nền.
5. Khi người dùng bấm **Phân tích hội thoại bằng Gemini** và xác nhận, ứng dụng lấy tối đa 10 tin mới nhất của thread gần nhất, giới hạn transcript ở khoảng 1.650 ký tự, rồi gửi tới API đã đăng nhập.

## Giao thức máy chủ

Ứng dụng dùng hợp đồng hiện có của `POST /api/chat`:

```json
{
  "message": "Phân tích hội thoại sau ...",
  "history": []
}
```

Server hiện tại đọc `message` và `history` (mỗi mục lịch sử có `role` và `text`). Không gửi trường `messages` theo định dạng của một phiên bản server khác. Máy chủ phải có route `/api/chat`, tài khoản cần đăng nhập và nhà cung cấp AI/Gemini phải được cấu hình.

## Giới hạn và quyền riêng tư

- Nội dung mạng xã hội chỉ có thể được thu thập khi xuất hiện trong thông báo; không đảm bảo có tin nhắn đã gửi, tin bị ẩn, media, hoặc toàn bộ lịch sử.
- Khóa nhận diện thread ưu tiên shortcut ID, sau đó dùng tiêu đề hội thoại/tiêu đề thông báo. Nếu ứng dụng nguồn không cung cấp định danh ổn định, một số thread có thể vẫn bị gộp hoặc thiếu dữ liệu.
- Tự động cảnh báo vẫn xử lý trên thiết bị. Chỉ thao tác Gemini do người dùng chủ động xác nhận mới gửi transcript lên máy chủ và nhà cung cấp AI.
- Không gửi OTP, mật khẩu, mã khôi phục, số thẻ, thông tin đăng nhập hoặc dữ liệu riêng tư không cần thiết. Mô hình có thể sai; luôn xem kết quả là chỉ báo chứ không phải kết luận.
