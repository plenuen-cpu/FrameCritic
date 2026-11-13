# FrameCritic 2.4

Native Android application and browser app for on-device photo criticism. Kotlin / Jetpack Compose / Material 3. Korean interface. Samsung Galaxy S25 Ultra (ARM64, Android 9+) target.

**Download the APK and the complete Android Studio source with weights from the newest `offline-v2-*` prerelease.** GitHub's automatic Source code archive excludes large weights; use `FrameCritic-2.0.0-source-with-models.zip` for the self-contained source deliverable.

## Actual inference pipeline

1. Android photo picker → EXIF-aware ImageDecoder → sRGB, max long edge 1280.
2. NIMA MobileNet AVA and TID2013 TFLite inference; untouched 10-bin probability distributions and expectation.
3. OpenCV histogram, extreme luminance masks, RGB means, hue distribution, contrast, local contrast, Laplacian variance, Canny edge density, near-horizontal line candidates.
4. MediaPipe EfficientDet-Lite0 object detection with position, size, confidence and surrounding frame space. Detection is limited to COCO categories; it does not recognize every possible subject.
5. Stage 4/4 combines the NIMA distribution, OpenCV measurements and MediaPipe boxes into six evidence axes — composition, lighting, color, sharpness, emotion, timing — with a Korean observation and an interpretation for each.
6. A deterministic on-device critic engine writes ten critique sections from those facts — composition, light, colour, sharpness, mood, strengths, what to refine, shooting and editing advice — in plain Korean prose with no numbers or measurement vocabulary. Every raw value (NIMA distribution, luminance statistics, clipping, RGB, hue, Laplacian variance, detections, per-axis evidence, the scoring formula) is kept one tap down in **상세 측정 데이터 및 기술적 근거**. No language model, no child process, no network: analysis and report finish in a fraction of a second.

Only the three TFLite models (~17MB) are prepared on first launch. The GGUF vision/language weights stay in the repository manifest for the Linux host verification job but are not loaded, unpacked or packaged into the APK, so the app no longer spends minutes extracting 1.8GB or risks the OS killing a native inference process.

Scores are heuristic aesthetic judgments of measured image properties, not validated objective artistic quality, and the Korean text never claims to see a subject, intent or emotion the measurements do not support. See [scoring](docs/SCORING.md), [validation limits](docs/VALIDATION.md), [model provenance](docs/MODELS.md).

## Features

One-touch flow: pick a photo, press **사진 분석하기**, read the score and the critique. No perspective, strictness, tone or depth selection on screen — the app runs with a balanced point of view and a mentor tone. The score card shows a single number and a one-line impression; the critique follows as cards; the measurement appendix, NIMA distribution and luminance histogram sit in a fold-out block at the bottom. The overlay chips (삼분할 · 피사체 · 수평선 후보 · 클리핑) and tap-to-inspect regions stay for reading a photo already on screen. Text copy, report-image sharing, cancellation and explicit partial failure states are unchanged. Model/legal information is included in assets.

## Web app (`web/`)

The same critic engine, running entirely in the browser: no server, no upload, no account. Drop a
photo, press **사진 분석하기**, and the score plus ten critique sections appear; the raw measurements
sit in a fold-out block at the bottom.

