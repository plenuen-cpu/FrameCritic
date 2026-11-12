# Verification status and device procedure

This file specifies validation scope. Actual outcomes and raw outputs are in the release `FrameCritic-verification.zip` and linked GitHub Actions run. A green build must not be described as a physical-device pass.

## Automated CI gates

- Exact SHA-256 and byte length for all six embedded weight files.
- Kotlin unit tests: NIMA expectation/spread, bounds, strictness monotonicity, different persona results, the boosted score bands (5.5→7.15, 6.5→7.86 on the Balanced curve), rejected invalid distributions, low confidence rejection, critic-engine bounds, all persona/level/tone/depth combinations producing every critique section, determinism, clipping sensitivity, and the 2.1.0 prose contract — no digit and no machine term (관찰되었, Laplacian, COCO, 휘도, 255, 검출, 측정, NIMA …) in any critique body, while every raw value still appears in `CriticEngine.measurements()`.
- ARM64 llama.cpp host build (verification only, not used by the app), Android compilation, debug signature, 16KB ZIP alignment, TFLite asset verification, lint, device-test APK compilation.
- Linux CPU actual NIMA inference on two different synthetic images, image-dependent normalized distributions.
- Linux CPU actual SmolVLM with image projector on two distinct images; red-circle / blue-rectangle grounding checks (the shipped runtime and pinned weights are still exercised even though the app no longer calls them).
- Linux CPU Qwen2.5 generation and schema validation for the same pinned weights.

These tests are smoke tests. Korean character presence is NOT a language-quality test. Synthetic color/shape grounding checks do NOT establish real-photo accuracy. Read the full JSON outputs. Host tests use the same caption prompt, report prompt template, Quick report schema and weights. Synthetic-fixture measurements are limited to RGB means; Android supplies OpenCV/MediaPipe data. They do not execute Android ProcessBuilder, MediaPipe Android inference or photo picker/sharing.

## Not run without an attached device

Galaxy S25 Ultra install/launch, offline first run, Android native process launch and model loading, MediaPipe object detection, memory/latency/thermal measurements, UI interaction/share/rotation/lifecycle behavior, ten-real-photo Korean critique review. Do not mark these passed.

Device procedure:
1. Install `FrameCritic-2.1.0-arm64.apk` and matching AndroidTest APK. About 20MB of extraction space is enough; the GGUF weights are not packaged.
2. Enable airplane mode before the first app launch. Import a photo; ensure the three TFLite models initialize with no network and 4/4 finishes in well under a second.
3. `adb shell am instrument -w com.framecritic.app.test/androidx.test.runner.AndroidJUnitRunner` runs bundled NIMA, OpenCV, MediaPipe and the measurement-critic report test. A synthetic-image pass is not photographic-quality validation.
4. Analyze portraits, landscapes, low light, intentional blur, silhouettes, monochrome and abstract/reflection images. Read the critique against the photo: does the prose describe what is actually there, and does the score feel fair for the picture? The screen offers no options, so every run uses the balanced perspective and Balanced strictness.
5. Open **상세 측정 데이터 및 기술적 근거** and check that the numbers match what the critique implies (clipping, contrast, detections, placement). The copied report text must contain the same appendix. Engine-level persona/level switching is still covered by unit tests, not by the screen.
6. Test cancel, background/foreground, rotation, low storage, process failure, image re-selection, tap boxes/clipping, copy, PNG share, no-server/low-memory errors.
7. Record `adb shell dumpsys meminfo com.framecritic.app`, `adb shell dumpsys thermalservice`, analysis-stage durations and exact device OS/build.

## Known limits

- No saliency heatmap: no verified attention model integrated.
- Hough lines are candidates, not semantic horizon detection. OpenCV RGB means are color-cast clues, not calibrated white balance.
- COCO detector omits unrecognized subjects. Spatial relationship uses boxes/centers/area and VLM evidence, not depth or segmentation.
- Tap feedback links a measured region to whole-image critique; it does not claim independently generated region-specific analysis.
- The deterministic engine cannot measure light direction, gesture, intent, originality or emotion. Its text says so instead of guessing, and it never describes a subject the detector did not find.
- No side-by-side device benchmarks for the two unselected VLMs; no validated aesthetic calibration dataset.
- Debug APK is for direct testing; production signing, store distribution and update migration are not completed.
