---
license: apache-2.0
library_name: litert
pipeline_tag: image-classification
tags: [nima, image-quality-assessment, aesthetic, technical, mobilenet, litert, tflite, on-device, gpu]
base_model: idealo/image-quality-assessment
base_model_relation: quantized
---

# NIMA — LiteRT on-device image quality assessment

[NIMA (Neural Image Assessment)](https://github.com/idealo/image-quality-assessment) (idealo,
Apache-2.0) re-authored for LiteRT: score a photo's quality on a **1-10** scale. Two MobileNet
models — **aesthetic** (AVA) and **technical** (TID2013) — each predict a 10-bin score distribution;
the score is the distribution mean. Both run fully on the CompiledModel **GPU** (~6.4 MB each).

Verified on a Pixel 8a: ~173 ms for both models; tflite-vs-Keras score parity 0.999998 (aesthetic) /
0.999915 (technical).

## Files
| file | in → out | delegate |
|---|---|---|
| `nima_aesthetic_fp16.tflite` | image [1,224,224,3] → dist [10] | GPU |
| `nima_technical_fp16.tflite` | image [1,224,224,3] → dist [10] | GPU |

```
image →[resize 224² · MobileNet /127.5−1]→ [GPU MobileNet]→ softmax dist[10] →[Σ i·pᵢ]→ score 1-10
```

## Minimal usage (Python)

```python
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

img = Image.open("photo.jpg").convert("RGB").resize((224, 224))
x = (np.asarray(img, np.float32) / 127.5 - 1.0)[None]        # NHWC, [-1,1]

def score(model):
    it = Interpreter(model_path=model); it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]["index"], x); it.invoke()
    dist = it.get_tensor(it.get_output_details()[0]["index"])[0]
    return float((np.arange(10) + 1) @ dist)                 # mean over 1..10

print("aesthetic", score("nima_aesthetic_fp16.tflite"))
print("technical", score("nima_technical_fp16.tflite"))
```

## Minimal usage (Kotlin, LiteRT CompiledModel)

```kotlin
val m = CompiledModel.create(assets, "nima_aesthetic_fp16.tflite", CompiledModel.Options(Accelerator.GPU), null)
val inp = m.createInputBuffers(); val out = m.createOutputBuffers()
inp[0].writeFloat(preprocess(bitmap))       // resize 224², NHWC, v/127.5f - 1f
m.run(inp, out)
val dist = out[0].readFloat()               // [10]
var score = 0f; for (i in 0 until 10) score += (i + 1) * dist[i]   // 1-10
```

## Upstream

[idealo/image-quality-assessment](https://github.com/idealo/image-quality-assessment) (Apache-2.0) —
NIMA MobileNet aesthetic + technical weights. Paper: *NIMA: Neural Image Assessment* (Talebi &
Milanfar, 2018).

## Performance

Measured on a **Pixel 8a** (Tensor G3, Android 16) with the standard TFLite [`benchmark_model`](https://ai.google.dev/edge/litert/models/measurement) tool — 10 warm-up runs then 50 timed runs, reported as the tool's mean.

| Runtime | Backend | Graph on GPU | Latency |
|---|---|---|---|
| TFLite `benchmark_model` (`TfLiteGpuDelegateV2`) — `nima_aesthetic_fp16.tflite` | GPU (OpenCL) | 86 / 86 | 11.3 ms |
| TFLite `benchmark_model` (`TfLiteGpuDelegateV2`) — `nima_technical_fp16.tflite` | GPU (OpenCL) | 86 / 86 | 10.9 ms |
| TFLite `benchmark_model` — `nima_aesthetic_fp16.tflite` | CPU (XNNPACK, 4 threads) | — | 18.5 ms |
| TFLite `benchmark_model` — `nima_technical_fp16.tflite` | CPU (XNNPACK, 4 threads) | — | 18.5 ms |

**Any on-device figure recorded when this model shipped came from a different runtime.** It was taken through LiteRT's own `CompiledModel` accelerator (logcat reports it as `LITERT_CL`), which is the path the Kotlin sample app and the LiteRT API use, and it appears elsewhere on this card. The rows above are the classic TFLite OpenCL delegate, measured with a tool anyone can download and re-run. The two are not comparable, so read the rows above as a reproducible floor rather than as this model's speed on LiteRT.

## Snapdragon NPU (Hexagon)

- `nima_aesthetic_fp16.tflite` — the NPU is **1.52x faster** than the GPU (0.565 ms against 0.858 ms) and loads 3.61x faster (101 ms against 366 ms).

- `nima_technical_fp16.tflite` — the NPU is **1.54x faster** than the GPU (0.554 ms against 0.854 ms) and loads 3.74x faster (99 ms against 371 ms).

| file | backend | inference (median / min) | load |
|---|---|---:|---:|
| `nima_aesthetic_fp16.tflite` | NPU (Hexagon v81) | 0.565 ms / 0.537 ms | 101 ms |
| `nima_aesthetic_fp16.tflite` | GPU (Adreno) | 0.858 ms / 0.724 ms | 366 ms |
| `nima_technical_fp16.tflite` | NPU (Hexagon v81) | 0.554 ms / 0.537 ms | 99 ms |
| `nima_technical_fp16.tflite` | GPU (Adreno) | 0.854 ms / 0.726 ms | 371 ms |

Measured on a **Samsung Galaxy S26** (Snapdragon 8 Elite Gen 5 / SM8850, Hexagon v81, Android 16), LiteRT `CompiledModel` 2.2.0, one accelerator per process, 5 warm-up runs then N=50 timed runs, median reported. Every run held thermal status `NONE` throughout. Headroom 0.67-0.67, where 1.0 is the throttling threshold.

The NPU rows here ran artifacts compiled ahead of time for SM8850 with QAIRT 2.47.0; the GPU rows ran the published files as they are. LiteRT can also compile for the NPU on the device at first load, which is what lets you ship the published file unchanged — that path and the ten runtime libraries it needs are in the [NPU recipe](https://github.com/john-rocky/hf-to-litertlm/blob/main/docs/android-npu.md), and we did not measure it here. GPU wiring is in the [GPU recipe](https://github.com/john-rocky/hf-to-litertlm/blob/main/docs/android-gpu.md).

## Raspberry Pi 5 (CPU)

Measured on a Raspberry Pi 5 Model B Rev 1.1 (8 GB, Raspberry Pi OS 64-bit) with the LiteRT [`benchmark_model`](https://ai.google.dev/edge/litert/models/measurement) tool from litert-cli-nightly 0.2.0.dev20260805: CPU inference (XNNPACK, 4 threads), 3 invocations per file of 10 warm-up plus 50 timed runs (the tool caps a phase at 150 s, so very slow graphs run fewer — the Runs column is the actual timed total). The latency is the median across invocations; the spread is the min–max over all timed runs. No thermal throttling occurred during these runs (`vcgencmd get_throttled` stayed `0x0`).

| File | Inference (median) | Spread (min–max) | Runs | Peak memory |
|---|---:|---:|---:|---:|
| `nima_aesthetic_fp16.tflite` | 11.3 ms | 11.2–12.3 ms | 266 | 135 MB |
| `nima_technical_fp16.tflite` | 11.3 ms | 11.1–12.2 ms | 267 | 135 MB |
