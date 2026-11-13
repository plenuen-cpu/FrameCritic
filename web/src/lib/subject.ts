/**
 * 사진의 주인공과 장면을 읽고, 그 갈래에 맞는 평론 어휘를 고른다.
 *
 * 두 가지 근거만 쓴다.
 *  - 브라우저에서 직접 돌린 객체 탐지기(EfficientDet-Lite0)가 찾은 상자와 이름, 신뢰도
 *  - 캔버스 픽셀에서 읽은 띠별 색·밝기·질감(하늘, 물, 초록, 도시의 선)
 *
 * 여기서 만든 SubjectScene은 CriticEngine이 열 개 항목의 문장을 고를 때 쓰고,
 * 화면 아래 근거 블록에는 evidence 한 줄이 그대로 남는다.
 *
 * Kotlin의 Domain.kt / CriticEngine.kt와 같은 값, 같은 문장을 쓴다.
 */

import {
  SCENE_TITLES,
  SUBJECT_TITLES,
  SceneContext,
  SubjectCategory,
  type SubjectScene,
} from './domain'

/** COCO 80범주의 한국어 이름. 상세 측정 블록의 라벨에도 같은 표를 쓴다. */
export const KO_LABELS: Record<string, string> = {
  person: '인물',
  bicycle: '자전거',
  car: '자동차',
  motorcycle: '오토바이',
  airplane: '비행기',
  bus: '버스',
  train: '기차',
  truck: '트럭',
  boat: '배',
  'traffic light': '신호등',
  'fire hydrant': '소화전',
  'stop sign': '표지판',
  'parking meter': '주차 계량기',
  bench: '벤치',
  bird: '새',
  cat: '고양이',
  dog: '개',
  horse: '말',
  sheep: '양',
  cow: '소',
  elephant: '코끼리',
  bear: '곰',
  zebra: '얼룩말',
  giraffe: '기린',
  backpack: '배낭',
  umbrella: '우산',
  handbag: '가방',
  tie: '넥타이',
  suitcase: '여행 가방',
  frisbee: '원반',
  skis: '스키',
  snowboard: '스노보드',
  'sports ball': '공',
  kite: '연',
  'baseball bat': '야구 방망이',
  'baseball glove': '야구 글러브',
  skateboard: '스케이트보드',
  surfboard: '서핑보드',
  'tennis racket': '테니스 라켓',
  bottle: '병',
  'wine glass': '와인잔',
  cup: '컵',
  fork: '포크',
  knife: '칼',
  spoon: '숟가락',
  bowl: '그릇',
  banana: '바나나',
  apple: '사과',
  sandwich: '샌드위치',
  orange: '오렌지',
  broccoli: '브로콜리',
  carrot: '당근',
  'hot dog': '핫도그',
  pizza: '피자',
  donut: '도넛',
  cake: '케이크',
  chair: '의자',
  couch: '소파',
  'potted plant': '화분',
  bed: '침대',
  'dining table': '식탁',
  toilet: '변기',
  tv: '모니터',
  laptop: '노트북',
  mouse: '마우스',
  remote: '리모컨',
  keyboard: '키보드',
  'cell phone': '휴대폰',
  microwave: '전자레인지',
  oven: '오븐',
  toaster: '토스터',
  sink: '싱크대',
  refrigerator: '냉장고',
  book: '책',
  clock: '시계',
  vase: '꽃병',
  scissors: '가위',
  'teddy bear': '곰인형',
  'hair drier': '헤어드라이어',
  toothbrush: '칫솔',
  /** 객체 탐지기를 쓸 수 없을 때 쓰는 대체 영역 이름. */
  salience: '주요 영역',
}

/** 사람으로 읽는 이름. */
const PERSON_LABELS = new Set(['person'])

/** 일상에서 흔히 만나는 반려동물. 이름을 그대로 인정하되 최소 신뢰도를 둔다. */
const COMPANION_LABELS = new Set(['dog', 'cat', 'bird'])

/**
 * 소·말·곰처럼 사람 사진에서 오탐이 잦은 가축·야생동물.
 * 실내나 도심에서는 0.85 이상일 때만 이름을 믿고, 그 밖에서는 0.60 이상을 요구한다.
 */
const LIVESTOCK_LABELS = new Set(['cow', 'sheep', 'horse', 'bear', 'elephant', 'zebra', 'giraffe'])

/** 동물로 읽는 이름과, 화면에 쓸 말. */
const ANIMAL_LABELS: Record<string, string> = {
  dog: '반려견',
  cat: '고양이',
  bird: '새',
  horse: '말',
  sheep: '양',
  cow: '소',
  elephant: '코끼리',
  bear: '곰',
  zebra: '얼룩말',
  giraffe: '기린',
}

/** 탈것으로 읽는 이름과, 화면에 쓸 말. */
const VEHICLE_LABELS: Record<string, string> = {
  car: '자동차',
  bicycle: '자전거',
  motorcycle: '오토바이',
  bus: '버스',
  truck: '트럭',
  train: '기차',
  airplane: '비행기',
  boat: '배',
}

/** 사물로 읽는 이름 가운데 화면에 그대로 쓰기 좋은 것들. 나머지는 '사물'로 묶는다. */
const OBJECT_LABELS = new Set([
  'bowl',
  'cup',
  'wine glass',
  'bottle',
  'book',

  'laptop',
  'cell phone',
  'vase',
  'clock',
  'chair',
  'couch',
  'potted plant',
  'teddy bear',
  'cake',
  'pizza',
  'apple',
  'umbrella',
  'backpack',
  'handbag',
  'camera',
])

/** 한 피사체를 이름 붙일 때 쓰는 말. 갈래와 상자 크기 비교에 쓴다. */
interface Reading {
  label: string
  category: SubjectCategory
  name: string
  score: number
  area: number
}

