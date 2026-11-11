# FrameCritic

Android photographic critique app — early development scaffold.

## Current implementation (v0.1)
- Native Kotlin / Jetpack Compose interface
- Android photo picker and local photo preview
- Photographer Persona: Bresson, Adams, Leiter, Fan Ho
- Critic Level: Generous, Balanced, Strict
- Critic Tone: Mentor, Critic, Analyst
- Real on-device pixel analysis: mean luminance, highlight/shadow clipping, saturation, edge contrast
- Rule-based, persona-aware technical observations (not generative AI)
- GitHub Actions debug APK build

**Implemented in source, not yet device-validated:** NIMA aesthetic and technical LiteRT/TFLite inference, with model downloads during CI build from https://huggingface.co/litert-community/NIMA-LiteRT. Scores are shown only when both model inferences succeed.\n\n**Not implemented yet:** multimodal VLM inference, OpenCV integration (current metrics use Android Bitmap), MediaPipe segmentation, generative natural-language critique. No AI results are simulated.

**Validation status:** source files committed; successful CI APK build, model compatibility, inference accuracy, and device execution have not yet been independently confirmed.

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


## Critique severity (implemented in source)
- **Mild**: forgiving technical thresholds, strengths-first headline.
- **Balanced**: middle technical thresholds.
- **Harsh**: tighter clipping and edge-contrast thresholds, defects-first headline.
- **Quick / Standard / Expert** report detail modes.
- **Critic Score**: explicit rule-based technical rubric (0–100); it is not an aesthetic model score. Severity changes thresholds but **never modifies raw NIMA scores**.
- **ML Kit image labeling**: real on-device content tags with confidence; not a generative vision-language model.
- Photographer personas are **inspired evaluation lenses**, not quotes or simulated identities.

## Outstanding production requirements
- Actual multimodal VLM runtime with image preprocessing, quantized weights, tokenizer and Korean output; not implemented.
- MediaPipe segmentation and overlay UI; not implemented.
- Automated device/instrumentation tests and verified APK build; not confirmed.
- Verify GitHub Actions model download, binary size and Android runtime compatibility from build logs.
