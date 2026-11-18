# Theo dõi hội thoại theo ngữ cảnh

## Luồng xử lý

1. `SocialListener` chỉ đọc phần nội dung ứng dụng nhắn tin đưa vào thông báo Android. Nó không truy cập cơ sở dữ liệu riêng, màn hình chat hay toàn bộ lịch sử của Zalo/Messenger.
2. Trên Android 11 trở lên, nếu thông báo dùng `Notification.MessagingStyle`, ứng dụng tách các tin có người gửi và dấu thời gian. Android cũ hoặc ứng dụng không dùng kiểu thông báo này sẽ dùng nội dung văn bản thông báo làm phương án dự phòng.
3. `MonitorStore` lưu tin đã chọn trong SQLite cục bộ. Mỗi tin có `conversation_key` để truy vấn chuỗi gần nhất của cùng một cuộc trò chuyện. Nâng cấp schema v2 lên v3 được thực hiện tự động.
4. Bộ quy tắc và TFLite vẫn phân tích cục bộ khi thông báo đến.
5. Tự động phân tích Gemini mặc định tắt. Chỉ khi người dùng bật công tắc trong màn hình **Theo dõi chat** và xác nhận đồng ý, app mới gom tin theo thread, chờ khoảng 15 giây sau tin mới rồi gửi tối đa 12 tin gần nhất của đúng thread đã theo dõi. Có giới hạn 45 giây cho từng thread và 12 yêu cầu/phút ở server.
6. Server gọi Gemini trực tiếp qua endpoint riêng `POST /api/monitor/conversation`; không chuyển qua provider tự chọn và không thay đổi endpoint `/api/chat` của chatbot thường. Server không lưu transcript; app lưu điểm rủi ro/tóm tắt/bằng chứng/khuyến nghị cục bộ.
7. Nút phân tích thủ công trong Theo dõi chat vẫn hoạt động, dùng cùng endpoint và cùng thread gần nhất.

## Giao thức máy chủ

Ứng dụng dùng endpoint riêng `POST /api/monitor/conversation`:

```json
{
  "messages": [
    {"sender": "Người gửi", "text": "Nội dung", "timestamp": 1794900000000}
  ]
}
```

Endpoint yêu cầu đăng nhập, tối đa 12 tin/6.000 ký tự, có giới hạn tần suất và trả về điểm rủi ro, mức độ, tóm tắt, bằng chứng, khuyến nghị và độ tin cậy. Transcript không được lưu trên server.

Server hiện tại đọc `message` và `history` (mỗi mục lịch sử có `role` và `text`). Không gửi trường `messages` theo định dạng của một phiên bản server khác. Máy chủ phải có route `/api/chat`, tài khoản cần đăng nhập và nhà cung cấp AI/Gemini phải được cấu hình.

## Giới hạn và quyền riêng tư

- Nội dung mạng xã hội chỉ có thể được thu thập khi xuất hiện trong thông báo; không đảm bảo có tin nhắn đã gửi, tin bị ẩn, media, hoặc toàn bộ lịch sử.
- Khóa nhận diện thread ưu tiên shortcut ID, sau đó dùng tiêu đề hội thoại/tiêu đề thông báo. Nếu ứng dụng nguồn không cung cấp định danh ổn định, một số thread có thể vẫn bị gộp hoặc thiếu dữ liệu.
- Tự động cảnh báo vẫn xử lý trên thiết bị. Chỉ thao tác Gemini do người dùng chủ động xác nhận mới gửi transcript lên máy chủ và nhà cung cấp AI.
- Không gửi OTP, mật khẩu, mã khôi phục, số thẻ, thông tin đăng nhập hoặc dữ liệu riêng tư không cần thiết. Mô hình có thể sai; luôn xem kết quả là chỉ báo chứ không phải kết luận.


## Bộ nhớ ngữ cảnh xuyên suốt cuộc trò chuyện

- Mỗi cặp `target_id + conversation_key` có một bộ nhớ riêng lưu trong SQLite trên thiết bị.
- Bộ nhớ chỉ gồm tóm tắt diễn biến, sự kiện quan trọng, hành vi quan sát được và câu hỏi còn thiếu; không thay thế bản ghi tin nhắn gốc.
- Mỗi lần Gemini phân tích, app gửi bộ nhớ cũ cùng tối đa 12 tin nhắn mới nhất thuộc đúng thread đó. Gemini trả về bộ nhớ đã cập nhật cùng đánh giá rủi ro mới.
- Server không lưu nội dung hội thoại hoặc bộ nhớ lâu dài. Người dùng có thể tắt tự động phân tích; xóa đối tượng theo dõi sẽ xóa cả tin nhắn, kết quả phân tích và bộ nhớ liên quan.
- Bộ nhớ là dữ liệu tóm tắt hỗ trợ ngữ cảnh, không phải bằng chứng độc lập. Gemini phải đánh giá lại bằng chứng mới và hạ độ tin cậy khi thông báo thiếu tin nhắn/thiếu chiều hội thoại.
