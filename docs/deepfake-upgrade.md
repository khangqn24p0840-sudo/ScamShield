# ScamShield Deepfake upgrade

## Scope

The feature provides a shared on-device inference path for:
- a video selected from the device library;
- live video-call screen observation through the existing MediaProjection service.

The two sources use the same ML Kit face detection, TFLite classifier, and temporal analyzer. Video frames are processed in memory and are not persisted or uploaded.

## Android compatibility

- Minimum supported Android version: Android 9 (Pie), API 28.
- The Android build does not force ARM-only packaging. It includes the native ABIs provided by its dependencies, which are inspected by CI.
- Physical-device and emulator compatibility still depends on the CPU ABI and Android image supported by the bundled native libraries.

## Runtime and performance

- Library video frames are decoded at a maximum 640-pixel longest side on Android 9 and newer.
- Short videos are sampled at up to 6 frames per second, capped at 120 frames. Longer videos are sampled uniformly across the whole video, also capped at 120 frames. Temporal indicators requiring a higher sample rate may be unavailable for long videos.
- Progress updates are throttled to avoid flooding the main thread.
- TFLite input buffers, pixel arrays, and output arrays are reused instead of allocated for every inference.
- UINT8 model input is converted using the tensor's quantization scale and zero point.
- The TFLite model is packaged as an uncompressed APK asset so it can be memory-mapped by the interpreter.

## APK validation

The GitHub Actions build verifies the APK signature, reads the packaged manifest with Android build-tools, and reports the native ABIs actually included in the APK. A successful compile alone is not treated as proof that an APK can be installed on a target device.

## Model packaging

The repository stores `deepfake_model.tflite` and `deepfake_config.json` at the repository root. The Android build copies those files into generated app assets. The APK does not include the DeepfakeBench training framework.

The runtime validates the configured input shape, input type, normalization, output size, and output contract before making the model available. If the model is absent or incompatible, model scoring is disabled and the UI reports that the AI model is unavailable.

## Validation still required

Before release, inspect the exported model's training provenance and evaluate it on a held-out, source-video-disjoint test set. Calibrate alert thresholds on validation data and measure video-level AUC, precision, recall, false-positive rate, latency, RAM, battery use, and thermals on supported Android devices.

The current FaceLivenessAnalyzer thresholds are heuristic and uncalibrated. They remain risk indicators, not proof that a person is genuine or synthetic. A successful APK build does not establish detection accuracy or confirm performance on a physical device.

## DeepfakeBench licensing

DeepfakeBench source code is licensed CC BY-NC 4.0, subject to separately licensed third-party components and datasets. Verify commercial-use permissions for the framework, adapted implementation, pretrained weights, and every dataset before shipping a product or service.
