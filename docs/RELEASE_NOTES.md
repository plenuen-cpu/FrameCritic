Fresh FrameCritic 2.0 implementation. All six AI weight files are inside the APK; no first-run download. NIMA + SmolVLM2-500M + Qwen2.5-1.5B-Instruct + MediaPipe EfficientDet, OpenCV measurements, four weighted photographer perspectives, independent strictness/tone/depth, Korean reports, overlays, copy and image sharing.

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable debug APK, ~1.8GB. Allow additional extraction storage. Prior debug build may need uninstalling because CI debug keys differ.
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, all weights, licenses and Gradle wrapper. GitHub's automatic Source code ZIP excludes models.
- `FrameCritic-verification.zip`: Android build reports and actual Linux CPU inference outputs.

**No physical Galaxy S25 Ultra test was performed.** Host inference and build checks do not establish Android runtime behavior, Korean critique quality, memory use or device speed. Small-model output needs human review. Saliency heatmap is not implemented. This is a test prerelease, not a production release.
