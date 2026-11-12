# FrameCritic 2.0.1 · build 62

Build 61 makes the analysis pipeline survive every stage. Each of the four stages can now fail on its own and hand a neutral, clearly labelled result to the next one, so a device-specific measurement error can no longer end the app. The screen also shows exactly which build is installed.

**Install note:** this APK is signed with the CI debug key, which differs from run to run. Uninstall the previous FrameCritic before installing (`adb install -r` will fail with a signature mismatch). After installing, the header must read a build number that matches this release tag (the value after `offline-v2-`); if it does not, the old build is still the one running.

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable APK (~20 MB; the unused native server binary is no longer packaged, down from ~114 MB).
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, pinned weight files, licenses and Gradle wrapper.
- `FrameCritic-verification.zip`: Android build reports and Linux CPU inference outputs for the pinned NIMA/MediaPipe weights.

What changed in this build:
- **Version is visible.** `versionCode` 21, `versionName` 2.0.1. The header reads `FRAMECRITIC` with `v2.0.1 (Build N) · 사진을 보고, 근거를 읽다`, where N is the CI run number and therefore the same N as this release tag; the model dialog repeats it.
- **Crash record can be copied.** The start-up dialog for a previous crash now has a **클립보드 복사** button beside 닫기, so the stack trace can be pasted straight into a message.
- **Stage record for silent exits.** While an analysis runs, the current stage is written to `filesDir/stage.txt` and deleted when the analysis finishes or fails normally. If the process is killed without a Java exception (native fault, low-memory killer), the next launch shows "직전 분석이 도중에 끊겼습니다" with the exact stage and time.
- **1/4 NIMA defence.** `Nima.analyze` returns `null` instead of throwing when a TFLite interpreter cannot be built or run, and it still returns a result when only one of the two models runs. `Pipeline` replaces a missing result with the neutral score (aesthetic 5.5, technical 5.0, deviation 1.0, ten 0.1 probability bins) and records the cause in `warnings`.
- **2/4 OpenCV defence.** `Vision.metrics` validates the bitmap size, converts hardware bitmaps, checks every `Mat`, treats a short `HoughLinesP` row as “no line”, allocates the clip map inside `runCatching`, and releases every `Mat` in `finally`. On failure `Pipeline` substitutes neutral metrics (luminance 128, zero contrast/sharpness/saturation) with a warning.
- **3/4 MediaPipe defence.** The detector now receives the model as a direct `ByteBuffer` (`setModelAssetBuffer`) instead of an absolute asset path, which the asset loader cannot open — so subject detection no longer fails silently on every photo. `categories().maxByOrNull { it.score() }` removes the `NoSuchElementException` on an empty category list, and non-finite or inverted boxes are skipped.
- **4/4 critic defence.** `safeAssess` still isolates each axis, and `Pipeline` wraps the whole assessment in its own fallback so a failure there cannot escape.
- **Report always has 11 sections.** `Pipeline.report` compares the engine output with the fixed 11-section list and fills any missing, blank or non-Korean item from the measured values, with a notice that the item was simplified. An empty section map can no longer reach the screen.
- **Analysis resolution cap.** Images larger than 1280 px on the long edge are scaled down before NIMA/OpenCV/MediaPipe, bounding the size of the pixel arrays and the clip map.
- **Smaller APK.** The ARM64 `llama-server` binary (~90 MB) was still packaged although no code loads it since build 58; it is no longer built into the APK.

**No physical Galaxy S25 Ultra test was performed.** Unit tests (`ScoringTest`, `CriticEngineTest`, `PipelineFallbackTest`), the APK signature/alignment/content check and host inference checks all run in CI, but they do not establish Android runtime behaviour. If the app still exits, the start-up dialog now carries either the stack trace or the stage that was running, and both can be copied.
