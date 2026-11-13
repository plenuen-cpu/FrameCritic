/**
 * 브라우저 안에서 사진을 재는 분석기.
 *
 * 사진은 서버로 보내지 않는다. HTML5 Canvas의 픽셀 데이터만으로 다음을 계산한다.
 *  - 256구간 휘도 히스토그램, 평균 휘도, 하이라이트·암부 비율, 전역 대비(표준편차)
 *  - 32픽셀 블록별 표준편차의 평균(국소 대비), 라플라시안 분산(선명도), 에지 밀도
 *  - 평균 RGB, 채널 차, 색온도 방향, 12구간 색상 히스토그램, 평균 채도
 *  - 수평선 후보의 기울기(투영 누적), 눈에 띄는 영역(블록 대비·에지 밀도)
 *
 * 미적·기술 점수는 브라우저에서 직접 돌리는 NIMA 신경망(nima.ts)이 계산한다.
 * 모델을 내려받지 못하거나 WebAssembly를 쓸 수 없을 때만, 위 측정값에서 계산한
 * 휴리스틱으로 대체하고 화면에 '추정'으로 표기한다.
 */

import { SubjectCategory } from './domain'
import type { NimaResult, NimaSource, SubjectScene } from './domain'
import { inferNima, toNimaResult } from './nima'
import { Measure, SubjectBox } from './critic-engine'
import type { LineCandidate } from './critic-engine'
import { detectSubjects } from './vision'
import { categoryOf, readSubject, type BandStats, type SceneCues } from './subject'

export interface Analysis {
  measure: Measure
  nima: NimaResult
  nimaSource: NimaSource
  nimaMs: number
  subjects: SubjectBox[]
  /** 사진의 주인공과 장면. 탐지기와 픽셀 근거로 읽는다. */
  scene: SubjectScene
  /** 화면 위에 표시할 주인공 영역. 없으면 null. */
  overlay: { x: number; y: number; w: number; h: number; label: string } | null
  /** 탐지기를 실제로 돌렸는지. */
  visionSource: 'MODEL' | 'SCENE'
  visionMs: number
  elapsedMs: number
  width: number
  height: number
  cues: SceneCues
}

const MAX_SIDE = 1280

function clamp(v: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, v))
}

/** 사진을 분석 해상도로 줄여 픽셀을 읽는다. 원본 비율은 유지한다. */
function drawToCanvas(image: HTMLImageElement | ImageBitmap): {
  ctx: CanvasRenderingContext2D
  width: number
  height: number
} {
  const sourceWidth = 'naturalWidth' in image ? image.naturalWidth : image.width
  const sourceHeight = 'naturalHeight' in image ? image.naturalHeight : image.height
  const longest = Math.max(sourceWidth, sourceHeight)
  const ratio = longest > MAX_SIDE ? MAX_SIDE / longest : 1
  const width = Math.max(1, Math.round(sourceWidth * ratio))
  const height = Math.max(1, Math.round(sourceHeight * ratio))
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) throw new Error('브라우저가 캔버스를 지원하지 않습니다.')
  ctx.drawImage(image as CanvasImageSource, 0, 0, width, height)
  return { ctx, width, height }
}

function laplacianVariance(gray: Float32Array, width: number, height: number): number {
  const values: number[] = []
  let sum = 0
  let sumSq = 0
  let count = 0
  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const i = y * width + x
      const v = gray[i - width] + gray[i + width] + gray[i - 1] + gray[i + 1] - 4 * gray[i]
      sum += v
      sumSq += v * v
      count++
    }
  }
  void values
  if (count === 0) return 0
  const mean = sum / count
  return Math.max(0, sumSq / count - mean * mean)
}

/** 32픽셀 블록의 표준편차 평균. OpenCV 측정과 같은 방식으로 국소 대비를 본다. */
function localContrast(gray: Float32Array, width: number, height: number): number {
  let total = 0
  let blocks = 0
  for (let y = 0; y < height; y += 32) {
    for (let x = 0; x < width; x += 32) {
      const xEnd = Math.min(x + 32, width)
      const yEnd = Math.min(y + 32, height)
      let sum = 0
      let sumSq = 0
      let count = 0
      for (let yy = y; yy < yEnd; yy++) {
        for (let xx = x; xx < xEnd; xx++) {
          const v = gray[yy * width + xx]
          sum += v
          sumSq += v * v
          count++
        }
      }
      if (count === 0) continue
      const mean = sum / count
      total += Math.sqrt(Math.max(0, sumSq / count - mean * mean))
      blocks++
    }
  }
  return blocks === 0 ? 0 : total / blocks
}

