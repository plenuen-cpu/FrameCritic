# FrameCritic 2.0 · build 59

Build 59 makes the stage‑4 result screen unable to end the process. The report path was already off the native inference process, but two things could still take the app down on a real device: a rendering value that the Compose UI could not lay out (NaN or a zero/infinite size in the histogram and NIMA charts), and a half‑written state where `analysis` was rendered before `report` existed. Both paths are closed, and an uncaught exit is now recorded so a remaining crash reports its own stack trace instead of disappearing.

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable debug APK (~17 MB of embedded TFLite models). A previously installed debug build may need uninstalling because CI debug keys differ.
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, pinned weight files, licenses and Gradle wrapper.
- `FrameCritic-verification.zip`: Android build reports and Linux CPU inference outputs for the pinned NIMA/MediaPipe weights.

What changed in this build:
- **Crash logger.** `MainActivity.onCreate` installs a `Thread.UncaughtExceptionHandler` that writes the full stack trace, thread name, device, app version and memory limit to `filesDir/crash.txt`. The next launch shows the record in a dialog and deletes the file when it is closed.
- **Rendering guards.** `Histogram` computes `strokeW = (size.width / 256f).coerceAtLeast(1f)` and returns early when the canvas has no finite positive size; NIMA bar heights are precomputed with `maxP` finite/non‑zero fallback and clamped to 1–44 dp; subject boxes, line overlays and tap coordinates are skipped when any component is not finite; every `Canvas` draw block runs inside `runCatching`; number formatting falls back to “—” instead of throwing; percentage rounding never calls `roundToInt()` on NaN.
- **Atomic state update.** `CriticViewModel.analyze` no longer publishes `analysis` before `report` exists. Both are written in one `mutable.update`, so the composition never renders a half state.
- **Throwable coverage.** `analyze()` and `load()` catch `Throwable` (after `CancellationException` and `OutOfMemoryError`), and `Pipeline` sanitises NIMA probabilities, metrics, subject boxes and line candidates to finite values before any UI or engine code sees them.
- **Engine fallbacks.** `CriticEngine.assess` isolates each axis with `runCatching` and neutralises only that axis, `safeAssess` always returns an `Assessment`, and `sections` returns a full 11‑section map even if prose generation fails.
- **NIMA normalisation.** TFLite output is normalised into a 10‑bin probability distribution before `Scoring.distribution`, so a malformed output can no longer throw inside `Nima.analyze`.

**No physical Galaxy S25 Ultra test was performed.** The unit tests (`CriticEngineTest`, `ScoringTest`), APK signature/alignment check and host inference checks all run in CI, but they do not establish Android runtime behaviour. If the app still exits, the crash dialog after restart holds the exact stack trace to fix it from.
