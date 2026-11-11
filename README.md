# FrameCritic 2.0

Fresh native Android application for on-device photo criticism. Kotlin / Jetpack Compose / Material 3. Korean interface. Samsung Galaxy S25 Ultra (ARM64, Android 9+) target.

**Download the APK and the complete Android Studio source with weights from the newest `offline-v2-*` prerelease.** GitHub's automatic Source code archive excludes large weights; use `FrameCritic-2.0.0-source-with-models.zip` for the self-contained source deliverable.

## Actual inference pipeline

1. Android photo picker → EXIF-aware ImageDecoder → sRGB, max long edge 1280.
2. NIMA MobileNet AVA and TID2013 TFLite inference; untouched 10-bin probability distributions and expectation.
3. OpenCV histogram, extreme luminance masks, RGB means, hue distribution, contrast, Laplacian variance, near-horizontal line candidates.
4. MediaPipe EfficientDet-Lite0 object detection with position, size, confidence and surrounding frame space. Detection is limited to COCO categories; it does not recognize every possible subject.
5. SmolVLM2-500M Q8 plus its image projector → a short, unconstrained factual caption, combined with measured OpenCV/MediaPipe observations. Qwen2.5 judges eight rubric axes and must reference one of these observation fields; unsupported sources get zero confidence.
6. Deterministic persona-weighted scoring, then continuous critic-level calibration.
7. Sequential Qwen2.5-1.5B Q4_K_M inference evaluates the grounded rubric and composes a Korean report from this evidence. No fixed generated critique or fallback scores.

All **six weight files (1,680,415,683 bytes)** are in the APK. No first-run internet download. First use extracts verified weights to app-private storage (~1.8GB extra free space). Runtime HTTP is authenticated loopback only; no remote API, API key, telemetry or photo upload.

Two language/vision models run sequentially to reduce peak RAM. Small models can hallucinate. Scores are heuristic aesthetic judgments, not validated objective artistic quality. See [model comparison](docs/MODELS.md), [scoring](docs/SCORING.md), [validation limits](docs/VALIDATION.md).

## Features

Four photographer-inspired weighted perspectives, independent three-level strictness / three tones / three report depths; dark photo-first UI; raw NIMA distribution; scores for all perspectives and levels; grid, detection boxes, candidate horizon lines, clipping masks; tap regions for measurements and related whole-image critique; text copy and report-image sharing; cancellation and explicit partial failure states. Model/legal information is included in assets.

## Build through GitHub Actions

Push to `rebuild/offline-v2`, or use workflow_dispatch. CI verifies pinned SHA-256 weights, builds pinned llama.cpp for ARM64 and x86_64, runs Kotlin score tests, builds APK and device-test APK, runs Android lint, verifies APK signature/alignment/content, and runs real CPU NIMA + VLM + Korean inference. Successful jobs publish a prerelease with APK, complete source including models, and test evidence.

Android Studio: open delivered source ZIP, install SDK 35 / NDK 28.2.13676358 and JDK 17. `bash scripts/prepare_runtime.sh android`, then `./gradlew assembleDebug`. Gradle wrapper is generated and included in the source artifact. Source Git checkout requires `python scripts/fetch_models.py` and `python scripts/fetch_licenses.py` first.

The app is signed with a CI debug certificate. Successive runs may use different debug certificates; uninstall the previous debug build before installing if Android reports a signature conflict. No public release signing key has been created.

## Verification honesty

GitHub host inference proves the shipped model files execute on Linux CPU. It **does not prove Galaxy S25 Ultra Android runtime behavior**. No physical device is connected to this task. Device latency, peak RAM, thermal behavior and Korean critique quality on real photos remain to be tested. The device instrumentation test sources and APK are supplied. See `docs/VALIDATION.md`.
