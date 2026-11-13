/**
 * FrameCritic 결정론적 오프라인 크리틱 엔진 (웹 포팅).
 *
 * Kotlin 엔진(app/src/main/java/com/framecritic/app/CriticEngine.kt)과 같은 계산, 같은 문장,
 * 같은 순서를 쓴다. 화면에 보이는 비평 본문에는 수치도 계측 어휘도 쓰지 않고, 모든 원시 값은
 * measurements()의 '상세 측정 데이터 및 기술적 근거' 블록에만 남긴다.
 */

import {
  AXIS_ORDER,
  AXIS_TITLES,
  Axis,
  Depth,
  DepthKey,
  Evidence,
  Level,
  LevelKey,
  NimaResult,
  NimaSource,
  Persona,
  PersonaKey,
  ScoreResult,
  SubjectScene,
  ToneKey,
  evaluate,
} from './domain'
import { KO_LABELS, buildVoice } from './subject'

export interface LineCandidate {
  angle: number
  length: number
}

export interface SubjectBoxInit {
  label: string
  confidence: number
  cx: number
  cy: number
  w: number
  h: number
}

export class SubjectBox {
  readonly label: string
  readonly confidence: number
  readonly cx: number
  readonly cy: number
  readonly w: number
  readonly h: number

  constructor(init: SubjectBoxInit) {
    this.label = init.label
    this.confidence = init.confidence
    this.cx = init.cx
    this.cy = init.cy
    this.w = init.w
    this.h = init.h
  }

  get area(): number {
    return this.w * this.h
  }

  get touchesEdge(): boolean {
    return (
      this.cx - this.w / 2 < 0.02 ||
      this.cy - this.h / 2 < 0.02 ||
      this.cx + this.w / 2 > 0.98 ||
      this.cy + this.h / 2 > 0.98
    )
  }
}

export interface MeasureInit {
  width: number
  height: number
  luminance: number
  highlights: number
  shadows: number
  contrast: number
  localContrast: number
  sharpness: number
  rgb: number[]
  hues: number[]
  histogram: number[]
  lines: LineCandidate[]
  saturation: number
  edges: number
}

export class Measure {
  readonly width: number
  readonly height: number
  readonly luminance: number
  readonly highlights: number
  readonly shadows: number
  readonly contrast: number
  readonly localContrast: number
  readonly sharpness: number
  readonly rgb: number[]
  readonly hues: number[]
  readonly histogram: number[]
  readonly lines: LineCandidate[]
  readonly saturation: number
  readonly edges: number

  constructor(init: MeasureInit) {
    this.width = init.width
    this.height = init.height
    this.luminance = init.luminance
    this.highlights = init.highlights
    this.shadows = init.shadows
    this.contrast = init.contrast
    this.localContrast = init.localContrast
    this.sharpness = init.sharpness
    this.rgb = init.rgb
    this.hues = init.hues
    this.histogram = init.histogram
    this.lines = init.lines
    this.saturation = init.saturation
    this.edges = init.edges
  }

  percentile(p: number): number {
    if (this.histogram.length === 0) return 0
    const total = this.histogram.reduce((acc, v) => acc + v, 0)
    if (total <= 0) return 0
    let acc = 0
    for (let i = 0; i < this.histogram.length; i++) {
      acc += this.histogram[i]
      if (acc / total >= p) return i
    }
    return this.histogram.length - 1
  }

  get channelCast(): number {
    if (this.rgb.length < 3) return 0
    return Math.max(...this.rgb) - Math.min(...this.rgb)
  }

  get warmth(): number {
    if (this.rgb.length < 3) return 0
    return this.rgb[0] - this.rgb[2]
  }

  get hueTotal(): number {
    return this.hues.reduce((acc, v) => acc + v, 0)
  }

  get dominantHue(): number {
    let best = 0
    for (let i = 0; i < this.hues.length; i++) if (this.hues[i] > this.hues[best]) best = i
    return best
  }

  get dominantShare(): number {
    const total = this.hueTotal
    return total <= 0 ? 0 : this.hues[this.dominantHue] / total
  }

  get hueVariety(): number {
    const total = this.hueTotal
    if (total <= 0) return 0
    return this.hues.filter((count) => count / total >= 0.03).length
  }

  get hueCoverage(): number {
    const pixels = this.width * this.height
    return pixels <= 0 ? 0 : this.hueTotal / pixels
  }

  get hueEntropy(): number {
    const total = this.hueTotal
    if (total <= 0) return 0
    let e = 0
    for (const count of this.hues) {
      const p = count / total
      if (p > 0) e -= p * Math.log(p)
    }
    return clamp01(e / Math.log(12))
  }

  get maxTilt(): number {
    return this.lines.reduce((max, line) => Math.max(max, Math.abs(line.angle)), 0)
  }
}

export interface Assessment {
  nima: NimaResult
  measure: Measure
  subjects: SubjectBox[]
  /** 사진의 주인공과 장면. 읽지 못했으면 null이다. */
  scene: SubjectScene | null
  evidence: Evidence[]
  observations: Record<string, string>
  description: string
}

export interface MeasureGroup {
  title: string
  rows: [string, string][]
}

const HUMAN = new Set(['person', 'cat', 'dog', 'bird', 'horse', 'sheep', 'cow'])

const HUE_NAMES = [
  '빨강',
  '주황',
  '노랑',
  '연두',
  '초록',
  '청록',
  '하늘',
  '파랑',
  '남보라',
  '보라',
  '자주',
  '분홍',
]

/** COCO 이름의 한국어 표기는 subject.ts 한 곳에서만 관리한다. */
const LABELS: Record<string, string> = KO_LABELS
/** 화면 본문에 보여줄 비평 항목. 수치는 이 목록이 아니라 measurements()에만 담는다. */
export const SECTION_TITLES = [
  '종합 크리틱',
  '빛과 노출',
  '색채',
  '구도',
  '선명도와 질감',
  '분위기와 순간',
  '잘 살아난 점',
  '더 다듬을 점',
  '촬영 제안',
  '보정 제안',
]

const CONFIDENCE_FLOOR = 0.42

// ---------- Korean particles and formatting ----------
function koLabel(label: string): string {
  return LABELS[label.toLowerCase()] ?? label
}

function hasFinalConsonant(word: string): boolean {
  if (!word) return false
  const code = word.charCodeAt(word.length - 1)
  if (code < 0xac00 || code > 0xd7a3) return false
  return (code - 0xac00) % 28 !== 0
}

function eun(word: string): string {
  return word + (hasFinalConsonant(word) ? '은' : '는')
}
function wa(word: string): string {
  return word + (hasFinalConsonant(word) ? '과' : '와')
}
function iGa(word: string): string {
  return word + (hasFinalConsonant(word) ? '이' : '가')
}
function f1(v: number): string {
  return (Number.isFinite(v) ? v : 0).toFixed(1)
}
function f2(v: number): string {
  return (Number.isFinite(v) ? v : 0).toFixed(2)
}
function p0(v: number): string {
  return Math.round((Number.isFinite(v) ? v : 0) * 100) + '%'
}
function p1(v: number): string {
  return ((Number.isFinite(v) ? v : 0) * 100).toFixed(1) + '%'
}
function p2(v: number): string {
  return ((Number.isFinite(v) ? v : 0) * 100).toFixed(2) + '%'
}
function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v))
}
function finite(v: number, fallback: number): number {
  return Number.isFinite(v) ? v : fallback
}
function ramp(v: number, low: number, high: number): number {
  return high <= low ? 0 : clamp01((v - low) / (high - low))
}
function band(score: number): number {
  return score >= 0.72 ? 2 : score >= 0.45 ? 1 : 0
}

function nearestThirds(cx: number, cy: number): number {
  let best = 1
  for (const tx of [1 / 3, 2 / 3]) {
    for (const ty of [1 / 3, 2 / 3]) {
      const d = Math.hypot(cx - tx, cy - ty)
      if (d < best) best = d
    }
  }
  return best
}

/** 같은 사진이면 같은 문장이 나오도록, 측정값에서만 문장 변형 시드를 만든다. */
function seedOf(m: Measure): number {
  const raw =
    m.luminance * 7 + m.contrast * 3 + m.sharpness + m.saturation * 97 + m.edges * 1000 + m.width
  return Number.isFinite(raw) ? Math.round(raw) : 0
}

function pick(seed: number, items: string[]): string {
  if (items.length === 0) return ''
  const index = ((seed % items.length) + items.length) % items.length
  return items[index]
}

/** 히스토그램이 비어 있어도 톤 분류가 가능하도록 평균 휘도로 대신한다. */
function medianTone(m: Measure): number {
  const total = m.histogram.reduce((acc, v) => acc + v, 0)
  return m.histogram.length > 0 && total > 0 ? m.percentile(0.5) : Math.round(m.luminance)
}

// ---------- assessment ----------
interface Ax {
  score: number
  confidence: number
  observation: string
  interpretation: string
}

function neutralAx(): Ax {
  return {
    score: 0.5,
    confidence: CONFIDENCE_FLOOR,
    observation: '이 항목의 측정값을 읽지 못했습니다.',
    interpretation: '이 부분은 이번 측정에서 판단할 근거를 얻지 못했습니다.',
  }
}

export function assess(
  nima: NimaResult,
  measure: Measure,
  subjects: SubjectBox[],
  scene: SubjectScene | null = null,
): Assessment {
  const sorted = subjects
    .filter(
      (s) =>
        Number.isFinite(s.confidence) &&
        Number.isFinite(s.cx) &&
        Number.isFinite(s.cy) &&
        Number.isFinite(s.w) &&
        Number.isFinite(s.h),
    )
    .slice()
    .sort((a, b) => b.area - a.area)

  const builders: (() => Ax)[] = [
    () => composition(measure, sorted),
    () => lighting(measure),
    () => color(measure),
    () => sharpness(measure),
    () => emotion(nima, measure, sorted),
    () => timing(measure, sorted),
  ]
  const axes = builders.map((build) => {
    try {
      return build()
    } catch {
      return neutralAx()
    }
  })
  const evidence: Evidence[] = axes.map((ax, i) => ({
    score: clampRange(finite(ax.score, 0.5), 0.05, 0.95),
    confidence: clampRange(finite(ax.confidence, CONFIDENCE_FLOOR), CONFIDENCE_FLOOR, 1),
    observation:
      ax.observation.trim().length > 0
        ? ax.observation
        : `${AXIS_TITLES[AXIS_ORDER[i]]} 항목의 관측 값을 만들지 못했습니다.`,
    interpretation:
      ax.interpretation.trim().length > 0
        ? ax.interpretation
        : '이 부분은 이번 측정에서 판단할 근거를 얻지 못했습니다.',
  }))
  const observations: Record<string, string> = {}
  AXIS_ORDER.forEach((axis, i) => {
    observations[Axis[axis]] = evidence[i]?.observation ?? `${AXIS_TITLES[axis]} 항목의 관측을 만들지 못했습니다.`
  })
  let description = '측정값 요약을 만들지 못했습니다. 아래 항목별 근거를 참고하십시오.'
  try {
    description = describe(measure, sorted)
  } catch {
    /* 기본 문장을 그대로 둔다. */
  }
  return { nima, measure, subjects: sorted, scene, evidence, observations, description }
}

function clampRange(v: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, v))
}

export function neutralAssessment(
  nima: NimaResult,
  measure: Measure,
  subjects: SubjectBox[],
  scene: SubjectScene | null = null,
): Assessment {
  const evidence: Evidence[] = AXIS_ORDER.map((axis) => ({
    score: 0.5,
    confidence: CONFIDENCE_FLOOR,
    observation: `${AXIS_TITLES[axis]} 항목의 측정값을 정리하지 못했습니다.`,
    interpretation: '이 부분은 이번 측정에서 판단할 근거를 얻지 못했습니다.',
  }))
  const observations: Record<string, string> = {}
  AXIS_ORDER.forEach((axis, i) => {
    observations[Axis[axis]] = evidence[i].observation
  })
  return {
    nima,
    measure,
    subjects,
    scene,
    evidence,
    observations,
    description: '측정값 요약을 만들지 못해 항목별 근거만 남겼습니다.',
  }
}

export function safeAssess(
  nima: NimaResult,
  measure: Measure,
  subjects: SubjectBox[],
  scene: SubjectScene | null = null,
): Assessment {
  try {
    return assess(nima, measure, subjects, scene)
  } catch {
    return neutralAssessment(nima, measure, subjects, scene)
  }
}

// ---------- measurements (numbers only live here) ----------
function composition(m: Measure, subjects: SubjectBox[]): Ax {
  const main = subjects[0]
  const crowded = subjects.filter((s) => s.touchesEdge && s.area > 0.05).length
  const tiltScore = m.lines.length === 0 ? 0 : 1 - ramp(m.maxTilt, 1, 9)
  const placement = main ? 1 - ramp(nearestThirds(main.cx, main.cy), 0.02, 0.32) : 0.5
  const room = 1 - ramp(crowded, 0, 2)
  const size = main ? 1 - ramp(Math.abs(main.area - 0.18), 0.1, 0.42) : 0.5
  const score = main
    ? 0.18 + 0.44 * placement + 0.16 * room + 0.12 * size + 0.1 * tiltScore
    : 0.46 + 0.1 * tiltScore
  const confidence =
    subjects.length > 0 && m.lines.length > 0
      ? 0.86
      : subjects.length > 0
        ? 0.74
        : m.lines.length > 0
          ? 0.56
          : 0.44
  const labels = subjects
    .slice(0, 3)
    .map((s) => `${koLabel(s.label)} ${f2(s.confidence)}`)
    .join(', ')
  const subjectText = main
    ? `주요 영역 ${subjects.length}개가 잡혔습니다(${labels}). 가장 큰 영역은 중심 (${p0(main.cx)}, ${p0(main.cy)})에서 프레임의 ${p1(main.area)}를 차지하고, 삼분할 교차점에서 프레임 폭의 ${p0(nearestThirds(main.cx, main.cy))}만큼 떨어져 있습니다.`
    : '객체 검출 범주에 해당하는 피사체는 잡히지 않았습니다. 빈 프레임이라는 뜻은 아닙니다.'
  const lineText =
    m.lines.length === 0
      ? '수평선 후보는 잡히지 않았습니다.'
      : `수평선 후보 ${m.lines.length}개의 최대 기울기는 ${f1(m.maxTilt)}°입니다.`
  const edgeText = crowded > 0 ? ` 가장자리에 닿은 요소가 ${crowded}개 있습니다.` : ''
  const observation = `${subjectText}${edgeText} ${lineText}`
  const interpretation = main
    ? band(score) === 2
      ? '시선이 한 번에 장면을 읽게 되는, 계산된 여백의 힘입니다.'
      : band(score) === 1
        ? '장면은 읽히지만 중심이 흐려, 보는 사람이 어디에 시선을 둘지 잠시 망설이게 됩니다.'
        : '프레임이 대상을 붙잡지 못해, 사진의 인상이 가장자리로 흘러 나갑니다.'
    : band(score) === 2
      ? '대상 없이도 면과 빛의 배열이 화면을 지탱합니다. 여백이 사진의 주제가 된 드문 경우입니다.'
      : band(score) === 1
        ? '여백이 주인공이 된 화면이라, 중심을 잡아 줄 요소가 하나만 더 있으면 인상이 완성됩니다.'
        : '화면 안에서 눈이 머물 자리를 찾기 어려워, 사진의 인상이 흩어집니다.'
  return { score, confidence, observation, interpretation }
}

function lighting(m: Measure): Ax {
  const exp = m.luminance / 255
  const placement = 1 - ramp(Math.abs(exp - 0.46), 0.02, 0.34)
  const clip = m.highlights + m.shadows
  const clipScore = 1 - ramp(clip, 0.01, 0.12)
  const separation = ramp(m.contrast, 12, 52)
  const local = ramp(m.localContrast, 3, 20)
  const score = 0.3 * (0.45 + 0.55 * placement) + 0.24 * clipScore + 0.28 * separation + 0.18 * local
  const observation = `휘도 평균 ${f1(m.luminance)}/255, 중간값 ${m.percentile(0.5)}, 5퍼센타일 ${m.percentile(0.05)}, 95퍼센타일 ${m.percentile(0.95)}입니다. 하이라이트 ${p2(m.highlights)}, 암부 ${p2(m.shadows)}가 잘렸고, 전역 대비 ${f1(m.contrast)}, 국소 대비 ${f1(m.localContrast)}입니다.`
  const interpretation =
    band(score) === 2
      ? '빛이 형태를 설명하고 있어, 사진을 보는 동안 장면의 공기까지 느껴집니다.'
      : band(score) === 1
        ? '빛이 고르게 퍼진 대신 깊이가 옅어, 장면이 평평하게 읽힙니다.'
        : '빛이 담고 있던 정보가 양 끝에서 사라져, 형태를 설명하는 힘이 약해졌습니다.'
  return { score, confidence: clip < 0.02 ? 0.92 : 0.88, observation, interpretation }
}

