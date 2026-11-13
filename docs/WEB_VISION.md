# Browser vision: what the detector reads, and how

FrameCritic's web app names the subject of a photo instead of writing about "피사체" in the
abstract. Two pieces do that, both inside the tab: a COCO object detector
(`web/src/lib/vision.ts`) and a pixel scene reader (`web/src/lib/subject.ts`). Nothing is uploaded.

## The model

`web/public/models/objects.tflite` is the MediaPipe EfficientDet-Lite0 int8 model
(4.6 MB, SHA-256 `0720bf24…23bb`, pinned in `app/src/main/assets/models/manifest.json`). It is the
same file the Android app hands to MediaPipe, executed here by LiteRT.js on WebAssembly/XNNPACK.
Measured in headless Chromium: about 75 ms per frame after the runtime is warm.

Input: `uint8[1,320,320,3]`, RGB, the model's own quantisation `(scale 1/128, zero point 127)`
maps it to `[-1,1]`. The image is letterboxed (aspect preserved, black padding) and the decoded
boxes are mapped back through the padding, so coordinates are fractions of the original photo.

Output: the model returns **raw heads**, not finished detections.

- `[1,19206,90]` class scores — the graph's last op is a LOGISTIC, so these are already
  probabilities in `[0,1]`; do not apply a sigmoid again.
- `[1,19206,4]` box encodings relative to the anchors.

### Class index convention

The 90 columns are COCO **category ids 1–90 in order**, so column *c* is category id *c+1*:
column 0 = person, column 5 = bus, column 16 = cat, column 17 = dog, column 44 = bowl. Ids with no
COCO category (12, 26, 29, 30, 45, 66, 68, 69, 71, 83) are empty slots and are skipped. There is no
background column. Treating column 0 as background (or shifting the whole list by one) is the
classic way to get "broccoli" on a portrait.

### Anchors

EfficientDet multi-scale anchors: levels 3–7 (stride 8/16/32/64/128 → 40×40, 20×20, 10×10, 5×5, 3×3),
anchor scale 4, three octave steps `2^(i/3)`, aspect ratios 2, 1, 0.5 → 9 anchors per cell,
19,206 in total. Order is row, column, octave step, aspect ratio (step outer, ratio inner).

Both the anchor order and the box-encoding order were measured on 2026-11-13 rather than assumed.
Six samples (a two-person press photo, a street with a bus and three people, a portrait, a cat, a
car, two cats) were traced with an independent YOLOX detector for ground-truth boxes, then every
plausible anchor order and encoding order was scored by mean IoU against them: step-outer with
ratios 2/1/0.5 decoded as `[dy, dx, dh, dw]` scored highest (0.644), the previous ratio-outer
`[dx, dy, dw, dh]` reading scored 0.604. The differences are visible in the boxes but modest, so
treat this as the best-scoring arrangement found, not as a proven exact match.

Box decoding is the SSD 10/10/5/5 encoding:

```
cx = ax + dx / 10 * aw      cy = ay + dy / 10 * ah
w  = exp(dw / 5) * aw       h  = exp(dh / 5) * ah
```

Boxes are kept above a 0.30 class score, deduplicated per class (IoU 0.48; 0.52 for person, because
two people standing side by side overlap heavily), and capped at ten.

## From detections to a subject

`readSubject` maps each detection to one of eight categories (person, animal, vehicle, landscape
water, landscape nature, urban/architecture, still object, general). The strongest
`score^1.5 × √area` detection becomes the subject — confidence dominates area, so a large misread box
cannot outrank a tighter confident one. Acceptance: a person needs score ≥ 0.30 and 1% of the frame,
anything else 0.36 and 1.5%. Guards keep the text honest:

- a person at 0.30 or more outranks a cattle/wildlife box (cow, sheep, horse, bear, elephant, zebra,
  giraffe) unless that box is both 0.85+ and 0.15 clear of the person — a portrait is not a cow;
- cattle/wildlife need 0.85 indoors, in the city or against the sky, and 0.60 outdoors; dogs, cats
  and birds need 0.40;
- an object detection in a scene that reads as water, nature, sky or city is ignored (a detector
  that calls waves a bed must not put "침대" in a seascape critique);
- when no detection passes, the subject is the landscape itself ("물가", "산", "숲", "들판",
  "도시의 선") or the neutral "장면", and the appendix says the reading rests on pixels only.

How many were found is counted from box centres, not from overlap: detections of the same label
above `max(0.42, best × 0.6)` are clustered, and two centres closer than 15% of the width and 22% of
the height count as one subject. One person's face, torso and full-body boxes share a centre; two
people side by side do not. Two people read "인물 2명", three or more "인물 여러 명".

## Scene reading

The image is split into top/middle/bottom bands; each band contributes mean RGB, luminance,
texture (luminance standard deviation), and the share of green (`g > r+8 and g > b+8`), blue
(`b > r+8`) and bright (`luma ≥ 200`) pixels. Rules, first match wins:

| Scene | Evidence |
|---|---|
| 밤 | mean luminance < 48 and almost no bright pixels |
| 물가/호수 | bottom band mostly blue under a bright top, or a bright warm top over a smooth dark bottom (sunset water) |
| 숲/들판 | green share ≥ 34% (textured green → 숲) |
| 산 | bright sky above a textured, darker middle band |
| 야외 | green share ≥ 15%, or a bright top without a sky reading |
| 하늘 | bright/blue top, no strong horizon |
| 도심 | low saturation with several straight line candidates |
| 실내 | everything else |

These are heuristics on purpose: the app can say "물가" from a bright warm sky over a smooth dark
band, but it never claims a named place (a specific sea, a specific mountain) and the appendix
prints the cues it used. The vision model in the Android app is MediaPipe's detector; the same
category vocabulary is applied to its labels in `app/src/main/java/com/framecritic/app/Subject.kt`.
