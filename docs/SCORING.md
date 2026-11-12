# Explicit, auditable scoring

NIMA: input RGB 224×224, float32 /127.5−1, output ten probabilities p1…p10. Raw score = Σ i×pi. Distribution standard deviation is displayed, not claimed as model confidence.

## Six measured axes

Every axis is computed on the device from OpenCV measurements, NIMA output and MediaPipe detection. No language model is involved, so the same photo and the same options always produce the same numbers and the same text.

| Axis | Basis | Score inputs |
|---|---|---|
| 구도·기하 (COMPOSITION) | 검출 상자의 삼분할 위치·점유율, 수평선 후보 각도 | 삼분할 교차점까지의 거리, 가장자리 접촉, 피사체 점유율, 수평선 기울기 |
| 빛·노출 (LIGHTING) | 휘도 분포, 하이라이트·암부 클리핑, 전역·국소 대비 | 중간톤 위치, 클리핑 합계, 전역 대비, 국소 대비 |
| 색채 (COLOR) | 채널 평균 균형, 색상 12구간 분포, 평균 채도 | 채널 간 차이, 색상 엔트로피·구간 수, 평균 채도의 중앙 선호 |
| 선명도·디테일 (SHARPNESS) | Laplacian 분산, 에지 밀도, 국소 대비 | Laplacian 분산(로그 스케일), Canny 에지 비율 |
| 감정·분위기 (EMOTION) | 피사체 존재·크기, 톤의 위치, 색온도 방향에서 추정 | 인물·동물 검출, 피사체 점유율, 중간값 휘도, 대비, 채도, 채널 차이 |
| 순간·타이밍 (TIMING) | 피사체 배열과 포착의 또렷함에서 추정 | 피사체 수, 인물 검출, Laplacian 분산, 국소 대비 |

Anchors are 0=unsupported organization, .5=coherent ordinary execution, 1=exceptional visible execution. Each axis carries a confidence that states how directly it is measured: lighting, color and sharpness use pixel statistics (0.74–0.92), while emotion and timing are inferences and stay near 0.42–0.58. Because every axis is grounded in at least one direct measurement, the engine applies a 0.42 confidence floor so a persona score is always available instead of failing on missing evidence.

| Perspective | Composition | Lighting | Color | Sharpness | Emotion | Timing |
|---|---:|---:|---:|---:|---:|---:|
| Cartier-Bresson | .30 | .10 | .05 | .10 | .15 | .30 |
| Adams | .20 | .35 | .10 | .20 | .10 | .05 |
| Leiter | .15 | .15 | .30 | .05 | .25 | .10 |
| Fan Ho | .25 | .30 | .05 | .10 | .15 | .15 |

Coverage = Σ weight×confidence. Otherwise V=Σ(weight×confidence×score)/coverage. Base x=.4×(rawNima−1)/9+.6×V. Adjusted=1+9×x^γ, where 2.1.0 uses Generous .40 / Balanced .55 / Strict .85. An exponent below 1 leaves 1.0 and 10.0 untouched and lifts the middle of the scale: on the Balanced curve a base of 5.5 reads 7.15, a base of 6.5 reads 7.86, a base of 8.5 reads 9.23. This is a deliberate, documented presentation choice — the app is meant to encourage a beginner rather than grade like a competition jury — not an empirical calibration.

Because the screen no longer offers a perspective choice, analysis runs with the balanced perspective (equal emphasis on composition, light, colour and detail) and Balanced strictness. The other perspectives and levels remain in the engine, and every score is still explained by the displayed weights and the fixed evidence.

These are explicitly designed heuristic weights and curves, not empirical calibration against photographers or professional judges. Observed differences are explained by displayed weights, fixed evidence and level functions. Tone and depth never enter the scoring function. Cached image evidence remains the same when switching modes.

## Text generation

The Korean report is composed by a deterministic engine (`CriticEngine.kt`) from the measured facts. The first ten entries of the report — 종합 크리틱, 빛과 노출, 색채, 구도, 선명도와 질감, 분위기와 순간, 잘 살아난 점, 더 다듬을 점, 촬영 제안, 보정 제안 — contain no digits and no measurement vocabulary: no 휘도, Laplacian, COCO, NIMA or percentage figures. They describe what is visible (the balance of a frame, whether light explains form, whether colour holds together, whether texture invites a closer look) and what it means for the photograph. `CriticEngineTest.critiqueBodyCarriesNoNumbersOrMachineWords` enforces that contract across every photo sample, perspective, level, tone and depth.

Every number the engine has lives in `CriticEngine.measurements()`, which feeds the fold-out **상세 측정 데이터 및 기술적 근거** block and the copied report text: NIMA raw scores and distribution, luminance statistics, clipping, contrast, RGB means, hue and saturation, Laplacian variance, edge density, detections with boxes and placement, per-axis evidence with confidence, and the scoring formula.

The engine reports numbers it has and states plainly what it cannot measure — light direction, intent, originality, emotion and the decisive moment. It never invents a subject: when the COCO detector finds nothing, the text says the frame is carried by light and space rather than describing a scene that is not there.

OpenCV statistics are descriptive inputs to critique, not automatic quality penalties. Deliberately black shadows, motion blur, abstraction and asymmetry may be appropriate. Mean RGB cannot identify true white balance; Hough lines are candidates, never a guaranteed horizon. Originality cannot be established from one photograph.