function color(m: Measure): Ax {
  const castScore = 1 - ramp(m.channelCast, 6, 30)
  const hueScore = m.hueCoverage < 0.05 ? 0.5 : ramp(m.hueEntropy, 0.35, 0.85)
  const satScore = 1 - ramp(Math.abs(m.saturation - 0.22), 0.06, 0.3)
  let score = 0.34 * castScore + 0.34 * hueScore + 0.32 * (0.35 + 0.65 * satScore)
  if (m.saturation < 0.1) score -= 0.08
  const hueText =
    m.hueTotal <= 0
      ? '유채색 픽셀이 거의 없어 색상 분포를 읽기 어렵습니다.'
      : `색상 12구간 중 ${m.hueVariety}개에 분포가 있고 ${HUE_NAMES[m.dominantHue]} 계열이 ${p0(m.dominantShare)}로 가장 넓습니다.`
  const observation = `평균 RGB는 ${f1(m.rgb[0] ?? 0)}/${f1(m.rgb[1] ?? 0)}/${f1(m.rgb[2] ?? 0)}, 채널 간 차이는 ${f1(m.channelCast)}, 평균 채도는 ${p1(m.saturation)}입니다. ${hueText}`
  const interpretation =
    m.saturation < 0.12
      ? '색을 덜어낸 화면은 형태와 빛으로 말을 걸지만, 색의 온기로 감정을 만드는 힘은 줄어듭니다.'
      : m.channelCast > 12
        ? '색온도가 한쪽으로 기울어 있어, 피사체의 고유색이 배경과 구분되는지 확인할 지점입니다.'
        : band(score) === 2
          ? '색이 서로를 지지하며 하나의 온도로 정리되어, 화면이 차분하게 가라앉습니다.'
          : band(score) === 1
            ? '색은 자연스럽지만 방향이 뚜렷하지 않아, 분위기를 색이 끌어 주지는 못합니다.'
            : '색이 흩어져 화면의 인상이 하나로 모이지 않습니다.'
  return { score, confidence: m.saturation < 0.12 || m.hueCoverage < 0.05 ? 0.62 : 0.74, observation, interpretation }
}

function sharpness(m: Measure): Ax {
  const sharpScore = ramp(Math.log10(1 + m.sharpness), Math.log10(16), Math.log10(251))
  const edgeScore = ramp(m.edges, 0.004, 0.09)
  const score = 0.16 + 0.58 * sharpScore + 0.26 * edgeScore
  const observation = `라플라시안 분산 ${f1(m.sharpness)}, 에지 밀도 ${p2(m.edges)}, 국소 대비 ${f1(m.localContrast)}이며 분석 해상도는 ${m.width}×${m.height}입니다.`
  const interpretation =
    band(score) === 2
      ? '초점이 놓인 자리가 분명해, 보는 눈이 자연스럽게 그곳으로 갑니다.'
      : band(score) === 1
        ? '형태는 읽히지만 표면의 결이 눌려 있어, 질감의 밀도가 아쉽습니다.'
        : '질감보다 분위기로 읽히는 사진입니다. 의도된 흐림이라면 그 자체로 표현이 됩니다.'
  return { score, confidence: 0.82, observation, interpretation }
}

function emotion(nima: NimaResult, m: Measure, subjects: SubjectBox[]): Ax {
  const human = subjects.some((s) => HUMAN.has(s.label.toLowerCase()))
  const main = subjects[0]
  const p50 = medianTone(m)
  const observedMedian = m.percentile(0.5)
  let s = 0.44
  if (subjects.length > 0) s += 0.08
  if (human) s += 0.06
  if (main && main.area >= 0.04 && main.area <= 0.55) s += 0.05
  if (p50 < 85) s += 0.05
  if (m.contrast >= 40) s += 0.05
  if (m.saturation >= 0.28) s += 0.04
  if (m.channelCast >= 10) s += 0.03
  if (m.contrast < 16) s -= 0.07
  if (subjects.length === 0) s -= 0.05
  const mainText = main
    ? `가장 큰 영역(${koLabel(main.label)})가 프레임의 ${p1(main.area)}를 차지합니다`
    : '객체 검출 범주에 해당하는 피사체는 잡히지 않았습니다'
  const keyText = p50 < 70 ? '저조도(low-key) 톤' : p50 > 170 ? '고조도(high-key) 톤' : '중간톤'
  const tempText =
    m.channelCast < 6
      ? '색온도 치우침이 거의 없는 중성'
      : m.warmth > 0
        ? '붉은 쪽으로 기운 따뜻한'
        : '푸른 쪽으로 기운 차가운'
  const observation = `${keyText}(중간값 휘도 ${observedMedian})이고, ${mainText}. 채널 차 ${f1(m.channelCast)}의 ${tempText} 색입니다. 감정은 측정값이 아니므로 이 지표들에서 추정합니다.`
  const interpretation =
    band(s) === 2
      ? '장면의 온도가 한 방향으로 모여 있어, 사진을 오래 들여다보게 하는 힘이 있습니다.'
      : band(s) === 1
        ? '분위기는 읽히지만 인상이 중립적이어서, 감정의 온도가 한 단계 더 올라가면 좋겠습니다.'
        : '장면이 정보를 담는 데 머물러 있어, 보는 사람의 마음을 건드리는 지점은 적습니다.'
  const confidence =
    0.4 +
    (human ? 0.12 : subjects.length > 0 ? 0.06 : 0) +
    (nima.deviation < 2 ? 0.06 : 0)
  return { score: s, confidence, observation, interpretation }
}

function timing(m: Measure, subjects: SubjectBox[]): Ax {
  const human = subjects.some((s) => HUMAN.has(s.label.toLowerCase()))
  let s = 0.46
  if (subjects.length > 0) s += 0.06
  if (human) s += 0.08
  if (m.sharpness >= 120) s += 0.04
  if (m.localContrast >= 14) s += 0.04
  if (subjects.length > 6) s -= 0.05
  const observation =
    subjects.length === 0
      ? `피사체가 잡히지 않아 배열을 읽을 대상이 없습니다. 라플라시안 분산 ${f1(m.sharpness)}, 국소 대비 ${f1(m.localContrast)}입니다.`
      : `주요 영역 ${subjects.length}개가 정지 배열로 잡혀 있습니다. 라플라시안 분산 ${f1(m.sharpness)}, 국소 대비 ${f1(m.localContrast)}로 포착의 또렷함을 가늠합니다.`
  const interpretation =
    band(s) === 2
      ? '포착이 또렷해 장면이 살아 있는 순간을 붙잡은 인상이 있습니다.'
      : band(s) === 1
        ? '제스처나 움직임의 흔적이 드러나지 않아, 순간의 긴장은 약합니다.'
        : '장면이 일어나기 전이나 지나간 뒤를 담은 듯, 정지된 공기가 남습니다.'
  const confidence = 0.3 + (subjects.length > 0 ? 0.1 : 0) + (human ? 0.06 : 0)
  return { score: s, confidence, observation, interpretation }
}

function describe(m: Measure, subjects: SubjectBox[]): string {
  const main = subjects[0]
  const toneText = m.percentile(0.5) < 70 ? '어두운 톤' : m.percentile(0.5) > 170 ? '밝은 톤' : '중간 톤'
  const subjectText = main
    ? `주요 영역 ${subjects.length}개가 잡혔고, 가장 큰 영역은 ${koLabel(main.label)}이며 프레임의 ${p1(main.area)}를 차지합니다.`
    : '객체 검출 범주에 해당하는 피사체는 잡히지 않았습니다.'
  const sharpText =
    band(ramp(Math.log10(1 + m.sharpness), Math.log10(16), Math.log10(251))) === 2
      ? '디테일이 또렷한 편입니다.'
      : band(ramp(Math.log10(1 + m.sharpness), Math.log10(16), Math.log10(251))) === 1
        ? '디테일은 무난한 수준입니다.'
        : '디테일이 무딘 편입니다.'
  const hueText =
    m.hueTotal <= 0
      ? '유채색이 거의 없습니다.'
      : `주조색은 ${HUE_NAMES[m.dominantHue]} 계열이고 평균 채도는 ${p1(m.saturation)}입니다.`
  return `휘도 평균 ${f1(m.luminance)}/255의 ${toneText} 사진입니다. ${subjectText} ${hueText} 라플라시안 분산은 ${f1(m.sharpness)}로 ${sharpText}`
}

// ---------- 자연스러운 비평 문장 (수치 없음) ----------
function say(seed: number, salt: number, ...items: string[]): string {
  return pick(seed + salt * 37, items)
}

function toneEnd(tone: ToneKey, body: string): string {
  return tone === 'MENTOR'
    ? `${body} 좋겠습니다.`
    : tone === 'CRITIC'
      ? `${body} 낫습니다.`
      : `${body} 화면에 드러난 요소와 맞습니다.`
}

// ---------- 빛과 노출 ----------
function lightTone(m: Measure, seed: number): string {
  const p50 = medianTone(m)
  if (p50 < 70)
    return say(
      seed,
      11,
      '화면은 낮은 빛 속에 잠겨 있습니다. 어둠이 먼저 다가오고, 그 위로 밝은 면이 드문드문 떠올라 시선이 자연스럽게 그쪽으로 모입니다.',
      '장면 전체가 어스름한 톤에 놓여 있습니다. 어두운 면이 화면의 대부분을 차지하고, 남은 빛이 그 사이를 조용히 지나갑니다.',
    )
  if (p50 > 170)
    return say(
      seed,
      12,
      '화면은 밝은 쪽으로 활짝 열려 있습니다. 빛이 넓게 퍼져 장면이 가볍게 떠 있는 듯하고, 그림자는 인상을 눌러 주는 정도로만 남아 있습니다.',
      '빛이 화면을 가득 채웁니다. 한낮의 공기처럼 산뜻하게 읽히고, 어두운 면은 형태를 구분해 주는 역할에 머물러 있습니다.',
    )
  return say(
    seed,
    13,
    '빛은 화면의 중간 어디쯤에서 균형을 잡고 있습니다. 어두운 면과 밝은 면이 서로의 자리를 나누어 장면이 담담하게 펼쳐집니다.',
    '화면의 밝기는 무난한 자리에 놓여 있습니다. 빛이 한쪽으로 치우치지 않아 인상이 차분하고, 눈이 편하게 머무를 수 있습니다.',
  )
}

function lightRange(m: Measure, seed: number): string {
  if (m.highlights > 0.02 && m.shadows > 0.04)
    return say(
      seed,
      21,
      '다만 가장 밝은 부분과 가장 어두운 부분이 함께 닫혀 있습니다. 눈이 부드럽게 넘어갈 중간 계조가 비어 있어, 장면이 두 덩어리로 갈라져 보입니다.',
      '밝은 끝과 어두운 끝이 동시에 막혀 있습니다. 그 사이를 이어 줄 계조가 얇아, 형태가 서로 다른 온도로 떨어져 있습니다.',
    )
  if (m.highlights > 0.02)
    return say(
      seed,
      22,
      '가장 밝은 면은 흰색으로 눌려 하나의 덩어리로 뭉쳤습니다. 그 안에 있던 결은 더 이상 읽히지 않고, 빛의 존재만 남았습니다.',
      '밝은 쪽이 끝까지 열리며 하얗게 타 버렸습니다. 밝은 면이 넓을수록 그 안의 정보는 빠르게 사라집니다.',
    )
  if (m.shadows > 0.04)
    return say(
      seed,
      23,
      '어두운 면은 검정으로 가라앉아 그 안의 모양이 지워졌습니다. 그림자가 무게를 얻지 못하고, 형태 대신 구멍처럼 비어 있습니다.',
      '어두운 쪽이 바닥까지 닫혀 있습니다. 그림자 속에 남았어야 할 형태가 사라져, 어둠이 이야기의 여백이 아니라 빈 공간이 되었습니다.',
    )
  if (m.contrast >= 40)
    return say(
      seed,
      24,
      '밝은 곳과 어두운 곳의 낙차가 크고, 어느 쪽도 눌리지 않았습니다. 빛이 지나온 자리가 또렷하게 남아 장면에 무게가 실립니다.',
      '명암의 폭이 넓게 열려 있습니다. 밝은 면은 밝은 채로, 어두운 면은 어두운 채로 각자의 결을 지키고 있습니다.',
    )
  if (m.contrast < 18)
    return say(
      seed,
      25,
      '밝은 곳과 어두운 곳의 차이가 완만합니다. 명암이 부드럽게 이어져 장면이 한 폭의 고른 면처럼 펼쳐집니다.',
      '빛의 낙차가 잦아들어 화면 전체가 비슷한 밝기로 흐릅니다. 편안하지만, 눈을 붙잡는 힘은 약한 편입니다.',
    )
  return say(
    seed,
    26,
    '밝은 곳과 어두운 곳이 고르게 살아 있습니다. 빛이 형태의 윤곽을 감싸고, 그림자가 그 윤곽을 다시 붙잡아 줍니다.',
    '명암이 양쪽으로 충분히 열려 있어, 장면의 앞뒤가 자연스럽게 구분됩니다.',
  )
}

function lightTexture(m: Measure, seed: number): string {
  if (m.highlights > 0.02)
    return say(
      seed,
      31,
      '가장 밝은 면에서 표면의 결이 지워진 대신 윤곽만 남아, 그 자리는 빛이 있다는 사실만 알려 줍니다.',
      '밝은 면이 하나의 흰 덩어리로 합쳐져, 빛이 닿은 물체의 재질보다 빛 자체가 먼저 보입니다.',
    )
  if (m.shadows < 0.02 && m.contrast >= 28)
    return say(
      seed,
      32,
      '밝은 끝의 계조가 마지막까지 남아 있습니다. 빛이 닿은 면이 흰 종이처럼 평평해지지 않고, 유리처럼 은은하게 반짝입니다.',
      '가장 밝은 부분에서도 표면의 결이 살아 있어, 빛이 부딪힌 자리에서 눈이 미끄러지지 않고 잠시 머뭅니다.',
    )
  return say(
    seed,
    33,
    '밝은 면이 끝까지 무너지지 않아, 빛이 닿은 곳에도 형태가 조용히 남아 있습니다.',
    '밝은 부분이 계단처럼 부드럽게 내려와, 빛의 세기가 표면을 따라 천천히 옮겨 가는 것이 느껴집니다.',
  )
}

function lightShadow(m: Measure, seed: number): string {
  if (m.shadows > 0.04)
    return say(
      seed,
      41,
      '어두운 면의 계조가 끊겨, 그림자가 형태를 감싸는 대신 통째로 삼켰습니다.',
      '그림자 속에서 형태가 이어지지 않아, 어두운 면은 다음 장면으로 넘어가는 여백처럼 읽힙니다.',
    )
  return say(
    seed,
    42,
    '어두운 면에도 계조가 남아 있습니다. 그림자가 형태를 감싸며 무게를 갖고, 밝은 면과 서로를 밀어 줍니다.',
    '그림자가 검정으로 닫히지 않아, 어두운 곳에서도 사물의 윤곽이 천천히 드러납니다.',
  )
}

function lightModel(m: Measure, seed: number): string {
  if (m.contrast >= 38 && m.localContrast >= 12)
    return say(
      seed,
      51,
      '빛이 면과 면 사이에 경계를 그어 주어, 형태가 서로를 밀어내며 공간에 서 있습니다.',
      '밝은 면과 어두운 면이 교차하며 덩어리를 나누고, 그 경계에서 사물의 부피가 생겨납니다.',
    )
  if (m.localContrast < 8)
    return say(
      seed,
      52,
      '빛이 넓게 퍼져 면과 면의 경계가 흐려졌습니다. 형태는 부피보다 분위기로 읽히고, 사물의 윤곽은 배경 속으로 부드럽게 풀립니다.',
      '국소적인 밝기 차가 얕아, 표면이 가진 요철이 거의 드러나지 않습니다. 사진은 입체보다 평면에 가깝게 다가옵니다.',
    )
  return say(
    seed,
    53,
    '빛이 형태를 세우는 힘은 절제되어 있습니다. 윤곽은 남아 있으나 부피감은 은은하게만 드러납니다.',
    '빛이 사물의 표면을 훑고 지나가며, 면의 방향에 따라 밝기가 조금씩 달라집니다.',
  )
}

function lightQuality(m: Measure, seed: number): string {
  if (m.contrast >= 35)
    return say(
      seed,
      61,
      '빛은 단단한 쪽에 가깝습니다. 밝음과 어둠의 경계가 분명해, 장면이 또렷한 목소리로 말합니다.',
      '빛이 곧게 떨어지는 듯한 인상입니다. 그림자의 가장자리가 서 있어 형태가 흔들리지 않습니다.',
    )
  return say(
    seed,
    62,
    '빛은 부드럽게 스미는 쪽에 가깝습니다. 경계가 무르게 풀려 장면 전체가 한 호흡으로 이어집니다.',
    '빛이 사방에서 감싸는 듯한 인상입니다. 그림자의 테두리가 부드러워 사물이 배경에서 급하게 떨어져 나오지 않습니다.',
  )
}

function lightReading(m: Measure, score: number): string {
  if (band(score) === 2)
    return '빛이 형태를 설명하고, 형태가 다시 빛을 받쳐 주는 관계가 화면에서 완성되어 있습니다. 사진을 보는 동안 장면의 공기까지 함께 느껴지고, 시선은 밝은 면과 어두운 면 사이를 천천히 오갑니다.'
  if (band(score) === 1) {
    if (m.highlights > 0.02 || m.shadows > 0.04)
      return '빛은 장면을 읽게 해 주지만, 양 끝에서 조금씩 정보를 잃었습니다. 그 손실이 사진의 인상을 흐리지 않고 오히려 여백처럼 작동한다면 지금의 노출도 하나의 선택이 됩니다.'
    return '빛은 안정적으로 자리를 잡았지만, 아직 장면을 밀어 올리는 힘은 부족합니다. 밝은 면과 어두운 면이 서로를 살리는 지점이 한 군데만 더 생겨도 사진의 깊이가 달라집니다.'
  }
  return '빛이 담고 있던 정보가 양 끝에서 사라져, 형태를 설명하는 힘이 약해졌습니다. 다만 지금의 어두운 분포 자체를 표현으로 삼는 방법도 남아 있습니다.'
}

