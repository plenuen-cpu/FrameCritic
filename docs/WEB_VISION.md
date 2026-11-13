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
anchor scale 4, three octave steps `2^(i/3)`, aspect ratios 1, 2, 0.5 → 9 anchors per cell,
19,206 in total. Order is row, column, aspect ratio, scale. Box decoding is the SSD 10/10/5/5
encoding:

```
cx = ax + dx / 10 * aw      cy = ay + dy / 10 * ah
w  = exp(dw / 5) * aw       h  = exp(dh / 5) * ah
```

Boxes are kept above a 0.35 class score, deduplicated per class (IoU 0.32, preferring the tighter
box when two proposals score within 0.03), and capped at ten.

## From detections to a subject

`readSubject` maps each detection to one of eight categories (person, animal, vehicle, landscape
water, landscape nature, urban/architecture, still object, general). The strongest
`score × √area` detection with score ≥ 0.4 and area ≥ 2% of the frame becomes the subject; the
count of same-name detections above 0.45 sets "여러 마리/여러 명". Two guards keep the text honest:

- an object detection in a scene that reads as water, nature, sky or city is ignored (a detector
  that calls waves a bed must not put "침대" in a seascape critique);
- when no detection passes, the subject is the landscape itself ("물가", "산", "숲", "들판",
  "도시의 선") or the neutral "장면", and the appendix says the reading rests on pixels only.

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
