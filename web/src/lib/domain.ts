/**
 * FrameCritic 측정 크리틱 엔진의 공용 정의.
 *
 * Kotlin 엔진(app/src/main/java/com/framecritic/app/Domain.kt)과 같은 값, 같은 순서를 쓴다.
 * 화면에 보이는 비평 문장은 이 정의들의 점수와 근거에서만 만들어진다.
 */

export enum Axis {
  COMPOSITION = 0,
  LIGHTING = 1,
  COLOR = 2,
  SHARPNESS = 3,
  EMOTION = 4,
  TIMING = 5,
}

export const AXIS_ORDER: Axis[] = [
  Axis.COMPOSITION,
  Axis.LIGHTING,
  Axis.COLOR,
  Axis.SHARPNESS,
  Axis.EMOTION,
  Axis.TIMING,
]

export const AXIS_TITLES: Record<number, string> = {
  [Axis.COMPOSITION]: '구도·기하',
  [Axis.LIGHTING]: '빛·노출',
  [Axis.COLOR]: '색채',
  [Axis.SHARPNESS]: '선명도·디테일',
  [Axis.EMOTION]: '감정·분위기',
  [Axis.TIMING]: '순간·타이밍',
}

export const AXIS_BASIS: Record<number, string> = {
  [Axis.COMPOSITION]: '주요 영역의 삼분할 위치·점유율, 수평선 후보 각도',
  [Axis.LIGHTING]: '휘도 분포, 하이라이트·암부 클리핑, 전역·국소 대비',
  [Axis.COLOR]: '채널 평균 균형, 색상 12구간 분포, 평균 채도',
  [Axis.SHARPNESS]: '라플라시안 분산, 에지 밀도, 국소 대비',
  [Axis.EMOTION]: '주요 영역의 존재·크기, 톤의 위치, 색온도 방향에서 추정',
  [Axis.TIMING]: '영역 배열과 포착의 또렷함에서 추정',
}

export type LevelKey = 'GENEROUS' | 'BALANCED' | 'STRICT'

export interface Level {
  key: LevelKey
  title: string
  exponent: number
}

/** 1 + 9·x^exponent. 지수가 1보다 작아 중간 구간만 위로 휜다. */
export const LEVELS: Level[] = [
  { key: 'GENEROUS', title: 'Generous', exponent: 0.4 },
  { key: 'BALANCED', title: 'Balanced', exponent: 0.55 },
  { key: 'STRICT', title: 'Strict', exponent: 0.85 },
]

export const LEVEL_BY_KEY: Record<LevelKey, Level> = {
  GENEROUS: LEVELS[0],
  BALANCED: LEVELS[1],
  STRICT: LEVELS[2],
}

export type ToneKey = 'MENTOR' | 'CRITIC' | 'ANALYST'

export const TONES: { key: ToneKey; title: string; instruction: string }[] = [
  { key: 'MENTOR', title: 'Mentor', instruction: '친절하고 건설적인 조언. 근거 없는 칭찬을 추가하지 말 것.' },
  { key: 'CRITIC', title: 'Critic', instruction: '직설적인 미학 비평. 인신공격 없이 사진의 표현을 논할 것.' },
  { key: 'ANALYST', title: 'Analyst', instruction: '관측값과 추론을 구분하고 객관적·기술적으로 설명할 것.' },
]

export type DepthKey = 'QUICK' | 'STANDARD' | 'EXPERT'

export interface Depth {
  key: DepthKey
  title: string
  sentences: number
}

/** 항목 하나에 담는 문장(묶음) 수. 기본값 STANDARD는 다섯에서 일곱 문장을 담는다. */
export const DEPTHS: Depth[] = [
  { key: 'QUICK', title: 'Quick', sentences: 4 },
  { key: 'STANDARD', title: 'Standard', sentences: 6 },
  { key: 'EXPERT', title: 'Expert', sentences: 9 },
]