function lightAdvice(m: Measure, tone: ToneKey, strong: boolean): string {
  const body = strong
    ? '지금의 노출을 그대로 두고, 밝은 면이 먼저 타 버리지 않게만 지키는 편이'
    : m.highlights > 0.02
      ? '노출을 반 스톱쯤 낮춰 가장 밝은 면의 결을 되찾는 편이'
      : m.shadows > 0.04
        ? '어두운 면을 살짝 들어 올리거나, 그림자가 향하는 방향을 바꿔 보는 편이'
        : m.contrast < 18
          ? '측광을 피사체에 맞추고 배경과의 밝기 차이를 만드는 편이'
          : '노출을 고정한 채 밝은 쪽과 어두운 쪽의 균형을 한 번 더 확인하는 편이'
  return toneEnd(tone, body)
}

// ---------- 색채 ----------
function colorTemperature(m: Measure, seed: number): string {
  if (m.warmth > 6)
    return say(
      seed,
      71,
      '화면 전체에 따뜻한 기운이 감돕니다. 노을이나 백열등 아래의 공기처럼 장면이 부드럽게 감싸이고, 사물의 윤곽도 함께 눅눅해집니다.',
      '붉은 기운이 넓게 퍼져 장면에 온기가 돕니다. 빛의 색이 사진의 정서를 먼저 결정하고 있습니다.',
    )
  if (m.warmth < -6)
    return say(
      seed,
      72,
      '푸른 기운이 화면을 가라앉힙니다. 차분하고 서늘한 공기가 남아, 장면이 조금 멀리서 바라본 것처럼 읽힙니다.',
      '푸른 쪽으로 기울어 장면이 한결 차분해졌습니다. 색의 온도가 낮아지면서 형태가 사색적인 인상을 얻습니다.',
    )
  return say(
    seed,
    73,
    '색온도가 한쪽으로 치우치지 않아 중성에 가깝습니다. 사물의 고유색이 비교적 정직하게 남아 있습니다.',
    '색온도가 고르게 잡혀 있습니다. 화면의 색이 자연스럽고, 특정 색만 튀어 오르지 않습니다.',
  )
}

function colorPigment(m: Measure, seed: number): string {
  if (m.hueTotal <= 0)
    return say(
      seed,
      81,
      '유채색이 거의 남지 않은 화면입니다. 색을 덜어낸 대신 밝기와 형태만으로 장면을 구성했고, 그 선택이 사진을 단단하게 만듭니다.',
      '화면이 무채색으로 정리되어 있습니다. 색의 소란 없이 계조와 윤곽만 남아, 인상이 차분하게 가라앉습니다.',
    )
  if (m.saturation < 0.12)
    return say(
      seed,
      82,
      '색은 옅게 깔려 있습니다. 색이 주장하지 않으므로 빛과 형태가 먼저 읽히고, 사진은 담백한 인상을 남깁니다.',
      '채도가 낮게 눌려 있어 색이 배경처럼 물러나 있습니다. 그 덕분에 화면의 분위기가 과하지 않습니다.',
    )
  if (m.dominantShare >= 0.45)
    return say(
      seed,
      83,
      '한 가지 색이 화면의 절반 이상을 차지합니다. 주조색이 분명해 사진의 인상이 한 번에 정리되고, 다른 색은 그 옆에서 조용히 보조합니다.',
      '주조색이 화면을 넓게 덮고 있습니다. 색이 하나로 모이면서 장면 전체가 같은 온도로 정돈됩니다.',
    )
  if (m.hueVariety >= 8)
    return say(
      seed,
      84,
      '여러 색이 고루 섞여 있습니다. 화면이 분주하고, 색과 색이 서로의 자리를 다투는 가운데 시선이 이리저리 옮겨 갑니다.',
      '색의 수가 많아 화면이 활기를 띱니다. 다만 색이 각자 말을 하려 해서, 인상이 하나로 모이기까지는 시간이 걸립니다.',
    )
  return say(
    seed,
    85,
    '몇 가지 색이 서로의 자리를 나누고 있습니다. 색의 수가 절제되어 화면이 단정하고 읽기 편합니다.',
    '색이 적당히 나뉘어 있어 주조색과 보조색의 관계가 무리 없이 읽힙니다.',
  )
}

function colorAir(m: Measure, seed: number): string {
  if (m.saturation >= 0.42)
    return say(
      seed,
      91,
      '색의 기운이 진하게 살아 있습니다. 채도가 높은 색들이 서로를 밀어내며 화면에 생기를 넣고, 사진이 먼저 말을 겁니다.',
      '색이 힘 있게 살아 있어 장면이 또렷합니다. 다만 진한 색이 여럿 겹치면 눈이 쉴 자리가 줄어듭니다.',
    )
  if (m.saturation < 0.12)
    return say(
      seed,
      92,
      '색의 온기가 낮아 화면이 사색적으로 흐릅니다. 감정이 색에서 오기보다 밝기와 형태에서 옵니다.',
      '색이 절제된 만큼 장면의 온도가 낮습니다. 차분함과 함께 조금 쓸쓸한 공기가 남습니다.',
    )
  return say(
    seed,
    93,
    '채도가 무난한 자리에 있어 색이 과하지도 무디지도 않습니다. 화면의 온도가 편안하게 유지됩니다.',
    '색의 세기가 적당해, 사진이 차분하면서도 생기를 잃지 않습니다.',
  )
}

function colorUnion(m: Measure, seed: number): string {
  if (m.edges >= 0.02 && m.sharpness >= 90)
    return say(
      seed,
      261,
      '색은 형태를 따라갑니다. 사물의 경계에서 색도 함께 멈춰, 색과 윤곽이 같은 자리에서 정리됩니다.',
      '색의 경계가 형태의 경계와 맞물려 있습니다. 사물이 색으로 구분되고, 색이 형태를 설명하는 구조입니다.',
    )
  if (m.edges < 0.01)
    return say(
      seed,
      262,
      '색이 형태의 경계를 넘어 번집니다. 사물과 배경이 같은 색으로 이어져, 화면이 색의 흐름으로만 나뉩니다.',
      '색의 경계가 흐려 형태와 색이 서로 섞여 있습니다. 사진이 사물의 윤곽보다 색의 공기를 먼저 보여 줍니다.',
    )
  return say(seed, 263, '색과 형태가 서로를 과하게 밀지 않습니다. 색이 형태를 따라가는 정도가 적당해, 화면이 읽기 편합니다.')
}

function colorWeight(m: Measure, seed: number): string {
  if (m.hueTotal <= 0 || m.saturation < 0.12)
    return say(
      seed,
      270,
      '색이 옅게 깔려 있어 색의 무게는 거의 느껴지지 않습니다. 사진의 인상은 색이 아니라 밝기와 형태가 끌고 갑니다.',
      '색이 뒤로 물러나 있어, 어떤 색이 주인인지 묻기 어려운 화면입니다. 사진의 중심은 계조와 윤곽이 대신 잡고 있습니다.',
    )
  if (m.dominantShare >= 0.45)
    return say(
      seed,
      271,
      '화면의 무게는 한 색에 실려 있습니다. 그 색이 사진의 성격을 거의 혼자 결정하고, 나머지 색은 그 옆에서 조용히 받칩니다.',
      '주조색이 화면의 대부분을 차지해, 사진을 기억할 때도 그 색이 먼저 떠오릅니다.',
    )
  if (m.hueVariety >= 8)
    return say(
      seed,
      272,
      '색의 무게가 여러 곳에 나뉘어 있습니다. 어느 색도 화면을 독점하지 못해, 시선이 색과 색 사이를 오갑니다.',
      '여러 색이 각자의 무게를 갖고 있습니다. 화면이 풍성하지만, 사진의 중심을 색이 대신 잡아 주지는 못합니다.',
    )
  return say(
    seed,
    273,
    '색의 무게가 고르게 나뉘어 있습니다. 주조색과 보조색이 서로의 자리를 인정해, 화면이 안정적으로 읽힙니다.',
    '색이 한쪽으로 쏠리지 않아, 사진의 인상이 특정 색에 묶이지 않습니다.',
  )
}

function colorPixel(m: Measure, seed: number): string {
  if (m.hueCoverage < 0.35)
    return say(
      seed,
      281,
      '유채색이 화면의 일부에만 남아 있습니다. 색이 있는 곳과 없는 곳이 나뉘어, 색 자체가 화면의 표시가 됩니다.',
      '색은 화면 전체가 아니라 일부 구역에만 깔려 있습니다. 그 국소성이 사진의 시선을 색 있는 쪽으로 모읍니다.',
    )
  if (m.saturation >= 0.3)
    return say(
      seed,
      282,
      '색이 화면 전반에 고르게 살아 있습니다. 사진을 볼 때 색의 밀도가 먼저 느껴지고, 형태는 그 뒤를 따릅니다.',
      '색이 넓게 퍼져 있어 화면의 온도가 일정합니다. 어디를 봐도 색이 함께 있어, 사진이 한 색조로 덮여 있습니다.',
    )
  return say(
    seed,
    283,
    '색이 화면 전체에 얕게 깔려 있습니다. 색이 주장하지 않으므로 빛과 형태가 앞에 서고, 사진은 차분한 인상을 남깁니다.',
    '색의 밀도가 낮아 화면이 담백합니다. 색을 빼고 남은 것이 사진의 성격이 되었습니다.',
  )
}

function colorReading(m: Measure, score: number): string {
  if (m.saturation < 0.12)
    return '색을 덜어낸 화면은 형태와 빛으로 말을 겁니다. 다만 색의 온기로 감정을 만드는 힘은 줄어들어, 사진이 조금 더 멀게 느껴집니다.'
  if (m.channelCast > 14)
    return m.warmth > 0
      ? '색온도가 붉은 쪽으로 기울어 있습니다. 장면 전체가 따뜻해지는 대신, 피사체의 고유색이 배경과 구분되는지 한 번 확인할 지점이 생깁니다.'
      : '색온도가 푸른 쪽으로 기울어 있습니다. 차분한 인상은 얻었지만, 피사체의 고유색이 배경 속에 잠기지 않았는지 살펴볼 만합니다.'
  if (band(score) === 2)
    return '색이 서로를 지지하며 하나의 온도로 정리되어 있습니다. 화면이 차분하게 가라앉고, 색을 오래 바라봐도 피로하지 않습니다.'
  if (band(score) === 1)
    return '색은 자연스럽지만 방향이 뚜렷하지 않습니다. 분위기를 색이 끌어 주지는 못하고, 빛과 구도가 그 몫을 대신하고 있습니다.'
  return '색이 흩어져 화면의 인상이 하나로 모이지 않습니다. 어떤 색이 주인인지 정해 주면 사진의 목소리가 달라집니다.'
}

function colorAdvice(m: Measure, tone: ToneKey, strong: boolean): string {
  const body = strong
    ? '지금의 색 균형을 그대로 두고, 색을 더 진하게 만들지 않는 편이'
    : m.channelCast > 8 && m.warmth > 0
      ? '화이트밸런스를 조금 차갑게 옮겨 피사체의 고유색을 되찾는 편이'
      : m.channelCast > 8
        ? '화이트밸런스를 조금 따뜻하게 옮겨 장면의 온기를 살리는 편이'
        : m.saturation > 0.42
          ? '색을 한 단계 낮추고 주조색 하나를 남기는 편이'
          : m.hueVariety >= 8
            ? '색이 많은 화면이므로 주조색 두 개로 정리하는 편이'
            : '광원의 색을 그대로 두고 피사체의 고유색을 살리는 편이'
  return toneEnd(tone, body)
}

// ---------- 구도 ----------
function compNoSubject(_m: Measure, seed: number): string {
  return say(
    seed,
    101,
    '뚜렷하게 자리 잡은 피사체 없이, 화면의 면과 빛이 구도를 이룹니다. 눈이 머물 대상을 정해 주지 않으므로 여백과 밝기의 배치가 곧 장면의 뼈대가 됩니다.',
    '눈에 띄게 잡히는 대상이 없어, 여백과 빛의 덩어리가 화면을 나눕니다. 이 경우 사진은 무엇을 보여 주는가보다 어떻게 나뉘어 있는가로 읽힙니다.',
  )
}

function compPlacement(m: Measure, subjects: SubjectBox[], seed: number): string {
  const main = subjects[0]
  if (!main) return compNoSubject(m, seed)
  const label = koLabel(main.label)
  const d = nearestThirds(main.cx, main.cy)
  const centered = Math.abs(main.cx - 0.5) <= 0.07 && Math.abs(main.cy - 0.5) <= 0.07
  if (centered)
    return say(
      seed,
      105,
      `${eun(label)} 화면의 한가운데에 놓여 있습니다. 정면의 대칭이 안정을 만들고, 사진이 대상을 향해 정면으로 말합니다.`,
      `${eun(label)} 중심을 지키고 있습니다. 좌우의 무게가 비슷해 화면이 조용하고, 시선이 흔들리지 않습니다.`,
    )
  if (d <= 0.1)
    return say(
      seed,
      102,
      `${eun(label)} 화면의 힘 있는 자리에 놓여 있습니다. 시선이 자연스럽게 그곳에 닿고, 남은 여백이 그 자리를 밀어 주어 화면이 답답하지 않습니다.`,
      `${eun(label)} 격자의 교차점 가까이에 자리해, 눈이 한 번에 닿습니다. 주변의 빈 공간이 그 자리를 받쳐 주어 사진의 중심이 흔들리지 않습니다.`,
    )
  if (d <= 0.18)
    return say(
      seed,
      103,
      `${eun(label)} 중심과 힘 있는 자리 사이에 걸쳐 있습니다. 시선의 착지점이 조금 흐려져, 사진을 보는 동안 눈이 미세하게 오갑니다.`,
      `${eun(label)} 화면의 정확한 중심도, 힘 있는 자리도 아닌 곳에 놓였습니다. 그 어중간함이 의도된 긴장이라면 살아나지만, 아니라면 중심이 흐릿하게 남습니다.`,
    )
  return say(
    seed,
    104,
    `${eun(label)} 화면의 힘 있는 자리에서 벗어나 있습니다. 눈이 중심을 잡지 못하고 화면 가장자리를 서성입니다.`,
    `${eun(label)} 중심에서 멀리 밀려나 있습니다. 여백이 넓어진 만큼 사진의 무게중심도 함께 밀려났습니다.`,
  )
}

function compLines(m: Measure, seed: number): string {
  if (m.lines.length === 0)
    return say(
      seed,
      111,
      '화면을 가로지르는 뚜렷한 선이 없어, 시선을 이끄는 방향은 색과 밝기의 흐름에 맡겨져 있습니다.',
      '기준이 되는 선이 잡히지 않습니다. 장면의 질서는 선이 아니라 면의 크기와 밝기 차이로만 만들어집니다.',
    )
  if (m.maxTilt > 3)
    return say(
      seed,
      112,
      '화면을 가로지르는 선이 살짝 기울어 있습니다. 그 기울기가 장면을 미묘하게 흔들리게 하고, 사진의 인상에 작은 긴장을 더합니다.',
      '가로선이 수평에서 조금 벗어나 있습니다. 의도된 기울기라면 장면에 속도를 주지만, 아니라면 사진이 조용히 흔들립니다.',
    )
  return say(
    seed,
    113,
    '화면을 가로지르는 선이 반듯합니다. 장면의 뼈대가 흔들리지 않아, 사진을 보는 동안 발밑이 단단한 느낌이 듭니다.',
    '가로선이 수평을 지키고 있어 안정감이 먼저 옵니다. 그 위에 놓인 요소들이 마음 놓고 움직일 수 있습니다.',
  )
}

function compSpace(_m: Measure, subjects: SubjectBox[], seed: number): string {
  const main = subjects[0]
  const crowded = subjects.filter((s) => s.touchesEdge && s.area > 0.05).length
  if (crowded >= 2)
    return say(
      seed,
      121,
      '여러 요소가 화면 가장자리에 닿아 있습니다. 프레임이 조여들어 화면이 답답하게 느껴지고, 시선이 밖으로 나갈 자리를 찾지 못합니다.',
      '가장자리를 채운 요소들이 프레임을 붙잡고 있습니다. 화면이 꽉 찬 대신 숨 쉴 여백이 줄어, 사진이 다소 빽빽합니다.',
    )
  if (crowded === 1)
    return say(
      seed,
      122,
      '한 요소가 화면 가장자리에 닿아 있습니다. 그 접점이 프레임을 살짝 밀어내는 긴장을 만들고, 사진에 조심스러운 긴장감을 더합니다.',
      '가장자리에 걸린 요소가 하나 있습니다. 그 요소가 잘린 채 남아 있어, 장면이 화면 밖으로 이어지는 느낌을 줍니다.',
    )
  if (main && main.area >= 0.45)
    return say(
      seed,
      123,
      '피사체가 화면을 크게 차지합니다. 배경이 뒤로 물러나고 대상 자체가 사진의 전부가 되며, 여백은 거의 남지 않습니다.',
      '대상이 프레임을 넓게 덮고 있습니다. 사진이 대상을 향해 바짝 다가선 만큼, 배경의 정보는 크게 줄었습니다.',
    )
  if (main && main.area <= 0.05)
    return say(
      seed,
      124,
      '피사체는 작게 자리하고 여백이 화면의 주인공이 됩니다. 사진이 대상을 보여 주기보다 대상이 놓인 공간을 보여 줍니다.',
      '작은 대상 하나가 넓은 여백에 놓여 있습니다. 그 적요가 사진의 인상을 만들고, 보는 사람의 시선이 여백을 천천히 훑게 합니다.',
    )
  return say(
    seed,
    125,
    '여백과 대상의 비율이 무난하게 나뉘어 있습니다. 화면이 답답하지도, 비어 보이지도 않습니다.',
    '프레임 안의 요소가 적당한 간격으로 놓여 있습니다. 눈이 대상을 본 뒤 여백으로 넘어가는 흐름이 자연스럽습니다.',
  )
}

