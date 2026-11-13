# FrameCritic 2.3.0

The critique now names what is in the photo. Instead of writing about "피사체", the engine says
인물, 반려견, 고양이, 자동차, 바다, 물가, 산, 숲, 들판, 도심 or the object it actually found (그릇, 컵,
노트북 …), and every section is written in that subject's own vocabulary.

**Install note:** this APK is signed with the CI debug key, which differs from run to run. Uninstall
the previous FrameCritic before installing (`adb install -r` will fail with a signature mismatch).
After installing, the header must read a build number that matches this release tag (the value after
`offline-v2-`); if it does not, the old build is still the one running.

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable APK. The file name keeps the 2.0 lineage that the
  release job has used since build 53; the app inside reports `v2.3.0`.
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, pinned weight files,
  licenses and Gradle wrapper.
- `FrameCritic-verification.zip`: Android build reports and Linux CPU inference outputs.

## What changed in this build

**A subject reading, shared by the app and the web app.** `SubjectCategory` (person, animal,
vehicle, landscape-water, landscape-nature, urban, still object, general) and `SceneContext`
(indoor, outdoor, urban, nature, water, sky, night) now live in `Domain.kt` and `domain.ts`.
`Subject.kt` / `subject.ts` turn the detector's boxes and the scene cues into one `SubjectScene`:
the subject's name, how many of them were found, the detector's confidence, the scene label, and
whether the subject separates from its background by brightness.

**Ten sections that speak about that subject.** The opening paragraph of 종합 크리틱 names it, and
빛과 노출, 색채, 구도, 선명도와 질감, 분위기와 순간, 잘 살아난 점, 더 다듬을 점, 촬영 제안, 보정 제안
each carry a sentence written for that category — rim light on fur, chrome highlights along a car's
shoulder, an eye-line at a low angle, a horizon dividing sky and water, a mountain ridge fading
with distance, a pot's shadow on a windowsill. Both sentence variants of every slot mention the
subject, so the word appears in at least six of the ten sections whatever the seed. The no-numbers,
no-measurement-vocabulary contract is unchanged, and the raw values stay in the appendix.

**The scene is read from pixels, and said honestly.** Three bands of the frame (mean colour,
texture, green/blue/bright shares) plus saturation and horizon candidates classify water, nature,
outdoor, urban, sky, night or indoor. Water needs a bright warm top over a smooth dark band, or a
blue bottom under a bright top; a textured dark bottom reads as a mountain, not a sea. When nothing
clearly fits, the subject stays the neutral 장면 and the appendix says the reading rests on pixels
only. Detector names that contradict an outdoor scene (waves called a bed) are dropped rather than
printed.

**Web app: real object detection in the tab.** `web/src/lib/vision.ts` runs the same
EfficientDet-Lite0 COCO model as the Android app through LiteRT.js, decodes its raw heads against
EfficientDet's anchor grid, and feeds the boxes into the same critique engine; the new subject row
sits above the critique and the appendix shows "피사체 · 신뢰도 · 장면 · 피사체·배경 분리 · 객체 탐지
시간". A first visit now downloads about 17.5 MB of models (two NIMA nets plus the 4.6 MB detector).

**Tests.** Kotlin: 23 unit tests, including six new subject-scene tests (every category named in at
least six sections, no digits or measurement words in any depth, scene reading, misfire guard,
empty fallback). Web: `tsc` clean, `npm run build` clean, and the sentence contract replays 37,800
generated sections with zero failures.

## Carried over from build 66

