# FrameCritic 2.0

Fresh native Android application for on-device photo criticism. Kotlin / Jetpack Compose / Material 3. Korean interface. Samsung Galaxy S25 Ultra (ARM64, Android 9+) target.

**Download the APK and the complete Android Studio source with weights from the newest `offline-v2-*` prerelease.** GitHub's automatic Source code archive excludes large weights; use `FrameCritic-2.0.0-source-with-models.zip` for the self-contained source deliverable.

## Actual inference pipeline

1. Android photo picker → EXIF-aware ImageDecoder → sRGB, max long edge 1280.
2. NIMA MobileNet AVA and TID2013 TFLite inference; untouched 10-bin probability distributions and expectation.
3. OpenCV histogram, extreme luminance masks, RGB means, hue distribution, contrast, local contrast, Laplacian variance, Canny edge density, near-horizontal line candidates.
4. MediaPipe EfficientDet-Lite0 object detection with position, size, confidence and surrounding frame space. Detection is limited to COCO categories; it does not recognize every possible subject.
5. Stage 4/4 combines the NIMA distribution, OpenCV measurements and MediaPipe boxes into six evidence axes — composition, lighting, color, sharpness, emotion, timing — with a Korean observation and an interpretation for each.
6. A deterministic on-device critic engine writes ten critique sections from those facts — composition, light, colour, sharpness, mood, strengths, what to refine, shooting and editing advice — in plain Korean prose with no numbers or measurement vocabulary. Every raw value (NIMA distribution, luminance statistics, clipping, RGB, hue, Laplacian variance, detections, per-axis evidence, the scoring formula) is kept one tap down in **상세 측정 데이터 및 기술적 근거**. No language model, no child process, no network: analysis and report finish in a fraction of a second.

Only the three TFLite models (~17MB) are prepared on first launch. The GGUF vision/language weights stay in the repository manifest for the Linux host verification job but are not loaded, unpacked or packaged into the APK, so the app no longer spends minutes extracting 1.8GB or risks the OS killing a native inference process.

Scores are heuristic aesthetic judgments of measured image properties, not validated objective artistic quality, and the Korean text never claims to see a subject, intent or emotion the measurements do not support. See [scoring](docs/SCORING.md), [validation limits](docs/VALIDATION.md), [model provenance](docs/MODELS.md).

## Features

One-touch flow: pick a photo, press **사진 분석하기**, read the score and the critique. No perspective, strictness, tone or depth selection on screen — the app runs with a balanced point of view and a mentor tone. The score card shows a single number and a one-line impression; the critique follows as cards; the measurement appendix, NIMA distribution and luminance histogram sit in a fold-out block at the bottom. The overlay chips (삼분할 · 피사체 · 수평선 후보 · 클리핑) and tap-to-inspect regions stay for reading a photo already on screen. Text copy, report-image sharing, cancellation and explicit partial failure states are unchanged. Model/legal information is included in assets.

## Build through GitHub Actions

Push to `rebuild/offline-v2`, or use workflow_dispatch. CI verifies pinned SHA-256 weights, builds pinned llama.cpp for ARM64 and x86_64 (host verification only), runs Kotlin score and critic-engine tests, builds APK and device-test APK, runs Android lint, verifies APK signature/alignment/content, and runs real CPU NIMA inference plus the deterministic critic engine unit tests. Successful jobs publish a prerelease with APK, complete source including models, and test evidence.

Android Studio: open delivered source ZIP, install SDK 35 / NDK 28.2.13676358 and JDK 17. `bash scripts/prepare_runtime.sh android`, then `./gradlew assembleDebug`. Gradle wrapper is generated and included in the source artifact. Source Git checkout requires `python scripts/fetch_models.py` and `python scripts/fetch_licenses.py` first.

The app is signed with a CI debug certificate. Successive runs may use different debug certificates; uninstall the previous debug build before installing if Android reports a signature conflict. No public release signing key has been created.

## Verification honesty

GitHub host inference proves the pinned NIMA weights execute on Linux CPU. It **does not prove Galaxy S25 Ultra Android runtime behavior**. No physical device is connected to this task. Device latency, peak RAM, thermal behavior and Korean critique quality on real photos remain to be tested. The device instrumentation test sources and APK are supplied. See `docs/VALIDATION.md`.