function compBalance(_m: Measure, subjects: SubjectBox[], seed: number): string {
  const main = subjects[0]
  if (!main)
    return say(
      seed,
      291,
      '대상이 없어 무게는 밝기의 분포가 정합니다. 밝은 면이 화면의 어느 쪽에 놓였는지에 따라 사진의 중심이 옮겨 갑니다.',
    )
  const offset = Math.abs(main.cx - 0.5)
  if (offset <= 0.08)
    return say(
      seed,
      292,
      '무게가 화면의 가운데에 놓여 있습니다. 안정적이지만, 좌우 어느 쪽으로도 기울지 않아 사진이 정적입니다.',
      '중심이 화면 한가운데에 잡혀 있습니다. 단정한 대신, 좌우의 긴장은 거의 남지 않습니다.',
    )
  if (offset <= 0.22)
    return say(
      seed,
      293,
      '무게가 한쪽으로 살짝 기울어 있습니다. 그 기울기가 화면에 방향을 주고, 사진이 조용히 움직이기 시작합니다.',
      '좌우의 무게가 완전히 같지는 않습니다. 기운 쪽으로 시선이 먼저 가고, 반대쪽 여백이 그 무게를 받쳐 줍니다.',
    )
  return say(
    seed,
    294,
    '무게가 한쪽으로 크게 쏠려 있습니다. 반대편에 남은 여백이 넓어, 사진이 그 빈 곳을 향해 열려 있습니다.',
    '화면의 균형이 한쪽으로 기울어 있습니다. 의도된 치우침이라면 긴장이 되지만, 아니라면 사진이 넘어질 듯 불안합니다.',
  )
}

function compFlow(m: Measure, subjects: SubjectBox[], seed: number): string {
  if (subjects.length >= 3)
    return say(
      seed,
      301,
      '여러 대상이 놓인 자리가 시선의 경로를 만듭니다. 눈이 한 대상에서 다음 대상으로 옮겨 가며 화면을 읽고, 그 순서가 사진의 리듬이 됩니다.',
      '대상이 흩어져 있어 시선이 정해진 길 없이 움직입니다. 사진을 볼 때마다 다른 순서로 읽히는 것이 이 화면의 성격입니다.',
    )
  if (m.lines.length > 0 && subjects.length > 0)
    return say(
      seed,
      302,
      '화면을 가로지르는 선과 피사체의 자리가 함께 시선의 길을 만듭니다. 눈이 선을 따라가다 피사체에서 멈추고, 사진이 그 자리에서 완성됩니다.',
      '선이 시선을 이끌고 피사체가 그 끝에 놓여 있습니다. 사진이 보는 사람에게 어디를 보아야 하는지 조용히 알려 줍니다.',
    )
  return say(
    seed,
    303,
    '시선의 길이 뚜렷하지 않습니다. 눈이 화면을 스스로 훑어야 하고, 사진은 그 자유를 보는 사람에게 맡깁니다.',
    '정해진 동선 없이 시선이 화면을 떠돕니다. 밝기와 면의 배치만이 눈의 순서를 정합니다.',
  )
}

function compFraming(_m: Measure, subjects: SubjectBox[], seed: number): string {
  const main = subjects[0]
  if (!main)
    return say(
      seed,
      311,
      '프레임은 대상을 담기보다 공간을 담았습니다. 무엇을 넣을지보다 무엇을 비울지로 결정한 화면입니다.',
    )
  if (main.touchesEdge)
    return say(
      seed,
      312,
      '가장 큰 피사체가 프레임의 가장자리에 닿아 있습니다. 잘린 몫이 있어 사진이 화면 밖을 상상하게 하고, 그 상상이 장면을 넓힙니다.',
      '대상의 일부가 프레임 밖에 남아 있습니다. 잘림이 사진을 닫힌 화면이 아니라 이어지는 장면으로 만듭니다.',
    )
  if (main.area >= 0.3)
    return say(
      seed,
      313,
      '프레임이 대상에 바짝 다가서 있습니다. 배경의 정보는 줄고 대상의 존재가 커져, 사진이 관찰보다 대면에 가까워집니다.',
    )
  return say(
    seed,
    314,
    '프레임이 대상을 여유 있게 담았습니다. 대상과 주변의 관계가 함께 읽혀, 사진이 장소의 맥락을 남깁니다.',
    '대상 둘레에 공간이 남아 있습니다. 그 여유가 사진을 서두르지 않게 만들고, 장면이 제 속도로 읽힙니다.',
  )
}

function compReading(_m: Measure, subjects: SubjectBox[], score: number): string {
  const main = subjects[0]
  if (!main) {
    if (band(score) === 2)
      return '대상 없이도 면과 빛의 배열이 화면을 지탱합니다. 여백이 사진의 주제가 된 드문 경우로, 무엇이 없는지를 보여 주는 구성입니다.'
    if (band(score) === 1)
      return '여백이 주인공이 된 화면입니다. 중심을 잡아 줄 요소가 하나만 더 있으면 인상이 완성되지만, 지금도 조용한 리듬은 남아 있습니다.'
    return '화면 안에서 눈이 머물 자리를 찾기 어렵습니다. 사진의 인상이 흩어지고, 보는 사람이 무엇을 봐야 할지 스스로 정해야 합니다.'
  }
  if (band(score) === 2)
    return '시선이 한 번에 장면을 읽습니다. 계산된 여백의 힘이 느껴지고, 눈이 화면을 훑는 순서가 자연스럽게 정해져 있습니다.'
  if (band(score) === 1)
    return '장면은 읽히지만 중심이 흐립니다. 보는 사람이 어디에 시선을 둘지 잠시 망설이게 되고, 그 망설임이 사진의 인상을 조금 흐립니다.'
  return '프레임이 대상을 붙잡지 못합니다. 사진의 인상이 가장자리로 흘러 나가고, 화면이 하나의 장면으로 묶이지 않습니다.'
}

function compAdvice(m: Measure, subjects: SubjectBox[], tone: ToneKey, strong: boolean): string {
  const main = subjects[0]
  const body = strong
    ? '지금의 배치를 그대로 두고, 같은 자리에서 한두 걸음만 옮겨 다른 여백의 비율도 시험해 보는 편이'
    : !main
      ? '화면에 머물 요소를 하나 정해 힘 있는 자리에 두고, 여백이 그 요소를 밀어 주도록 잡는 편이'
      : nearestThirds(main.cx, main.cy) > 0.14
        ? '가장 큰 피사체를 격자의 교차점 쪽으로 반 걸음 옮기는 편이'
        : subjects.some((s) => s.touchesEdge && s.area > 0.05)
          ? '가장자리에 닿은 요소를 안으로 들이거나 과감히 잘라내는 편이'
          : m.maxTilt > 3
            ? '수평선을 반듯하게 맞추는 편이'
            : '지금의 배치를 유지하되 여백을 한쪽으로 몰아 긴장을 만드는 편이'
  return toneEnd(tone, body)
}

// ---------- 선명도와 질감 ----------
function sharpFocus(m: Measure, seed: number): string {
  const level = band(ramp(Math.log10(1 + m.sharpness), Math.log10(16), Math.log10(251)))
  if (level === 2)
    return say(
      seed,
      131,
      '윤곽이 또렷하고 표면의 결이 살아 있습니다. 초점이 놓인 자리가 분명해, 보는 눈이 자연스럽게 그곳으로 갑니다.',
      '디테일이 촘촘하게 살아 있어 표면이 또렷하게 읽힙니다. 사진을 가까이 들여다볼수록 정보가 더 나옵니다.',
    )
  if (level === 1)
    return say(
      seed,
      132,
      '형태는 분명하지만 표면의 결이 조금 눌려 있습니다. 전체적인 초점은 잡혀 있으나, 미세한 결이 얕게 죽어 있습니다.',
      '윤곽은 읽히는데 표면의 요철이 흐릿합니다. 사진이 형태는 전하지만 촉감은 전하지 못합니다.',
    )
  return say(
    seed,
    133,
    '윤곽이 부드럽게 풀려 있습니다. 초점이 얕게 풀리며 화면이 부드러운 인상으로 덮여 있고, 의도된 흐림이라면 그 자체로 표현이 됩니다.',
    '질감보다 분위기로 읽히는 사진입니다. 선명함이 빠진 자리를 빛과 색이 대신 채우고 있습니다.',
  )
}

function sharpTexture(m: Measure, seed: number): string {
  if (m.sharpness >= 120 && m.localContrast >= 12)
    return say(
      seed,
      141,
      '표면의 결이 손끝에 닿을 듯 남아 있습니다. 돌의 거칠기, 옷감의 주름, 벽의 얼룩이 각자의 촉감으로 구분됩니다.',
      '질감의 밀도가 고릅니다. 같은 밝기 안에서도 표면이 다른 재질로 읽혀, 사진이 손으로 만질 수 있는 것처럼 느껴집니다.',
    )
  if (m.sharpness >= 120)
    return say(
      seed,
      142,
      '윤곽은 선명한데 표면의 미세한 결은 조금 얕습니다. 형태가 먼저 서고 재질은 그 뒤에서 조용히 따라옵니다.',
      '초점은 맞았지만 질감의 층위가 얇습니다. 사진이 사물의 모양은 정확히 전하되, 만졌을 때의 감촉은 덜 전합니다.',
    )
  if (m.sharpness < 60)
    return say(
      seed,
      143,
      '표면의 결이 흐려져 있습니다. 질감이 정보로 남기보다 분위기로 번져, 사진이 회화에 가까운 인상을 띱니다.',
      '미세한 결이 뭉개져 표면이 매끈하게 정리되었습니다. 사물의 재질보다 빛의 흐름이 먼저 보입니다.',
    )
  return say(
    seed,
    144,
    '표면의 결이 무난하게 남아 있습니다. 사물의 재질이 읽히고, 사진이 지나치게 날카롭지도 흐릿하지도 않습니다.',
    '질감이 적당히 살아 있어, 사진이 형태와 촉감 사이에서 균형을 잡고 있습니다.',
  )
}

function sharpSpace(m: Measure, subjects: SubjectBox[], seed: number): string {
  if (subjects.length === 0)
    return say(
      seed,
      150,
      '대상이 없어 깊이는 표면의 결로만 만들어집니다. 밀도가 진한 구역과 옅은 구역이 나뉘며 화면에 조용한 원근이 생깁니다.',
      '대상이 없으니 앞뒤의 구분은 질감이 대신합니다. 결이 섬세한 곳이 앞으로, 매끈한 곳이 뒤로 물러나 사진 안에 거리가 생깁니다.',
    )
  if (m.localContrast >= 14)
    return say(
      seed,
      151,
      '앞에 선 사물과 뒤로 물러난 배경의 밀도 차이가 분명합니다. 그 차이가 공간의 깊이를 만들고, 사진 안에 앞뒤가 생깁니다.',
      '질감이 진한 곳과 옅은 곳이 나뉘어 있습니다. 그 대비가 거리감을 만들어, 화면이 평면이 아니라 공간으로 읽힙니다.',
    )
  if (m.localContrast < 8)
    return say(
      seed,
      152,
      '국소적인 밀도 차이가 얕아, 앞뒤가 한 면처럼 붙어 보입니다. 사진이 깊이보다 평면의 무늬처럼 다가옵니다.',
      '질감의 층이 얇아 배경이 뒤로 물러나지 못합니다. 대상과 배경이 같은 거리에 놓인 것처럼 읽힙니다.',
    )
  return say(
    seed,
    153,
    '질감의 차이가 은은하게 남아 있어, 앞뒤가 완전히 붙지도 완전히 떨어지지도 않습니다.',
    '표면의 밀도가 조금씩 달라지며 공간의 결이 만들어집니다. 사진이 깊이를 과장하지 않고 조용히 보여 줍니다.',
  )
}

function sharpEdge(m: Measure, seed: number): string {
  if (m.edges >= 0.04)
    return say(
      seed,
      321,
      '화면에 세밀한 윤곽이 많습니다. 작은 요소들이 각자의 경계를 갖고 있어, 사진을 가까이 볼수록 읽을 것이 늘어납니다.',
      '윤곽이 촘촘하게 살아 있어 화면이 복잡하고 풍성합니다. 다만 눈이 쉴 빈 면은 적습니다.',
    )
  if (m.edges < 0.01)
    return say(
      seed,
      322,
      '윤곽선이 적어 화면이 단순합니다. 큰 면이 넓게 이어져, 사진이 형태보다 덩어리로 읽힙니다.',
      '세밀한 경계가 거의 없어 화면이 매끈합니다. 사진이 복잡한 장면보다 조용한 면을 담았습니다.',
    )
  return say(seed, 323, '윤곽선이 적당히 남아 있습니다. 사물의 경계가 읽히되 화면이 어수선해지지 않을 만큼입니다.')
}

function sharpEven(m: Measure, seed: number): string {
  if (m.localContrast >= 14)
    return say(
      seed,
      331,
      '결이 화면 전반에 고르게 살아 있습니다. 어느 구역을 보아도 표면의 요철이 남아 있어, 사진이 부분마다 다른 정보를 줍니다.',
      '국소적인 밝기 차가 여러 곳에서 살아 있습니다. 사진을 훑을 때마다 새로운 결이 눈에 들어옵니다.',
    )
  if (m.localContrast < 8)
    return say(
      seed,
      332,
      '결이 화면 전체에서 얕습니다. 표면의 요철이 거의 드러나지 않아, 사진이 전체적으로 매끈하고 평평하게 읽힙니다.',
      '국소적인 차이가 적어, 사진의 정보가 큰 덩어리로만 전달됩니다.',
    )
  return say(seed, 333, '결이 화면 전체에 무난하게 퍼져 있습니다. 어떤 구역은 진하고 어떤 구역은 옅어, 사진에 자연스러운 기복이 생깁니다.')
}

function sharpIntent(m: Measure, seed: number): string {
  if (m.sharpness < 60 && m.contrast >= 30)
    return say(
      seed,
      341,
      '흐림이 있어도 명암은 살아 있어, 사진이 정보를 잃은 것이 아니라 다른 방식으로 말하고 있습니다. 의도된 흐림이라면 충분히 표현이 됩니다.',
      '선명함을 포기한 대신 빛과 어둠의 대비가 남아 있습니다. 사진이 재질을 보여 주기보다 공기를 보여 주는 쪽을 택한 것처럼 읽힙니다.',
    )
  if (m.sharpness >= 150)
    return say(
      seed,
      342,
      '선명함이 사진의 인상을 강하게 끌고 갑니다. 다만 지나치게 날카로우면 인물의 피부나 하늘의 결이 거칠게 드러날 수 있습니다.',
      '디테일이 넘치는 대신 화면이 조금 긴장되어 있습니다. 선명함을 한 단계 낮추면 사진이 부드러워질 여지도 있습니다.',
    )
  return say(seed, 343, '선명도는 사진의 인상을 방해하지 않습니다. 표현의 중심은 선명함이 아니라 빛과 색에 있습니다.')
}

function sharpReading(m: Measure, score: number): string {
  if (band(score) === 2)
    return '선명도가 사진의 인상을 단단하게 받쳐 주고 있습니다. 초점이 놓인 자리에서 정보가 충분히 나와, 보는 사람이 표면을 따라 눈을 옮길 수 있습니다.'
  if (band(score) === 1)
    return '기본은 갖추었지만 질감이 사진의 무기가 되지는 못합니다. 초점이 닿은 곳의 결이 조금만 더 살아나면 인상이 한 단계 또렷해집니다.'
  if (m.sharpness >= 60)
    return '선명도가 낮은 대신 화면이 부드러운 인상으로 덮여 있습니다. 의도된 흐림이라면 표현이 되지만, 아니라면 사진의 정보가 크게 줄어든 상태입니다.'
  return '흐림이 사진 전체를 덮고 있습니다. 빛과 색의 인상은 남지만, 사물의 윤곽과 재질은 거의 전달되지 않습니다.'
}

function sharpAdvice(m: Measure, tone: ToneKey, strong: boolean): string {
  const body = strong
    ? '과한 샤프닝 없이 지금의 초점과 조리개를 유지하는 편이'
    : m.sharpness < 60
      ? '셔터 속도를 초점거리의 역수보다 빠르게 잡고, 삼각대나 벽에 기대어 흔들림을 줄이는 편이'
      : m.localContrast < 12
        ? '조리개를 반 스톱 조여 국소 대비를 올리는 편이'
        : m.edges < 0.01
          ? '초점 위치를 피사체의 눈이나 윤곽으로 옮기는 편이'
          : '초점을 피사체의 핵심부에 고정하고 흔들림을 줄이는 편이'
  return toneEnd(tone, body)
}

