"""
Đặc trưng văn bản cho mô hình TFLite phát hiện lừa đảo.
PHẢI GIỐNG HỆT bản Kotlin trong app (TextModel.kt): nếu sửa ở đây thì sửa cả bên đó.

  1. cắt 2000 ký tự đầu, chữ thường, bỏ dấu tiếng Việt (đ -> d)
  2. tách từ bằng [a-z0-9]+ ; đặc trưng = từ đơn "u:<từ>" và cặp từ liền kề "b:<từ1> <từ2>"
  3. băm FNV-1a 32 bit (UTF-8) rồi lấy phần dư theo DIM -> chỉ số
  4. giá trị = log(1 + số lần xuất hiện), chuẩn hóa L2
"""
import math
import re
import unicodedata

import numpy as np

DIM = 4096
_TOKEN = re.compile(r"[a-z0-9]+")


def norm(text):
    s = unicodedata.normalize("NFD", str(text)[:2000].lower())
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    return s.replace("đ", "d")


def fnv1a(s):
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def featurize(text):
    words = _TOKEN.findall(norm(text))
    counts = {}
    for i, w in enumerate(words):
        for f in ("u:" + w, ("b:" + w + " " + words[i + 1]) if i + 1 < len(words) else None):
            if f:
                k = fnv1a(f) % DIM
                counts[k] = counts.get(k, 0) + 1
    x = np.zeros(DIM, dtype=np.float32)
    for k, c in counts.items():
        x[k] = math.log1p(c)
    n = float(np.sqrt(np.sum(x * x)))
    return x / n if n > 0 else x


def featurize_many(texts):
    return np.stack([featurize(t) for t in texts]) if len(texts) else np.zeros((0, DIM), np.float32)