function readingOf(label: string, score: number, area: number): Reading | null {
  const key = label.toLowerCase()
  if (PERSON_LABELS.has(key))
    return { label: key, category: SubjectCategory.PERSON, name: '인물', score, area }
  if (ANIMAL_LABELS[key])
    return { label: key, category: SubjectCategory.ANIMAL, name: ANIMAL_LABELS[key], score, area }
  if (VEHICLE_LABELS[key])
    return { label: key, category: SubjectCategory.VEHICLE, name: VEHICLE_LABELS[key], score, area }
  if (OBJECT_LABELS.has(key))
    return {
      label: key,
      category: SubjectCategory.OBJECT_STILL,
      name: KO_LABELS[key] ?? '사물',
      score,
      area,
    }
  return null
}

/** 이름 하나가 어느 갈래인지만 돌려준다. 화면에 남길 상자를 고를 때 쓴다. */
export function categoryOf(label: string): SubjectCategory | null {
  return readingOf(label, 1, 1)?.category ?? null
}

/** 띠 하나의 색·밝기·질감. 장면을 가르는 근거다. */
export interface BandStats {
  r: number
  g: number
  b: number
  luminance: number
  texture: number
  /** 띠 안에서 초록으로 읽히는 픽셀 비율 */
  green: number
  /** 띠 안에서 푸른 기가 도는 픽셀 비율 */
  blue: number
  /** 띠 안에서 아주 밝은 픽셀 비율 */
  bright: number
}

export interface SceneCues {
  top: BandStats
  middle: BandStats
  bottom: BandStats
  hasHorizon: boolean
}

export interface RawDetection {
  label: string
  score: number
  cx: number
  cy: number
  w: number
  h: number
}

function band(): BandStats {
  return { r: 0, g: 0, b: 0, luminance: 0, texture: 0, green: 0, blue: 0, bright: 0 }
}

export function emptyCues(): SceneCues {
  return { top: band(), middle: band(), bottom: band(), hasHorizon: false }
}

/**
 * 띠별 근거로 장면을 고른다. 확신이 서지 않으면 '장면'으로 남기고 단정하지 않는다.
 * 촉박하게 초록 하나, 푸른 기 하나로 바다·숲을 말하지 않도록 비율 문턱을 높게 잡았다.
 */
export function readScene(
  cues: SceneCues | null,
  saturation: number,
  luminance: number,
  lineCount: number,
): { scene: SceneContext; label: string } {
  if (!cues) return { scene: SceneContext.UNKNOWN, label: SCENE_TITLES[SceneContext.UNKNOWN] }
  const { top, middle, bottom } = cues
  const greenShare = Math.max(middle.green, bottom.green, top.green * 0.8)
  const skyTop = top.bright >= 0.18 || (top.blue >= 0.4 && top.luminance > 90)
  const texturedGreen = middle.texture > 32 && greenShare >= 0.3
  const night = luminance < 48 && cues.top.bright < 0.1
  /**
   * 물가의 두 얼굴. 하늘이 비치며 아래가 푸른 경우와, 해 질 녁에 위만 밝고 아래가 어두운 경우다.
   * 바닥이 거친 화면(바위·풀)은 물로 보지 않는다.
   */
  const blueWater = bottom.blue >= 0.55 && top.bright >= 0.3 && middle.texture < 45
  const sunsetWater =
    top.r > top.b + 30 &&
    top.luminance - bottom.luminance >= 45 &&
    bottom.texture <= 45 &&
    middle.texture < 45 &&
    greenShare < 0.2

  if (night) return { scene: SceneContext.NIGHT, label: '밤' }
  if (blueWater || sunsetWater)
    return { scene: SceneContext.WATER, label: middle.texture <= 30 ? '호수' : '물가' }
  if (greenShare >= 0.34)
    return { scene: SceneContext.NATURE, label: texturedGreen ? '숲' : '들판' }
  if (skyTop && middle.texture >= 26 && middle.luminance < top.luminance - 15)
    return { scene: SceneContext.NATURE, label: '산' }
  if (greenShare >= 0.15) return { scene: SceneContext.OUTDOOR, label: '야외' }
  if (skyTop && lineCount <= 1) return { scene: SceneContext.SKY, label: '하늘' }
  if (saturation < 0.15 && lineCount >= 2) return { scene: SceneContext.URBAN, label: '도심' }
  if (skyTop) return { scene: SceneContext.OUTDOOR, label: '야외' }
  return { scene: SceneContext.INDOOR, label: '실내' }
}

/** 인물·반려동물·가축이 이름을 얻는 최소 신뢰도. 탐지기가 문턱을 0.30까지 열어 두었으므로 여기서 한 번 더 거른다. */
const PERSON_FLOOR = 0.3
const COMPANION_FLOOR = 0.4
const LIVESTOCK_OUTDOOR_FLOOR = 0.6
const LIVESTOCK_INDOOR_FLOOR = 0.85
/** 사진 한 장에서 동시에 고려하는 탐지의 최소 신뢰도와 면적. */
const DETECTION_FLOOR = 0.3
const DETECTION_AREA_FLOOR = 0.008

/**
 * 같은 이름이 몇 개인지 셀 때, 중심이 이만큼 가까우면 한 대상으로 본다.
 * 나란히 선 두 사람은 중심이 화면 폭의 15% 이상 떨어지지만, 한 사람의 얼굴·상체·전신 상자는
 * 중심이 거의 같은 자리에 몰린다. 상자끼리 겹치는지만 보면 나란히 선 두 사람이 하나로 합쳐진다.
 */
const SAME_TARGET_X = 0.15
const SAME_TARGET_Y = 0.22

/** 실내·도심·하늘처럼 가축이 있을 리 없는 장면. */
function domesticScene(scene: SceneContext): boolean {
  return scene === SceneContext.INDOOR || scene === SceneContext.URBAN || scene === SceneContext.SKY
}

