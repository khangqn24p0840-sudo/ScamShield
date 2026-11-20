# Theo dõi hội thoại theo ngữ cảnh

## Luồng xử lý

1. `SocialListener` đọc phần nội dung mà ứng dụng nhắn tin công bố trong thông báo Android. Nó không truy cập cơ sở dữ liệu riêng hoặc toàn bộ lịch sử chat.
2. `ChatAccessibilityService` có thể đọc văn bản bong bóng tin đến đang hiển thị trong đúng cuộc trò chuyện đã chọn, sau khi người dùng đồng ý và bật quyền Trợ năng. Service không chụp ảnh màn hình, không đọc ô nhập hoặc trường mật khẩu.
3. `MonitorStore` lưu tin nhắn trong SQLite trên thiết bị, tách riêng theo `target_id + conversation_key`.
4. Bộ quy tắc và TFLite đánh giá cục bộ khi tin nhắn mới được thu thập.
5. Tự động đồng bộ/phân tích ngữ cảnh mặc định tắt. Khi người dùng đăng nhập, bật công tắc và xác nhận đồng ý, app gọi `POST /api/monitor/consent` để ghi nhận trạng thái. Khi có tin mới, app chờ khoảng 15 giây rồi gửi tối đa 12 tin mới nhất của đúng thread qua `POST /api/monitor/conversation`.
6. Payload có mã thread, ứng dụng nguồn, tiêu đề cuộc trò chuyện, người gửi, văn bản, thời điểm, loại nguồn và bộ nhớ tóm tắt. Một số OTP, mật khẩu, số điện thoại và dãy số dài được che trước khi gửi.
7. Server cập nhật trạng thái `processing` trước khi gọi nhà cung cấp AI đang cấu hình, sau đó lưu kết quả `completed` hoặc `error`. Trang quản trị có thể xem các tài khoản đã đồng ý, từng cuộc trò chuyện, nội dung tin nhắn và trạng thái phân tích; trang tự làm mới khoảng 3 giây khi đang mở.
8. Khi người dùng tắt công tắc, app gửi trạng thái `enabled=false`, hủy hàng đợi và dừng đồng bộ mới. Tin đã đồng bộ trước đó vẫn được lưu trên server thử nghiệm cho đến khi được quản lý/xóa riêng.
9. Phân tích thủ công trong màn hình Theo dõi chat vẫn dùng cùng endpoint.

## Giao thức máy chủ

`POST /api/monitor/consent` nhận `{"enabled": true|false}` và yêu cầu đăng nhập.

`POST /api/monitor/conversation` yêu cầu tài khoản đã bật đồng ý và nhận cấu trúc:

```json
{
  "target_id": "id-cua-doi-tuong-theo-doi",
  "conversation_key": "package|conversation",
  "conversation_title": "Tên cuộc trò chuyện",
  "app_package": "com.example.chat",
  "messages": [
    {
      "sender": "Người gửi",
      "text": "Nội dung đã lọc dữ liệu nhạy cảm",
      "timestamp": 1794900000000,
      "source": "screen"
    }
  ],
  "memory": {}
}
```

Server giới hạn 12 tin mỗi lượt, 6.000 ký tự, 12 yêu cầu/phút cho mỗi tài khoản và lưu tối đa 1.000 tin gần nhất cho mỗi thread. Phản hồi gồm `risk_score`, `summary`, `evidence`, `recommended_action`, `confidence` và `conversation_memory`. Endpoint này độc lập với chatbot thông thường `/api/chat`.

## Giới hạn và quyền riêng tư

- Mạng xã hội chỉ thu thập được nội dung thông báo và văn bản đang hiển thị được cung cấp qua cây giao diện Trợ năng. Tin cũ ngoài màn hình, media hoặc một số giao diện tùy biến có thể bị bỏ sót.
- Khóa thread dựa trên ứng dụng và định danh cuộc trò chuyện; nếu ứng dụng nguồn không cung cấp tiêu đề ổn định, một số thread có thể thiếu hoặc gộp dữ liệu.
- Nội dung chỉ được đồng bộ khi tài khoản đã bật đồng ý. Khi đồng bộ, nội dung có thể được xem bởi quản trị viên của máy chủ thử nghiệm. Người dùng nên chỉ bật cho cuộc trò chuyện mà họ có quyền theo dõi và đã thông báo cho người tham gia.
- Tắt đồng bộ ngăn các lần gửi mới nhưng không xóa dữ liệu đã đồng bộ. Quản trị viên cần xử lý việc xóa bản sao trên server riêng.
- Mô hình có thể sai; kết quả chỉ là chỉ báo và cần được kiểm chứng.
