"""
Huấn luyện mô hình phát hiện lừa đảo bằng TensorFlow/Keras rồi xuất .tflite cho app.
Chạy:  pip install tensorflow numpy scikit-learn
       python train_tflite.py --csv du_lieu.csv        # CSV có cột content,label (1 = lừa đảo, 0 = bình thường)
Kết quả: app/src/main/assets/scam_text.tflite (ghi đè bản khởi động) + in Precision/Recall/F1/FPR trên tập kiểm tra.
Đầu vào của mô hình = vector 4096 chiều từ features.py (giống hệt TextModel.kt trong app), đầu ra = xác suất lừa đảo 0..1.
Không có --csv thì dùng dữ liệu tổng hợp để chạy thử (đừng dùng số liệu đó để báo cáo).
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split

import seed_data
from build_starter_model import load_csv, metrics
from features import DIM, featurize_many

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "scam_text.tflite"

ap = argparse.ArgumentParser()
ap.add_argument("--csv")
ap.add_argument("--epochs", type=int, default=40)
a = ap.parse_args()

if a.csv:
    texts, labels = load_csv(a.csv)
else:
    texts, labels, _ = seed_data.generate()
if len(set(labels)) < 2 or len(texts) < 40:
    sys.exit("Cần ít nhất ~40 mẫu, gồm cả hai loại nhãn.")

X, y = featurize_many(texts), np.array(labels, dtype="float32")
Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.2, stratify=y, random_state=1)

model = tf.keras.Sequential([
    tf.keras.layers.Input(shape=(DIM,)),
    tf.keras.layers.Dense(1, activation="sigmoid", kernel_regularizer=tf.keras.regularizers.l2(1e-4)),
])
model.compile(optimizer=tf.keras.optimizers.Adam(0.02), loss="binary_crossentropy")
model.fit(Xtr, ytr, epochs=a.epochs, batch_size=32, verbose=0, validation_data=(Xte, yte))

print("Kiểm tra:", metrics(yte, (model.predict(Xte, verbose=0)[:, 0] >= 0.5).astype(int)))
model.fit(X, y, epochs=a.epochs, batch_size=32, verbose=0)           # bản cuối học trên toàn bộ dữ liệu

blob = tf.lite.TFLiteConverter.from_keras_model(model).convert()
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_bytes(blob)
print(f"Đã ghi {OUT} ({len(blob)} byte)")
