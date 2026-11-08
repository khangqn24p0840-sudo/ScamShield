#!/usr/bin/env python3
"""Train a face-crop Deepfake classifier and export float32 TFLite.

CSV columns: image_path,label,video_id. Labels: real/fake.
video_id identifies the source video; frames from one video stay in one split.
"""
import argparse, json, random
from pathlib import Path
import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit

SEED, SIZE, BATCH = 42, 224, 32

def load_manifest(path):
    df = pd.read_csv(path)
    required = {"image_path", "label", "video_id"}
    if required - set(df.columns):
        raise ValueError(f"Missing columns: {sorted(required - set(df.columns))}")
    df = df.copy()
    df["label"] = df["label"].astype(str).str.strip().str.lower()
    if not set(df["label"]).issubset({"real", "fake"}):
        raise ValueError("label must be real or fake")
    df["y"] = (df["label"] == "fake").astype("float32")
    df["image_path"] = df["image_path"].map(lambda p: str((path.parent / str(p)).resolve()))
    missing = [p for p in df.image_path if not Path(p).is_file()]
    if missing:
        raise FileNotFoundError(f"{len(missing)} images missing; first: {missing[0]}")
    if df.video_id.nunique() < 5 or df.y.nunique() != 2:
        raise ValueError("Need both labels and at least 5 distinct source videos.")
    return df

def split_groups(df):
    s1 = GroupShuffleSplit(n_splits=1, test_size=.20, random_state=SEED)
    tr, hold = next(s1.split(df, df.y, groups=df.video_id))
    train, rest = df.iloc[tr], df.iloc[hold]
    s2 = GroupShuffleSplit(n_splits=1, test_size=.50, random_state=SEED+1)
    va, te = next(s2.split(rest, rest.y, groups=rest.video_id))
    return train, rest.iloc[va], rest.iloc[te]

def make_ds(df, training):
    ds = tf.data.Dataset.from_tensor_slices((df.image_path.astype(str).values, df.y.values.astype("float32")))
    def load(path, label):
        image = tf.io.decode_image(tf.io.read_file(path), channels=3, expand_animations=False)
        image.set_shape([None, None, 3])
        image = tf.cast(tf.image.resize(image, [SIZE, SIZE]), tf.float32)
        # EfficientNetB0 contains its own rescaling; input stays in [0,255].
        return image, tf.reshape(label, [1])
    ds = ds.map(load, num_parallel_calls=tf.data.AUTOTUNE)
    if training:
        ds = ds.shuffle(min(len(df), 4096), seed=SEED)
    return ds.batch(BATCH).prefetch(tf.data.AUTOTUNE)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True, type=Path)
    ap.add_argument("--output", type=Path, default=Path("deepfake_face.tflite"))
    ap.add_argument("--epochs", type=int, default=8)
    args = ap.parse_args()
    random.seed(SEED); np.random.seed(SEED); tf.random.set_seed(SEED)
    df = load_manifest(args.manifest)
    train, val, test = split_groups(df)
    for name, part in (("train", train), ("validation", val), ("test", test)):
        print(f"{name}: {len(part)} frames / {part.video_id.nunique()} videos; labels={part.y.value_counts().to_dict()}")
        if part.y.nunique() != 2:
            raise ValueError(f"{name} split lacks one class; add source videos.")
    base = tf.keras.applications.EfficientNetB0(include_top=False, weights="imagenet", input_shape=(SIZE,SIZE,3))
    base.trainable = False
    inp = tf.keras.Input((SIZE,SIZE,3), name="rgb_0_255")
    x = tf.keras.layers.GlobalAveragePooling2D()(base(inp, training=False))
    x = tf.keras.layers.Dropout(.30)(x)
    out = tf.keras.layers.Dense(1, activation="sigmoid", name="fake_probability")(x)
    model = tf.keras.Model(inp, out)
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="binary_crossentropy",
                  metrics=[tf.keras.metrics.AUC(name="auc"), "accuracy"])
    model.fit(make_ds(train, True), validation_data=make_ds(val, False), epochs=args.epochs,
              callbacks=[tf.keras.callbacks.EarlyStopping(monitor="val_auc", mode="max", patience=3, restore_best_weights=True)])
    probs = model.predict(make_ds(test, False)).reshape(-1)
    labels = test.y.to_numpy(dtype=int)
    print(json.dumps({"confusion_matrix_real_fake": confusion_matrix(labels,(probs>=.5).astype(int),labels=[0,1]).tolist(),
      "roc_auc": float(roc_auc_score(labels,probs)),
      "report": classification_report(labels,(probs>=.5).astype(int),target_names=["real","fake"],output_dict=True,zero_division=0)},
      ensure_ascii=False, indent=2))
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(converter.convert())
    print(f"Exported: {args.output.resolve()}")
    print("TFLite input [1,224,224,3] float32 RGB 0..255; output [1,1] sigmoid fake probability.")

if __name__ == "__main__":
    main()
