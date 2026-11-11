# Verification status and device procedure

This file specifies validation scope. Actual outcomes and raw outputs are in the release `FrameCritic-verification.zip` and linked GitHub Actions run. A green build must not be described as a physical-device pass.

## Automated CI gates

- Exact SHA-256 and byte length for all six embedded weight files.
- Kotlin unit tests: NIMA expectation/spread, bounds, strictness monotonicity, different persona results, rejected invalid distributions, low confidence rejection.
- ARM64 llama.cpp native build, Android compilation, debug signature, 16KB ZIP alignment, asset verification, lint, device-test APK compilation.
- Linux CPU actual NIMA inference on two different synthetic images, image-dependent normalized distributions.
- Linux CPU actual SmolVLM with image projector on two distinct images; red-circle / blue-rectangle grounding checks.
- Linux CPU Qwen2.5 report generation, schema validation and Korean character presence.

These tests are smoke tests. Korean character presence is NOT a language-quality test. Synthetic color/shape grounding checks do NOT establish real-photo accuracy. Read the full JSON outputs. Host tests use the same caption prompt, report prompt template, Quick report schema and weights. Synthetic-fixture measurements are limited to RGB means; Android supplies OpenCV/MediaPipe data. They do not execute Android ProcessBuilder, MediaPipe Android inference or photo picker/sharing.

## Not run without an attached device

Galaxy S25 Ultra install/launch, offline first run, Android native process launch and model loading, MediaPipe object detection, memory/latency/thermal measurements, UI interaction/share/rotation/lifecycle behavior, ten-real-photo Korean critique review. Do not mark these passed.

Device procedure:
1. Install `FrameCritic-2.0.0-arm64.apk` and matching AndroidTest APK. Leave ≥1.8GB extraction space beyond APK install space.
2. Enable airplane mode before the first app launch. Import a photo; ensure six models initialize with no network.
3. `adb shell am instrument -w com.framecritic.app.test/androidx.test.runner.AndroidJUnitRunner` runs bundled NIMA, OpenCV, MediaPipe, VLM and Korean tests. Keep device awake. A synthetic-image pass is not photographic-quality validation.
4. Analyze portraits, landscapes, low light, intentional blur, silhouettes, monochrome and abstract/reflection images. Review observations against actual image, translations, strictness and persona differences.
5. Switch Tone only: numerical scores must stay unchanged. Switch Persona / Level: raw NIMA must stay unchanged. Check explanation and weights.
6. Test cancel, background/foreground, rotation, low storage, process failure, image re-selection, tap boxes/clipping, copy, PNG share, no-server/low-memory errors.
7. Record `adb shell dumpsys meminfo com.framecritic.app`, `adb shell dumpsys thermalservice`, analysis-stage durations and exact device OS/build. App native child memory must also be measured.

## Known limits

- No saliency heatmap: no verified attention model integrated.
- Hough lines are candidates, not semantic horizon detection. OpenCV RGB means are color-cast clues, not calibrated white balance.
- COCO detector omits unrecognized subjects. Spatial relationship uses boxes/centers/area and VLM evidence, not depth or segmentation.
- Tap feedback links a measured region to whole-image critique; does not claim independently generated region-specific VLM critique.
- VLM errors remain possible. Invalid output produces an explicit error; it is never replaced with canned critique or invented score.
- No side-by-side device benchmarks for the two unselected VLMs; no validated aesthetic calibration dataset.
- Debug APK is for direct testing; production signing, store distribution and update migration are not completed.