export const DEPTH_BY_KEY: Record<DepthKey, Depth> = {
  QUICK: DEPTHS[0],
  STANDARD: DEPTHS[1],
  EXPERT: DEPTHS[2],
}

export type PersonaKey = 'BALANCED' | 'BRESSON' | 'ADAMS' | 'LEITER' | 'FAN_HO'

export interface Persona {
  key: PersonaKey
  title: string
  subtitle: string
  weights: [number, number, number, number, number, number]
  priority: string
}

/**
 * 점수에 쓰는 시선의 가중치. 순서는 Axis와 같다.
 * 웹은 화면에서 관점을 고르지 않고 BALANCED를 기본으로 쓴다.
 */
export const PERSONAS: Persona[] = [
  {
    key: 'BALANCED',
    title: '균형 잡힌 시선',
    subtitle: '구도 · 빛 · 색 · 디테일을 고르게',
    weights: [0.24, 0.24, 0.18, 0.14, 0.1, 0.1],
    priority: '구도와 빛, 색과 디테일을 어느 한쪽으로 치우치지 않게 함께 검토',
  },
  {
    key: 'BRESSON',
    title: 'Henri Cartier-Bresson',
    subtitle: '순간 · 기하 · 관계',
    weights: [0.3, 0.1, 0.05, 0.1, 0.15, 0.3],
    priority: '순간의 제스처, 인물 간 관계와 기하학적 프레이밍부터 검토',
  },
  {
    key: 'ADAMS',
    title: 'Ansel Adams',
    subtitle: '계조 · 빛 · 형태',
    weights: [0.2, 0.35, 0.1, 0.2, 0.1, 0.05],
    priority: '계조 분리와 빛이 드러내는 형태, 미세한 디테일부터 검토',
  },
  {
    key: 'LEITER',
    title: 'Saul Leiter',
    subtitle: '색채 · 중첩 · 시적 분위기',
    weights: [0.15, 0.15, 0.3, 0.05, 0.25, 0.1],
    priority: '색채의 관계, 반사와 중첩, 시적 분위기를 우선',
  },
  {
    key: 'FAN_HO',
    title: 'Fan Ho',
    subtitle: '명암 · 실루엣 · 공간',
    weights: [0.25, 0.3, 0.05, 0.1, 0.15, 0.15],
    priority: '빛과 그림자의 기하학, 실루엣, 공간 깊이부터 검토',
  },
]

export const PERSONA_BY_KEY: Record<PersonaKey, Persona> = PERSONAS.reduce(
  (all, persona) => ({ ...all, [persona.key]: persona }),
  {} as Record<PersonaKey, Persona>,
)

/**
 * 사진의 주인공이 무엇인지. 비평은 이 갈래에 맞는 어휘를 골라 쓴다.
 * Kotlin의 Domain.kt SubjectCategory와 같은 값, 같은 순서다.
 */
export enum SubjectCategory {
  GENERAL = 0,
  PERSON = 1,
  ANIMAL = 2,
  VEHICLE = 3,
  LANDSCAPE_NATURE = 4,
  LANDSCAPE_WATER = 5,
  URBAN_ARCHITECTURE = 6,
  OBJECT_STILL = 7,
}

export const SUBJECT_TITLES: Record<number, string> = {
  [SubjectCategory.GENERAL]: '장면',
  [SubjectCategory.PERSON]: '인물',
  [SubjectCategory.ANIMAL]: '동물',
  [SubjectCategory.VEHICLE]: '탈것',
  [SubjectCategory.LANDSCAPE_NATURE]: '자연 풍경',
  [SubjectCategory.LANDSCAPE_WATER]: '물가 풍경',
  [SubjectCategory.URBAN_ARCHITECTURE]: '도시·건축',
  [SubjectCategory.OBJECT_STILL]: '사물',
}

