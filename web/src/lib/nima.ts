/**
 * 브라우저 안에서 도는 NIMA 신경망.
 *
 * idealo의 NIMA(Neural Image Assessment)를 LiteRT용으로 다시 만든 MobileNet 모델 두 개를
 * 내려받아 기기에서 직접 추론한다. 사진은 어디로도 전송되지 않는다.
 *  - nima_aesthetic : AVA 데이터셋으로 학습한 미적 평가 (1~10점 10구간 확률분포)
 *  - nima_technical : TID2013으로 학습한 기술 품질 (같은 형식)
 *
 * 입력은 224×224 RGB를 [-1, 1]로 정규화한 float32[N,224,224,3]이고, 출력은 softmax 확률 10개다.
 * 점수는 분포의 가중 평균 Σ i·pᵢ, 표준편차는 그 분포의 표준편차다.
 *
 * 런타임은 LiteRT.js(WASM/XNNPack)를 쓴다. WebAssembly를 못 쓰는 브라우저이거나 모델을 받지
 * 못하면 null을 돌려주고, 호출부(analyzer)가 캔버스 측정 기반 추정으로 넘어간다.
 */

import { distribution, type NimaResult, type NimaSource } from './domain'
import { assetUrl, loadRuntime } from './runtime'

const INPUT_SIZE = 224
const SPAN = INPUT_SIZE * INPUT_SIZE * 3

const MODEL_FILES = {
  aesthetic: 'nima_aesthetic.tflite',
  technical: 'nima_technical.tflite',
} as const

type Core = typeof import('@litertjs/core')
type CompiledModel = Awaited<ReturnType<Core['loadAndCompile']>>

export type NimaStatus = 'idle' | 'loading' | 'ready' | 'unavailable'

export interface NimaInference {
  source: NimaSource
  aesthetic: number
  technical: number
  deviation: number
  probabilities: number[]
  modelMs: number
}

interface Engine {
  core: Core
  aesthetic: CompiledModel
  technical: CompiledModel | null
}

let enginePromise: Promise<Engine | null> | null = null
let status: NimaStatus = 'idle'

/** 페이지 주소를 기준으로 정적 자산 주소를 만든다(하위 경로 배포에도 맞는다). */
function modelUrl(file: string): string {
  return assetUrl(`models/${file}`)
}

async function loadModelBytes(file: string): Promise<Uint8Array> {
  const response = await fetch(modelUrl(file))
  if (!response.ok) throw new Error(`모델을 내려받지 못했습니다: ${file} (${response.status})`)
  return new Uint8Array(await response.arrayBuffer())
}

async function startEngine(): Promise<Engine | null> {
  const core = await loadRuntime()
  if (!core) return null
  const [aestheticBytes, technicalBytes] = await Promise.all([
    loadModelBytes(MODEL_FILES.aesthetic),
    loadModelBytes(MODEL_FILES.technical).catch(() => null),
  ])
  const aesthetic = await core.loadAndCompile(aestheticBytes, { accelerator: 'wasm' })
  let technical: CompiledModel | null = null
  if (technicalBytes) {
    try {
      technical = await core.loadAndCompile(technicalBytes, { accelerator: 'wasm' })
    } catch {
      technical = null
    }
  }
  return { core, aesthetic, technical }
}

/**
 * 모델을 준비한다. 여러 번 불러도 한 번만 내려받고 컴파일한다.
 * 실패하면 null을 돌려주고, 그 뒤로는 다시 시도하지 않는다(추정으로 동작).
 */
export function prepareNima(): Promise<Engine | null> {
  if (!enginePromise) {
    status = 'loading'
    enginePromise = startEngine()
      .then((engine) => {
        status = 'ready'
        return engine
      })
      .catch(() => {
        status = 'unavailable'
        return null
      })
  }
  return enginePromise
}

export function nimaStatus(): NimaStatus {
  return status
}

/** 224×224로 줄이고 [-1, 1]로 정규화한 NHWC 입력을 만든다. */
function preprocess(image: HTMLImageElement | ImageBitmap): Float32Array {
  const canvas = document.createElement('canvas')
  canvas.width = INPUT_SIZE
  canvas.height = INPUT_SIZE
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) throw new Error('브라우저가 캔버스를 지원하지 않습니다.')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(image as CanvasImageSource, 0, 0, INPUT_SIZE, INPUT_SIZE)
  const { data } = ctx.getImageData(0, 0, INPUT_SIZE, INPUT_SIZE)
  const input = new Float32Array(SPAN)
  for (let i = 0, p = 0; p < INPUT_SIZE * INPUT_SIZE; i += 4, p++) {
    input[p * 3] = data[i] / 127.5 - 1
    input[p * 3 + 1] = data[i + 1] / 127.5 - 1
    input[p * 3 + 2] = data[i + 2] / 127.5 - 1
  }
  return input
}

/** 출력 텐서가 NaN이거나 합이 1이 아니어도 10구간 확률분포로 정리한다. */
function normalise(values: ArrayLike<number>): number[] {
  const clean = Array.from({ length: 10 }, (_, index) => {
    const value = values[index]
    return Number.isFinite(value) && value > 0 ? value : 0
  })
  const sum = clean.reduce((total, value) => total + value, 0)
  return sum > 0 ? clean.map((value) => value / sum) : new Array(10).fill(0.1)
}

async function runModel(engine: Engine, model: CompiledModel, input: Float32Array): Promise<number[]> {
  const tensor = new engine.core.Tensor(input, [1, INPUT_SIZE, INPUT_SIZE, 3])
  try {
    const outputs = await model.run(tensor)
    try {
      const moved = await outputs[0].moveTo('wasm')
      try {
        return normalise(moved.toTypedArray())
      } finally {
        moved.delete()
      }
    } finally {
      for (const output of outputs) output.delete()
    }
  } finally {
    tensor.delete()
  }
}

/**
 * 사진 한 장을 NIMA로 평가한다. 모델을 쓸 수 없으면 null을 돌려준다.
 * 기술 모델만 실패하면 미적 분포로 기술 점수를 대신한다(기기 앱과 같은 규칙).
 */
export async function inferNima(image: HTMLImageElement | ImageBitmap): Promise<NimaInference | null> {
  const engine = await prepareNima()
  if (!engine) return null
  try {
    const started = performance.now()
    const input = preprocess(image)
    const aesthetic = await runModel(engine, engine.aesthetic, input)
    const technical = engine.technical ? await runModel(engine, engine.technical, input) : null
    const [aestheticScore, deviation] = distribution(aesthetic)
    const [technicalScore] = technical ? distribution(technical) : [aestheticScore]
    const modelMs = Math.max(1, Math.round(performance.now() - started))
    if (!Number.isFinite(aestheticScore) || aestheticScore <= 0) return null
    return {
      source: 'MODEL',
      aesthetic: clamp(aestheticScore, 1, 10),
      technical: clamp(technicalScore, 1, 10),
      deviation: clamp(deviation, 0, 4.5),
      probabilities: aesthetic,
      modelMs,
    }
  } catch {
    return null
  }
}

export function toNimaResult(inference: NimaInference): NimaResult {
  return {
    aesthetic: inference.aesthetic,
    technical: inference.technical,
    deviation: inference.deviation,
    probabilities: inference.probabilities,
  }
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value))
}