/** 이 장면에서 그 이름을 그대로 믿어도 되는지. 소·말 오탐을 걸러내는 자리다. */
function believable(label: string, score: number, scene: SceneContext): boolean {
  if (COMPANION_LABELS.has(label)) return score >= COMPANION_FLOOR
  if (LIVESTOCK_LABELS.has(label))
    return score >= (domesticScene(scene) ? LIVESTOCK_INDOOR_FLOOR : LIVESTOCK_OUTDOOR_FLOOR)
  return true
}

/** 주인공을 고르는 무게중심. 면적만 키우면 큰 오탐이 이기고, 신뢰도만 보면 작은 디테일이 이긴다. */
function weightOf(reading: Reading): number {
  return Math.pow(reading.score, 1.5) * Math.sqrt(Math.max(0, reading.area))
}

/** 한 사진의 주인공 영역으로 받아들일 만한가. */
function acceptable(best: Reading): boolean {
  if (best.category === SubjectCategory.PERSON) return best.score >= PERSON_FLOOR && best.area >= 0.01
  return best.score >= 0.36 && best.area >= 0.015
}

/**
 * 같은 이름의 탐지가 몇 개인지 센다. 상자가 아니라 중심을 모아 세므로,
 * 한 사람을 여러 상자로 잡은 것과 나란히 선 두 사람을 구분한다.
 */
function countOf(detections: RawDetection[], label: string, bestScore: number): number {
  const floor = Math.max(0.42, bestScore * 0.6)
  const centers: { x: number; y: number }[] = []
  const list = detections
    .filter((d) => d.label.toLowerCase() === label)
    .filter((d) => d.score >= floor && d.w > 0 && d.h > 0 && d.w * d.h >= 0.01)
    .sort((a, b) => b.score - a.score)
  for (const d of list) {
    const same = centers.some((c) => Math.abs(c.x - d.cx) < SAME_TARGET_X && Math.abs(c.y - d.cy) < SAME_TARGET_Y)
    if (!same) centers.push({ x: d.cx, y: d.cy })
  }
  return Math.min(3, centers.length)
}

/**
 * 탐지 결과와 장면 근거를 합쳐 피사체 하나를 정한다.
 * 탐지기가 잡은 것이 충분히 크면 그것이 주인공이고, 아니면 장면 자체가 주인공이다.
 */
export function readSubject(
  detections: RawDetection[],
  cues: SceneCues | null,
  saturation: number,
  luminance: number,
  lineCount: number,
  separation: number,
  source: 'MODEL' | 'SCENE',
): SubjectScene {
  const { scene, label } = readScene(cues, saturation, luminance, lineCount)
  const readings = detections
    .map((d) => readingOf(d.label, d.score, Math.max(0, d.w) * Math.max(0, d.h)))
    .filter((r): r is Reading => r !== null)
    .filter((r) => r.score >= DETECTION_FLOOR && r.area >= DETECTION_AREA_FLOOR)
    .filter((r) => believable(r.label, r.score, scene))
    .sort((a, b) => weightOf(b) - weightOf(a))

  // 인물이 잡혀 있으면, 소·말 같은 가축의 큰 상자가 주인공을 가져가지 못하게 한다.
  const person = readings.find((r) => r.category === SubjectCategory.PERSON && r.score >= PERSON_FLOOR)
  let best = readings[0]
  if (best && person && best.category === SubjectCategory.ANIMAL && LIVESTOCK_LABELS.has(best.label)) {
    const overwhelming = best.score >= 0.85 && best.score - person.score >= 0.15
    if (!overwhelming) best = person
  }

  const outdoorScene =
    scene === SceneContext.WATER ||
    scene === SceneContext.NATURE ||
    scene === SceneContext.SKY ||
    scene === SceneContext.URBAN
  // 야외 풍경으로 읽히는 화면에서 실내 사물 이름이 잡히면, 그 이름보다 장면을 믿는다.
  const objectMisfire = best?.category === SubjectCategory.OBJECT_STILL && outdoorScene
  if (source === 'MODEL' && best && !objectMisfire && acceptable(best)) {
    const count = countOf(detections, best.label, best.score)
    const sceneNote = sceneNoteFor(scene, label)
    const evidence =
      `${detectionEvidence(best, count)} · 장면 ${label}${sceneNote}` +
      (separation >= 0.35 ? ' · 피사체와 배경이 밝기로 나뉨' : '')
    return {
      category: best.category,
      subject: best.name,
      count,
      confidence: best.score,
      scene,
      sceneLabel: label,
      separation,
      source,
      evidence,
    }
  }

  const landscape =
    scene === SceneContext.WATER
      ? SubjectCategory.LANDSCAPE_WATER
      : scene === SceneContext.NATURE
        ? SubjectCategory.LANDSCAPE_NATURE
        : scene === SceneContext.URBAN
          ? SubjectCategory.URBAN_ARCHITECTURE
          : SubjectCategory.GENERAL
  const subject =
    landscape === SubjectCategory.LANDSCAPE_WATER
      ? label
      : landscape === SubjectCategory.LANDSCAPE_NATURE
        ? label
        : landscape === SubjectCategory.URBAN_ARCHITECTURE
          ? '도시의 선'
          : '장면'
  return {
    category: landscape,
    subject,
    count: 0,
    confidence: 0,
    scene,
    sceneLabel: label,
    separation,
    source: 'SCENE',
    evidence: `${SUBJECT_TITLES[landscape]} · 장면 ${label} (픽셀 근거로만 읽음)`,
  }
}

function sceneNoteFor(scene: SceneContext, label: string): string {
  switch (scene) {
    case SceneContext.WATER:
      return ' · 수평선과 물빛'
    case SceneContext.NATURE:
      return ' · 초록과 능선'
    case SceneContext.URBAN:
      return ' · 무채색과 직선'
    case SceneContext.INDOOR:
      return ' · 하늘 없이 닫힌 공간'
    case SceneContext.NIGHT:
      return ' · 낮은 빛'
    case SceneContext.SKY:
      return ' · 열린 하늘'
    default:
      return label ? '' : ''
  }
}

