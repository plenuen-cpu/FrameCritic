# FrameCritic 2.4.0

A subject the app names correctly, and a three-line summary on top of it. The detector now reads
two people standing together as two, does not call a portrait a cow, and the result screen opens
with 한 줄 총평 · 가장 돋보이는 점 · 원포인트 조언 instead of ten paragraphs.

**Install note:** this APK is signed with the CI debug key, which differs from run to run. Uninstall
the previous FrameCritic before installing (`adb install -r` will fail with a signature mismatch).
After installing, the header must read a build number that matches this release tag (the value after
`offline-v2-`); if it does not, the old build is still the one running.

Downloads:
- `FrameCritic-2.0.0-arm64.apk`: installable APK. The file name keeps the 2.0 lineage that the
  release job has used since build 53; the app inside reports `v2.4.0`.
- `FrameCritic-2.0.0-source-with-models.zip`: complete Android Studio project, pinned weight files,
  licenses and Gradle wrapper.
- `FrameCritic-verification.zip`: Android build reports and Linux CPU inference outputs.

## What changed in this build

**Two people are two people.** The count used to come from boxes overlapping less than a third;
two people standing side by side overlap more than that, so the second one was deleted by
deduplication (IoU 0.32). Deduplication now runs at 0.52 for people, and the count is taken from
distinct box centres — the face, torso and full-body boxes of one person share a centre within 15% of
the width, two people do not. The badge reads `인물 2명 (신뢰도 83%)`, three or more read
`인물 여러 명`, and the opening paragraph of the critique has a two-person sentence of its own:
"화면에는 두 사람이 나란히 자리해 있고, 사진의 관심은 두 사람 사이의 거리와 시선에 놓여 있습니다."

**A portrait is not a cow.** Cattle and wildlife names (cow, sheep, horse, bear, elephant, zebra,
giraffe) were reaching portraits through coats, dark clothing and crouched poses at 0.38–0.45, and a
large box scored higher than a tight person box. Now:

- a person at 0.30 or more outranks a cattle/wildlife box unless that box is 0.85+ **and** 0.15
  clear of the person;
- cattle/wildlife need 0.85 indoors, in the city or against the sky, and 0.60 outdoors;
- dogs, cats and birds need 0.40 — they are ordinary companions, not misfires;
- the subject is chosen by `score^1.5 × √area`, so confidence leads and area only breaks ties;
- detections start at a 0.30 class score (Android `setScoreThreshold(0.30f)`, `setMaxResults(10)`),
  enough to keep a second person or a small product in frame without naming noise.

**Box geometry, measured instead of assumed.** The browser decoder's anchor order and box-encoding
order were traced on six photos with ground-truth boxes from an independent YOLOX detector. Octave
step outer, ratios 2/1/0.5, decoded as `[dy, dx, dh, dw]` scored best (mean IoU 0.644 against 0.604
for the previous reading). The gain is real but modest — it is the best-scoring arrangement of the
candidates tested, not a proof of the model's internal layout — and it is documented that way in
`docs/WEB_VISION.md`.

**한눈에 보는 핵심 요약.** Both the APK and the web app now open the result with a three-line card,
above the ten sections:

- 📌 **한 줄 총평** — the subject named from the detection plus the completeness band
  ("두 사람이 나란히 선 장면이 화면의 중심을 잡고, 균형이 좋은 한 장입니다.")
- ✨ **가장 돋보이는 점** — the strongest axis, or the subject's own praise sentence when that axis
  is only middling
- 💡 **원포인트 조언** — one actionable shooting or editing tip for that subject

A 보기 toggle (웹: `핵심 요약` / `상세 비평`, 앱: chips) switches between the card and the full ten
sections, and the card is the default. The brief is built by `quickSummary()` in
`web/src/lib/critic-engine.ts` and `CriticEngine.kt`, so the same photo gives the same three lines on
both, and it obeys the same contract: no digits, no measurement vocabulary, under 320 characters per
line. Copy and report-image sharing include the summary.

**Tests.** Kotlin: 36 unit tests, 13 of them subject tests — person beats a larger cattle box
indoors, two people count as two and three as several, an indoor cow at 0.62 is not named, an outdoor
cow is, a dog photo keeps its dog over a weak person, and the quick summary has three short lines
without numbers. Web: `tsc` clean, `npm run build` clean, the sentence contract replays 49,140
generated sections and summaries with zero failures, and `scripts/subject-check.cjs` replays 16
detection scenarios (person priority, cattle guard, two-person count, water misfire, empty
detection) with zero failures. Both were also driven in headless Chromium with real photographs:
zidane.jpg (two people) reads `인물 2명 (신뢰도 83%)` and the two-person summary, a portrait reads
`인물`, two cats read `고양이 여러 마리`, and a still life falls back to `장면 추정`.

## Limits

- The detector is COCO's 80 classes; a still life whose object is not confidently recognised stays
  "장면 추정" rather than guessing a name.
- Box geometry is approximate on this model (mean IoU against an independent detector is around
  0.6), which is enough for naming, counting and the overlay, not for measuring subject size.
- No physical Galaxy S25 Ultra test was performed for this build. Kotlin unit tests, the APK
  signature/alignment/content check and host inference checks run in CI; they do not establish
  Android runtime behaviour.

## Carried over

- Ten critique sections written at length (Quick 4 / Standard 6 / Expert 9 sentences per section),
  deterministic on-device, no language model, no network, no child process.
- Subject-aware vocabulary for person, companion animal, vehicle, water, nature, urban and still
  object; scene reading from pixel bands; the neutral fallback when nothing is clear.
- No manual `recycle()` of a bitmap the screen draws; every analysis stage can fail alone and hand a
  neutral, labelled result to the next; crash and stage records; 1280 px analysis cap; report copy
  and image sharing.
- Score curve `1 + 9·x^γ` with γ = 0.40 / 0.55 / 0.85 — unchanged.

**Version.** `versionCode` 26, `versionName` 2.4.0. The header reads `v2.4.0 (Build N)`, N being the
CI run number and therefore this release tag.
