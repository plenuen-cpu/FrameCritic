/**
 * 브라우저 안에서 사진을 재는 분석기.
 *
 * 사진은 서버로 보내지 않는다. HTML5 Canvas의 픽셀 데이터만으로 다음을 계산한다.
 *  - 256구간 휘도 히스토그램, 평균 휘도, 하이라이트·암부 비율, 전역 대비(표준편차)
 *  - 32픽셀 블록별 표준편차의 평균(국소 대비), 라플라시안 분산(선명도), 에지 밀도
 *  - 평균 RGB, 채널 차, 색온도 방향, 12구간 색상 히스토그램, 평균 채도
 *  - 수평선 후보의 기울기(투영 누적), 눈에 띄는 영역(블록 대비·에지 밀도)
 *
 * NIMA와 객체 검출은 브라우저에 모델을 내려받지 않기 위해, 위 측정값에서 계산한
 * 휴리스틱으로 대체한다. 화면에는 그 사실을 숨기지 않고 '추정'으로 표기한다.
 */

import type { NimaResult } from './domain'
import { Measure, SubjectBox } from './critic-engine'
import type { LineCandidate } from './critic-engine'

export interface Analysis {
  measure: Measure
  nima: NimaResult
  subjects: SubjectBox[]
  elapsedMs: number
  width: number
  height: number
  salienceMap: { x: number; y: number; w: number; h: number } | null
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
 * NIMA 대체 휴리스틱. 측정값만으로 '어느 정도 정리된 사진인가'를 추정한다.
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

export interface PixelMeasurement {
  measure: Measure
  nima: NimaResult
  subjects: SubjectBox[]
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
    salienceMap: saliency?.rect ?? null,
  }
}

/** 사진 한 장을 재고, 비평 엔진이 쓸 Measure를 만든다. */
export function analyzeImage(image: HTMLImageElement | ImageBitmap): Analysis {
  const started = performance.now()
  const { ctx, width, height } = drawToCanvas(image)
  const data = ctx.getImageData(0, 0, width, height).data
  const core = measurePixels(data, width, height)
  return {
    ...core,
    elapsedMs: Math.max(1, Math.round(performance.now() - started)),
    width,
    height,
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