/** Canny 대신 Sobel 경사 크기로 에지 비율을 센다. 임계값은 OpenCV Canny의 하단에 맞춘다. */
function edgeFraction(gray: Float32Array, width: number, height: number): number {
  let edges = 0
  let count = 0
  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const i = y * width + x
      const gx =
        -gray[i - width - 1] -
        2 * gray[i - 1] -
        gray[i + width - 1] +
        gray[i - width + 1] +
        2 * gray[i + 1] +
        gray[i + width + 1]
      const gy =
        -gray[i - width - 1] -
        2 * gray[i - width] -
        gray[i - width + 1] +
        gray[i + width - 1] +
        2 * gray[i + width] +
        gray[i + width + 1]
      if (Math.hypot(gx, gy) > 220) edges++
      count++
    }
  }
  return count === 0 ? 0 : edges / count
}

/**
 * 수평선 후보. 에지 픽셀을 각도별로 비스듬히 투영해 한 줄에 얼마나 모이는지 센다.
 * 정확한 허프 변환은 아니지만 '거의 수평인 긴 선'의 존재와 기울기를 가려내는 데는 충분하다.
 */
function horizontalLines(
  gray: Float32Array,
  width: number,
  height: number,
): LineCandidate[] {
  const edge: number[] = []
  const xs: number[] = []
  for (let y = 1; y < height - 1; y += 2) {
    for (let x = 1; x < width - 1; x += 2) {
      const i = y * width + x
      const gx = gray[i + 1] - gray[i - 1]
      const gy = gray[i + width] - gray[i - width]
      if (Math.hypot(gx, gy) > 16 && Math.abs(gy) >= Math.abs(gx)) {
        edge.push(y * width + x)
        xs.push(x)
      }
    }
  }
  if (edge.length < 40) return []
  const candidates: LineCandidate[] = []
  const seen = new Set<number>()
  for (const angle of [-12, -9, -6, -4, -2, -1, 0, 1, 2, 4, 6, 9, 12]) {
    const slope = Math.tan((angle * Math.PI) / 180)
    const bins = new Map<number, number>()
    for (const index of edge) {
      const y = Math.floor(index / width)
      const x = index % width
      const r = Math.round(y - x * slope)
      bins.set(r, (bins.get(r) ?? 0) + 1)
    }
    let best = 0
    for (const value of bins.values()) if (value > best) best = value
    const share = best / edge.length
    if (share > 0.12 && !seen.has(angle)) {
      seen.add(angle)
      candidates.push({ angle, length: clamp(share * 2.2, 0.1, 1) })
    }
  }
  void xs
  return candidates
    .sort((a, b) => b.length - a.length)
    .slice(0, 5)
}

interface Salience {
  box: SubjectBox
  rect: { x: number; y: number; w: number; h: number }
}

/**
 * 객체 검출기 대신 '눈에 띄는 영역'을 찾는다.
 * 8×8 블록의 국소 대비와 에지 밀도를 더해 상위 블록의 바운딩 박스를 만든다.
 * 사물의 이름을 붙이지 않고 위치와 크기만 쓰므로, 본문은 '주요 영역'이라고만 말한다.
 */
function salience(
  gray: Float32Array,
  width: number,
  height: number,
): Salience | null {
  const cols = 8
  const rows = 8
  const scores: number[] = []
  for (let by = 0; by < rows; by++) {
    for (let bx = 0; bx < cols; bx++) {
      const x0 = Math.floor((bx * width) / cols)
      const x1 = Math.floor(((bx + 1) * width) / cols)
      const y0 = Math.floor((by * height) / rows)
      const y1 = Math.floor(((by + 1) * height) / rows)
      let sum = 0
      let sumSq = 0
      let count = 0
      let edgeCount = 0
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          const v = gray[y * width + x]
          sum += v
          sumSq += v * v
          count++
          if (x > 0 && y > 0) {
            const dx = Math.abs(v - gray[y * width + x - 1])
            const dy = Math.abs(v - gray[(y - 1) * width + x])
            if (dx + dy > 40) edgeCount++
          }
        }
      }
      if (count === 0) {
        scores.push(0)
        continue
      }
      const mean = sum / count
      const std = Math.sqrt(Math.max(0, sumSq / count - mean * mean))
      scores.push(std + (edgeCount / count) * 90)
    }
  }
  const sorted = scores.slice().sort((a, b) => b - a)
  const threshold = sorted[Math.floor(sorted.length * 0.12)] ?? 0
  if (!(threshold > 0)) return null
  let minX = cols
  let minY = rows
  let maxX = -1
  let maxY = -1
  for (let by = 0; by < rows; by++) {
    for (let bx = 0; bx < cols; bx++) {
      if (scores[by * cols + bx] < threshold) continue
      minX = Math.min(minX, bx)
      maxX = Math.max(maxX, bx)
      minY = Math.min(minY, by)
      maxY = Math.max(maxY, by)
    }
  }
  if (maxX < 0) return null
  const rect = {
    x: minX / cols,
    y: minY / rows,
    w: (maxX - minX + 1) / cols,
    h: (maxY - minY + 1) / rows,
  }
  const box = new SubjectBox({
    label: 'salience',
    confidence: clamp(0.45 + (threshold / (sorted[0] || 1)) * 0.4, 0.35, 0.95),
    cx: rect.x + rect.w / 2,
    cy: rect.y + rect.h / 2,
    w: rect.w,
    h: rect.h,
  })
  return { box, rect }
}