function detectionEvidence(best: Reading, count: number): string {
  const percent = Math.round(best.score * 100)
  if (best.category === SubjectCategory.ANIMAL) {
    return `${best.name} ${count >= 2 ? '여러 마리' : '한 마리'} (신뢰도 ${percent}%)`
  }
  if (best.category === SubjectCategory.PERSON) {
    const many = count >= 3 ? '여러 명' : count === 2 ? '2명' : '한 명'
    return `인물 ${many} (신뢰도 ${percent}%)`
  }
  if (best.category === SubjectCategory.VEHICLE) {
    return `${best.name} (신뢰도 ${percent}%)`
  }
  return `${best.name} (신뢰도 ${percent}%)`
}

// ---------- 화면에 쓸 어휘 ----------
/** 갈래별 문장 묶음. 모든 문장은 수치도 계측 어휘도 쓰지 않는다. */
export interface SubjectVoice {
  /** 종합 크리틱 첫 문단에 붙는 한 문장. */
  opening: string
  light: string
  color: string
  composition: string
  texture: string
  mood: string
  moment: string
  praise: string
  refine: string
  shoot: string
  edit: string
}

function hasFinalConsonant(word: string): boolean {
  if (!word) return false
  const code = word.charCodeAt(word.length - 1)
  if (code < 0xac00 || code > 0xd7a3) return false
  return (code - 0xac00) % 28 !== 0
}
function josa(word: string, withFinal: string, withoutFinal: string): string {
  return word + (hasFinalConsonant(word) ? withFinal : withoutFinal)
}

/** 같은 사진이면 같은 문장이 나오도록 시드로만 변형을 고른다. */
function choose(seed: number, salt: number, items: string[]): string {
  if (items.length === 0) return ''
  const index = ((seed + salt * 37) % items.length + items.length) % items.length
  return items[index]
}

function animalVoice(name: string, count: number, seed: number): SubjectVoice {
  const many = count >= 2 ? `${name} 여러 마리` : name
  return {
    opening:
      count >= 2
        ? `${josa(many, '이', '가')} 나란히 자리를 잡고, 사진의 관심은 그 눈과 표정에 모여 있습니다.`
        : `${josa(name, '이', '가')} 화면의 주인공이고, 사진의 관심은 그 눈과 표정에 모여 있습니다.`,
    light: choose(seed, 401, [
      `${josa(name, '의', '의')} 털 표면을 스치는 빛이 결을 따라 흐르고, 눈에 작은 하이라이트가 맺혀 있습니다.`,
      `빛이 ${name}의 등 쪽에서 들어와 털의 가장자리를 밝히고, 얼굴은 그 빛을 부드럽게 받아 안습니다.`,
    ]),
    color: choose(seed, 402, [
      `털의 색과 배경의 색이 서로를 밀어내지 않아, ${josa(name, '이', '가')} 장면 안에 자연스럽게 놓여 있습니다.`,
      `${name}의 따뜻한 색과 배경의 차분한 색이 만나, 사진의 온도가 한쪽으로 기울지 않습니다.`,
    ]),
    composition: choose(seed, 403, [
      count >= 2
        ? `두 마리가 화면을 나누어 앉아 서로의 무게를 받쳐 주고, 그 사이의 간격이 사진의 리듬이 됩니다.`
        : `낮은 눈높이에서 담아 ${josa(name, '의', '의')} 시선과 카메라가 마주치고, 그 높이가 사진의 친밀감을 만듭니다.`,
      `${josa(name, '을', '를')} 화면의 한쪽에 두고 반대쪽에 여백을 남겨, 사진이 다음 움직임을 기다리는 자리를 만듭니다.`,
    ]),
    texture: choose(seed, 404, [
      `${name}의 털 한 올과 젖은 코의 반사까지 남아 있어, 손을 뻗으면 닿을 듯 가깝습니다.`,
      `털의 결이 가까운 곳에서 또렷하고 목 뒤쪽으로 갈수록 부드러워져, 사진에 촉감이 생깁니다.`,
    ]),
    mood: choose(seed, 405, [
      `${josa(name, '이', '가')} 무엇을 보는지 알 수 없어, 사진이 그 시선 너머의 공간을 상상하게 합니다.`,
      `${josa(name, '의', '의')} 표정이 편안해서, 사진을 보는 사람의 마음까지 조금 느슨해집니다.`,
    ]),
    moment: choose(seed, 406, [
      `귀를 세우거나 고개를 갸웃하는 순간의 각도가 남아 있어, 정지한 화면에 한 박자의 움직임이 깃듭니다.`,
      `숨을 들이쉬기 직전처럼 몸이 멈춰 있어, 사진 속 시간이 아주 짧게 붙잡혀 있습니다.`,
    ]),
    praise: choose(seed, 407, [
      `${josa(name, '의', '의')} 생기가 사진의 온도를 데우고, 보는 사람의 표정까지 풀어 놓습니다.`,
      `${josa(name, '을', '를')} 향한 시선이 다정해서, 사진이 대상을 함부로 다루지 않습니다.`,
    ]),
    refine: choose(seed, 408, [
      `${josa(name, '의', '의')} 시선 앞쪽에 여백을 조금 더 남기면, 다음에 무엇이 올지 상상할 자리가 생깁니다.`,
      `배경 가운데 사람을 부르는 요소를 조금 덜어 내면 ${josa(name, '의', '의')} 표정이 더 또렷해집니다.`,
    ]),
    shoot: choose(seed, 409, [
      `${josa(name, '의', '의')} 눈높이까지 몸을 낮추고, 시선이 머무는 곳을 배경에 함께 담아 보십시오.`,
      `한 걸음 더 가까이에서 ${josa(name, '의', '의')} 눈에 초점을 두고, 배경은 조금 흐리게 두어 보십시오.`,
    ]),
    edit: choose(seed, 410, [
      `털의 밝은 부분을 조금만 낮추고 눈의 하이라이트를 살리면 표정이 또렷해집니다.`,
      `눈 주변의 어두운 부분을 살짝 들어 올리고 배경의 채도를 낮추면, 시선이 ${name}에게 더 붙습니다.`,
    ]),
  }
}

