# FrameCritic

Android photographic critique app — early development scaffold.

## Current implementation (v0.1)
- Native Kotlin / Jetpack Compose interface
- Android photo picker and local photo preview
- Photographer Persona: Bresson, Adams, Leiter, Fan Ho
- Critic Level: Generous, Balanced, Strict
- Critic Tone: Mentor, Critic, Analyst
- GitHub Actions debug APK build

**Not implemented yet:** NIMA inference, multimodal VLM inference, OpenCV metrics, MediaPipe segmentation, automated scoring, natural-language critique. No AI results are simulated.

## Build
Open the repository's **Actions** tab and select **Android APK** → **Run workflow**, or push to `main`. After successful execution, download the `FrameCritic-debug` artifact.

## Planned AI references
- NIMA: https://github.com/idealo/image-quality-assessment
- VLM: https://github.com/huggingface/smollm
- Multimodal inference: https://github.com/ggml-org/llama.cpp
- LiteRT: https://github.com/google-ai-edge/LiteRT
- OpenCV: https://github.com/opencv/opencv
- MediaPipe: https://github.com/google-ai-edge/mediapipe

Model licenses, Android compatibility and runtime behavior must be validated before integration.