// ---------- 분위기와 순간 ----------
function moodTone(m: Measure, seed: number): string {
  const p50 = medianTone(m)
  if (p50 < 70)
    return say(
      seed,
      161,
      '어두운 톤이 장면을 감싸고 있어 고요한 공기가 남습니다. 화면이 소리를 낮추고, 보는 사람도 함께 낮은 목소리로 말하게 됩니다.',
      '낮게 가라앉은 빛이 차분한 기운을 만듭니다. 장면이 서두르지 않고, 사진을 오래 들여다보게 합니다.',
    )
  if (p50 > 170)
    return say(
      seed,
      162,
      '밝은 톤이 화면을 열어 주어 가볍고 산뜻한 인상입니다. 장면이 경쾌하게 읽히고, 사진에서 바람이 지나가는 듯합니다.',
      '밝은 빛이 화면을 채워 장면이 환하게 열려 있습니다. 무게보다 가벼움이 먼저 느껴집니다.',
    )
  return say(
    seed,
    163,
    '중간 톤이 중심을 잡아 안정된 인상을 줍니다. 빛과 어둠이 고르게 섞여 담담한 공기가 흐릅니다.',
    '화면의 밝기가 무난한 자리에 놓여, 장면이 과장 없이 제자리에서 말합니다.',
  )
}

function moodPresence(_m: Measure, subjects: SubjectBox[], seed: number): string {
  const human = subjects.some((s) => HUMAN.has(s.label.toLowerCase()))
  if (human)
    return say(
      seed,
      171,
      '사람이 있다는 것만으로 장면에 온도가 생깁니다. 시선이 인물의 표정과 몸짓을 따라가고, 사진이 사건을 품은 이야기로 바뀝니다.',
      '화면 안의 인물이 분위기를 끌고 갑니다. 사람의 존재가 빛과 색을 하나의 정서로 묶어 주고, 사진이 관찰에서 경험으로 넘어갑니다.',
    )
  if (subjects.length > 0)
    return say(
      seed,
      172,
      '화면 안의 대상이 분위기를 만들고, 그 주변의 여백이 호흡을 더합니다. 대상이 말을 걸고 여백이 그 말을 받아 주는 구조입니다.',
      '대상 하나가 장면의 중심에 서 있고, 배경이 그 뒤에서 조용히 받쳐 줍니다. 사진의 시선이 흩어지지 않습니다.',
    )
  return say(
    seed,
    173,
    '특정 대상 없이 빛과 색만으로 분위기가 만들어집니다. 장면이 무엇을 담았다기보다 어떤 공기를 담았다고 말하는 사진입니다.',
    '대상이 비어 있는 대신 공간 자체가 주제가 됩니다. 사진이 장소의 온도를 보여 주고, 보는 사람이 그 안에 서 보게 합니다.',
  )
}

function moodStillness(m: Measure, subjects: SubjectBox[], seed: number): string {
  const human = subjects.some((s) => HUMAN.has(s.label.toLowerCase()))
  if (human && m.sharpness >= 120)
    return say(
      seed,
      181,
      '움직임이 있을 법한 장면인데 포착이 또렷합니다. 순간을 붙잡은 인상이 남고, 사진이 시간을 잠시 붙들고 있습니다.',
      '인물의 몸짓이 흐려지지 않고 남아 있습니다. 지나가는 순간을 정확히 붙잡아, 장면이 정지 화면이 아니라 한 장면의 정점처럼 읽힙니다.',
    )
  if (subjects.length > 6)
    return say(
      seed,
      182,
      '여러 요소가 한꺼번에 담겨 장면이 분주합니다. 시선의 초점이 조금 흩어지지만, 그 분주함 자체가 장소의 성격을 보여 줍니다.',
      '화면 안에 사건이 여럿 겹쳐 있습니다. 한 장면에 여러 이야기가 들어와, 사진을 볼 때마다 다른 곳에 눈이 갑니다.',
    )
  if (m.sharpness >= 120)
    return say(
      seed,
      183,
      '정지된 장면이 또렷하게 담겨, 시간이 잠시 멈춘 듯한 공기가 있습니다. 사진이 장면을 붙잡아 두는 힘을 갖고 있습니다.',
      '포착이 또렷해 장면의 결이 그대로 남았습니다. 사진을 보는 동안 시간이 조금 느리게 흐르는 느낌이 듭니다.',
    )
  return say(
    seed,
    184,
    '장면이 잔잔하게 가라앉아, 지나간 뒤의 공기를 담은 듯합니다. 사진이 사건보다 그 뒤에 남은 정적을 보여 줍니다.',
    '큰 움직임 없이 정돈된 장면이라, 긴장보다 안온한 기운이 남습니다. 사진이 사람을 재촉하지 않습니다.',
  )
}

function moodReading(a: Assessment): string {
  const emotionStrong = band(a.evidence[Axis.EMOTION].score) >= 2
  const timingStrong = band(a.evidence[Axis.TIMING].score) >= 2
  if (emotionStrong && timingStrong)
    return '분위기와 순간이 함께 살아 있어, 장면이 정지된 이미지가 아니라 하나의 사건처럼 읽힙니다. 사진을 덮은 뒤에도 그 공기가 잠시 따라옵니다.'
  if (emotionStrong)
    return '분위기가 먼저 다가오고, 순간의 긴장은 뒤에서 조용히 받쳐 주고 있습니다. 사진이 정서로 기억되는 쪽에 가깝습니다.'
  if (timingStrong)
    return '순간이 또렷하게 붙잡혀 있어, 장면의 공기가 사진 안에 머물러 있습니다. 다만 그 순간을 받쳐 줄 정서의 온도는 조금 더 올라갈 여지가 있습니다.'
  return '화면은 차분하지만 감정의 온도가 낮습니다. 장면이 정보를 담는 데 머물러 있어, 보는 사람의 마음을 오래 붙잡지는 못합니다.'
}

function moodAdvice(tone: ToneKey, human: boolean, empty: boolean): string {
  const body = human
    ? '인물의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하고, 표정이 정점에 이르는 순간을 한 박자 기다리는 편이'
    : empty
      ? '장면에 온도를 줄 요소를 하나 더 넣거나, 지금의 여백을 끝까지 밀어 적요를 주제로 삼는 편이'
      : '피사체의 주변에 남은 여백의 크기를 한 번 바꿔 보고, 정서가 살아나는 쪽을 고르는 편이'
  return toneEnd(tone, body)
}

function moodAfter(m: Measure, seed: number): string {
  if (m.contrast >= 35 && m.saturation >= 0.2)
    return say(
      seed,
      351,
      '사진을 덮은 뒤에도 장면의 온도가 잠시 남습니다. 강한 명암과 살아 있는 색이 함께 기억에 남아, 인상이 쉽게 지워지지 않습니다.',
      '한 장을 본 뒤에도 화면의 공기가 따라옵니다. 빛과 색이 함께 만든 인상이 오래 머뭅니다.',
    )
  if (m.contrast < 20 || m.saturation < 0.12)
    return say(
      seed,
      352,
      '사진을 덮으면 인상이 빠르게 옅어집니다. 화면이 담백한 대신 기억에 오래 붙는 장면은 적습니다.',
      '남는 것이 적은 사진입니다. 편안하지만, 다시 떠올릴 이유도 함께 적습니다.',
    )
  return say(
    seed,
    353,
    '사진을 덮으면 장면의 분위기가 은은하게 남습니다. 강하게 붙잡지는 않지만, 한 번쯤 다시 보고 싶어지는 인상입니다.',
    '기억에 남는 방식이 조용합니다. 화면을 다시 떠올리면 빛의 위치부터 생각나고, 그다음에 형태가 따라옵니다.',
  )
}

function moodStory(_m: Measure, subjects: SubjectBox[], seed: number): string {
  const human = subjects.some((s) => HUMAN.has(s.label.toLowerCase()))
  if (human)
    return say(
      seed,
      361,
      '사람이 있으면 사진은 이야기를 품습니다. 누가 어디에 서 있고 무엇을 보고 있는지가 장면의 앞뒤를 상상하게 만듭니다.',
      '인물이 장면에 사건을 만듭니다. 사진이 무엇을 찍었는지보다 무슨 일이 있었는지를 먼저 묻게 됩니다.',
    )
  if (subjects.length >= 3)
    return say(
      seed,
      362,
      '여러 대상이 놓인 자리가 작은 사건처럼 읽힙니다. 누가 어디에 있는지가 관계를 상상하게 하고, 사진이 한 장면의 기록이 됩니다.',
    )
  return say(
    seed,
    363,
    '이야기보다 장소의 성격이 남습니다. 사진이 사건을 담았다기보다 어떤 공기가 있었는지를 기록합니다.',
    '특별한 사건 없이도 장면이 하나의 이야기처럼 읽힙니다. 무엇이 일어나지 않았다는 사실이 오히려 사진의 주제가 됩니다.',
  )
}

function moodBreath(m: Measure, subjects: SubjectBox[], seed: number): string {
  if (subjects.length === 0 && m.contrast < 24)
    return say(
      seed,
      371,
      '화면은 아주 조용합니다. 대상도 움직임도 없이 빛만 남아, 사진이 소리를 낮춘 방처럼 읽힙니다.',
      '장면에 사람의 기척이 없습니다. 빈 공간과 빛만 남아, 사진이 머무는 시간이 길어집니다.',
    )
  if (m.sharpness >= 150 && m.edges >= 0.03)
    return say(
      seed,
      372,
      '화면에 움직임의 기운이 남아 있습니다. 요소가 많고 또렷해, 정지된 사진인데도 장면이 조금씩 움직이는 것처럼 느껴집니다.',
    )
  if (subjects.length >= 2)
    return say(
      seed,
      373,
      '대상들 사이에 긴장이 남아 있습니다. 서로의 거리와 방향이 관계를 만들고, 사진이 그 관계의 한 순간을 붙잡고 있습니다.',
    )
  return say(seed, 374, '장면은 차분하지만 완전히 멈추지는 않았습니다. 빛의 방향이나 대상의 자세에서 다음 순간을 짐작하게 됩니다.')
}

// ---------- 항목별 조언 (촬영·보정) ----------
function shootAdvice(axis: Axis, a: Assessment, tone: ToneKey): string {
  const m = a.measure
  const main = a.subjects[0]
  const strong = band(a.evidence[axis].score) === 2
  let body = ''
  switch (axis) {
    case Axis.COMPOSITION:
      body = strong
        ? '지금의 배치를 그대로 두고, 같은 자리에서 카메라를 반 걸음 옮겨 여백의 비율만 바꿔 한 장 더 담는 편이'
        : !main
          ? '화면에 머물 요소를 하나 정해 격자의 교차점에 두고, 그 요소와 배경 사이의 거리를 의식해 프레임을 잡는 편이'
          : nearestThirds(main.cx, main.cy) > 0.14
            ? '가장 큰 피사체를 격자의 교차점 쪽으로 반 걸음 옮기고, 그 자리에서 한 장 더 누르는 편이'
            : a.subjects.some((s) => s.touchesEdge && s.area > 0.05)
              ? '가장자리에 닿은 요소를 안으로 들이거나 과감히 잘라내고, 프레임 안에 숨 쉴 여백을 남기는 편이'
              : m.maxTilt > 3
                ? '수평선을 뷰파인더의 격자에 맞춰 반듯하게 잡는 편이'
                : '지금의 배치를 유지하되 여백을 한쪽으로 몰아 화면에 긴장을 만드는 편이'
      break
    case Axis.LIGHTING:
      body = strong
        ? '지금의 노출을 유지하고, 밝은 면이 먼저 타 버리지 않는지 화면에서 확인하며 누르는 편이'
        : m.highlights > 0.02
          ? '노출을 반 스톱쯤 낮추거나 밝은 부분을 기준으로 측광해, 가장 밝은 면의 결을 남기는 편이'
          : m.shadows > 0.04
            ? '어두운 면을 기준으로 측광하거나 반사판 대신 밝은 벽을 활용해, 그림자 속 형태를 살리는 편이'
            : m.contrast < 18
              ? '측광을 피사체에 맞추고, 배경과의 밝기 차이가 생기는 방향으로 반 걸음 움직이는 편이'
              : '같은 장면을 노출만 반 스톱씩 다르게 몇 장 담아, 가장 마음에 드는 균형을 고르는 편이'
      break
    case Axis.COLOR:
      body = strong
        ? '지금의 색 균형을 유지하고, 광원이 바뀌지 않는 짧은 시간 안에 몇 장을 더 담는 편이'
        : m.channelCast > 8 && m.warmth > 0
          ? '화이트밸런스를 자동에 맡기지 말고 차가운 쪽으로 한 단계 옮겨, 피사체의 고유색을 살리는 편이'
          : m.channelCast > 8
            ? '화이트밸런스를 따뜻한 쪽으로 한 단계 옮겨, 장면의 온기를 되찾는 편이'
            : m.saturation > 0.42
              ? '색이 강한 요소를 하나 덜어내고, 주조색 하나가 중심을 잡게 하는 편이'
              : m.hueVariety >= 8
                ? '색이 많은 장면에서는 주조색 두 개를 정하고, 나머지는 프레임 밖으로 밀어내는 편이'
                : '광원의 색을 그대로 두고, 같은 장면을 빛의 방향이 달라지는 시간에 한 번 더 담는 편이'
      break
    case Axis.SHARPNESS:
      body = strong
        ? '지금의 조리개와 초점을 유지하고, 같은 구도에서 한 장만 더 여유 있게 담는 편이'
        : m.sharpness < 60
          ? '셔터 속도를 초점거리의 역수보다 빠르게 잡고, 호흡을 멈춘 뒤 반셔터로 초점을 고정하는 편이'
          : m.localContrast < 12
            ? '조리개를 반 스톱 조여 피사체의 결을 세우고, 배경은 자연스럽게 물러나게 두는 편이'
            : m.edges < 0.01
              ? '초점을 피사체의 눈이나 윤곽에 두고, 초점이 잠긴 것을 확인한 뒤 누르는 편이'
              : '초점을 피사체의 핵심부에 고정하고, 카메라를 몸에 붙여 흔들림을 줄이는 편이'
      break
    case Axis.EMOTION:
      body = strong
        ? '지금의 톤 방향을 유지하며, 피사체와 배경의 온도 차이가 살아 있는 순간을 한 장 더 담는 편이'
        : a.subjects.length === 0
          ? '장면에 온도를 줄 요소를 하나 더 넣거나, 반대로 여백만 남겨 적요를 주제로 삼는 편이'
          : '피사체의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하는 편이'
      break
    case Axis.TIMING:
      body = a.subjects.some((s) => HUMAN.has(s.label.toLowerCase()))
        ? '제스처가 정점에 이르기 직전부터 연속으로 담아, 가장 또렷한 한 장을 고르는 편이'
        : '장면의 움직임이 멈추는 지점을 기다렸다가 누르는 편이'
      break
  }
  return toneEnd(tone, body)
}

function editAdvice(axis: Axis, a: Assessment, tone: ToneKey): string {
  const m = a.measure
  const strong = band(a.evidence[axis].score) === 2
  let body = ''
  switch (axis) {
    case Axis.LIGHTING:
      body = strong
        ? '지금의 톤을 그대로 두고, 밝은 끝과 어두운 끝만 조금 다듬는 편이'
        : m.highlights > 0.02
          ? '하이라이트를 낮춰 흰색으로 눌린 결을 되살리고, 톤 커브의 어깨를 부드럽게 만드는 편이'
          : m.shadows > 0.04
            ? '어두운 쪽을 들어 올리되 검정은 남겨, 그림자 속 형태를 꺼내는 편이'
            : m.contrast < 20
              ? '검정 지점을 조금 내려 계조의 폭을 넓히고, 중간 톤을 살짝 눌러 깊이를 만드는 편이'
              : '노출과 대비를 그대로 두고, 밝은 면과 어두운 면의 균형만 확인하는 편이'
      break
    case Axis.COLOR:
      body = strong
        ? '지금의 색을 그대로 두고, 채도를 더 올리지 않는 편이'
        : m.channelCast > 8 && m.warmth > 0
          ? '색온도를 차가운 쪽으로 조금 옮기고, 피사체의 고유색이 돌아오는지 확인하는 편이'
          : m.channelCast > 8
            ? '색온도를 따뜻한 쪽으로 조금 옮겨 장면의 온기를 살리는 편이'
            : m.saturation > 0.42
              ? '채도를 한 단계 낮추고 주조색이 도드라지게 만드는 편이'
              : '색조의 균형을 그대로 두고, 색이 서로 섞이지 않게 채도만 미세하게 정리하는 편이'
      break
    case Axis.COMPOSITION:
      body = strong
        ? '지금의 프레임을 그대로 두고, 크롭으로 여백을 조금만 정리하는 편이'
        : a.subjects.length === 0
          ? '크롭으로 화면을 나누어 면과 빛의 비율을 다시 잡는 편이'
          : nearestThirds(a.subjects[0].cx, a.subjects[0].cy) > 0.14
            ? '크롭으로 가장 큰 피사체를 힘 있는 자리로 옮기는 편이'
            : m.maxTilt > 3
              ? '회전으로 수평선을 반듯하게 맞추는 편이'
              : '크롭으로 가장자리 여백을 조금 덜어내 화면을 조이는 편이'
      break
    case Axis.SHARPNESS:
      body = strong
        ? '샤프닝을 더하지 않고 지금의 선명도를 유지하는 편이'
        : m.sharpness < 60
          ? '약한 샤프닝과 노이즈 정리로 윤곽을 세우되, 과하게 만들지 않는 편이'
          : m.localContrast < 12
            ? '부분 대비를 살짝 올려 표면의 결을 세우는 편이'
            : '초점이 놓인 곳만 국소적으로 다듬고, 배경은 그대로 두는 편이'
      break
    case Axis.EMOTION:
      body = '톤의 방향을 한쪽으로 정리하고, 색온도를 조금 옮겨 장면의 온도를 맞추는 편이'
      break
    case Axis.TIMING:
      body = '지금은 결과를 크게 바꾸기 어려우므로, 대비와 채도를 미세하게 조정해 인상만 정돈하는 편이'
      break
  }
  return toneEnd(tone, body)
}