/**
 * NIMA를 쓸 수 없을 때의 대체 휴리스틱. 측정값만으로 '어느 정도 정리된 사진인가'를 추정한다.
 * 객관적 예술 평가가 아니며, 화면과 문서 어디에서도 그렇게 주장하지 않는다.
 */
function estimateNima(
  luminance: number,
  contrast: number,
  highlights: number,
  shadows: number,
  localContrastValue: number,
  sharpness: number,
  saturation: number,
  subject: SubjectBox | null,
): NimaResult {
  const exposure = 1 - clamp(Math.abs(luminance / 255 - 0.46) / 0.34, 0, 1)
  const clip = 1 - clamp((highlights + shadows) / 0.12, 0, 1)
  const separation = clamp((contrast - 12) / 40, 0, 1)
  const texture = clamp((localContrastValue - 3) / 17, 0, 1)
  const detail = clamp((Math.log10(1 + sharpness) - Math.log10(16)) / (Math.log10(251) - Math.log10(16)), 0, 1)
  const color = 1 - clamp(Math.abs(saturation - 0.24) / 0.3, 0, 1)
  const placement = subject
    ? 1 - clamp(Math.abs(Math.hypot(subject.cx - 1 / 3, subject.cy - 1 / 3) - 0.05) / 0.4, 0, 1)
    : 0.5
  const raw =
    0.16 * exposure + 0.18 * clip + 0.18 * separation + 0.14 * texture + 0.16 * detail + 0.08 * color + 0.1 * placement
  const aesthetic = clamp(4.2 + 4.4 * raw, 3.4, 8.9)
  const technical = clamp(
    3.6 + 5.2 * (0.3 * clip + 0.25 * separation + 0.25 * detail + 0.2 * texture),
    3.2,
    9.1,
  )
  const sigma = 1.1 + (1 - raw) * 0.9
  const probabilities: number[] = []
  let total = 0
  for (let i = 0; i < 10; i++) {
    const center = aesthetic - 1
    const value = Math.exp(-((i - center) ** 2) / (2 * sigma * sigma))
    probabilities.push(value)
    total += value
  }
  return {
    aesthetic,
    technical,
    deviation: clamp(sigma, 0.4, 4.5),
    probabilities: probabilities.map((v) => v / (total || 1)),
  }
}

/** 띠 하나를 세는 누산기. 화면을 위·가운데·아래로 나누어 장면을 가른다. */
interface BandAcc {
  r: number
  g: number
  b: number
  y: number
  y2: number
  count: number
  green: number
  blue: number
  bright: number
}

function bandAcc(): BandAcc {
  return { r: 0, g: 0, b: 0, y: 0, y2: 0, count: 0, green: 0, blue: 0, bright: 0 }
}

function bandStats(acc: BandAcc): BandStats {
  if (acc.count === 0)
    return { r: 0, g: 0, b: 0, luminance: 0, texture: 0, green: 0, blue: 0, bright: 0 }
  const mean = acc.y / acc.count
  return {
    r: acc.r / acc.count,
    g: acc.g / acc.count,
    b: acc.b / acc.count,
    luminance: mean,
    texture: Math.sqrt(Math.max(0, acc.y2 / acc.count - mean * mean)),
    green: acc.green / acc.count,
    blue: acc.blue / acc.count,
    bright: acc.bright / acc.count,
  }
}

/**
 * 주인공 상자 안과 바깥의 밝기 차이. 피사체가 배경에서 얼마나 떨어져 보이는지의 근거다.
 * 0에 가까우면 같은 톤으로 이어지고, 1에 가까우면 밝기로 분명히 나뉜다.
 */