function personVoice(count: number, seed: number): SubjectVoice {
  const two = count === 2
  const many = count >= 2
  return {
    opening: two
      ? '화면에는 두 사람이 나란히 자리해 있고, 사진의 관심은 두 사람 사이의 거리와 시선에 놓여 있습니다.'
      : many
        ? '화면에는 사람이 둘 이상 있고, 사진의 관심은 그들 사이의 거리와 시선에 놓여 있습니다.'
        : '화면의 주인공은 한 사람이고, 사진의 관심은 그 얼굴과 시선에 모여 있습니다.',
    light: choose(seed, 301, [
      '인물의 이마와 뺨에 얹힌 빛이 부드럽게 흐르고, 턱 아래로 지는 그림자가 얼굴의 입체를 만듭니다.',
      '빛이 인물의 한쪽 어깨를 밝히고 반대쪽을 어둡게 남겨, 사람의 윤곽이 배경에서 천천히 떠오릅니다.',
    ]),
    color: choose(seed, 302, [
      '인물의 살색 온도가 장면의 색온도와 어긋나지 않아, 사람이 배경에서 떠 보이지 않고 같은 공기에 놓여 있습니다.',
      '인물의 얼굴색과 옷의 색이 서로를 받쳐 주어, 시선이 사람에게서 다른 곳으로 새지 않습니다.',
    ]),
    composition: choose(seed, 303, [
      two
        ? '두 사람이 화면을 나누어 서고, 그 사이의 거리가 사진의 리듬이 됩니다.'
        : '인물이 화면의 한쪽에 서고 시선이 비어 있는 쪽을 향해, 그 여백이 인물의 생각처럼 남습니다.',
      '인물의 어깨선과 배경의 선이 나란히 놓여, 사진의 뼈대가 사람을 중심으로 서 있습니다.',
    ]),
    texture: choose(seed, 304, [
      '인물의 눈동자 윤기와 머리카락 결이 살아 있어, 얼굴을 가까이 들여다보게 됩니다.',
      '인물의 피부 결이 과하게 매끈해지지 않아, 사진이 사람을 지우지 않고 그대로 남깁니다.',
    ]),
    mood: choose(seed, 305, [
      '인물의 표정이 지금 막 무언가를 말하려는 순간에 멈춰 있어, 사진을 덮은 뒤에도 그 사람이 남습니다.',
      '인물의 눈빛이 카메라를 통과해 바깥을 보고 있어, 사진이 초상이면서 동시에 대화처럼 읽힙니다.',
    ]),
    moment: choose(seed, 306, [
      '인물이 고개를 돌리기 직전의 각도가 남아 있어, 정지한 화면에 한 박자의 움직임이 깃듭니다.',
      '인물의 웃음이 터지기 직전의 얼굴이어서, 사진 속 시간이 아주 짧게 붙잡혀 있습니다.',
    ]),
    praise: choose(seed, 307, [
      '인물을 향한 시선이 다정해서, 사진이 사람을 관찰 대상이 아니라 함께 있는 사람으로 대합니다.',
      '인물의 얼굴 빛을 서두르지 않고 담아, 사진이 사람의 인상을 왜곡하지 않습니다.',
    ]),
    refine: choose(seed, 308, [
      '인물 뒤의 배경이 조금 더 단순해지면 얼굴에 닿는 시선이 흐트러지지 않습니다.',
      '인물의 어깨 위로 지나가는 선을 조금만 비켜 두면, 사진이 사람에게 더 집중합니다.',
    ]),
    shoot: choose(seed, 309, [
      '인물의 눈높이로 한 걸음 다가가면 표정의 결이 더 가까워집니다.',
      '인물이 빛을 받는 쪽으로 얼굴을 조금 돌리고, 배경은 어둡게 두어 보십시오.',
    ]),
    edit: choose(seed, 310, [
      '인물의 얼굴에 닿은 하이라이트를 조금 눌러 주고 피부의 붉은 기를 살짝 낮추면 인상이 차분해집니다.',
      '인물의 눈과 입술 주변 대비를 살짝 올리고 배경의 채도를 낮추면 시선이 얼굴에 머뭅니다.',
    ]),
  }
}

