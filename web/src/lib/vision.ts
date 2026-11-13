/**
 * 브라우저 안에서 도는 피사체 탐지기.
 *
 * 기기 앱(Android)이 쓰는 것과 같은 EfficientDet-Lite0(COCO) 모델을 LiteRT.js(WASM)로
 * 직접 추론한다. 사진은 어디로도 전송되지 않는다.
 *
 * 모델의 출력은 후처리 전의 원시 텐서 두 개다.
 *  - 클래스 로짓 [1, 19206, 90]  (배경 포함 90칸, 시그모이드를 지나야 확률이 된다)
 *  - 상자 인코딩 [1, 19206, 4]   (앵커에 대한 상대값)
 * 그래서 앵커를 만들어 상자를 되돌리고, 같은 범주끼리 겹침을 정리해야 한다. 앵커 격자는
 * EfficientDet의 다중 스케일 앵커(레벨 3~7, 앵커 배율 4, 한 옥타브에 세 단계, 종횡비 세 가지)를
 * 그대로 옮겼다. 상자 인코딩은 10·10·5·5 배율의 SSD 방식이다.
 *
 * 탐지기가 없거나 실패하면 null을 돌려주고, 분석기는 '주요 영역' 휴리스틱으로 넘어간다.
 */

import { assetUrl, loadRuntime } from './runtime'

const SIZE = 320
const LABELS = 90

/**
 * 모델의 출력 열은 COCO 범주 id 하나에 대응한다(0번 열 = id 1 = person).
 * 그래서 90칸짜리 표를 id 순서로 만들고, 비어 있는 id는 이름 없이 둔다.
 */
const COCO_ID_NAMES: Record<number, string> = {
  1: 'person',
  2: 'bicycle',
  3: 'car',
  4: 'motorcycle',
  5: 'airplane',
  6: 'bus',
  7: 'train',
  8: 'truck',
  9: 'boat',
  10: 'traffic light',
  11: 'fire hydrant',
  13: 'stop sign',
  14: 'parking meter',
  15: 'bench',
  16: 'bird',
  17: 'cat',
  18: 'dog',
  19: 'horse',
  20: 'sheep',
  21: 'cow',
  22: 'elephant',
  23: 'bear',
  24: 'zebra',
  25: 'giraffe',
  27: 'backpack',
  28: 'umbrella',
  31: 'handbag',
  32: 'tie',
  33: 'suitcase',
  34: 'frisbee',
  35: 'skis',
  36: 'snowboard',
  37: 'sports ball',
  38: 'kite',
  39: 'baseball bat',
  40: 'baseball glove',
  41: 'skateboard',
  42: 'surfboard',
  43: 'tennis racket',
  44: 'bottle',
  46: 'wine glass',
  47: 'cup',
  48: 'fork',
  49: 'knife',
  50: 'spoon',
  51: 'bowl',
  52: 'banana',
  53: 'apple',
  54: 'sandwich',
  55: 'orange',
  56: 'broccoli',
  57: 'carrot',
  58: 'hot dog',
  59: 'pizza',
  60: 'donut',
  61: 'cake',
  62: 'chair',
  63: 'couch',
  64: 'potted plant',
  65: 'bed',
  67: 'dining table',
  70: 'toilet',
  72: 'tv',
  73: 'laptop',
  74: 'mouse',
  75: 'remote',
  76: 'keyboard',
  77: 'cell phone',
  78: 'microwave',
  79: 'oven',
  80: 'toaster',
  81: 'sink',
  82: 'refrigerator',
  84: 'book',
  85: 'clock',
  86: 'vase',
  87: 'scissors',
  88: 'teddy bear',
  89: 'hair drier',
  90: 'toothbrush',
}

const COLUMN_NAMES: string[] = Array.from({ length: LABELS }, (_, column) => COCO_ID_NAMES[column + 1] ?? '')
interface Anchor {
  x: number
  y: number
  w: number
  h: number
}

let anchors: Anchor[] | null = null

/**
 * EfficientDet의 앵커 격자. 이미지 320×320, 레벨 3~7, 앵커 배율 4,
 * 한 옥타브에 세 단계(1, 2^(1/3), 2^(2/3)), 종횡비 1·2·0.5.
 * 모델의 출력 순서는 (행, 열, 종횡비, 단계) 순이라 그 순서를 그대로 만든다.
 */