/** 사진이 놓인 자리. 피사체 어휘와 빛·공간 문장의 결을 고르는 데 쓴다. */
export enum SceneContext {
  UNKNOWN = 0,
  INDOOR = 1,
  OUTDOOR = 2,
  URBAN = 3,
  NATURE = 4,
  WATER = 5,
  SKY = 6,
  NIGHT = 7,
}

export const SCENE_TITLES: Record<number, string> = {
  [SceneContext.UNKNOWN]: '장면',
  [SceneContext.INDOOR]: '실내',
  [SceneContext.OUTDOOR]: '야외',
  [SceneContext.URBAN]: '도심',
  [SceneContext.NATURE]: '자연',
  [SceneContext.WATER]: '물가',
  [SceneContext.SKY]: '하늘',
  [SceneContext.NIGHT]: '밤',
}

/** 피사체·장면 식별 결과. 무엇을 근거로 그렇게 읽었는지도 함께 남긴다. */
export interface SubjectScene {
  category: SubjectCategory
  /** 화면에 그대로 쓸 이름. 예: 반려견, 그릇, 바다, 도심 */
  subject: string
  /** 같은 갈래로 잡힌 피사체 수. 0이면 검출된 사물 없이 장면만 읽은 경우다. */
  count: number
  /** 피사체 신뢰도(0~1). 장면만 읽었으면 0이다. */
  confidence: number
  scene: SceneContext
  sceneLabel: string
  /** 피사체와 배경이 밝기·색으로 얼마나 나뉘는지(0~1). */
  separation: number
  source: 'MODEL' | 'SCENE'
  /** 화면 아래 근거 블록에 그대로 쓰는 한 줄. */
  evidence: string
}

export interface Evidence {
  score: number
  confidence: number
  observation: string
  interpretation: string
}

export interface NimaResult {
  aesthetic: number
  technical: number
  deviation: number
  probabilities: number[]
}

/** 미적·기술 점수를 어디서 얻었는지. MODEL은 브라우저에서 돌린 NIMA 추론, ESTIMATE는 캔버스 측정 추정이다. */
export type NimaSource = 'MODEL' | 'ESTIMATE'

export interface ScoreResult {
  rawNima: number
  weighted: number
  base: number
  adjusted: number
  coverage: number
  contributions: number[]
}

export function distribution(values: number[]): [number, number] {
  const sum = values.reduce((acc, v) => acc + v, 0)
  const total = sum > 0 ? sum : 1
  let mean = 0
  for (let i = 0; i < 10; i++) mean += ((i + 1) * (values[i] ?? 0)) / total
  let variance = 0
  for (let i = 0; i < 10; i++) variance += ((i + 1 - mean) ** 2 * (values[i] ?? 0)) / total
  return [mean, Math.sqrt(variance)]
}

export function curve(x: number, level: Level): number {
  const clamped = Math.min(1, Math.max(0, Number.isFinite(x) ? x : 0))
  return Math.pow(clamped, level.exponent)
}

/**
 * 측정 근거와 NIMA 원점수를 하나의 점수로 합친다.
 * 근거의 신뢰도가 0.35 아래면 점수를 만들지 않고 null을 돌려준다.
 */
export function evaluate(
  nima: number,
  evidence: Evidence[],
  persona: Persona,
  level: Level,
): ScoreResult | null {
  if (evidence.length !== AXIS_ORDER.length) return null
  const weights = persona.weights
  let coverage = 0
  for (let i = 0; i < weights.length; i++) coverage += weights[i] * evidence[i].confidence
  if (!Number.isFinite(coverage) || coverage < 0.35) return null
  const contributions = weights.map(
    (w, i) => (w * evidence[i].confidence * evidence[i].score) / coverage,
  )
  const weighted = contributions.reduce((acc, v) => acc + v, 0)
  const base = 0.4 * ((nima - 1) / 9) + 0.6 * weighted
  const boosted = curve(base, level)
  return {
    rawNima: nima,
    weighted: 1 + 9 * weighted,
    base: 1 + 9 * base,
    adjusted: 1 + 9 * boosted,
    coverage,
    contributions,
  }
}