function vehicleVoice(name: string, seed: number): SubjectVoice {
  const eul = josa(name, '을', '를')
  const ui = josa(name, '의', '의')
  return {
    opening: `화면의 주인공은 ${name} 한 대이고, 사진은 그 표면을 따라 흐르는 빛을 보여 줍니다.`,
    light: choose(seed, 501, [
      `${ui} 곡면을 타고 흐르는 하이라이트가 형태의 볼륨을 만들고, 어두운 아래쪽이 무게를 받칩니다.`,
      `빛이 ${ui} 모서리에 걸려 선명한 선을 그리고, 그 선을 따라 사진의 형태가 서 있습니다.`,
    ]),
    color: choose(seed, 502, [
      `${ui} 색이 배경의 색과 만나며 표면의 반사에 장면이 통째로 비쳐, 사진이 두 겹으로 읽힙니다.`,
      `${name}의 금속 색이 배경에서 튀지 않아, 사진이 대상의 성격을 과장하지 않습니다.`,
    ]),
    composition: choose(seed, 503, [
      `${eul} 화면의 앞쪽에 두어, 정지한 차가 다음에 나아갈 방향을 화면이 미리 열어 둡니다.`,
      `${eul} 화면의 대각선에 맞추어, 정지한 형태에 속도의 방향을 남겼습니다.`,
    ]),
    texture: choose(seed, 504, [
      `${ui} 표면은 매끈하게 정리되어 있고, 유리와 타이어의 재질 차이가 분명히 구분됩니다.`,
      `표면의 반사가 지나치게 뭉개지지 않아, ${name}의 재질이 그대로 남아 있습니다.`,
    ]),
    mood: choose(seed, 505, [
      `정지해 있는 ${name}에게서도 속도의 기억이 남아, 사진이 조용한 긴장을 유지합니다.`,
      `아무도 없는 장면인데도 ${name} 곁에는 사람의 손길이 느껴집니다.`,
    ]),
    moment: choose(seed, 506, [
      `바퀴와 지면이 만나는 지점이 또렷해, ${name}의 무게가 실제로 땅에 닿아 있다는 느낌을 줍니다.`,
      `빛이 ${eul} 미끄러지는 각도가 지금의 시간을 붙잡고 있습니다.`,
    ]),
    praise: choose(seed, 507, [
      `${ui} 비례가 반듯하게 담겨, 사진이 대상의 성격을 왜곡하지 않습니다.`,
      `${eul} 향한 시선이 차분해서, 사진이 기계를 과장하지 않고 그대로 보여 줍니다.`,
    ]),
    refine: choose(seed, 508, [
      `배경의 반사가 조금 더 정리되면 ${ui} 선이 더 분명해집니다.`,
      `화면 가장자리의 다른 차나 표지판을 덜어 내면 ${name}의 형태가 더 또렷해집니다.`,
    ]),
    shoot: choose(seed, 509, [
      `${ui} 어깨선과 수평이 나란한 각도를 잡으면 표면의 흐름이 한 번에 읽힙니다.`,
      `해가 낮게 드는 시간에 ${eul} 담으면 표면의 반사가 길게 늘어나 형태가 풍부해집니다.`,
    ]),
    edit: choose(seed, 510, [
      `${name} 표면의 하이라이트 끝을 조금 눌러 주고 반사의 색을 정리하면 표면이 더 단단해집니다.`,
      `${ui} 채도를 조금 낮추고 유리의 반사를 살리면 재질의 대비가 살아납니다.`,
    ]),
  }
}

function landscapeWaterVoice(label: string, seed: number): SubjectVoice {
  const eun = josa(label, '은', '는')
  const ui = josa(label, '의', '의')
  const eul = josa(label, '을', '를')
  return {
    opening: `화면을 채운 것은 ${label}와 하늘입니다. 사진은 그 넓이와 수평의 방향을 먼저 보여 줍니다.`,
    light: choose(seed, 601, [
      `${label} 위에 부서진 빛이 조각으로 흩어지고, 하늘의 밝기가 물 위에 한 번 더 내려앉습니다.`,
      `빛이 ${ui} 표면에서 한 번 꺾여, ${label}와 하늘이 서로 다른 밝기로 나뉩니다.`,
    ]),
    color: choose(seed, 602, [
      `${ui} 푸른 기와 하늘의 색이 이어지면서도 경계에서 미세하게 갈라져, 사진에 두 개의 공기가 생깁니다.`,
      `${label}의 넓은 면에 색이 한 방향으로 모여, 사진 전체가 하나의 공기를 공유합니다.`,
    ]),
    composition: choose(seed, 603, [
      `${ui} 수평선이 화면을 가로지르며 위아래의 무게를 나누고, 그 높이가 사진의 성격을 정합니다.`,
      `넓은 ${label}와 좁은 하늘이 만나는 자리에 수평선이 놓여, 사진이 안정된 비례로 서 있습니다.`,
    ]),
    texture: choose(seed, 604, [
      `${ui} 잔물결이 가까운 곳에서 진하고 먼 곳에서 옅어져, 평면인 물이 깊이를 얻습니다.`,
      `${label}의 표면이 매끈하게 뭉개지지 않고 결을 남겨, 사진에 촉감이 생깁니다.`,
    ]),
    mood: choose(seed, 605, [
      `${ui} 넓이에는 소리가 없어, 사진을 보는 동안 마음이 천천히 느려집니다.`,
      `${eun} 사람의 자리가 비어 있는 풍경이어서, 사진이 오히려 편안하게 읽힙니다.`,
    ]),
    moment: choose(seed, 606, [
      `${label} 위에서 물결이 한 번 부서지는 순간과 잔잔한 순간 사이에 놓여, 장면의 시간이 길게 늘어납니다.`,
      `해가 ${ui} 수면에 닿기 직전의 빛이 남아, 사진이 하루의 끝을 붙잡고 있습니다.`,
    ]),
    praise: choose(seed, 607, [
      `넓은 ${eul} 서두르지 않고 담아, 사진이 시원하게 열립니다.`,
      `${ui} 수평을 반듯하게 잡아, 풍경의 안정이 흐트러지지 않습니다.`,
    ]),
    refine: choose(seed, 608, [
      `${ui} 수평선 높이를 조금만 옮겨도 하늘과 물의 무게가 달라집니다.`,
      `화면 가장자리의 잡다한 요소를 덜어 내면 ${label}의 수평선이 더 길게 읽힙니다.`,
    ]),
    shoot: choose(seed, 609, [
      `${ui} 수평선을 삼분할 중 한 줄에 두고, 물가의 바위나 모래를 앞쪽에 조금 남겨 보십시오.`,
      `해가 낮게 드는 시간에 ${ui} 수평선을 화면 아래쪽에 두면, 하늘의 색이 사진의 주인공이 됩니다.`,
    ]),
    edit: choose(seed, 610, [
      `${ui} 푸른 기를 조금 눌러 주고 하늘의 하이라이트를 살리면 수평선이 또렷해집니다.`,
      `수평선을 반듯하게 세우고 ${label}의 암부 색을 정리하면 사진이 차분해집니다.`,
    ]),
  }
}