function anchorGrid(): Anchor[] {
  if (anchors) return anchors
  const out: Anchor[] = []
  const scales = [1, Math.pow(2, 1 / 3), Math.pow(2, 2 / 3)]
  const ratios = [1, 2, 0.5]
  for (let level = 3; level <= 7; level++) {
    const stride = Math.pow(2, level)
    const cells = Math.ceil(SIZE / stride)
    const base = stride * 4
    for (let row = 0; row < cells; row++) {
      for (let col = 0; col < cells; col++) {
        const cy = (row * stride + stride / 2) / SIZE
        const cx = (col * stride + stride / 2) / SIZE
        for (const ratio of ratios) {
          for (const scale of scales) {
            out.push({
              x: cx,
              y: cy,
              w: ((scale * Math.sqrt(ratio)) * base) / SIZE,
              h: ((scale / Math.sqrt(ratio)) * base) / SIZE,
            })
          }
        }
      }
    }
  }
  anchors = out
  return out
}

export interface Detection {
  label: string
  score: number
  cx: number
  cy: number
  w: number
  h: number
}

export interface VisionInference {
  detections: Detection[]
  modelMs: number
}

type CompiledModel = Awaited<ReturnType<(typeof import('@litertjs/core'))['loadAndCompile']>>

interface Engine {
  core: NonNullable<Awaited<ReturnType<typeof loadRuntime>>>
  model: CompiledModel
}

let enginePromise: Promise<Engine | null> | null = null

async function startEngine(): Promise<Engine | null> {
  const core = await loadRuntime()
  if (!core) return null
  const response = await fetch(assetUrl('models/objects.tflite'))
  if (!response.ok) throw new Error(`탐지 모델을 내려받지 못했습니다 (${response.status})`)
  const bytes = new Uint8Array(await response.arrayBuffer())
  const model = await core.loadAndCompile(bytes, { accelerator: 'wasm' })
  return { core, model }
}

/** 탐지 모델을 준비한다. 여러 번 불러도 한 번만 내려받고 컴파일한다. */
export function prepareVision(): Promise<Engine | null> {
  if (!enginePromise) {
    enginePromise = startEngine().catch(() => null)
  }
  return enginePromise
}

/** 사진을 비율을 지켜 320×320 안에 넣고, 남는 자리는 검게 채운다. */
function letterbox(image: HTMLImageElement | ImageBitmap): {
  pixels: Uint8Array
  padX: number
  padY: number
} {
  const sourceWidth = 'naturalWidth' in image ? image.naturalWidth : image.width
  const sourceHeight = 'naturalHeight' in image ? image.naturalHeight : image.height
  const scale = Math.min(SIZE / Math.max(1, sourceWidth), SIZE / Math.max(1, sourceHeight))
  const width = Math.max(1, Math.round(sourceWidth * scale))
  const height = Math.max(1, Math.round(sourceHeight * scale))
  const padX = Math.floor((SIZE - width) / 2)
  const padY = Math.floor((SIZE - height) / 2)
  const canvas = document.createElement('canvas')
  canvas.width = SIZE
  canvas.height = SIZE
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) throw new Error('브라우저가 캔버스를 지원하지 않습니다.')
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, SIZE, SIZE)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(image as CanvasImageSource, padX, padY, width, height)
  const { data } = ctx.getImageData(0, 0, SIZE, SIZE)
  const pixels = new Uint8Array(SIZE * SIZE * 3)
  for (let i = 0, p = 0; p < SIZE * SIZE; i += 4, p++) {
    pixels[p * 3] = data[i]
    pixels[p * 3 + 1] = data[i + 1]
    pixels[p * 3 + 2] = data[i + 2]
  }
  return { pixels, padX, padY }
}

function overlap(a: Detection, b: Detection): number {
  const ax0 = a.cx - a.w / 2
  const ay0 = a.cy - a.h / 2
  const bx0 = b.cx - b.w / 2
  const by0 = b.cy - b.h / 2
  const ix = Math.max(0, Math.min(ax0 + a.w, bx0 + b.w) - Math.max(ax0, bx0))
  const iy = Math.max(0, Math.min(ay0 + a.h, by0 + b.h) - Math.max(ay0, by0))
  const inter = ix * iy
  const union = a.w * a.h + b.w * b.h - inter
  return union <= 0 ? 0 : inter / union
}