- **Stack:** Vite + React + TypeScript + Tailwind CSS, static output in `web/dist`.
- **NIMA on device:** the aesthetic and technical scores come from the real NIMA MobileNet models
  (`nima_aesthetic`/`nima_technical`, fp16 TFLite, 6.4 MB each) executed inside the browser with
  [LiteRT.js](https://ai.google.dev/edge/litert/web) on WebAssembly/XNNPACK. The models are fetched
  once, cached by the browser and never uploaded to; a photo never leaves the tab. 224×224 RGB is
  normalised to [-1, 1], the softmax output is the 10-bin score distribution, and the score is its
  weighted mean (standard deviation is reported alongside). Measured with headless Chromium: ~85 ms
  for both models after a first load, and scores matched a Python LiteRT reference to within 0.11
  on the same photo (browser canvas resampling vs bicubic is the only difference). A first visit
  downloads about 29 MB — 17.5 MB of models (two NIMA nets plus the detector) and the 11 MB WASM
  runtime variant the browser picks — and the browser caches both.
- **Subject detection in the browser:** the same EfficientDet-Lite0 (COCO) model the Android app
  runs through MediaPipe is executed in the tab with LiteRT.js (`web/src/lib/vision.ts`). The
  model's raw heads (19,206 anchors × 90 COCO ids, sigmoid already applied) are decoded against
  EfficientDet's multi-scale anchor grid, deduplicated per class, and mapped back through the
  letterbox to image coordinates. It recognises person, dog/cat/bird, car/bicycle/bus, and the
  still-life objects that fit a frame, with a confidence score shown in the UI. Detections are
  taken from a 0.30 class score, people are deduplicated at an IoU of 0.52 so two people standing
  side by side stay two, and a person at 0.30 outranks a cattle/wildlife box unless that box is
  overwhelming (0.85 and clearly higher). Cattle and wildlife (cow, sheep, horse, bear, elephant,
  zebra, giraffe) need 0.85 indoors, in the city or against the sky and 0.60 outdoors; dogs, cats
  and birds need 0.40. How many were found is counted from distinct box centres, not from box
  overlap, so the face/torso/full-body boxes of one person read as one. See
  `docs/WEB_VISION.md` for the anchor layout and the class-index convention.
- **핵심 요약 (quick summary):** the result screen opens with a three-line card — 한 줄 총평,
  가장 돋보이는 점, 원포인트 조언 — written by the same deterministic engine from the same facts,
  and a 보기 toggle switches between that card and the ten full sections. The card is computed in
  `quickSummary()` on both sides, so the web app and the APK show the same three lines for the same
  photo.
- **Scene reading:** three horizontal bands of the image give mean colour, luminance texture and
  the share of blue/green/bright pixels; with the measured saturation and horizon candidates they
  classify the scene as water, nature (mountain/forest/field), outdoor, urban, sky, night or indoor
  (`web/src/lib/subject.ts`). The classifier only fires on combinations (e.g. a bright warm top
  above a smooth dark band for water) and otherwise reports the neutral "장면" — it is a scene
  estimate from pixels, and the appendix says so.
- **Assets:** `web/scripts/prepare-assets.cjs` downloads the three models (SHA-256 verified against
  `app/src/main/assets/models/manifest.json`), pins their dynamic batch dimension to 1 (LiteRT.js
  compares input shapes against the model's `shape_signature`), and copies the LiteRT WASM runtime
  out of `node_modules`. It runs automatically through `predev`/`prebuild`, so `public/models` and
  `public/litert` stay out of git.
- **Measurement:** HTML5 Canvas reads the pixels directly — 256-bin luminance histogram, mean
  luminance, highlight/shadow clipping, global contrast (standard deviation), local contrast
  (mean of 32px block standard deviations), Laplacian variance, Sobel edge density, mean RGB,
  channel spread, 12-bin hue histogram, mean saturation, near-horizontal line candidates
  (angle-projected accumulation) and a salience region from block contrast and edge density. The
  "주요 영역" box is the salience estimate used when the detector finds nothing; when it does find a
  subject, the overlay and the critique name that subject instead.
- **Engine:** `web/src/lib/critic-engine.ts` is a line-for-line port of the Kotlin engine. It was
  verified against 4,050 Kotlin outputs (5 measurement sets × 3 subject sets × 2 NIMA results ×
  every persona · level · tone · depth combination); section text and scores matched exactly. The
  subject voice (`web/src/lib/subject.ts`, mirrored in `app/.../Subject.kt`) adds the identified
  subject to the opening paragraph and to the light, colour, composition, texture, mood, strength,
  refinement, shooting and editing sections, with wording that fits a person, a pet, a car, water,
  mountains, a city or a single object.
- **Fallback:** if the models cannot be fetched, or the browser lacks WebAssembly, the analysis
  still runs on the canvas measurements, and both the UI and the copied report label the score
  "추정" (estimate) instead of "NIMA". The measurement appendix always states which path produced
  the score, plus the model inference time when the network ran.
- **Check:** `npm run build` runs `tsc --noEmit` and the production build; `node scripts/contract-check.cjs`
  replays 37,800 generated sections (four synthetic images × no subject and six identified subjects
  × every persona · level · tone · depth) and fails on any digit, any measurement word, or a subject
  that is not named in at least six of the ten sections.

```bash
cd web
npm install
npm run dev      # http://localhost:5173
npm run build    # type check + static build
```

Deployment to Vercel (import, root directory, build settings, CLI, troubleshooting) is in
[docs/VERCEL_DEPLOYMENT.md](docs/VERCEL_DEPLOYMENT.md). The repository ships both `vercel.json`
files, so importing the repo with the root directory set to `web` — or leaving it at the repository
root — builds without further configuration.

## Build through GitHub Actions

Push to `rebuild/offline-v2`, or use workflow_dispatch. CI verifies pinned SHA-256 weights, builds pinned llama.cpp for ARM64 and x86_64 (host verification only), runs Kotlin score and critic-engine tests, builds APK and device-test APK, runs Android lint, verifies APK signature/alignment/content, and runs real CPU NIMA inference plus the deterministic critic engine unit tests. Successful jobs publish a prerelease with APK, complete source including models, and test evidence.

Android Studio: open delivered source ZIP, install SDK 35 / NDK 28.2.13676358 and JDK 17. `bash scripts/prepare_runtime.sh android`, then `./gradlew assembleDebug`. Gradle wrapper is generated and included in the source artifact. Source Git checkout requires `python scripts/fetch_models.py` and `python scripts/fetch_licenses.py` first.

The app is signed with a CI debug certificate. Successive runs may use different debug certificates; uninstall the previous debug build before installing if Android reports a signature conflict. No public release signing key has been created.

## Verification honesty

GitHub host inference proves the pinned NIMA weights execute on Linux CPU. It **does not prove Galaxy S25 Ultra Android runtime behavior**. No physical device is connected to this task. Device latency, peak RAM, thermal behavior and Korean critique quality on real photos remain to be tested. The device instrumentation test sources and APK are supplied. See `docs/VALIDATION.md`.
