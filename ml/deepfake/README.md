# Deepfake face-model training pipeline

This pipeline is separate from SMS scam detection. Training runs on a computer or Colab; the Android app only runs TFLite inference.

## Dataset

Use properly licensed research data and face-cropped RGB images. Create a CSV manifest with columns image_path,label,video_id. Labels must be real or fake. video_id identifies the original source video, not an individual frame. Keep all frames from one source video in the same split; the script uses GroupShuffleSplit to reduce frame leakage. Provide both labels and at least five distinct source videos, and enough videos that all three splits contain both labels.

Example rows:

| image_path | label | video_id |
|---|---|---|
| faces/real_01_001.jpg | real | real_video_01 |
| faces/fake_07_001.jpg | fake | fake_video_07 |

Install requirements with: python -m pip install -r ml/deepfake/requirements.txt

Train with: python ml/deepfake/train_deepfake.py --manifest dataset/manifest.csv --output deepfake_face.tflite --epochs 8

Review test metrics before using the model. Only then copy the exported file to app/src/main/assets/deepfake_face.tflite.

## Android model contract

Input: [1,224,224,3] float32 RGB, pixel values 0..255. Output: [1,1] sigmoid probability for fake. EfficientNetB0 includes internal input rescaling. Keep float32 until the app pipeline is validated.

## Important limitation

No trained Deepfake weights are included in this change. A meaningful model cannot be produced from synthetic labels or a handful of sample images. The dataset must be representative, legally usable, and tested on source videos not seen during training. Reported metrics describe only that test set; they do not guarantee real-world detection.