/**
 * 사진에서 피사체를 찾는다. 신뢰도가 낮은 상자는 버리고, 같은 이름이 겹치면 큰 것만 남긴다.
 * 실패하면 null을 돌려준다.
 */
export async function detectSubjects(
  image: HTMLImageElement | ImageBitmap,
): Promise<VisionInference | null> {
  const engine = await prepareVision()
  if (!engine) return null
  try {
    const started = performance.now()
    const { pixels, padX, padY } = letterbox(image)
    const tensor = new engine.core.Tensor(pixels, [1, SIZE, SIZE, 3])
    const outputs = await engine.model.run(tensor)
    tensor.delete()
    const classes = await outputs[0].moveTo('wasm')
    const boxes = await outputs[1].moveTo('wasm')
    const scores = classes.toTypedArray() as Float32Array
    const raw = boxes.toTypedArray() as Float32Array
    const count = Math.floor(scores.length / LABELS)
    const grid = anchorGrid()
    const candidates: Detection[] = []
    for (let i = 0; i < count && i < grid.length; i++) {
      let bestScore = 0
      let bestClass = -1
      for (let c = 0; c < LABELS; c++) {
        if (!COLUMN_NAMES[c]) continue
        const value = scores[i * LABELS + c]
        if (value > bestScore) {
          bestScore = value
          bestClass = c
        }
      }
      // 모델 출력은 이미 시그모이드를 지난 확률이다. 다시 시그모이드를 씌우지 않는다.
      const score = Math.min(1, Math.max(0, bestScore))
      if (score < 0.35 || bestClass < 0) continue
      const anchor = grid[i]
      const dx = raw[i * 4]
      const dy = raw[i * 4 + 1]
      const dw = raw[i * 4 + 2]
      const dh = raw[i * 4 + 3]
      if (!Number.isFinite(dx) || !Number.isFinite(dy) || !Number.isFinite(dw) || !Number.isFinite(dh))
        continue
      const cy = anchor.y + (dy / 10) * anchor.h
      const cx = anchor.x + (dx / 10) * anchor.w
      const h = Math.exp(dh / 5) * anchor.h
      const w = Math.exp(dw / 5) * anchor.w
      // 편지함 여백을 걷어내고 원본 사진 기준의 비율로 되돌린다.
      const imageWidth = SIZE - padX * 2
      const imageHeight = SIZE - padY * 2
      const left = ((cx - w / 2) * SIZE - padX) / imageWidth
      const top = ((cy - h / 2) * SIZE - padY) / imageHeight
      const right = ((cx + w / 2) * SIZE - padX) / imageWidth
      const bottom = ((cy + h / 2) * SIZE - padY) / imageHeight
      const clippedLeft = Math.min(1, Math.max(0, left))
      const clippedTop = Math.min(1, Math.max(0, top))
      const clippedRight = Math.min(1, Math.max(0, right))
      const clippedBottom = Math.min(1, Math.max(0, bottom))
      if (clippedRight - clippedLeft < 0.01 || clippedBottom - clippedTop < 0.01) continue
      candidates.push({
        label: COLUMN_NAMES[bestClass] ?? 'object',
        score,
        cx: (clippedLeft + clippedRight) / 2,
        cy: (clippedTop + clippedBottom) / 2,
        w: clippedRight - clippedLeft,
        h: clippedBottom - clippedTop,
      })
    }
    classes.delete()
    boxes.delete()
    for (const output of outputs) output.delete()
    candidates.sort((a, b) => b.score - a.score)
    const kept: Detection[] = []
    for (const candidate of candidates) {
      const duplicate = kept.findIndex(
        (other) => other.label === candidate.label && overlap(other, candidate) > 0.32,
      )
      if (duplicate >= 0) {
        // 같은 대상을 가리키는 상자가 둘이면, 점수가 비슷할 때 더 작게 잡은 쪽을 남긴다.
        const other = kept[duplicate]
        if (candidate.score >= other.score - 0.03 && candidate.w * candidate.h < other.w * other.h)
          kept[duplicate] = candidate
        continue
      }
      kept.push(candidate)
      if (kept.length >= 10) break
    }
    return {
      detections: kept.map((d) => ({
        label: d.label,
        score: d.score,
        cx: d.cx,
        cy: d.cy,
        w: d.w,
        h: d.h,
      })),
      modelMs: Math.max(1, Math.round(performance.now() - started)),
    }
  } catch {
    return null
  }
}