function reflection(axis: Axis, _a: Assessment): string {
  switch (axis) {
    case Axis.COMPOSITION:
      return '프레임 밖의 상황은 알 수 없으므로, 화면 안의 배치만을 근거로 삼았습니다. 같은 구도라도 무엇이 잘렸는지에 따라 의미는 달라집니다.'
    case Axis.LIGHTING:
      return '빛이 어느 방향에서 왔는지, 주광과 부광이 어떻게 나뉘었는지는 한 장으로 확정할 수 없습니다. 화면에 드러난 명암의 관계만 읽었습니다.'
    case Axis.COLOR:
      return '평균적인 색만으로 조명의 색을 확정할 수는 없습니다. 화면에 보이는 색의 관계를 기준으로 삼았고, 실제 광원은 달랐을 수 있습니다.'
    case Axis.SHARPNESS:
      return '같은 정도의 선명함이라도 피사체와 해상도에 따라 의미가 달라집니다. 의도적으로 흐린 사진일 수도 있습니다.'
    case Axis.EMOTION:
      return '감정은 값으로 확정할 수 없으므로, 화면의 빛과 색에서 읽히는 인상만 적었습니다. 같은 사진이 다른 사람에게는 다른 온도로 다가옵니다.'
    case Axis.TIMING:
      return '한 장의 정지된 이미지로 결정적 순간이었는지 확정하지는 않았습니다. 장면의 배열에서 읽히는 인상만 남겼습니다.'
  }
}

// ---------- 잘 살아난 점 / 더 다듬을 점 ----------
function praise(axis: Axis, a: Assessment): string {
  const m = a.measure
  const main = a.subjects[0]
  const strong = band(a.evidence[axis].score) >= 2
  let body = ''
  switch (axis) {
    case Axis.COMPOSITION:
      if (!main)
        body = strong
          ? '피사체 없이도 화면이 무너지지 않습니다. 여백의 크기와 밝기의 위치가 서로를 견제해, 장면이 스스로 균형을 잡고 있습니다.'
          : '대상 없이 화면을 채우려 하지 않은 절제가 있습니다. 여백이 사진의 주제로 남아, 보는 사람이 그 빈 곳에 자기 생각을 놓을 수 있습니다.'
      else if (nearestThirds(main.cx, main.cy) <= 0.1)
        body = '가장 큰 피사체가 힘 있는 자리에 놓여 시선이 한 번에 닿습니다. 남은 여백이 그 자리를 밀어 주어 화면이 답답하지 않고, 눈이 사진 위를 걷는 동안 걸리는 곳이 없습니다.'
      else if (m.maxTilt <= 3)
        body = '화면을 가로지르는 선이 반듯해 장면의 뼈대가 흔들리지 않습니다. 그 안정 위에서 대상이 마음 놓고 자리를 잡고 있습니다.'
      else body = '프레임 안의 요소가 서로의 자리를 침범하지 않습니다. 각자 숨 쉴 공간을 가진 덕분에 화면이 조용하게 정돈되었습니다.'
      break
    case Axis.LIGHTING:
      if (m.highlights <= 0.02 && m.shadows <= 0.04 && m.contrast >= 30)
        body = '밝은 면과 어두운 면이 각자의 결을 지킨 채 마주하고 있습니다. 빛이 형태를 설명하고 형태가 다시 빛을 받쳐, 사진이 입체로 서 있습니다.'
      else if (m.contrast >= 40)
        body = '명암의 낙차가 크게 열려 있어 장면에 무게가 실립니다. 밝은 곳에서 어두운 곳으로 눈이 옮겨 갈 때, 빛이 지나온 자리가 또렷하게 남아 있습니다.'
      else body = '빛이 과하지 않게 정돈되어 있습니다. 어두운 면이 검정으로 닫히지 않고, 밝은 면도 흰색으로 타지 않아 사진이 편안하게 읽힙니다.'
      break
    case Axis.COLOR:
      if (m.hueTotal <= 0)
        body = '색을 덜어낸 선택이 사진을 단단하게 만듭니다. 계조와 윤곽만으로 장면을 세워, 인상이 유행과 무관하게 남습니다.'
      else if (m.dominantShare >= 0.45 && m.saturation < 0.42)
        body = '주조색이 화면을 넓게 덮으면서도 채도가 절제되어 있습니다. 색이 하나로 모여 장면 전체가 같은 온도로 정돈되고, 눈이 편안해집니다.'
      else if (m.channelCast <= 8)
        body = '색온도가 고르게 잡혀 사물의 고유색이 정직하게 남아 있습니다. 색이 서로를 지지해, 화면을 오래 바라봐도 피로하지 않습니다.'
      else body = '색의 방향이 한쪽으로 정리되어 있습니다. 장면이 하나의 공기를 공유하고, 그 통일감이 사진의 인상을 만들어냅니다.'
      break
    case Axis.SHARPNESS:
      if (m.sharpness >= 120 && m.localContrast >= 12)
        body = '표면의 결이 손끝에 닿을 듯 남아 있습니다. 돌의 거칠기와 옷감의 주름이 각자의 촉감으로 구분되어, 사진이 눈이 아니라 손으로 기억됩니다.'
      else if (m.sharpness >= 120)
        body = '초점이 놓인 자리가 분명해 보는 눈이 자연스럽게 그곳으로 갑니다. 사진이 어디를 봐야 하는지 스스로 알려 주고 있습니다.'
      else body = '선명함 대신 부드러움이 사진의 성격이 되었습니다. 질감이 정보로 남기보다 분위기로 번져, 장면이 조용히 다가옵니다.'
      break
    case Axis.EMOTION:
      if (a.subjects.some((s) => HUMAN.has(s.label.toLowerCase())))
        body = '사람이 있다는 것만으로 장면에 온도가 생깁니다. 인물의 표정과 몸짓이 빛과 색을 하나의 정서로 묶어, 사진이 관찰에서 경험으로 넘어갑니다.'
      else if (a.subjects.length > 0)
        body = '대상 하나가 장면의 중심을 잡고, 주변의 여백이 그 대상을 받쳐 줍니다. 사진의 시선이 흩어지지 않아 인상이 오래 남습니다.'
      else body = '빈 공간이 사진의 주제가 되었습니다. 무엇이 없는지를 보여 주는 구성이 장면에 적요를 만들고, 보는 사람을 그 안에 세웁니다.'
      break
    case Axis.TIMING:
      if (a.subjects.some((s) => HUMAN.has(s.label.toLowerCase())) && m.sharpness >= 120)
        body = '움직임이 있을 법한 장면을 또렷하게 붙잡았습니다. 순간이 흐려지지 않고 남아, 사진이 정지 화면이 아니라 장면의 정점처럼 읽힙니다.'
      else if (m.sharpness >= 120)
        body = '포착이 또렷해 장면의 결이 그대로 남았습니다. 시간이 잠시 멈춘 듯한 공기가 사진 안에 머물러 있습니다.'
      else body = '장면이 잔잔하게 가라앉아 지나간 뒤의 공기를 담았습니다. 서두르지 않는 시간이 사진의 성격이 되었습니다.'
      break
  }
  return strong ? body : modest(axis, a)
}

function modest(axis: Axis, a: Assessment): string {
  const m = a.measure
  switch (axis) {
    case Axis.COMPOSITION:
      return '구도는 크게 흔들리지 않습니다. 눈이 걸리는 곳이 없고, 화면이 무난하게 정돈되어 사진의 뼈대가 버티고 있습니다.'
    case Axis.LIGHTING:
      return '빛이 과하지 않게 정돈되어 있습니다. 밝은 면과 어두운 면이 서로를 지우지 않아, 사진이 편안하게 읽힙니다.'
    case Axis.COLOR:
      return '색이 크게 어긋나지 않습니다. 튀는 색 없이 화면의 온도가 유지되어, 사진의 인상이 흩어지지 않습니다.'
    case Axis.SHARPNESS:
      return m.sharpness >= 60
        ? '형태를 읽는 데는 부족함이 없습니다. 윤곽이 남아 있어 사진이 무엇을 담았는지 분명합니다.'
        : '선명함은 낮지만 윤곽이 완전히 사라지지는 않았습니다. 사진의 형태는 여전히 읽힙니다.'
    case Axis.EMOTION:
      return '분위기가 담담하게 전해집니다. 강하게 붙잡지는 않지만, 장면의 공기가 남아 있습니다.'
    case Axis.TIMING:
      return '장면이 정돈된 채로 남아 있습니다. 움직임의 정점은 아니어도, 사진이 담으려 한 순간은 분명합니다.'
  }
}

function refine(axis: Axis, a: Assessment): string {
  const m = a.measure
  const main = a.subjects[0]
  switch (axis) {
    case Axis.COMPOSITION:
      if (a.subjects.length === 0)
        return '화면에 머물 요소를 하나 정해 주면 구도가 생깁니다. 지금은 여백이 넓은 만큼, 그 여백을 받쳐 줄 중심이 아쉽습니다.'
      if (a.subjects.some((s) => s.touchesEdge && s.area > 0.05))
        return '가장자리에 닿은 요소가 프레임을 조여 화면이 다소 답답합니다. 그 요소를 안으로 들이거나 과감히 덜어내면 여백이 숨을 쉬게 됩니다.'
      if (m.maxTilt > 3)
        return '수평선이 살짝 기울어 장면이 미묘하게 흔들립니다. 반듯하게 맞추는 것만으로도 사진이 한결 안정됩니다.'
      if (main && nearestThirds(main.cx, main.cy) > 0.14)
        return '가장 큰 피사체가 힘 있는 자리에서 조금 벗어나 있습니다. 반 걸음 옮기는 것만으로 시선의 착지점이 분명해집니다.'
      return '지금의 배치는 안정적이지만 인상이 조금 평범합니다. 여백을 한쪽으로 몰아 긴장을 만들면 사진에 표정이 생깁니다.'
    case Axis.LIGHTING:
      if (m.highlights > 0.02 && m.shadows > 0.04)
        return '밝은 끝과 어두운 끝이 함께 닫혀 있습니다. 노출을 조금 조정해 양쪽의 결을 남기면, 사진이 담고 있던 정보가 되살아납니다.'
      if (m.highlights > 0.02)
        return '가장 밝은 면이 흰색으로 눌려 표면의 결이 사라졌습니다. 노출을 반 스톱 낮추면 빛의 질감이 돌아옵니다.'
      if (m.shadows > 0.04)
        return '어두운 면이 검정으로 뭉개져 그림자 속 형태가 지워졌습니다. 어두운 쪽을 살짝 열어 주면 장면의 깊이가 살아납니다.'
      if (m.contrast < 18)
        return '명암의 낙차가 완만해 장면이 평평하게 읽힙니다. 빛의 방향이 다른 시간을 기다리거나 측광을 바꾸면 입체감이 생깁니다.'
      return '빛은 안정적이지만 장면을 밀어 올리는 힘이 조금 부족합니다. 밝은 면과 어두운 면이 만나는 지점을 한 번 더 의식해 보십시오.'
    case Axis.COLOR:
      if (m.channelCast > 12)
        return '색온도가 한쪽으로 기울어 피사체의 고유색이 배경에 잠길 수 있습니다. 화이트밸런스를 중립 쪽으로 옮기면 색이 제자리를 찾습니다.'
      if (m.saturation > 0.42)
        return '채도가 높아 색이 서로 경쟁합니다. 한 단계 낮추고 주조색 하나를 남기면 인상이 정리됩니다.'
      if (m.hueVariety >= 8)
        return '색의 수가 많아 화면이 분주합니다. 주조색 두 개로 정리하면 사진의 목소리가 선명해집니다.'
      if (m.saturation < 0.12)
        return '색의 온기가 낮아 장면이 조금 멀게 느껴집니다. 채도를 미세하게 올리거나, 반대로 완전한 무채색으로 밀어 정리를 끝내는 방법도 있습니다.'
      return '색은 자연스럽지만 방향이 뚜렷하지 않습니다. 어떤 색이 주인인지 정해 주면 사진의 인상이 한 번에 잡힙니다.'
    case Axis.SHARPNESS:
      if (m.sharpness < 60)
        return '질감이 눌려 사물의 재질이 전달되지 않습니다. 촬영할 때 흔들림을 줄이거나, 보정에서 약한 샤프닝으로 윤곽을 세울 수 있습니다.'
      if (m.localContrast < 12)
        return '표면의 요철이 얕아 사진이 평면에 가깝게 읽힙니다. 조리개를 조이거나 부분 대비를 올리면 결이 살아납니다.'
      return '선명도는 충분하지만 초점이 놓인 자리가 사진의 이야기와 어긋날 수 있습니다. 가장 중요한 곳에 초점이 닿았는지 확인해 보십시오.'
    case Axis.EMOTION:
      if (a.subjects.length === 0)
        return '장면이 정보를 담는 데 머물러 있습니다. 온도를 줄 요소를 하나 더하거나, 여백을 끝까지 밀어 적요를 주제로 삼으면 인상이 달라집니다.'
      return '분위기는 읽히지만 인상이 중립적입니다. 피사체의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하면 감정의 온도가 올라갑니다.'
    case Axis.TIMING:
      return '장면이 일어나기 전이나 지나간 뒤를 담은 듯 정지된 공기가 남습니다. 움직임이 정점에 이르는 순간을 노리면 사진이 살아납니다.'
  }
}

// ---------- 종합 크리틱의 문단 재료 ----------
function frameBreath(m: Measure, seed: number): string {
  if (m.contrast >= 40 && m.highlights <= 0.02 && m.shadows <= 0.04)
    return say(
      seed,
      211,
      '프레임은 크게 숨을 들이쉰 듯 열려 있습니다. 밝은 면과 어두운 면이 서로를 밀어내며 화면에 깊이를 만들고, 눈이 그 사이를 오가는 것만으로도 사진이 읽힙니다.',
      '화면의 호흡이 깊습니다. 밝음과 어둠이 각자의 자리를 지키며 넓게 열려 있어, 사진을 보는 동안 장면의 공기가 함께 움직입니다.',
    )
  if (m.contrast < 18)
    return say(
      seed,
      212,
      '프레임의 호흡은 짧고 고릅니다. 명암의 낙차가 완만해 화면이 한 폭의 면처럼 펼쳐지고, 사진은 강하게 말하기보다 조용히 머뭅니다.',
      '화면이 잔잔하게 가라앉아 있습니다. 빛의 낙차가 크지 않아 장면 전체가 같은 온도로 이어지고, 눈이 급하게 움직이지 않습니다.',
    )
  if (m.highlights > 0.02 || m.shadows > 0.04)
    return say(
      seed,
      213,
      '프레임은 한쪽으로 기울어 숨을 내쉬고 있습니다. 밝은 끝이나 어두운 끝에서 정보가 빠져나가, 화면의 무게가 남은 쪽으로 쏠립니다.',
      '화면의 양 끝이 조금씩 닫혀 있습니다. 그 닫힘이 사진을 차분하게 만들기도 하지만, 장면이 담고 있던 결의 일부도 함께 지웁니다.',
    )
  return say(
    seed,
    214,
    '프레임의 호흡은 고르게 이어집니다. 밝은 곳과 어두운 곳이 서로의 자리를 나누어, 사진을 보는 동안 눈이 편하게 머뭅니다.',
    '화면이 무리 없이 열려 있습니다. 빛과 그림자가 서로를 방해하지 않아, 장면이 제 속도로 읽힙니다.',
  )
}

