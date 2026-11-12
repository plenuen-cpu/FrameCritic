# FrameCritic 2.0 · build 58

The offline critic no longer depends on a child inference process. Stages 1–3 (NIMA, OpenCV, MediaPipe) run in-app as before, and stage 4 now composes a full Korean critique from those measurements in well under a second. The 1.8 GB vision/language weights that caused the 4/4 crash are neither loaded nor unpacked, so first launch only prepares three TFLite models (~17 MB).

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable debug APK. The GGUF language/vision weights are no longer packaged, so the download and install are small. A previously installed debug build may need uninstalling because CI debug keys differ.
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, pinned weight files, licenses and Gradle wrapper. GitHub's automatic Source code ZIP excludes large weights.
- `FrameCritic-verification.zip`: Android build reports and Linux CPU inference outputs for the pinned NIMA/MediaPipe weights.

What changed in this build:
- The Korean report is generated on the device by a deterministic measurement engine: 11 sections, six evidence axes (composition, lighting, color, sharpness, emotion, timing), persona weights, strictness, tone and depth all still apply.
- No `libframecritic-server.so` process is started, so the OS memory killer no longer has a native child to end mid-report.
- `ModelStore.prepare` copies only the three TFLite models and skips `.gguf` entries.

**No physical Galaxy S25 Ultra test was performed.** Host inference and build checks do not establish Android runtime behavior, on-device latency or how the generated Korean reads on a real photograph. This is a test prerelease, not a production release.