function separationOf(
  data: Uint8ClampedArray,
  width: number,
  height: number,
  box: { cx: number; cy: number; w: number; h: number },
): number {
  const left = box.cx - box.w / 2
  const right = box.cx + box.w / 2
  const top = box.cy - box.h / 2
  const bottom = box.cy + box.h / 2
  let inside = 0
  let insideCount = 0
  let outside = 0
  let outsideCount = 0
  for (let y = 0; y < height; y += 4) {
    const ny = y / height
    for (let x = 0; x < width; x += 4) {
      const nx = x / width
      const i = (y * width + x) * 4
      const luma = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]
      if (nx >= left && nx <= right && ny >= top && ny <= bottom) {
        inside += luma
        insideCount++
      } else {
        outside += luma
        outsideCount++
      }
    }
  }
  if (insideCount === 0 || outsideCount === 0) return 0
  return clamp(Math.abs(inside / insideCount - outside / outsideCount) / 70, 0, 1)
}

export interface PixelMeasurement {
  measure: Measure
  nima: NimaResult
  subjects: SubjectBox[]
  cues: SceneCues
  /** 눈에 띄는 영역. 탐지기를 못 쓸 때 대신 쓴다. */
  salienceMap: { x: number; y: number; w: number; h: number } | null
}

/**
 * 픽셀 배열에서 모든 측정값을 계산한다. DOM 없이도 돌릴 수 있게 분리했다.
 * data는 RGBA 8비트, 길이는 width*height*4다.
 */
export function measurePixels(data: Uint8ClampedArray, width: number, height: number): PixelMeasurement {
  const pixels = width * height
  const gray = new Float32Array(pixels)
  const histogram = new Array(256).fill(0)
  const hues = new Array(12).fill(0)
  let sumR = 0
  let sumG = 0
  let sumB = 0
  let sumY = 0
  let sumYSq = 0
  let highlights = 0
  let shadows = 0
  let sumSaturation = 0
  const bands = [bandAcc(), bandAcc(), bandAcc()]

  for (let i = 0, p = 0; i < data.length && p < pixels; i += 4, p++) {
    const r = data[i]
    const g = data[i + 1]
    const b = data[i + 2]
    const y = 0.299 * r + 0.587 * g + 0.114 * b
    gray[p] = y
    const bin = y < 0 ? 0 : y > 255 ? 255 : Math.round(y)
    histogram[bin] += 1
    sumY += y
    sumYSq += y * y
    if (bin >= 250) highlights++
    if (bin <= 5) shadows++
    sumR += r
    sumG += g
    sumB += b

    const row = Math.floor(p / width)
    const acc = bands[row < height / 3 ? 0 : row < (height * 2) / 3 ? 1 : 2]
    acc.r += r
    acc.g += g
    acc.b += b
    acc.y += y
    acc.y2 += y * y
    acc.count += 1
    if (g > r + 8 && g > b + 8) acc.green += 1
    if (b > r + 8) acc.blue += 1
    if (bin >= 200) acc.bright += 1

    const max = Math.max(r, g, b)
    const min = Math.min(r, g, b)
    const delta = max - min
    const saturation = max === 0 ? 0 : delta / max
    sumSaturation += saturation
    if (delta > 0 && saturation > 32 / 255) {
      let hue: number
      if (max === r) hue = ((g - b) / delta) % 6
      else if (max === g) hue = (b - r) / delta + 2
      else hue = (r - g) / delta + 4
      let degrees = hue * 60
      if (degrees < 0) degrees += 360
      const hueBin = clamp(Math.floor(degrees / 15), 0, 11)
      hues[hueBin] += 1
    }
  }

  const meanY = pixels === 0 ? 0 : sumY / pixels
  const contrast = pixels === 0 ? 0 : Math.sqrt(Math.max(0, sumYSq / pixels - meanY * meanY))
  const localContrastValue = localContrast(gray, width, height)
  const sharpness = laplacianVariance(gray, width, height)
  const edges = edgeFraction(gray, width, height)
  const lines = horizontalLines(gray, width, height)
  const saliency = salience(gray, width, height)
  const subjects = saliency ? [saliency.box] : []
  const cues: SceneCues = {
    top: bandStats(bands[0]),
    middle: bandStats(bands[1]),
    bottom: bandStats(bands[2]),
    hasHorizon: lines.some((line) => Math.abs(line.angle) <= 3),
  }

  const measure = new Measure({
    width,
    height,
    luminance: meanY,
    highlights: pixels === 0 ? 0 : highlights / pixels,
    shadows: pixels === 0 ? 0 : shadows / pixels,
    contrast,
    localContrast: localContrastValue,
    sharpness,
    rgb: pixels === 0 ? [0, 0, 0] : [sumR / pixels, sumG / pixels, sumB / pixels],
    hues,
    histogram,
    lines,
    saturation: pixels === 0 ? 0 : sumSaturation / pixels,
    edges,
  })

  const nima = estimateNima(
    meanY,
    contrast,
    measure.highlights,
    measure.shadows,
    localContrastValue,
    sharpness,
    measure.saturation,
    saliency?.box ?? null,
  )

  return {
    measure,
    nima,
    subjects,
    cues,
    salienceMap: saliency?.rect ?? null,
  }
}

