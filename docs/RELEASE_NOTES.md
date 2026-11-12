# FrameCritic 2.0.2 · build 64

Build 64 removes the recycled-bitmap crash that ended the app while a photo was on screen. The stack trace from build 63 was `java.lang.RuntimeException: Canvas: trying to use a recycled bitmap` raised from Compose's `BitmapPainter.onDraw`, which means the picture the screen was drawing had already been released by the analysis code.

**Install note:** this APK is signed with the CI debug key, which differs from run to run. Uninstall the previous FrameCritic before installing (`adb install -r` will fail with a signature mismatch). After installing, the header must read a build number that matches this release tag (the value after `offline-v2-`); if it does not, the old build is still the one running.

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable APK (~20 MB).
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, pinned weight files, licenses and Gradle wrapper.
- `FrameCritic-verification.zip`: Android build reports and Linux CPU inference outputs for the pinned NIMA/MediaPipe weights.

What changed in this build:
- **MediaPipe no longer receives the displayed bitmap.** `Vision.subjects` used to hand the original photo to `BitmapImageBuilder` and then close the `MPImage`. Closing an MPImage can release the Android `Bitmap` it was built from, so the photo the screen was rendering was freed in the middle of an analysis. Subject detection now always works on an `ARGB_8888` copy; only that copy is released, and the photo is untouched.
- **No manual `recycle()` anywhere on an analysis bitmap.** `Pipeline.analyze` (`work.recycle()`), `Vision.metrics` (`source.recycle()`) and `Nima.analyze` (`scaled.recycle()`) no longer release bitmaps. Each of those calls could hit the very bitmap Compose was drawing — `downscaled` returns the same instance when the photo is already ≤1280 px, and `createScaledBitmap` returns the source when the size is unchanged. Bitmap memory is left to the garbage collector, which never frees an object the UI still references. OpenCV `Mat` objects are still released in `finally`; they are native and not drawn.
- **The screen checks before it draws.** `PhotoAnalysis` now renders the photo and the clipping mask through a painter that re-reads `Bitmap.isRecycled` immediately before each draw and skips the frame instead of letting `android.graphics.Canvas` throw. The clipping-mask tap handler, the share-image renderer and the mask overlay all test the bitmap state first.
- **Version.** `versionCode` 22, `versionName` 2.0.2. The header reads `FRAMECRITIC` with `v2.0.2 (Build N) · 사진을 보고, 근거를 읽다`, where N is the CI run number and therefore the same N as this release tag; the model dialog repeats it.
- **Regression guard.** The instrumentation test now asserts that the original bitmap is still usable after `Nima.analyze`, `Vision.metrics`, `Vision.subjects` and `Pipeline.analyze`.

Carried over from build 63 (unchanged in behaviour):
- Every one of the four analysis stages can fail on its own and hand a neutral, clearly labelled result to the next, so a measurement error cannot end the app.
- Crash record and stage record: a previous crash shows its stack trace with a **클립보드 복사** button, and a silent process death shows the stage that was running.
- Subject detection passes the model as a direct `ByteBuffer`, empty category lists are skipped, non-finite and inverted boxes are dropped.
- The report always has the eleven sections; missing or non-Korean items are filled from measured values with a notice.
- Images are capped at 1280 px on the long edge, and the unused ARM64 `llama-server` binary is not packaged.

**No physical Galaxy S25 Ultra test was performed.** Unit tests (`ScoringTest`, `CriticEngineTest`, `PipelineFallbackTest`), the APK signature/alignment/content check and host inference checks all run in CI, but they do not establish Android runtime behaviour, and the reopened crash came from a path only a device exercised. If the app still exits, the start-up dialog now carries either the stack trace or the stage that was running, and both can be copied.
