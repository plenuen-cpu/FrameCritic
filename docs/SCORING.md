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

Coverage = Σ weight×confidence. Otherwise V=Σ(weight×confidence×score)/coverage. Base x=.4×(rawNima−1)/9+.6×V. Adjusted=1+9×x^gamma, where Generous .75 / Balanced 1 / Strict 1.5. Anchors 1 and 10 are preserved; stricter modes demand more evidence for an intermediate high score instead of subtracting a fixed offset.

These are explicitly designed heuristic weights and curves, not empirical calibration against photographers or professional judges. Observed differences are explained by displayed weights, fixed evidence and level functions. Tone and depth never enter the scoring function. Cached image evidence remains the same when switching modes.

## Text generation

The Korean report is composed by a deterministic engine (`CriticEngine.kt`) from the measured facts: every section sentence is selected by score band, persona, strictness, tone and depth. It reports numbers it has, and states plainly what it cannot measure — light direction, intent, originality, emotion and the decisive moment. It never invents a subject: when the COCO detector finds nothing, the text says so instead of describing a scene.

OpenCV statistics are descriptive inputs to critique, not automatic quality penalties. Deliberately black shadows, motion blur, abstraction and asymmetry may be appropriate. Mean RGB cannot identify true white balance; Hough lines are candidates, never a guaranteed horizon. Originality cannot be established from one photograph.
