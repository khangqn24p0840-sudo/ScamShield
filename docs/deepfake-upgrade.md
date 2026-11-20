# ScamShield Deepfake upgrade

## Scope

This branch adds a shared on-device inference path for:
- a video selected from the device library;
- live video-call screen observation through the existing MediaProjection service.

The two sources use the same ML Kit face detection, TFLite classifier, and temporal analyzer. Video frames are processed in memory and are not persisted or uploaded.

## Model packaging

The repository currently stores \`deepfake_model.tflite\` and \`deepfake_config.json\` at the repository root. The Android build copies those two files into generated app assets. The APK does not include the DeepfakeBench training framework.

The runtime validates the configured input shape, input type, normalization, output size, and output contract before making the model available. If the model is absent or incompatible, model scoring is disabled and the UI reports that the AI model is unavailable. This fallback must not be described as a successful AI deepfake scan.

## Current model contract

The checked-in config declares:
- input: RGB, 224 x 224;
- normalization: [-1, 1];
- classes: Fake, Real;
- output: probability_of_real.

The classifier converts a scalar probability-of-real output to fake probability. For a two-value output, it uses the Fake class index declared by class_names. Runtime tensor validation is required because the config alone does not prove the binary model matches it.

## Important validation work still required

Before release, inspect the exported model's training provenance and evaluate it on a held-out, source-video-disjoint test set. Calibrate alert thresholds on validation data and measure video-level AUC, precision, recall, false-positive rate, latency, RAM and thermals on supported Android devices.

The current FaceLivenessAnalyzer thresholds are heuristic and uncalibrated. They remain risk indicators, not proof that a person is genuine or synthetic. The video-library scanner samples at most 120 frames per video; this is a first implementation and must be measured against representative video lengths and compression levels.

## DeepfakeBench licensing

DeepfakeBench source code is licensed CC BY-NC 4.0, subject to separately licensed third-party components and datasets. Verify commercial-use permissions for the framework, any adapted implementation, pretrained weights, and each dataset before shipping a product or service.