function lightColorUnion(m: Measure, seed: number): string {
  if (m.warmth > 6 && m.contrast >= 30)
    return say(
      seed,
      221,
      '따뜻한 색과 단단한 명암이 같은 방향을 가리킵니다. 빛이 장면을 데우고 그림자가 그 온기를 붙잡아, 사진 전체가 하나의 공기를 공유합니다.',
      '붉은 기운이 깊은 그림자 위에 얹혀 있습니다. 색이 빛을 따라가며 장면의 온도를 만들고, 사진이 저녁의 공기처럼 읽힙니다.',
    )
  if (m.warmth < -6 && m.contrast >= 30)
    return say(
      seed,
      222,
      '푸른 색과 단단한 명암이 만나 장면이 서늘하게 서 있습니다. 빛이 형태를 세우고 색이 그 형태에 온도를 낮춰, 사진이 사색적인 인상을 얻습니다.',
    )
  if (m.saturation < 0.12 && m.contrast >= 30)
    return say(
      seed,
      223,
      '색을 덜어낸 화면에서 빛과 형태가 주인공이 됩니다. 명암의 낙차가 사진의 구조를 만들고, 색의 부재가 오히려 장면을 단단하게 합니다.',
    )
  if (m.saturation >= 0.28 && m.contrast < 22)
    return say(
      seed,
      224,
      '색은 살아 있는데 빛의 낙차가 완만해, 화면이 색의 무늬처럼 펼쳐집니다. 형태보다 색의 흐름이 먼저 보이고, 사진이 회화에 가까워집니다.',
    )
  return say(
    seed,
    225,
    '빛과 색이 서로를 방해하지 않고 나란히 놓여 있습니다. 한쪽이 다른 쪽을 압도하지 않아, 사진이 균형 잡힌 인상으로 남습니다.',
    '빛은 형태를 받치고 색은 분위기를 맡습니다. 두 요소가 각자의 몫을 다해, 화면이 한 번에 읽힙니다.',
  )
}

function gazeAnchor(a: Assessment, seed: number): string {
  const main = a.subjects[0]
  const m = a.measure
  if (main && main.area >= 0.45)
    return say(
      seed,
      231,
      '시선은 대상 자체에 붙어 있습니다. 화면을 크게 덮은 피사체가 다른 모든 요소를 뒤로 물리고, 사진은 그 대상에 바짝 다가선 관찰이 됩니다.',
      '눈이 먼저 닿는 곳은 화면을 가득 채운 대상입니다. 배경은 거의 정보를 주지 않고, 사진의 무게가 한곳에 모여 있습니다.',
    )
  if (main && main.area <= 0.05)
    return say(
      seed,
      232,
      '시선은 작은 대상에 닿은 뒤 넓은 여백으로 흘러갑니다. 그 이동 자체가 사진의 리듬이 되고, 여백이 대상보다 큰 목소리로 말합니다.',
      '작은 피사체 하나가 넓은 공간에 놓여 있습니다. 눈은 그 점을 확인한 뒤 주변을 천천히 훑고, 사진은 그 느린 시선을 기다려 줍니다.',
    )
  if (main)
    return say(
      seed,
      233,
      '시선은 가장 큰 대상에서 출발해 주변으로 퍼집니다. 대상과 여백의 비율이 무난해, 눈이 화면을 한 번에 읽고 오래 머물 자리를 스스로 정합니다.',
    )
  if (m.lines.length > 0)
    return say(
      seed,
      234,
      '뚜렷한 대상이 없어 시선은 화면을 가로지르는 선을 따라갑니다. 선의 방향이 사진의 흐름을 만들고, 눈이 그 흐름을 따라 화면 끝까지 이동합니다.',
      '시선을 붙잡는 대상은 없지만, 화면을 가로지르는 선이 눈길을 이끕니다. 사진은 무엇을 보는 사진이 아니라 어디로 가는 사진이 됩니다.',
    )
  return say(
    seed,
    235,
    '시선을 붙잡는 대상도, 이끄는 선도 없습니다. 눈이 화면을 스스로 훑어야 하고, 사진은 그 자유를 보는 사람에게 넘깁니다.',
    '머물 자리가 정해지지 않은 화면입니다. 밝기와 면의 배치만이 눈의 순서를 정하고, 그 순서는 사람마다 달라집니다.',
  )
}

function spatialDepth(m: Measure, subjects: SubjectBox[], seed: number): string {
  if (m.localContrast >= 14 && subjects.length >= 2)
    return say(
      seed,
      241,
      '앞뒤의 밀도 차이가 분명해 공간이 여러 겹으로 나뉩니다. 가까운 것은 결이 진하게, 먼 것은 옅게 남아 사진 안에 거리가 생깁니다.',
      '화면 안에 앞과 뒤가 있습니다. 질감이 살아 있는 쪽이 앞으로 나오고, 부드러워진 쪽이 뒤로 물러나 장면이 평면에 머물지 않습니다.',
    )
  if (m.localContrast < 8)
    return say(
      seed,
      242,
      '다만 앞뒤의 밀도 차이가 얕아 공간이 한 겹으로 붙어 있습니다. 사진이 깊이보다 무늬처럼 읽히고, 장면이 조금 평평합니다.',
    )
  if (subjects.length === 0)
    return say(
      seed,
      243,
      '대상이 없어 깊이는 빛의 분포로만 만들어집니다. 밝은 면이 앞으로, 어두운 면이 뒤로 물러나며 화면에 조용한 원근이 생깁니다.',
    )
  return say(
    seed,
    244,
    '공간의 깊이는 과장되지 않았습니다. 앞뒤가 완전히 붙지도, 완전히 떨어지지도 않아 장면이 자연스러운 거리로 읽힙니다.',
  )
}

function direction(tone: ToneKey, depth: Depth): string {
  if (tone === 'MENTOR')
    return depth.key === 'QUICK'
      ? '아래에서 빛과 색, 구도를 차례로 짚었습니다. 오늘은 한 가지만 의식해 보셔도 충분합니다.'
      : '아래에서 빛과 색, 구도와 질감을 하나씩 짚었습니다. 한 번에 모두 바꾸지 않으셔도 됩니다. 다음 장에서 한 가지만 의식해 보시면 차이가 보입니다.'
  if (tone === 'CRITIC')
    return depth.key === 'QUICK'
      ? '아래 항목은 화면에 실제로 드러난 것만 적었습니다. 감상보다 근거를 먼저 확인하십시오.'
      : '아래 항목은 화면에 실제로 드러난 것만 적었습니다. 취향의 문제와 사진의 문제를 구분해 읽으시면 이 비평이 더 유용해집니다.'
  return depth.key === 'QUICK'
    ? '아래 항목은 화면에서 본 요소와 그 해석을 구분해 적었습니다.'
    : '아래 항목은 화면에서 본 요소와 그 해석을 구분해 적었습니다. 해석은 단정이 아니라 가능성으로 읽어 주십시오.'
}

function closingFor(tone: ToneKey, depth: Depth): string {
  if (depth.key === 'QUICK') return ''
  if (tone === 'MENTOR') return '사진은 한 장으로 완성되지 않습니다. 지금의 장면을 다시 만났을 때 기억나는 한 가지만 가져가셔도 좋겠습니다.'
  if (tone === 'CRITIC') return '좋은 사진은 흠이 없는 사진이 아니라, 무엇을 선택했는지 분명한 사진입니다. 이 장에서 그 선택이 어디였는지 다시 보십시오.'
  return '같은 장면을 조건만 바꿔 몇 장 담으면, 어떤 요소가 결과를 움직였는지 스스로 확인할 수 있습니다.'
}

// ---------- sections ----------
export interface SectionOptions {
  persona: Persona
  level: Level
  tone: ToneKey
  depth: Depth
  score?: ScoreResult | null
}

export function sections(a: Assessment, options: SectionOptions): Record<string, string> {
  try {
    return buildSections(a, options)
  } catch {
    return fallbackSections()
  }
}

function fallbackSections(): Record<string, string> {
  const out: Record<string, string> = {}
  for (const title of SECTION_TITLES) {
    out[title] =
      title === '종합 크리틱'
        ? '비평 문장을 엮는 중 문제가 생겨 이번에는 짧게 남깁니다. 사진의 빛과 색, 구도를 먼저 눈으로 확인해 보십시오.'
        : '이 항목의 문장을 끝까지 만들지 못했습니다. 사진을 다시 분석하면 대부분의 항목이 채워집니다.'
  }
  return out
}

function buildSections(a: Assessment, options: SectionOptions): Record<string, string> {
  const { persona, level, tone, depth } = options
  const ev = a.evidence
  const resolved = options.score ?? evaluate(a.nima.aesthetic, ev, persona, level)
  const contributions = resolved?.contributions ?? ev.map((e, i) => persona.weights[i] * e.score)
  const ranked = AXIS_ORDER.slice().sort((x, y) => {
    if (ev[y].score !== ev[x].score) return ev[y].score - ev[x].score
    return persona.weights[y] - persona.weights[x]
  })
  const top = ranked.slice(0, 2)
  const weak = ranked.slice(-2).reverse()
  const s10 = resolved?.adjusted ?? 1 + 9 * clamp01(contributions.reduce((acc, v) => acc + v, 0))
  const limit = depth.sentences
  const out: Record<string, string> = {}
  out['종합 크리틱'] = summary(a, s10, tone, depth, top, weak)
  out['빛과 노출'] = lightSection(a, tone, limit)
  out['색채'] = colorSection(a, tone, limit)
  out['구도'] = compositionSection(a, tone, limit)
  out['선명도와 질감'] = sharpnessSection(a, tone, limit)
  out['분위기와 순간'] = moodSection(a, tone, limit)
  out['잘 살아난 점'] = strengths(a, top, limit)
  out['더 다듬을 점'] = improvements(a, level, tone, weak, limit)
  out['촬영 제안'] = shooting(a, tone, depth, weak)
  out['보정 제안'] = editing(a, tone, depth, weak)
  return out
}

function join(items: string[], limit: number): string {
  return items
    .filter((item) => item.trim().length > 0)
    .slice(0, limit)
    .map((item) => item.trim())
    .join(' ')
}

/** 점수대에 따른 한 줄 인상. 점수 카드 아래에 바로 놓인다. */
export function verdict(a: Assessment, s10: number): string {
  let top = 0
  for (let i = 1; i < AXIS_ORDER.length; i++) if (a.evidence[i].score > a.evidence[top].score) top = i
  const flavour = [
    '구도가 반듯한',
    '빛이 살아 있는',
    '색이 정돈된',
    '질감이 또렷한',
    '분위기가 서린',
    '순간을 붙잡은',
  ][top]
  const quality =
    s10 >= 9
      ? '흠을 찾기 어려운 한 장'
      : s10 >= 8.2
        ? '완성도가 높은 한 장'
        : s10 >= 7.4
          ? '균형이 좋은 한 장'
          : s10 >= 6.6
            ? '안정적으로 정리된 한 장'
            : s10 >= 5.8
              ? '가능성이 보이는 한 장'
              : '다듬을 여지가 큰 한 장'
  return `${flavour}, ${quality}`
}

function openingFor(s10: number): string {
  if (s10 >= 9)
    return '완성도가 단단한 한 장입니다. 흠을 찾기보다 유지할 이유를 먼저 보게 되고, 화면을 오래 바라봐도 인상이 흐트러지지 않습니다.'
  if (s10 >= 8.2) return '완성도가 높은 한 장입니다. 빛과 색이 서로를 받쳐 주고, 구도가 그 위에서 흔들리지 않습니다.'
  if (s10 >= 7.4) return '균형이 좋은 한 장입니다. 큰 흔들림 없이 화면이 자기 자리를 잡고 있어, 사진의 목소리가 또렷하게 들립니다.'
  if (s10 >= 6.6) return '안정적으로 정리된 한 장입니다. 지금의 기본을 조금만 더 밀면 인상이 한 단계 또렷해집니다.'
  if (s10 >= 5.8) return '가능성이 보이는 한 장입니다. 방향은 맞고, 손볼 지점도 분명합니다.'
  return '아직 다듬을 여지가 큰 한 장입니다. 다만 출발점은 분명하고, 어디서부터 손대면 되는지도 보입니다.'
}

function quota(limit: number, parts: number): number[] {
  const q = new Array(parts).fill(1)
  let left = Math.max(0, limit - parts)
  let index = 0
  while (left > 0) {
    q[index % parts] += 1
    index += 1
    left -= 1
  }
  return q
}

function summary(
  a: Assessment,
  s10: number,
  tone: ToneKey,
  depth: Depth,
  top: number[],
  weak: number[],
): string {
  const m = a.measure
  const seed = seedOf(m)
  const best = top[0]
  const weakest = weak[0]
  const voice = buildVoice(a.scene, seed)
  const opening = voice ? `${voice.opening} ${openingFor(s10)}` : openingFor(s10)
  const first = [opening, frameBreath(m, seed), lightColorUnion(m, seed)]
  const second = [gazeAnchor(a, seed), spatialDepth(m, a.subjects, seed), highlight(best, a, seed)]
  const third = [
    `${eun(AXIS_TITLES[weakest])} 이 사진에서 먼저 살펴볼 지점입니다. ${shortNote(weakest)}`,
    direction(tone, depth),
    closingFor(tone, depth),
  ]
  const q = quota(depth.sentences, 3)
  const parts = [first, second, third]
  return parts
    .map((part, i) => part.slice(0, q[i]).filter((line) => line.trim().length > 0).join(' '))
    .filter((text) => text.trim().length > 0)
    .join('\n\n')
}

function highlight(axis: number, a: Assessment, seed: number): string {
  const level = band(a.evidence[axis].score)
  if (level === 2) {
    return [
      '무엇보다 구도가 눈에 편합니다. 화면이 한 번에 읽히고, 어디를 봐야 할지 망설일 필요가 없습니다.',
      '무엇보다 빛이 좋습니다. 밝은 면과 어두운 면이 서로를 살려 주어, 사진을 보는 동안 장면의 공기가 함께 움직입니다.',
      '무엇보다 색이 차분합니다. 화면의 온도가 한 방향으로 모여 있어, 눈이 편안하게 오래 머뭅니다.',
      '무엇보다 질감이 살아 있습니다. 표면의 결이 눈에 잡혀, 사진이 손에 닿을 듯 가깝게 느껴집니다.',
      '무엇보다 분위기가 남습니다. 사진을 덮은 뒤에도 장면의 공기가 잠시 이어집니다.',
      '무엇보다 순간이 살아 있습니다. 장면이 정지해 있지 않고, 한 박자 움직일 준비를 하고 있습니다.',
    ][axis]
  }
  if (level === 1)
    return say(
      seed,
      251,
      `눈에 띄는 강점은 크지 않지만, ${eun(AXIS_TITLES[axis])} 무난하게 자리를 잡고 있어 사진이 무너지지 않습니다.`,
      `이 사진을 한 문장으로 말한다면 ${wa(AXIS_TITLES[axis])} 관련된 지점이 가장 먼저 떠오릅니다. 다만 아직 사진의 인상을 결정할 만큼 밀어 올라가지는 않았습니다.`,
    )
  if (a.subjects.length === 0)
    return say(
      seed,
      252,
      '뚜렷한 강점을 짚기는 어렵습니다. 그래도 화면이 완전히 비어 있지는 않아, 빛의 분포를 다시 보면 시작점이 보입니다.',
      '강점을 꼽기 어려운 한 장입니다. 그래도 사진이 실패한 것은 아니고, 아직 인상을 만들 재료가 정리되지 않은 상태입니다.',
    )
  return `뚜렷한 강점을 짚기는 어렵습니다. 그래도 ${AXIS_TITLES[axis]} 쪽에서 시작해 볼 수 있습니다.`
}

function shortNote(axis: number): string {
  return [
    '구도를 한 걸음만 더 정리해도 사진의 인상이 크게 달라집니다.',
    '빛의 균형을 한 번 더 보면 사진이 한 단계 깊어집니다.',
    '색의 방향을 하나 정하면 인상이 훨씬 선명해집니다.',
    '질감을 조금만 더 살리면 사진이 단단해집니다.',
    '분위기에 온기를 더하면 사진이 오래 남습니다.',
    '순간을 반 박자 더 기다리면 장면이 살아납니다.',
  ][axis]
}

function lightSection(a: Assessment, tone: ToneKey, limit: number): string {
  const m = a.measure
  const seed = seedOf(m)
  const score = a.evidence[Axis.LIGHTING].score
  const reading = lightReading(m, score)
  const advice = lightAdvice(m, tone, band(score) === 2)
  const voice = buildVoice(a.scene, seed)
  const subjectLight = voice?.light ?? lightTexture(m, seed)
  const items =
    limit <= 4
      ? [lightTone(m, seed), subjectLight, reading, advice]
      : [
          lightTone(m, seed),
          lightRange(m, seed),
          subjectLight,
          lightShadow(m, seed),
          reading,
          advice,
          lightModel(m, seed),
          lightQuality(m, seed),
          reflection(Axis.LIGHTING, a),
        ]
  return join(items, limit)
}

function colorSection(a: Assessment, tone: ToneKey, limit: number): string {
  const m = a.measure
  const seed = seedOf(m)
  const score = a.evidence[Axis.COLOR].score
  const reading = colorReading(m, score)
  const advice = colorAdvice(m, tone, band(score) === 2)
  const voice = buildVoice(a.scene, seed)
  const subjectColor = voice?.color ?? colorPigment(m, seed)
  const items =
    limit <= 4
      ? [colorTemperature(m, seed), subjectColor, reading, advice]
      : [
          colorTemperature(m, seed),
          subjectColor,
          colorAir(m, seed),
          colorUnion(m, seed),
          reading,
          advice,
          colorWeight(m, seed),
          colorPixel(m, seed),
          reflection(Axis.COLOR, a),
        ]
  return join(items, limit)
}

