# FrameCritic 2.1.0 · build 65

Build 65 turns the app into a one-touch reader. Pick a photo, press **사진 분석하기**, and the score and a written critique appear; nothing else has to be chosen first. The critique is now written the way a photographer talks about a photograph, and every raw measurement has moved into a fold-out block at the bottom.

**Install note:** this APK is signed with the CI debug key, which differs from run to run. Uninstall the previous FrameCritic before installing (`adb install -r` will fail with a signature mismatch). After installing, the header must read a build number that matches this release tag (the value after `offline-v2-`); if it does not, the old build is still the one running.

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable APK (~20 MB). The file name keeps the 2.0 lineage that the release job has used since build 53; the app inside reports `v2.1.0`.
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, pinned weight files, licenses and Gradle wrapper.
- `FrameCritic-verification.zip`: Android build reports and Linux CPU inference outputs for the pinned NIMA/MediaPipe weights.

## What changed in this build

**One-touch screen.** The photographer cards and the strictness / tone / depth chip rows are gone from the main screen. The app runs with a balanced point of view (equal weight on composition, light, colour and detail) and a mentor tone, so a first-time user has exactly one decision: which photo. After a result appears, the single button reads **다시 분석하기**. The overlay chips under the photo (삼분할 · 피사체 · 수평선 후보 · 클리핑) remain, because they inspect a photo already on screen rather than setting up an analysis.

**Critique written as prose.** The seven report sections were rewritten around what the eye sees: the weight of a frame, whether light explains form, whether colour supports itself, whether texture invites a closer look. Sentences such as "휘도 평균 118.0/255의 중간 톤 사진입니다", "Laplacian 분산은 180.0으로…" or "COCO 범주 피사체는 검출되지 않았습니다" no longer appear in the report body; a unit test (`critiqueBodyCarriesNoNumbersOrMachineWords`) fails the build if any body section contains a digit or one of the machine terms (관찰되었, Laplacian, COCO, 휘도, 255, 검출, 측정, NIMA …). Sentence variants are selected from a seed computed from the measurements, so the same photo still produces the same text every time.

**Measurements kept, not discarded.** Everything the engine measured is still visible, one tap down, under **상세 측정 데이터 및 기술적 근거**: NIMA aesthetic/technical raw scores and the ten-bin distribution, luminance average, median and percentiles, highlight and shadow clipping, global and local contrast, mean RGB, channel spread, saturation, hue spread, dominant hue share, Laplacian variance, edge density, detection count and boxes, three-way placement distance, per-axis evidence and confidence, and the scoring formula with its exponents. The same block is appended to the copied/shared report text, so the numbers travel with the prose instead of replacing it.

**Pleasant score curve.** The curve `1 + 9·x^γ` now uses γ = 0.40 / 0.55 / 0.85 (Generous / Balanced / Strict) instead of 0.75 / 1 / 1.5. The exponent below 1 lifts the middle of the scale and leaves 1.0 and 10.0 where they were. Measured on the Balanced curve: a pre-boost 5.5 becomes 7.15, a pre-boost 6.5 becomes 7.86, a pre-boost 8.5 becomes 9.2. In practice an ordinary phone photograph with something visually right about it lands in the low-to-mid sevens, a well-made frame in the high sevens or low eighties, and the rare strong frame above nine. Strictness still orders the three levels (7.8 / 7.15 / 6.0 for the same evidence), and no score leaves 1.0–10.0.

**Version.** `versionCode` 23, `versionName` 2.1.0. The header reads `FRAMECRITIC` with `v2.1.0 (Build N) · 사진을 보고, 근거를 읽다`, where N is the CI run number and therefore the same N as this release tag; the model dialog repeats it.

## Carried over unchanged

- No manual `recycle()` of any bitmap the screen draws; `SafeBitmapImage` / `GuardedBitmapPainter` still re-check `isRecycled` immediately before each draw, and the image the screen renders is never handed to MediaPipe or released by the analysis.
- Every analysis stage (NIMA, OpenCV, MediaPipe, axis synthesis) can fail alone and hand a neutral, clearly labelled result to the next; the report is always filled with all ten critique sections.
- Crash record with the stack trace and a stage record naming the step that was running when the process died, both copyable.
- Images capped at 1280 px on the long edge, GGUF weights not packaged, no child process and no network.
- Report copy and report-image sharing.

**No physical Galaxy S25 Ultra test was performed.** Unit tests (`ScoringTest`, `CriticEngineTest`, `PipelineFallbackTest`), the APK signature/alignment/content check and host inference checks all run in CI, but they do not establish Android runtime behaviour. The scoring change is verified numerically on the JVM and the prose change is verified by the no-digits / no-machine-words contract; neither claims that the new text reads like a particular photographer.
