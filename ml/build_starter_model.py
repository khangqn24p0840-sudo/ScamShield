"""
Tạo mô hình KHỞI ĐỘNG scam_text.tflite mà KHÔNG cần TensorFlow:
  huấn luyện hồi quy logistic bằng scikit-learn -> ghi thẳng file .tflite (FULLY_CONNECTED + LOGISTIC).
Chạy:  python build_starter_model.py [--csv du_lieu.csv]
Cần:   pip install numpy scikit-learn flatbuffers

Mô hình này rất nhỏ (~16 KB). Khi có dữ liệu thật và máy cài được TensorFlow, hãy dùng train_tflite.py cho bản chính thức.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import flatbuffers
import numpy as np
from sklearn.linear_model import LogisticRegression

import seed_data
from features import DIM, featurize_many

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "scam_text.tflite"


def load_csv(path):
    texts, labels = [], []
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            t, l = (row.get("content") or "").strip(), str(row.get("label", "")).strip()
            if t and l in ("0", "1"):
                texts.append(t)
                labels.append(int(l))
    return texts, labels


def split(texts, labels, groups):
    """Giữ riêng ~20% KHUÔN CÂU (hoặc mẫu CSV) làm tập kiểm tra để đo trên dữ liệu mô hình chưa thấy."""
    uniq = sorted(set(groups))
    test_groups = {g for i, g in enumerate(uniq) if i % 5 == 4}
    tr = [i for i, g in enumerate(groups) if g not in test_groups]
    te = [i for i, g in enumerate(groups) if g in test_groups]
    return tr, te


def metrics(y, p):
    y, p = np.array(y), np.array(p)
    tp, fp = int(((p == 1) & (y == 1)).sum()), int(((p == 1) & (y == 0)).sum())
    fn, tn = int(((p == 0) & (y == 1)).sum()), int(((p == 0) & (y == 0)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    return dict(n=len(y), tp=tp, fp=fp, fn=fn, tn=tn, precision=round(prec, 3), recall=round(rec, 3),
                f1=round(2 * prec * rec / (prec + rec), 3) if prec + rec else 0.0, fpr=round(fp / (fp + tn), 3) if fp + tn else 0.0)


# ------------------------------------------------------------ ghi file .tflite bằng tay (lược đồ TFLite v3)
FULLY_CONNECTED, LOGISTIC = 9, 14


def build_tflite(w, bias):
    b = flatbuffers.Builder(1 << 15)

    def ivec(vals):
        b.StartVector(4, len(vals), 4)
        for v in reversed(vals):
            b.PrependInt32(v)
        return b.EndVector()

    def tvec(offs):
        b.StartVector(4, len(offs), 4)
        for o in reversed(offs):
            b.PrependUOffsetTRelative(o)
        return b.EndVector()

    def buffer(data):
        if data is None:
            b.StartObject(1)
            return b.EndObject()
        raw = np.asarray(data, dtype="<f4").tobytes()
        b.StartVector(1, len(raw), 1)
        for byte in reversed(raw):
            b.PrependByte(byte)
        vec = b.EndVector()
        b.StartObject(1)
        b.PrependUOffsetTRelativeSlot(0, vec, 0)
        return b.EndObject()

    buffers = [buffer(None), buffer(w.reshape(1, -1)), buffer(bias)]

    def tensor(shape, buf, name):
        sh, nm = ivec(shape), b.CreateString(name)
        b.StartObject(8)
        b.PrependUOffsetTRelativeSlot(0, sh, 0)
        b.PrependUint32Slot(2, buf, 0)       # kiểu mặc định = FLOAT32
        b.PrependUOffsetTRelativeSlot(3, nm, 0)
        return b.EndObject()

    tensors = [tensor([1, DIM], 0, "input"), tensor([1, DIM], 1, "fc/weights"), tensor([1], 2, "fc/bias"),
               tensor([1, 1], 0, "fc/logit"), tensor([1, 1], 0, "scam_probability")]

    def opcode(code):
        b.StartObject(4)
        b.PrependInt8Slot(0, code, 0)
        b.PrependInt32Slot(2, 1, 1)
        b.PrependInt32Slot(3, code, 0)
        return b.EndObject()

    codes = [opcode(FULLY_CONNECTED), opcode(LOGISTIC)]

    b.StartObject(4)                         # FullyConnectedOptions: toàn mặc định (không kích hoạt thêm)
    fc_opts = b.EndObject()

    def operator(idx, ins, outs, opt_type=0, opts=None):
        i, o = ivec(ins), ivec(outs)
        b.StartObject(9)
        b.PrependUint32Slot(0, idx, 0)
        b.PrependUOffsetTRelativeSlot(1, i, 0)
        b.PrependUOffsetTRelativeSlot(2, o, 0)
        if opts is not None:
            b.PrependUint8Slot(3, opt_type, 0)
            b.PrependUOffsetTRelativeSlot(4, opts, 0)
        return b.EndObject()

    ops = [operator(0, [0, 1, 2], [3], 8, fc_opts), operator(1, [3], [4])]
    t_vec, i_vec, o_vec, op_vec, name = tvec(tensors), ivec([0]), ivec([4]), tvec(ops), b.CreateString("main")
    b.StartObject(5)
    b.PrependUOffsetTRelativeSlot(0, t_vec, 0)
    b.PrependUOffsetTRelativeSlot(1, i_vec, 0)
    b.PrependUOffsetTRelativeSlot(2, o_vec, 0)
    b.PrependUOffsetTRelativeSlot(3, op_vec, 0)
    b.PrependUOffsetTRelativeSlot(4, name, 0)
    sub = b.EndObject()
    sg_vec, oc_vec, bf_vec, desc = tvec([sub]), tvec(codes), tvec(buffers), b.CreateString("ScamShield text classifier (logistic regression)")
    b.StartObject(8)
    b.PrependUint32Slot(0, 3, 0)
    b.PrependUOffsetTRelativeSlot(1, oc_vec, 0)
    b.PrependUOffsetTRelativeSlot(2, sg_vec, 0)
    b.PrependUOffsetTRelativeSlot(3, desc, 0)
    b.PrependUOffsetTRelativeSlot(4, bf_vec, 0)
    model = b.EndObject()
    b.Finish(model, b"TFL3")
    return bytes(b.Output())


def self_check(blob, w, bias, X):
    """Đọc ngược file vừa ghi, kiểm tra cấu trúc và chạy lại phép tính để so với scikit-learn."""
    from flatbuffers import encode, number_types as N, table
    assert blob[4:8] == b"TFL3", "thiếu định danh TFL3"
    root = table.Table(blob, encode.Get(N.UOffsetTFlags.packer_type, blob, 0))

    def vec(t, slot):
        o = t.Offset(4 + 2 * slot)
        return (t.Vector(o), t.VectorLen(o)) if o else (0, 0)

    def sub_table(t, slot, i):
        base, _ = vec(t, slot)
        return table.Table(blob, t.Indirect(base + 4 * i))

    codes = [sub_table(root, 1, i).Get(N.Int32Flags, sub_table(root, 1, i).Offset(10) + sub_table(root, 1, i).Pos) for i in range(vec(root, 1)[1])]
    assert codes == [FULLY_CONNECTED, LOGISTIC], codes
    sg = sub_table(root, 2, 0)
    assert vec(sg, 0)[1] == 5 and vec(sg, 3)[1] == 2
    wbuf = sub_table(root, 4, 1)
    base, n = vec(wbuf, 0)
    w_back = np.frombuffer(bytes(blob[base:base + n]), dtype="<f4")
    assert w_back.shape[0] == DIM and np.allclose(w_back, w.astype("f4")), "trọng số ghi sai"
    z = X @ w_back + float(bias[0])
    return 1 / (1 + np.exp(-z))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", help="file CSV có cột content,label (1 = lừa đảo, 0 = bình thường)")
    a = ap.parse_args()
    if a.csv:
        texts, labels = load_csv(a.csv)
        groups = [str(i) for i in range(len(texts))]
        src = f"CSV {a.csv} ({len(texts)} mẫu)"
    else:
        texts, labels, groups = seed_data.generate()
        src = "dữ liệu TỔNG HỢP (chỉ để chạy thử)"
    if len(set(labels)) < 2:
        sys.exit("Cần cả mẫu lừa đảo và mẫu bình thường.")
    X = featurize_many(texts)
    y = np.array(labels)
    tr, te = split(texts, labels, groups)
    clf = LogisticRegression(C=4.0, max_iter=2000).fit(X[tr], y[tr])
    m = metrics(y[te], clf.predict(X[te]))
    print(f"Nguồn dữ liệu: {src}\nKiểm tra trên phần giữ riêng: {m}")
    clf = LogisticRegression(C=4.0, max_iter=2000).fit(X, y)           # bản cuối học trên toàn bộ dữ liệu
    w, bias = clf.coef_[0].astype("f4"), clf.intercept_.astype("f4")
    blob = build_tflite(w, bias)
    p_ref = self_check(blob, w, bias, X[:50])
    assert np.allclose(p_ref, clf.predict_proba(X[:50])[:, 1], atol=1e-4), "kết quả không khớp scikit-learn"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(blob)
    (OUT.parent.parent.parent.parent.parent / "ml" / "starter_metrics.json").write_text(
        json.dumps(dict(source=src, holdout=m), ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Đã ghi {OUT} ({len(blob)} byte). Tự kiểm tra cấu trúc + phép tính: OK")


if __name__ == "__main__":
    main()
