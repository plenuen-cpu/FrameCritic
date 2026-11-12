#!/usr/bin/env node
/**
 * 웹 배포에 필요한 정적 자산을 준비한다.
 *
 * 1) app/src/main/assets/models/manifest.json에 적힌 NIMA TFLite 모델을 내려받고
 *    SHA-256이 manifest와 같은지 확인한 뒤 web/public/models/에 둔다.
 * 2) 두 모델의 배치 차원을 고정한다. 공개 모델은 배치 축이 -1(동적)인데,
 *    브라우저 런타임(LiteRT.js)은 입력 텐서의 차원을 모델의 shape_signature와
 *    정확히 비교하므로 -1인 채로는 추론을 거부한다. shape_signature의 -1을
 *    이미 계산된 shape 값으로 바꾸면 배치 1짜리 고정 모델이 된다(연산 그래프는 그대로).
 * 3) LiteRT.js WASM 런타임 파일을 node_modules에서 public/litert/로 복사한다.
 *
 * 사용: node scripts/prepare-assets.cjs [--force]
 */
const { createHash } = require('node:crypto')
const { mkdirSync, readFileSync, writeFileSync, existsSync, copyFileSync } = require('node:fs')
const { dirname, join } = require('node:path')

const WEB_ROOT = join(__dirname, '..')
const REPO_ROOT = join(WEB_ROOT, '..')
const MANIFEST = join(REPO_ROOT, 'app/src/main/assets/models/manifest.json')
const MODEL_DIR = join(WEB_ROOT, 'public/models')
const LITERT_DIR = join(WEB_ROOT, 'public/litert')
const CORE_WASM = join(WEB_ROOT, 'node_modules/@litertjs/core/wasm')

/** 웹에서 쓰는 모델 이름 → manifest의 파일 이름 */
const MODELS = {
  'nima_aesthetic.tflite': 'nima_aesthetic.tflite',
  'nima_technical.tflite': 'nima_technical.tflite',
}

/**
 * manifest.json을 읽을 수 없을 때(예: web만 체크아웃된 빌드 환경) 쓰는 같은 값.
 * app/src/main/assets/models/manifest.json의 항목과 같다.
 */
const FALLBACK_ENTRIES = [
  {
    name: 'nima_aesthetic.tflite',
    url: 'https://huggingface.co/litert-community/NIMA-LiteRT/resolve/5e27e756cf38d2e738ee0c7c5d6f1c43f2073038/nima_aesthetic_fp16.tflite',
    sha256: 'a5051a0fcced735682735e3e0fd58ee54c83ed664282a003f52235b3dbcb9320',
    size: 6448632,
  },
  {
    name: 'nima_technical.tflite',
    url: 'https://huggingface.co/litert-community/NIMA-LiteRT/resolve/5e27e756cf38d2e738ee0c7c5d6f1c43f2073038/nima_technical_fp16.tflite',
    sha256: '34fe245d9b6ae14f48cc1b0e276b93d18b905083dafe24f502e862dd8b81f0fa',
    size: 6449632,
  },
]

const WASM_FILES = [
  'litert_wasm_internal.js',
  'litert_wasm_internal.wasm',
  'litert_wasm_compat_internal.js',
  'litert_wasm_compat_internal.wasm',
]

// ── 아주 작은 flatbuffer 읽기 도구 ────────────────────────────────────────────
// TFLite 파일은 flatbuffer이고, 우리는 Tensor 테이블의 shape(필드 0)과
// shape_signature(필드 7)만 읽고 고치면 된다.
const i32 = (buf, pos) => buf.readInt32LE(pos)
const u32 = (buf, pos) => buf.readUInt32LE(pos)
const u16 = (buf, pos) => buf.readUInt16LE(pos)

function tableFields(buf, tablePos) {
  const vtable = tablePos - i32(buf, tablePos)
  const size = u16(buf, vtable)
  const count = (size - 4) >> 1
  const offsets = []
  for (let i = 0; i < count; i++) offsets.push(u16(buf, vtable + 4 + i * 2))
  return offsets
}

/** 테이블의 필드가 가리키는 절대 위치. 없으면 null. */
function fieldPos(buf, tablePos, index) {
  const offsets = tableFields(buf, tablePos)
  const offset = offsets[index] ?? 0
  return offset === 0 ? null : tablePos + offset
}

/** 벡터 원소 위치들(테이블 벡터) 또는 시작 위치(스칼라 벡터). */
function vectorInfo(buf, tablePos, index) {
  const pos = fieldPos(buf, tablePos, index)
  if (pos === null) return null
  const start = pos + u32(buf, pos)
  return { start, length: u32(buf, start), data: start + 4 }
}