function landscapeNatureVoice(label: string, seed: number): SubjectVoice {
  const eun = josa(label, '은', '는')
  const ui = josa(label, '의', '의')
  const eul = josa(label, '을', '를')
  return {
    opening: `화면의 주인공은 ${label}의 결입니다. 사진은 그 너비와 계절의 공기를 담습니다.`,
    light: choose(seed, 701, [
      `빛이 ${eul} 비스듬히 지나며, 먼 쪽은 옅고 가까운 쪽은 진하게 나뉩니다.`,
      `빛이 ${ui} 윤곽을 따라 내려와, ${label}의 깊이가 밝기로 설명됩니다.`,
    ]),
    color: choose(seed, 702, [
      `${ui} 초록과 흙의 색이 한 방향으로 모여, 사진 전체가 같은 계절의 온도를 공유합니다.`,
      `${label}의 색이 지나치게 선명하지 않아, 사진이 계절의 공기를 담담하게 전합니다.`,
    ]),
    composition: choose(seed, 703, [
      `${ui} 원경과 근경이 겹쳐지며 화면에 여러 겹의 거리가 생깁니다.`,
      `화면을 가르는 ${label}의 선이 방향을 정하고, 눈이 그 선을 따라 천천히 이동합니다.`,
    ]),
    texture: choose(seed, 704, [
      `${label}의 결이 가까운 곳에서 살아 있고, 먼 쪽은 공기에 섞여 부드러워집니다.`,
      `${ui} 표면 요철이 남아 있어, 사진을 보는 동안 발밑의 감각까지 떠오릅니다.`,
    ]),
    mood: choose(seed, 705, [
      `${label}에는 사람의 흔적이 적어 사진이 조용하고, 보는 사람의 호흡을 느리게 합니다.`,
      `${eun} 소리가 없는 장면인데도 바람의 방향이 느껴져, 정적과 움직임을 함께 품습니다.`,
    ]),
    moment: choose(seed, 706, [
      `${label} 위로 안개나 빛이 걷히기 전의 공기가 남아, 장면이 잠시만 머무는 것처럼 읽힙니다.`,
      `계절이 바뀌는 자리의 색이 ${ui} 가장자리에 남아, 사진이 한 철의 끝을 붙잡고 있습니다.`,
    ]),
    praise: choose(seed, 707, [
      `${ui} 규모를 과장하지 않고 담아, 사진이 정직하게 넓습니다.`,
      `가까운 것과 먼 것의 거리를 살려, ${eun} 평면에 머물지 않습니다.`,
    ]),
    refine: choose(seed, 708, [
      `화면 아래의 잡다한 요소를 조금 덜어 내면 ${label}의 흐름이 더 분명해집니다.`,
      `하늘의 여백을 조금 더 남기면 ${label}의 규모가 한층 시원해집니다.`,
    ]),
    shoot: choose(seed, 709, [
      `${label}의 윤곽이 겹치는 지점을 화면 안에 두고, 하늘의 여백을 조금 남겨 보십시오.`,
      `빛이 비스듬히 드는 시간에 ${eul} 담으면 능선의 입체가 훨씬 분명해집니다.`,
    ]),
    edit: choose(seed, 710, [
      `먼 쪽의 대비를 살짝 올리고 ${label}의 초록을 낮추면 공기의 층이 살아납니다.`,
      `암부를 조금 들어 올려 ${label}의 결을 살리면 풍경이 답답해지지 않습니다.`,
    ]),
  }
}

function urbanVoice(seed: number): SubjectVoice {
  return {
    opening: '화면의 주인공은 건물의 선과 반복입니다. 사진은 도시가 만든 기하를 보여 줍니다.',
    light: choose(seed, 801, [
      '건물의 면과 면이 만나는 모서리에 빛이 걸려, 구조가 입체로 서 있습니다.',
      '빛이 창과 벽을 나누어, 같은 재질이 밝기로 여러 층이 됩니다.',
    ]),
    color: choose(seed, 802, [
      '콘크리트와 유리의 무채색 위에 한두 가지 색만 얹혀, 사진이 도시의 표정처럼 읽힙니다.',
      '도시의 색이 차분하게 정리되어, 사진이 소란하지 않습니다.',
    ]),
    composition: choose(seed, 803, [
      '수직과 수평이 화면을 나누고, 반복되는 창과 계단이 리듬을 만듭니다.',
      '건물의 모서리가 화면의 대각선을 따라가며, 정지한 구조에 방향이 생깁니다.',
    ]),
    texture: choose(seed, 804, [
      '벽면의 거친 결와 유리의 매끈함이 나란히 놓여, 재질의 대비가 사진의 촉감을 만듭니다.',
      '콘크리트의 결이 남아 있어, 사진이 도시의 나이를 말해 줍니다.',
    ]),
    mood: choose(seed, 805, [
      '사람이 보이지 않아도 도시의 속도가 느껴져, 사진이 조용한 소음을 품습니다.',
      '반복되는 창문이 익명의 생활을 암시해, 사진이 도시의 표정을 조용히 보여 줍니다.',
    ]),
    moment: choose(seed, 806, [
      '그림자와 빛이 만드는 각도가 지금의 시간을 붙잡고 있습니다.',
      '해가 기울며 생긴 긴 그림자가 도시의 시간을 화면에 남겼습니다.',
    ]),
    praise: choose(seed, 807, [
      '도시의 선을 반듯하게 담아, 사진이 구조를 흐트러뜨리지 않습니다.',
      '반복되는 형태를 리듬으로 살려, 도시가 단조롭지 않게 읽힙니다.',
    ]),
    refine: choose(seed, 808, [
      '화면 가장자리의 자질구레한 요소를 덜어 내면 기하가 더 선명해집니다.',
      '수직선을 조금 더 반듯하게 세우면 도시의 구조가 단단해집니다.',
    ]),
    shoot: choose(seed, 809, [
      '건물의 모서리를 화면의 대각선에 맞추고, 하늘을 조금 남겨 보십시오.',
      '해가 낮게 드는 시간에 담으면 건물의 그림자가 화면을 가로지르는 선이 됩니다.',
    ]),
    edit: choose(seed, 810, [
      '수직선을 반듯하게 세우고 그림자의 색을 중성으로 정리하면 구조가 단단해집니다.',
      '무채색의 대비를 조금 올리고 채도를 낮추면 도시의 질감이 살아납니다.',
    ]),
  }
}