/**
 * 사진 한 장을 재고, 비평 엔진이 쓸 Measure·NIMA 점수·피사체와 장면을 만든다.
 * 픽셀 측정은 동기로 끝나고, 신경망 추론(모델 첫 로딩 포함)만 기다린다.
 */
export async function analyzeImage(image: HTMLImageElement | ImageBitmap): Promise<Analysis> {
  const started = performance.now()
  const { ctx, width, height } = drawToCanvas(image)
  const data = ctx.getImageData(0, 0, width, height).data
  const core = measurePixels(data, width, height)
  // 두 신경망은 서로 독립이므로 함께 돌린다. 첫 로딩만 기다리고, 그 뒤로는 각자 짧다.
  const [inference, vision] = await Promise.all([inferNima(image), detectSubjects(image)])

  const detected = vision?.detections ?? []
  // 주인공 상자는 주인공을 고르는 기준(subject.ts의 무게중심)과 같이 고른다.
  const primary = detected
    .slice()
    .sort(
      (a, b) =>
        Math.pow(b.score, 1.5) * Math.sqrt(b.w * b.h) -
        Math.pow(a.score, 1.5) * Math.sqrt(a.w * a.h),
    )[0]
  const separation = primary ? separationOf(data, width, height, primary) : 0
  const scene = readSubject(
    detected,
    core.cues,
    core.measure.saturation,
    core.measure.luminance,
    core.measure.lines.length,
    separation,
    vision ? 'MODEL' : 'SCENE',
  )

  /**
   * 장면 자체가 주인공인 풍경에서는 탐지기가 엉뚱한 사물(예: 물결을 침대로)을 잡을 수 있다.
   * 그런 사진에서는 탐지 상자 대신 '주요 영역'만 남겨, 본문이 잘못된 사물 이름을 말하지 않게 한다.
   */
  const landscape =
    scene.category === SubjectCategory.LANDSCAPE_WATER ||
    scene.category === SubjectCategory.LANDSCAPE_NATURE ||
    scene.category === SubjectCategory.URBAN_ARCHITECTURE
  const usable = landscape
    ? []
    : detected.filter((d) => {
        const category = categoryOf(d.label)
        return category !== null && category === scene.category
      })
  const boxes = usable.map(
    (d) =>
      new SubjectBox({
        label: scene.count > 0 && !landscape ? scene.subject : d.label,
        confidence: d.score,
        cx: d.cx,
        cy: d.cy,
        w: d.w,
        h: d.h,
      }),
  )
  const subjects = boxes.length > 0 ? boxes : core.subjects
  const overlay = boxes.length > 0
    ? {
        x: boxes[0].cx - boxes[0].w / 2,
        y: boxes[0].cy - boxes[0].h / 2,
        w: boxes[0].w,
        h: boxes[0].h,
        label: scene.subject,
      }
    : core.salienceMap
      ? { ...core.salienceMap, label: '주요 영역' }
      : null

  return {
    measure: core.measure,
    nima: inference ? toNimaResult(inference) : core.nima,
    nimaSource: inference ? 'MODEL' : 'ESTIMATE',
    nimaMs: inference?.modelMs ?? 0,
    subjects,
    scene,
    overlay,
    visionSource: vision ? 'MODEL' : 'SCENE',
    visionMs: vision?.modelMs ?? 0,
    elapsedMs: Math.max(1, Math.round(performance.now() - started)),
    width,
    height,
    cues: core.cues,
  }
}

/** 파일을 화면에 그릴 수 있는 이미지로 읽는다. EXIF 회전은 브라우저가 처리한다. */
export function loadImage(file: File): Promise<{ image: HTMLImageElement; url: string }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => resolve({ image, url })
    image.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('사진을 열지 못했습니다. 다른 파일을 선택해 주세요.'))
    }
    image.src = url
  })
}