**Ten critique sections, written at length.** Each section is now built from a pool of
measurement-driven sentences instead of one or two. Depth sets how many: Quick four, **Standard six
(the app's default)**, Expert nine.

**Ten critique sections, written at length.** Each section is now built from a pool of
measurement-driven sentences instead of one or two. Depth sets how many: Quick four, **Standard six
(the app's default)**, Expert nine. Because most sentences carry two clauses, a Standard section
runs about eight to twelve sentences and 350–450 Korean characters, an Expert section fourteen to
nineteen sentences and 500–640 characters — against roughly 100–150 characters in build 65. The
sections cover what the brief asked for: the whole frame's breath and the meeting of light and
colour in 종합 크리틱 (now three paragraphs); tone, the closing of both ends of the scale, highlight
roll-off, shadow detail, how light models form and whether the light reads soft or hard in 빛과 노출;
colour temperature, dominant and supporting colour, saturation restraint, the emotional air of the
palette, whether colour follows form or bleeds past it, and where the colour weight sits in 색채;
placement, horizon candidates, negative space and crowding, left-right balance, the path the eye
takes, and how the frame holds its subject in 구도; focus, surface texture, separation from the
background, edge character, evenness of detail, and whether the blur is deliberate in 선명도와 질감;
tone of the moment, human presence, stillness against a caught instant, the story the frame holds,
and what stays after you close it in 분위기와 순간; then strengths, refinements, shooting advice
(angle, distance, metering, shutter, aperture in words rather than numbers) and editing advice
(tone curve, white balance, highlight and shadow balance, noise order, crop).

**The contract still holds.** No section body contains a digit or one of the machine terms
(관찰되었, 관측, Laplacian, COCO, 휘도, 255, 검출, 측정, NIMA, 히스토그램, 클리핑, 분산); the Kotlin
unit test `critiqueBodyCarriesNoNumbersOrMachineWords` runs on every persona · level · tone · depth
combination and fails the build otherwise. Every raw value still appears in
**상세 측정 데이터 및 기술적 근거**. The score curve is untouched: `1 + 9·x^γ` with γ = 0.40 / 0.55 /
0.85 — build 65's numbers stay valid.

**New: browser app in `web/`.** Vite + React + TypeScript + Tailwind, 100% client-side. The photo
never leaves the tab: pixels are read through Canvas and the TypeScript port of the same engine
writes the same Korean prose. `web/src/lib/critic-engine.ts` was verified against 4,050 Kotlin
outputs (five measurement sets × three subject sets × two NIMA results × every persona, level, tone
and depth) with zero differences in section text or score. Measurement in the browser covers the
256-bin luminance histogram, mean luminance, clipping, global and local contrast, Laplacian variance,
Sobel edge density, mean RGB, channel spread, the 12-bin hue histogram, mean saturation, horizontal
line candidates and a salience region. The browser has no NIMA or detector weights, so the aesthetic
score and the "주요 영역" box are heuristics from those measurements; the UI labels them 추정 and the
README/doc say so. `npm run build` runs `tsc --noEmit` plus the production build, and
`node scripts/contract-check.cjs` replays 37,800 generated sections against the same contract.

**Deployment.** Both `vercel.json` files are in the repository; importing `plenuen-cpu/FrameCritic`
with the root directory set to `web` builds with no further configuration. See
`docs/VERCEL_DEPLOYMENT.md`.

**Version.** `versionCode` 25, `versionName` 2.3.0. The header reads `v2.3.0 (Build N)`, N being the
CI run number and therefore this release tag.

## Carried over unchanged

- No manual `recycle()` of any bitmap the screen draws; the image the screen renders is never handed
  to MediaPipe or released by the analysis.
- Every analysis stage can fail alone and hand a neutral, clearly labelled result to the next; the
  report is always filled with all ten critique sections.
- Crash record with the stack trace and a stage record naming the step that was running.
- Images capped at 1280 px on the long edge, GGUF weights not packaged, no child process and no
  network. Report copy and report-image sharing.

**No physical Galaxy S25 Ultra test was performed.** Kotlin unit tests, the APK
signature/alignment/content check and host inference checks run in CI, but they do not establish
Android runtime behaviour. On the web side, the build is type-checked, the engine is compared against
the Kotlin output, and the app was driven in a headless Chromium session with a real photograph
(upload → analysis → ten sections → measurement appendix) with no console error; that is not a test
on the user's own browser or device.
