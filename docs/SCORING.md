# Explicit, auditable scoring

NIMA: input RGB 224×224, float32 /127.5−1, output ten probabilities p1…p10. Raw score = Σ i×pi. Distribution standard deviation is displayed, not claimed as model confidence.

Eight subjective VLM axes use anchors 0=unsupported organization, .25=weak, .5=coherent ordinary execution, .75=strong expression, 1=exceptional visible execution. Each must include a visible observation, an interpretation and confidence. Confidence is model self-assessment, **not calibrated probability**. Cannot assess: low confidence; do not automatically penalize missing techniques.

| Perspective | Moment | Geometry | Tonal | Light | Color | Layers | Depth | Expression |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Cartier-Bresson | .30 | .27 | .04 | .05 | .03 | .07 | .09 | .15 |
| Adams | .02 | .12 | .32 | .25 | .04 | .03 | .12 | .10 |
| Leiter | .05 | .10 | .04 | .08 | .29 | .27 | .05 | .12 |
| Fan Ho | .07 | .20 | .14 | .26 | .02 | .04 | .19 | .08 |

Coverage = Σ weight×confidence. Coverage < .35 means no persona score. Otherwise V=Σ(weight×confidence×score)/coverage. Base x=.4×(rawNima−1)/9+.6×V. Adjusted=1+9×x^gamma, where Generous .75 / Balanced 1 / Strict 1.5. Anchors 1 and 10 are preserved; stricter modes demand more evidence for an intermediate high score instead of subtracting a fixed offset.

These are explicitly designed heuristic weights and curves, not empirical calibration against photographers or professional judges. Observed differences are explained by displayed weights, fixed evidence and level functions. Tone and depth never enter the scoring function. Cached image evidence remains the same when switching modes.

OpenCV statistics are descriptive inputs to critique, not automatic quality penalties. Deliberately black shadows, motion blur, abstraction and asymmetry may be appropriate. Mean RGB cannot identify true white balance; Hough lines are candidates, never a guaranteed horizon. Originality cannot be established from one photograph.