function compositionSection(a: Assessment, tone: ToneKey, limit: number): string {
  const m = a.measure
  const seed = seedOf(m)
  const score = a.evidence[Axis.COMPOSITION].score
  const reading = compReading(m, a.subjects, score)
  const advice = compAdvice(m, a.subjects, tone, band(score) === 2)
  const voice = buildVoice(a.scene, seed)
  const subjectComposition = voice?.composition ?? compBalance(m, a.subjects, seed)
  const items =
    limit <= 4
      ? [compPlacement(m, a.subjects, seed), subjectComposition, reading, advice]
      : [
          compPlacement(m, a.subjects, seed),
          compLines(m, seed),
          compSpace(m, a.subjects, seed),
          subjectComposition,
          reading,
          advice,
          compFlow(m, a.subjects, seed),
          compFraming(m, a.subjects, seed),
          reflection(Axis.COMPOSITION, a),
        ]
  return join(items, limit)
}

function sharpnessSection(a: Assessment, tone: ToneKey, limit: number): string {
  const m = a.measure
  const seed = seedOf(m)
  const score = a.evidence[Axis.SHARPNESS].score
  const reading = sharpReading(m, score)
  const advice = sharpAdvice(m, tone, band(score) === 2)
  const voice = buildVoice(a.scene, seed)
  const subjectTexture = voice?.texture ?? sharpTexture(m, seed)
  const items =
    limit <= 4
      ? [sharpFocus(m, seed), subjectTexture, reading, advice]
      : [
          sharpFocus(m, seed),
          subjectTexture,
          sharpSpace(m, a.subjects, seed),
          reading,
          advice,
          sharpEdge(m, seed),
          sharpEven(m, seed),
          sharpIntent(m, seed),
          reflection(Axis.SHARPNESS, a),
        ]
  return join(items, limit)
}

function moodSection(a: Assessment, tone: ToneKey, limit: number): string {
  const m = a.measure
  const seed = seedOf(m)
  const human = a.subjects.some((s) => HUMAN.has(s.label.toLowerCase()))
  const reading = moodReading(a)
  const advice = moodAdvice(tone, human, a.subjects.length === 0)
  const voice = buildVoice(a.scene, seed)
  const subjectMood = voice?.mood ?? moodAfter(m, seed)
  const items =
    limit <= 4
      ? [moodTone(m, seed), subjectMood, reading, advice]
      : [
          moodTone(m, seed),
          moodPresence(m, a.subjects, seed),
          subjectMood,
          moodStillness(m, a.subjects, seed),
          moodStory(m, a.subjects, seed),
          reading,
          advice,
          moodBreath(m, a.subjects, seed),
          reflection(Axis.EMOTION, a),
        ]
  return join(items, limit)
}

function strengthFrame(top: number[], a: Assessment): string {
  const first = AXIS_TITLES[top[0]]
  const second = AXIS_TITLES[top[1]]
  const best = a.evidence[top[0]].score
  const secondScore = a.evidence[top[1]].score
  if (best >= 0.72 && secondScore >= 0.6)
    return `이 사진은 ${wa(first)} ${iGa(second)} 함께 받쳐 주고 있습니다. 두 축이 같은 방향을 가리켜 인상이 흩어지지 않습니다.`
  if (best >= 0.55) return `가장 나은 지점은 ${first}이고, ${eun(second)} 그다음으로 안정적입니다.`
  if (best >= 0.45) return `두드러진 강점은 크지 않지만, 상대적으로 나은 쪽은 ${wa(first)} ${second}입니다.`
  return `뚜렷한 강점을 짚기는 어렵습니다. 그래도 ${first} 쪽에 가능성이 남아 있습니다.`
}

function strengths(a: Assessment, top: number[], limit: number): string {
  const seed = seedOf(a.measure)
  const first = AXIS_TITLES[top[0]]
  const second = AXIS_TITLES[top[1]]
  const best = a.evidence[top[0]].score
  const secondScore = a.evidence[top[1]].score
  const voice = buildVoice(a.scene, seed)
  const items = [strengthFrame(top, a)]
  if (voice) items.push(voice.praise)
  items.push(praise(top[0], a), praise(top[1], a))
  if (best >= 0.72 && secondScore >= 0.6)
    items.push(
      '두 지점이 같은 방향을 가리키고 있어, 사진의 인상이 한 번에 정리됩니다. 강점이 여럿 흩어진 사진보다, 둘이 겹친 사진이 기억에 오래 남습니다.',
    )
  items.push(`기억에 남는 사진은 대개 한 가지 인상이 분명합니다. 이 장에서는 그 인상이 ${first} 쪽에서 옵니다.`)
  items.push('지금의 강점을 유지한 채 다른 요소를 손보면, 사진의 인상이 흔들리지 않고 조금씩 단단해집니다.')
  items.push(`다음 장에서 이 장면을 다시 만난다면, ${first}에서 온 인상을 먼저 기억하시고 같은 조건을 한 번 더 만들어 보십시오.`)
  void second
  void seed
  return join(items, limit)
}

function improvements(a: Assessment, level: Level, tone: ToneKey, weak: number[], limit: number): string {
  const seed = seedOf(a.measure)
  const first = AXIS_TITLES[weak[0]]
  const second = AXIS_TITLES[weak[1]]
  const voice = buildVoice(a.scene, seed)
  const items = [
    level.key === 'GENEROUS'
      ? `지금 값에서 조금만 밀어 올리면 좋아질 지점은 ${wa(first)} ${second}입니다.`
      : level.key === 'BALANCED'
        ? `지금 흐름에서 한 번 더 다듬을 지점은 ${wa(first)} ${second}입니다.`
        : `완성도를 막고 있는 지점은 ${wa(first)} ${second}입니다.`,
    refine(weak[0], a),
    refine(weak[1], a),
  ]
  if (voice) items.splice(1, 0, voice.refine)
  items.push(`둘 중 하나만 고르라면 ${first} 쪽을 먼저 손보시는 편이 좋습니다. 사진의 인상이 가장 크게 흔들리는 곳이 그쪽입니다.`)
  items.push('이 지점들은 사진을 다시 찍지 않아도 일부는 보정으로 정리할 수 있습니다. 다만 원본에 남아 있지 않은 정보를 만들어 낼 수는 없습니다.')
  items.push(
    tone === 'MENTOR'
      ? '한 번에 모두 바꾸지 않아도 됩니다. 다음 장에서 한 가지만 시도해 보시면 차이가 보입니다.'
      : tone === 'CRITIC'
        ? '이 두 지점을 고치지 않으면 다른 요소를 손봐도 인상은 크게 달라지지 않습니다.'
        : '두 지점은 근거 신뢰도가 비교적 높은 항목이라, 고친 뒤 효과를 확인하기 쉽습니다.',
  )
  items.push('지금의 부족함은 사진의 실패가 아니라 아직 결정되지 않은 부분입니다. 어느 쪽으로 밀지 정하는 순간 사진이 달라집니다.')
  items.push('사진을 다시 볼 때는 화면을 절반쯤 멀리 두고 보십시오. 가까이서 보이지 않던 균형의 문제가 그 거리에서 드러납니다.')
  void seed
  return join(items, limit)
}

function shotAngle(a: Assessment, tone: ToneKey): string {
  const main = a.subjects[0]
  const body = !main
    ? '지금의 높이를 유지하되, 화면의 가로와 세로를 바꿔 같은 장면을 한 번 더 담는 편이'
    : main.cy > 0.62
      ? '지금보다 카메라를 조금 내려 눈높이를 낮추고, 대상을 살짝 올려다보는 각도로 담는 편이'
      : main.cy < 0.38
        ? '카메라를 조금 들어 대상을 내려다보는 각도로 담아, 배경을 바닥처럼 깔아 보는 편이'
        : '지금의 눈높이를 유지하되 좌우로 반 걸음 움직여 배경의 방향을 바꿔 보는 편이'
  return toneEnd(tone, body)
}

function shotDistance(a: Assessment, tone: ToneKey): string {
  const main = a.subjects[0]
  const body = !main
    ? '한 걸음 물러나 장면 전체를 조금 더 담아 보고, 다시 반 걸음 다가가 두 장을 비교하는 편이'
    : main.area >= 0.35
      ? '한 걸음 물러나 대상을 조금 덜 채우고, 주변의 맥락을 함께 담는 편이'
      : main.area <= 0.08
        ? '두 걸음 다가가 대상을 키우고, 주변 여백의 비율을 다시 잡는 편이'
        : '지금의 거리를 유지하되, 대상과 배경 사이의 간격이 달라지는 위치를 한 번 더 찾아보는 편이'
  return toneEnd(tone, body)
}

function shotExposure(a: Assessment, tone: ToneKey): string {
  const m = a.measure
  const body =
    m.highlights > 0.02
      ? '밝은 부분을 기준으로 측광하고 노출을 반 스톱 낮춰, 가장 밝은 면의 결을 남기는 편이'
      : m.shadows > 0.04
        ? '어두운 부분을 기준으로 측광해 그림자 속 형태를 살리고, 밝은 면은 그대로 두는 편이'
        : m.contrast < 18
          ? '측광을 피사체에 맞추고 배경과의 밝기 차이가 생기는 방향으로 움직이는 편이'
          : '지금의 측광을 유지하되, 같은 장면을 노출만 반 스톱씩 다르게 세 장 담아 고르는 편이'
  return toneEnd(tone, body)
}

function shooting(a: Assessment, tone: ToneKey, depth: Depth, weak: number[]): string {
  const voice = buildVoice(a.scene, seedOf(a.measure))
  const items = [
    tone === 'MENTOR'
      ? '다음 장에서는 이렇게 잡아 보시면 좋겠습니다.'
      : tone === 'CRITIC'
        ? '촬영 단계에서 지켜야 할 것은 이 두 가지입니다.'
        : '촬영 제안은 근거가 약한 두 항목을 보완하는 순서로 적었습니다.',
    shootAdvice(weak[0], a, tone),
    shootAdvice(weak[1], a, tone),
    ...(voice ? [voice.shoot] : []),
    shotAngle(a, tone),
    shotDistance(a, tone),
    shotExposure(a, tone),
  ]
  items.push('같은 장면에서 이 세 가지만 바꿔 몇 장을 겹쳐 찍으면, 어떤 선택이 이 사진에 맞는지 스스로 확인할 수 있습니다.')
  items.push('촬영은 한 번에 완성되지 않습니다. 지금의 구도를 기준점으로 두고, 한 요소씩만 바꿔 비교하는 편이 배우는 속도가 빠릅니다.')
  items.push('장면을 떠나기 전에 화면을 한 번 더 확인하십시오. 프레임 가장자리와 초점이 놓인 자리만 보아도 절반은 정리됩니다.')
  return join(items, depth.sentences)
}

function editing(a: Assessment, tone: ToneKey, depth: Depth, weak: number[]): string {
  const m = a.measure
  const voice = buildVoice(a.scene, seedOf(m))
  const items = [
    tone === 'MENTOR'
      ? '보정은 원본이 담고 있는 정보 안에서 마무리하시면 좋겠습니다.'
      : tone === 'CRITIC'
        ? '보정으로 없는 디테일을 만들 수는 없습니다. 원본에 남은 정보 안에서만 손대십시오.'
        : '보정은 화면의 밝기와 색 값을 바꾸는 작업이므로, 바꾼 뒤의 균형을 다시 확인하십시오.',
    editAdvice(weak[0], a, tone),
    editAdvice(weak[1], a, tone),
    ...(voice ? [voice.edit] : []),
  ]
  if (m.highlights > 0.02)
    items.push('밝은 쪽은 하이라이트 슬라이더를 낮추고, 톤 커브의 어깨를 부드럽게 만들어 흰색으로 눌린 결을 되살리는 편이 좋겠습니다.')
  if (m.shadows > 0.03) items.push('어두운 쪽은 섀도우를 살짝 들어 올리되, 검정 지점은 남겨 두어야 그림자가 뜨지 않습니다.')
  if (m.contrast < 20) items.push('검정 지점을 조금 내리고 중간 톤을 살짝 눌러 계조의 폭을 넓히면, 사진이 한 단계 깊어집니다.')
  if (m.channelCast > 8)
    items.push(
      m.warmth > 0
        ? '색온도를 차가운 쪽으로 조금 옮겨 피사체의 고유색을 되찾는 편이 좋습니다.'
        : '색온도를 따뜻한 쪽으로 조금 옮겨 장면의 온기를 살리는 편이 좋습니다.',
    )
  if (m.saturation > 0.42) items.push('채도를 한 단계 낮추고 주조색 하나를 남기면 색이 서로 다투지 않습니다.')
  if (m.sharpness < 60)
    items.push('노이즈가 눈에 띄면 색 노이즈 정리를 먼저 하고, 그다음 윤곽을 세우는 순서가 좋습니다. 순서를 바꾸면 거친 결이 함께 살아납니다.')
  items.push('보정은 화면 전체의 균형을 보며 조금씩 움직이고, 단계마다 원본과 비교해 보십시오. 한 번에 크게 움직이면 되돌릴 기준을 잃습니다.')
  items.push('마지막에는 크롭을 한 번 더 확인하십시오. 가장자리의 자잘한 요소를 조금 덜어내는 것만으로 화면이 정돈됩니다.')
  items.push('보정을 마친 뒤 하루 정도 지나 다시 보면, 손이 과했는지 스스로 판단하기 쉬워집니다.')
  return join(items, depth.sentences)
}

// ---------- 상세 측정 데이터 및 기술적 근거 ----------
export interface NimaMeta {
  source: NimaSource
  modelMs?: number
}

export function measurements(
  a: Assessment,
  score: ScoreResult | null,
  nima?: NimaMeta,
): MeasureGroup[] {
  const m = a.measure
  const main = a.subjects[0]
  const groups: MeasureGroup[] = []
  const modelScores = nima?.source === 'MODEL'
  const modelRows: [string, string][] = [
    [modelScores ? 'NIMA 미적 점수' : '미적 추정 점수', `${f2(a.nima.aesthetic)} / 10`],
    [modelScores ? 'NIMA 기술 점수' : '기술 추정 점수', `${f2(a.nima.technical)} / 10`],
    ['확률분포 표준편차', f2(a.nima.deviation)],
    ['분석 해상도', `${m.width}×${m.height}`],
  ]
  if (nima) {
    modelRows.push([
      '점수 출처',
      modelScores ? '브라우저 내 NIMA 추론 (MobileNet · LiteRT WASM)' : '캔버스 측정 기반 추정 (모델 미적용)',
    ])
    if (modelScores && nima.modelMs) modelRows.push(['모델 추론 시간', `${nima.modelMs}ms`])
  }
  groups.push({ title: '평가 모델', rows: modelRows })
  groups.push({
    title: '빛과 계조',
    rows: [
      ['휘도 평균', `${f1(m.luminance)} / 255`],
      ['휘도 중간값', `${m.percentile(0.5)}`],
      ['5 · 95 퍼센타일', `${m.percentile(0.05)} · ${m.percentile(0.95)}`],
      ['하이라이트 클리핑', p2(m.highlights)],
      ['암부 클리핑', p2(m.shadows)],
      ['전역 대비', f1(m.contrast)],
      ['국소 대비', f1(m.localContrast)],
    ],
  })
  groups.push({
    title: '색채',
    rows: [
      ['평균 RGB', `${f1(m.rgb[0] ?? 0)} / ${f1(m.rgb[1] ?? 0)} / ${f1(m.rgb[2] ?? 0)}`],
      ['채널 간 차이', f1(m.channelCast)],
      ['평균 채도', p1(m.saturation)],
      ['색상 구간', `${m.hueVariety} / 12`],
      ['주조색 비중', m.hueTotal <= 0 ? '—' : `${HUE_NAMES[m.dominantHue]} ${p0(m.dominantShare)}`],
    ],
  })
  groups.push({
    title: '선명도',
    rows: [
      ['라플라시안 분산', f1(m.sharpness)],
      ['에지 밀도', p2(m.edges)],
      ['국소 대비', f1(m.localContrast)],
    ],
  })
  const compositionRows: [string, string][] = [
    ['주요 영역', `${a.subjects.length}개`],
    ['수평선 후보', `${m.lines.length}개`],
    ['최대 기울기', `${f1(m.maxTilt)}°`],
  ]
  if (main) {
    compositionRows.push(['가장 큰 영역', `${koLabel(main.label)} · 신뢰도 ${p0(main.confidence)}`])
    compositionRows.push(['중심 위치', `${p0(main.cx)} · ${p0(main.cy)}`])
    compositionRows.push(['프레임 점유', p1(main.area)])
    compositionRows.push(['삼분할 거리', p0(nearestThirds(main.cx, main.cy))])
  }
  groups.push({ title: '구도 · 영역', rows: compositionRows })
  groups.push({
    title: '항목별 근거',
    rows: AXIS_ORDER.map((axis) => [
      AXIS_TITLES[axis],
      `근거 ${f2(a.evidence[axis].score)} · 신뢰도 ${p0(a.evidence[axis].confidence)}`,
    ]),
  })
  if (score) {
    groups.push({
      title: '점수 계산',
      rows: [
        ['관점 점수', f2(score.weighted)],
        ['보정 전', f2(score.base)],
        ['부스팅 후', f2(score.adjusted)],
        ['가중 신뢰도', p0(score.coverage)],
        ['산식', `${modelScores ? 'NIMA 미적' : '미적 추정'} 40% + 근거 60%, 1 + 9·x^γ`],
        ['γ (Generous·Balanced·Strict)', '0.40 · 0.55 · 0.85'],
      ],
    })
  }
  return groups
}

export function evidenceLines(a: Assessment): [string, string][] {
  return AXIS_ORDER.map((axis) => [AXIS_TITLES[axis], a.evidence[axis].observation])
}

export type { DepthKey, PersonaKey, LevelKey }
