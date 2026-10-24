# Mô hình học máy TFLite của ScamShield

App có mô hình `app/src/main/assets/scam_text.tflite` (hồi quy logistic trên đặc trưng băm n-gram, 4096 chiều).
`TextModel.kt` nạp file này bằng TensorFlow Lite; `ScamDetector.analyze` cộng thêm điểm khi mô hình tin đó là lừa đảo.
Không có file hoặc nạp lỗi thì app tự chạy bằng bộ quy tắc như cũ (không crash).

- `build_starter_model.py` – tạo mô hình khởi động, không cần TensorFlow (đã chạy sẵn, kết quả nằm trong assets).
- `train_tflite.py` – huấn luyện bằng Keras với dữ liệu thật của bạn (CSV: content,label) rồi ghi đè file .tflite.
- `features.py` – cách biến chữ thành vector. PHẢI khớp `TextModel.kt`; sửa một bên thì sửa cả hai.
- `seed_data.py` – dữ liệu TỔNG HỢP để chạy thử, không dùng để báo cáo độ chính xác.
