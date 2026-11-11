# Model selection and provenance

| Candidate | Fit for this build | Korean / image understanding | Memory and speed | Distribution |
|---|---|---|---|---|
| SmolVLM2-500M | Selected; official ggml-org Q8 GGUF + projector; llama.cpp ARM64 CPU | English-focused according to original card; supplies visual evidence, not dependable Korean prose | Weights 546 MB; Android peak RAM and throughput unmeasured | Apache-2.0 |
| Gemma 3n E2B | Not embedded | Multilingual multimodal candidate; no comparative Korean benchmark performed | Official LiteRT preview .task ~3.14GB, larger than selected pipeline; no device benchmark performed | Gemma terms; gated download requires account acceptance |
| Qwen2.5-VL-3B | Not embedded | Larger multilingual visual model; no comparative Korean benchmark performed | More weights / working memory; no device benchmark performed | 3B revision checked uses Qwen Research License, commercial use requires separate license |

Qwen3-0.6B Q8 (639 MB, Apache-2.0) is additionally embedded as a **text-only Korean writer**. It receives only SmolVLM evidence and measured data, not the image. This is a practical language bridge, not a guarantee of expert Korean criticism. Both models are sequential CPU processes.

Sources:
- https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct
- https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF
- https://huggingface.co/Qwen/Qwen3-0.6B-GGUF
- https://huggingface.co/google/gemma-3n-E2B-it-litert-preview
- https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct/blob/37ce9f696340e294a5d3e0e806466addd1b22b3a/LICENSE
- https://huggingface.co/litert-community/NIMA-LiteRT
- https://github.com/idealo/image-quality-assessment
- https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector
- https://github.com/ggml-org/llama.cpp

Exact weight URLs, sizes, revision hashes and SHA-256 digests: `app/src/main/assets/models/manifest.json`. CI fails if a required model is missing or mismatched. The source-with-models ZIP includes all weights and notices.

NIMA conversion: community MobileNet AVA and TID2013 fp16 TFLite, 6.45MB each, float32 input/output. Publisher reports conversion parity; this project independently verifies tensor shape, finite normalized distributions and image-dependent inference. It does not recreate upstream training or independently establish dataset image rights. Apache-2.0 metadata is the publisher's declaration. Upstream source code license and conversion model card are bundled, with original attribution. No additional conversion is necessary.

NIMA image stretching follows the distribution model's documented 224-square input; geometric overlays use the aspect-preserving original analysis image. NIMA/technical scores are separately identified.

MediaPipe EfficientDet-Lite0 int8: official versioned model, COCO detector; does not infer importance or foreground depth from a bounding box alone. Areas/centers/edge space are geometric estimates. No saliency model was obtained; no saliency heatmap is simulated.