/** 배치 축이 동적인 텐서의 shape_signature를 shape로 맞춘다. 고친 개수를 돌려준다. */
function pinBatchDimension(buf) {
  const root = u32(buf, 0)
  const subgraphs = vectorInfo(buf, root, 2)
  if (!subgraphs) return 0
  let patched = 0
  for (let s = 0; s < subgraphs.length; s++) {
    const subgraphPos = subgraphs.data + s * 4 + u32(buf, subgraphs.data + s * 4)
    const tensors = vectorInfo(buf, subgraphPos, 0)
    if (!tensors) continue
    for (let t = 0; t < tensors.length; t++) {
      const tensorPos = tensors.data + t * 4 + u32(buf, tensors.data + t * 4)
      const shape = vectorInfo(buf, tensorPos, 0)
      const signature = vectorInfo(buf, tensorPos, 7)
      if (!shape || !signature || shape.length !== signature.length) continue
      for (let k = 0; k < signature.length; k++) {
        const sigValue = i32(buf, signature.data + k * 4)
        const shapeValue = i32(buf, shape.data + k * 4)
        if (sigValue < 0 && shapeValue > 0) {
          buf.writeInt32LE(shapeValue, signature.data + k * 4)
          patched++
        }
      }
    }
  }
  return patched
}

/** 파일 안 어딘가에 남은 동적 텐서(-1)가 있는지 확인한다. */
function hasDynamicTensors(buf) {
  const root = u32(buf, 0)
  const subgraphs = vectorInfo(buf, root, 2)
  if (!subgraphs) return false
  for (let s = 0; s < subgraphs.length; s++) {
    const subgraphPos = subgraphs.data + s * 4 + u32(buf, subgraphs.data + s * 4)
    const tensors = vectorInfo(buf, subgraphPos, 0)
    if (!tensors) continue
    for (let t = 0; t < tensors.length; t++) {
      const tensorPos = tensors.data + t * 4 + u32(buf, tensors.data + t * 4)
      const signature = vectorInfo(buf, tensorPos, 7)
      if (!signature) continue
      for (let k = 0; k < signature.length; k++) {
        if (i32(buf, signature.data + k * 4) < 0) return true
      }
    }
  }
  return false
}

// ── 실행 ────────────────────────────────────────────────────────────────────
function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

async function fetchModel(entry, target, force) {
  if (!force && existsSync(target)) {
    const existing = readFileSync(target)
    if (sha256(existing) === entry.sha256) {
      if (!hasDynamicTensors(existing)) {
        console.log(`· ${target} 이미 준비됨 (배치 고정 완료)`)
        return
      }
    }
  }
  console.log(`↓ ${entry.url}`)
  let original = null
  let lastError = null
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const response = await fetch(entry.url, { redirect: 'follow' })
      if (!response.ok) throw new Error(`${entry.url} → HTTP ${response.status}`)
      original = Buffer.from(await response.arrayBuffer())
      break
    } catch (error) {
      lastError = error
      if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, 1000 * attempt))
    }
  }
  if (!original) throw lastError
  const digest = sha256(original)
  if (original.length !== entry.size || digest !== entry.sha256) {
    throw new Error(`무결성 검사 실패: ${entry.name} (${original.length} bytes, ${digest})`)
  }
  const patched = Buffer.from(original)
  const pinned = pinBatchDimension(patched)
  if (hasDynamicTensors(patched)) throw new Error(`배치 차원을 고정하지 못했습니다: ${entry.name}`)
  writeFileSync(target, patched)
  console.log(`✓ ${target} · ${patched.length} bytes · 배치 고정 ${pinned}건 · sha256 ${digest}`)
}

function copyWasm(force) {
  mkdirSync(LITERT_DIR, { recursive: true })
  for (const file of WASM_FILES) {
    const from = join(CORE_WASM, file)
    const to = join(LITERT_DIR, file)
    if (!existsSync(from)) throw new Error(`LiteRT 런타임 파일이 없습니다: ${from} (npm install 필요)`)
    if (!force && existsSync(to) && readFileSync(to).length === readFileSync(from).length) continue
    copyFileSync(from, to)
    console.log(`✓ public/litert/${file}`)
  }
}

async function main() {
  const force = process.argv.includes('--force')
  let manifest = []
  try {
    manifest = JSON.parse(readFileSync(MANIFEST, 'utf8'))
  } catch {
    console.log('· manifest.json을 읽을 수 없어 스크립트에 적힌 모델 정보를 씁니다')
  }
  mkdirSync(MODEL_DIR, { recursive: true })
  for (const [targetName, manifestName] of Object.entries(MODELS)) {
    const entry =
      manifest.find((item) => item.name === manifestName) ?? FALLBACK_ENTRIES.find((item) => item.name === manifestName)
    if (!entry) throw new Error(`manifest.json에 ${manifestName} 항목이 없습니다`)
    await fetchModel(entry, join(MODEL_DIR, targetName), force)
  }
  copyWasm(force)
}

main().catch((error) => {
  console.error(`자산 준비 실패: ${error.message}`)
  process.exit(1)
})