function objectVoice(name: string, seed: number): SubjectVoice {
  const ui = josa(name, '의', '의')
  const eul = josa(name, '을', '를')
  const eun = josa(name, '은', '는')
  return {
    opening: `화면의 주인공은 ${josa(name, '이라는', '라는')} 작은 사물 하나입니다. 사진은 그 물건이 놓인 자리와 빛을 조용히 살핍니다.`,
    light: choose(seed, 901, [
      `${ui} 윗면에 빛이 머물고 아래로 그림자가 번져, 작은 형태가 또렷한 입체를 얻습니다.`,
      `창에서 들어온 빛이 ${eul} 한쪽만 밝히고, 나머지를 부드러운 그늘에 두어 형태가 조용히 떠오릅니다.`,
    ]),
    color: choose(seed, 902, [
      `${ui} 색과 바닥의 색이 가까워, 사진이 하나의 재질처럼 이어집니다.`,
      `${name}의 색이 배경에서 튀지 않아, 사진이 조용한 인상을 유지합니다.`,
    ]),
    composition: choose(seed, 903, [
      `${eun} 화면의 한쪽에 놓이고 반대쪽에 여백이 남아, 정지한 장면에 숨 쉴 자리가 생깁니다.`,
      `${eul} 화면의 비껴진 자리에 두어, 사진이 중심의 무게를 덜어 냅니다.`,
    ]),
    texture: choose(seed, 904, [
      `${ui} 표면에 남은 흠과 결이 그대로 보여, 사진이 물건의 나이를 말해 줍니다.`,
      `${name}의 재질이 뭉개지지 않아, 눈으로 만질 수 있을 만큼 가깝게 느껴집니다.`,
    ]),
    mood: choose(seed, 905, [
      `아무 일도 일어나지 않는 ${name} 곁의 장면이 오히려 오래 남아, 사진이 조용한 기다림처럼 읽힙니다.`,
      `누군가 잠시 ${eul} 두고 간 듯한 공기가 남아, 사진이 작은 이야기를 품습니다.`,
    ]),
    moment: choose(seed, 906, [
      `창가의 빛이 ${name} 위에 지금 이 각도로만 머무는 시간이 남아 있습니다.`,
      `그림자가 가장 길게 늘어진 순간이어서, 사진이 하루의 기울기를 담고 있습니다.`,
    ]),
    praise: choose(seed, 907, [
      `작은 ${eul} 서두르지 않고 담아, 사진이 사물을 함부로 크게 만들지 않습니다.`,
      `${ui} 자리를 존중한 프레이밍이어서, 사진이 조용하지만 완성되어 있습니다.`,
    ]),
    refine: choose(seed, 908, [
      `배경의 밝은 부분을 조금 눌러 주면 ${ui} 윤곽이 더 분명해집니다.`,
      `화면 아래쪽의 ${josa(name, '과', '와')} 관계없는 요소를 덜어 내면 시선이 ${name}에게 더 모입니다.`,
    ]),
    shoot: choose(seed, 909, [
      `${ui} 눈높이까지 내려가 배경을 단순하게 두고, 빛이 드는 쪽에서 담아 보십시오.`,
      '빛이 드는 창가에 사물을 두고, 배경은 어둡게 남겨 보십시오.',
    ]),
    edit: choose(seed, 910, [
      `${ui} 그림자를 조금 더 깊게 하고 표면의 하이라이트를 살리면 재질이 살아납니다.`,
      `배경의 채도를 낮추고 ${name}의 밝은 면을 조금 눌러 주면 형태가 또렷해집니다.`,
    ]),
  }
}

/**
 * 피사체 갈래에 맞는 문장 묶음을 만든다.
 * 갈래를 정하지 못했으면 null을 돌려주고, 엔진은 기존의 일반 문장을 그대로 쓴다.
 */
export function buildVoice(scene: SubjectScene | null, seed: number): SubjectVoice | null {
  if (!scene) return null
  switch (scene.category) {
    case SubjectCategory.PERSON:
      return personVoice(scene.count, seed)
    case SubjectCategory.ANIMAL:
      return animalVoice(scene.subject, scene.count, seed)
    case SubjectCategory.VEHICLE:
      return vehicleVoice(scene.subject, seed)
    case SubjectCategory.LANDSCAPE_WATER:
      return landscapeWaterVoice(scene.subject, seed)
    case SubjectCategory.LANDSCAPE_NATURE:
      return landscapeNatureVoice(scene.subject, seed)
    case SubjectCategory.URBAN_ARCHITECTURE:
      return urbanVoice(seed)
    case SubjectCategory.OBJECT_STILL:
      return objectVoice(scene.subject, seed)
    default:
      return null
  }
}

/** 피사체 이름과 수를 화면에 쓸 말로 정리한다. 예: '반려견 여러 마리', '인물 2명', '그릇' */
export function subjectWithCount(scene: SubjectScene): string {
  if (scene.category === SubjectCategory.PERSON) {
    if (scene.count >= 3) return '인물 여러 명'
    if (scene.count === 2) return '인물 2명'
    return scene.subject
  }
  if (scene.count < 2) return scene.subject
  if (scene.category === SubjectCategory.ANIMAL) return `${scene.subject} 여러 마리`
  return `${scene.subject} 여러 개`
}

/** 화면 아래 줄에 쓸 짧은 이름. 예: '반려견 · 바다' */
export function subjectLine(scene: SubjectScene): string {
  const sceneName = scene.sceneLabel || SCENE_TITLES[scene.scene]
  if (scene.category === SubjectCategory.GENERAL) return sceneName
  return `${subjectWithCount(scene)} · ${sceneName}`
}

export { josa }
