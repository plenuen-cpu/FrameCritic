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


## Recent additions
- ML Kit on-device **person segmentation** with an overlay toggle. This is not MediaPipe and is not general-object segmentation.
- Android share sheet exports a text critique report.
- JUnit tests for Mild/Balanced/Harsh invariance of NIMA raw scores and report depth.
- GitHub Actions now runs tests before APK packaging.
- NIMA model weights are **not automatically assumed to exist**. Configure repository variables `NIMA_AESTHETIC_URL` and `NIMA_TECHNICAL_URL` with validated compatible TFLite model URLs. When absent, APK builds without weights and the app displays an explicit NIMA failure. Input preprocessing and tensor format must be verified against the chosen model.

## Critical remaining work
- A genuine local **multimodal VLM** with image encoder, language decoder, quantized weights, tokenizer, Android runtime and Korean generation has **not** been integrated. ML Kit labels are not a VLM substitute.
- A successful Actions build and Galaxy S25 Ultra device test are not yet verified. Check Actions logs and artifacts before distributing the APK.


## Genuine image-aware Korean VLM critique (Gemini cloud)
The app includes a user-triggered multimodal Gemini 2.5 Flash integration. Users enter their own Gemini API key in a masked field. Only after tapping the VLM critique button does the app send a downscaled JPEG and the chosen persona/severity/tone/depth to the Gemini API. Keys are held in Compose memory and not stored in preferences or source. This feature requires internet access, may incur API charges, and is **not offline/on-device inference**. Failed HTTP requests show errors rather than fabricated critiques.

The intended offline local VLM (SmolVLM2 or equivalent with native Android multimodal runtime and verified quantized weights) remains unimplemented. ML Kit scene labels and segmentation run on device. Android build success and device inference have not been verified yet.


## Offline VLM (Gemma 3n)
The prior Gemini cloud implementation was removed. This app now uses MediaPipe Tasks GenAI 0.10.27 to run Gemma 3n multimodal vision inference locally, using an imported compatible `.task` model. The user selects a local model file with Android's document picker, which is copied to app-private storage. No API key, remote endpoint or INTERNET permission is used for critique. Select a **MediaPipe-compatible multimodal Gemma 3n E2B/E4B .task bundle**; text-only Gemma 3 models and arbitrary GGUF models are incompatible. See https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android#image_input . Model weights are not bundled due to their size and distribution terms. The code path is implemented, but a successful CI build, model load and device inference are not yet verified. MediaPipe LLM Inference is maintenance-only; consider LiteRT-LM for a future migration.


## SmolVLM 500M local rebuild
The app now uses llama.cpp's local ARM64 `llama-server` built by GitHub Actions and invoked as a bundled executable, listening on 127.0.0.1 only. A localhost HTTP connection requires Android INTERNET permission and cleartext permission; **no remote inference endpoint is used**. Download both files from https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF : `SmolVLM-500M-Instruct-Q8_0.gguf` and `mmproj-SmolVLM-500M-Instruct-Q8_0.gguf`. Import them separately in the app. They are not bundled. The binary is built from llama.cpp upstream at CI time. **Build, executable startup, image processing and real-device inference remain unverified.** The model is small and may produce limited Korean text; no translation model is embedded.
