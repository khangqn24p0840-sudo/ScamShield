# Deepfake scanner: current status and limitations

## What the current implementation does
- Detects a face in captured video-call frames with ML Kit.
- Computes temporal/image heuristics (head-pose jitter, face/background sharpness variation, and face-box jumps).
- Uses a sliding observation window and delays alerts to avoid repeated notifications.
- Does not save captured frames as part of the observation model.

## Important model status
The repository currently contains `scam_text.tflite`, which is for text/scam analysis. It does not contain `deepfake.tflite`.
`DeepfakeClassifier.kt` defaults to `NoopClassifier`; the optional TFLite adapter is only a text template and is not wired into the running service. Therefore the current video scanner is NOT a trained deepfake classifier.

## Changes in deepfake-optimization
- Blink count is no longer used as evidence of manipulation because normal human blink behavior varies and eye landmarks can be unreliable in compressed/low-light video.
- Without a model output, the analyzer can only show heuristic risk; it cannot emit the highest risk level.
- A suspicious result is worded as a request for further verification, not a definitive claim that a video is fake.
- Model frame scores are aggregated with a median to reduce the effect of isolated bad frames.

## What is still required for real deepfake classification
1. Select a model trained for the intended domain (face-swap and/or generated-face videos).
2. Verify its exact input normalization, tensor dimensions, output labels, and color channel order before integration.
3. Evaluate on a held-out set containing genuine and manipulated clips, including recompressed/low-light examples.
4. Report precision, recall, false-positive rate, and calibration separately by video quality.
5. Only enable model-based high-risk alerts after the model is actually packaged, loaded, and validated.

Do not interpret a low heuristic score as proof that a person/video is genuine. For financial or account requests, verify through a separate trusted channel.
