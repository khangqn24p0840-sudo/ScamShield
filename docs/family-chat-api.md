# Chat gia đình — API contract

Chat gia đình dùng API riêng ở server repository `khangqn24p0840-sudo/serrverr`, thư mục `ScamShieldServer-Groq-useragent-fix/server`. Tất cả endpoint dưới đây yêu cầu `Authorization: Bearer <token>`. Server luôn lấy danh tính người gửi từ token, không nhận danh tính do client tự khai báo.

## Nhóm

- `GET /api/family/chats` → danh sách nhóm của tài khoản, số thành viên, tin mới nhất và số tin chưa đọc.
- `POST /api/family/chats`, body `{"name":"Gia đình mình"}` → tạo nhóm; người tạo có vai trò `owner`.
- `POST /api/family/chats/join`, body `{"invite_code":"AB12CD34"}` → tham gia nhóm bằng mã còn hiệu lực.

## Mã mời

- `POST /api/family/chats/{chat_id}/invite` → tạo mã mời mới và thu hồi mã cũ đang còn hiệu lực.
- `DELETE /api/family/chats/{chat_id}/invites` → thu hồi mã mời đang hoạt động.

Mã mời được lưu dưới dạng SHA-256 digest, có hiệu lực 7 ngày và tối đa 20 lượt tham gia. Chỉ `owner` và `admin` được tạo hoặc thu hồi mã. Mỗi nhóm chỉ có một mã đang hoạt động; mã đã thu hồi, hết hạn hoặc đủ lượt sẽ không thể tham gia.

## Thành viên và vai trò

- `GET /api/family/chats/{chat_id}/members` → danh sách thành viên, vai trò và cờ `mine`.
- `PATCH /api/family/chats/{chat_id}/members/{user_id}`, body `{"role":"admin"}` hoặc `{"role":"member"}` → chủ nhóm cấp/hạ quyền quản trị.
- `DELETE /api/family/chats/{chat_id}/members/{user_id}` → chủ nhóm hoặc quản trị viên xóa thành viên. Quản trị viên chỉ xóa được thành viên thường; không ai được xóa chủ nhóm qua endpoint này.
- `POST /api/family/chats/{chat_id}/leave` → thành viên tự rời nhóm. Nếu chủ nhóm rời, thành viên ở lại lâu nhất được chuyển thành chủ nhóm.
- `DELETE /api/family/chats/{chat_id}` → chỉ chủ nhóm được xóa nhóm.

Vai trò là `owner`, `admin` hoặc `member`. Quyền luôn được kiểm tra ở server; việc ẩn/hiện nút trên Android chỉ là giao diện, không thay thế kiểm soát truy cập.

## Tin nhắn và trạng thái đã xem

- `GET /api/family/chats/{chat_id}/messages?limit=50&after_id=0` → lấy tối đa 50 tin mới nhất để khởi tạo.
- `GET /api/family/chats/{chat_id}/messages?limit=50&after_id={last_message_id}` → chỉ lấy tin mới hơn con trỏ đã biết. Server trả thêm `read_receipts` cho 50 tin gần nhất do tài khoản hiện tại gửi.
- `POST /api/family/chats/{chat_id}/messages`, body `{"body":"Con về lúc 6 giờ"}` → lưu tin nhắn; giới hạn 2.000 ký tự.

Mỗi tin nhắn có `read_by_count` và `recipient_count`. Khi một thành viên tải tin nhắn, server cập nhật `last_read_message_id`; trạng thái đọc được tính từ con trỏ này. Client polling mỗi 6 giây nhưng chỉ tải tin mới, tránh thay toàn bộ lịch sử ở mỗi lượt. Đây vẫn là đồng bộ polling, chưa phải WebSocket tức thời.

Mọi thao tác đọc/ghi đều kiểm tra thành viên nhóm. Người ngoài nhóm nhận phản hồi 404 để không làm lộ sự tồn tại của nhóm.
