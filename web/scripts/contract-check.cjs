/**
 * 비평 본문 계약 검사.
 *
 * 화면에 보이는 열 개 항목에는 숫자도, 계측 어휘도 나오면 안 된다. 이 스크립트는 합성한 픽셀에서
 * 측정 → 근거 → 문장을 실제 코드로 끝까지 돌리고, 모든 문장 조합을 검사한다. CI가 같은 검사를
 * 수행하므로, 문장을 고치다 계약을 깨면 빌드가 실패한다.
 *
 * 사용:
 *   npx tsc src/lib/domain.ts src/lib/critic-engine.ts src/lib/analyzer.ts \
 *     --outDir .verify --module commonjs --target es2020 --moduleResolution node --skipLibCheck
 *   echo '{"type":"commonjs"}' > .verify/package.json
 *   node scripts/contract-check.cjs
 */
const path = require('path')

const OUT = path.join(__dirname, '..', '.verify')
const engine = require(path.join(OUT, 'critic-engine.js'))
const domain = require(path.join(OUT, 'domain.js'))
const analyzer = require(path.join(OUT, 'analyzer.js'))

const BANNED = [
  '관찰되었',
  '관측',
  'Laplacian',
  'COCO',
  '휘도',
  '255',
  '검출',
  '측정',
  'NIMA',
  '히스토그램',
  '클리핑',
  '분산',
]

function buildImage(kind, width = 320, height = 240) {
  const data = new Uint8ClampedArray(width * height * 4)
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4
      let r = 128
      let g = 128
      let b = 128
      if (kind === 'gradient') {
        const v = Math.round((x / width) * 255)
        r = v
        g = v
        b = v
        if (x % 40 < 2 && y % 40 < 2) {
          r = 255
          g = 255
          b = 255
        }
      } else if (kind === 'sky') {
        if (y < height * 0.55) {
          r = 90
          g = 140
          b = 210
        } else {
          r = 205
          g = 150
          b = 95
        }
      } else if (kind === 'blown') {
        r = 252
        g = 252
        b = 252
      }
      data[i] = r
      data[i + 1] = g
      data[i + 2] = b
      data[i + 3] = 255
    }
  }
  return data
}

/** 피사체·장면 조합. 화면 문장이 카테고리 어휘를 실제로 쓰는지 확인한다. */
const SCENES = [
  {
    name: '인물',
    scene: {
      category: domain.SubjectCategory.PERSON,
      subject: '인물',
      count: 1,
      confidence: 0.93,
      scene: domain.SceneContext.INDOOR,
      sceneLabel: '실내',
      separation: 0.4,
      source: 'MODEL',
      evidence: '인물 한 명 (신뢰도 93%)',
    },
    expect: '인물',
  },
  {
    name: '반려견',
    scene: {
      category: domain.SubjectCategory.ANIMAL,
      subject: '반려견',
      count: 2,
      confidence: 0.81,
      scene: domain.SceneContext.NATURE,
      sceneLabel: '들판',
      separation: 0.2,
      source: 'MODEL',
      evidence: '반려견 여러 마리 (신뢰도 81%)',
    },
    expect: '반려견',
  },
  {
    name: '자동차',
    scene: {
      category: domain.SubjectCategory.VEHICLE,
      subject: '자동차',
      count: 1,
      confidence: 0.77,
      scene: domain.SceneContext.URBAN,
      sceneLabel: '도심',
      separation: 0.5,
      source: 'MODEL',
      evidence: '자동차 (신뢰도 77%)',
    },
    expect: '자동차',
  },
  {
    name: '바다',
    scene: {
      category: domain.SubjectCategory.LANDSCAPE_WATER,
      subject: '바다',
      count: 0,
      confidence: 0,
      scene: domain.SceneContext.WATER,
      sceneLabel: '바다',
      separation: 0,
      source: 'SCENE',
      evidence: '물가 풍경',
    },
    expect: '바다',
  },
  {
    name: '산',
    scene: {
      category: domain.SubjectCategory.LANDSCAPE_NATURE,
      subject: '산',
      count: 0,
      confidence: 0,
      scene: domain.SceneContext.NATURE,
      sceneLabel: '산',
      separation: 0,
      source: 'SCENE',
      evidence: '자연 풍경',
    },
    expect: '산',
  },
  {
    name: '그릇',
    scene: {
      category: domain.SubjectCategory.OBJECT_STILL,
      subject: '그릇',
      count: 1,
      confidence: 0.55,
      scene: domain.SceneContext.INDOOR,
      sceneLabel: '실내',
      separation: 0.3,
      source: 'MODEL',
      evidence: '그릇 (신뢰도 55%)',
    },
    expect: '그릇',
  },
]

let failures = 0
let checked = 0
function fail(label, detail) {
  failures++
  if (failures <= 20) console.log('FAIL', label, detail === undefined ? '' : detail)
}

for (const kind of ['flat', 'gradient', 'sky', 'blown']) {
  const width = 320
  const height = 240
  const core = analyzer.measurePixels(buildImage(kind, width, height), width, height)

  for (const sample of [{ name: '무피사체', scene: null, expect: null }, ...SCENES]) {
  const scene = sample.scene
  const assessment = engine.assess(core.nima, core.measure, core.subjects, scene)

  for (const persona of domain.PERSONAS) {
    for (const level of domain.LEVELS) {
      const score = domain.evaluate(assessment.nima.aesthetic, assessment.evidence, persona, level)
      for (const tone of domain.TONES) {
        for (const depth of domain.DEPTHS) {
          const out = engine.sections(assessment, { persona, level, tone: tone.key, depth, score })
          if (Object.keys(out).length !== engine.SECTION_TITLES.length) {
            fail(`${kind} 섹션 수`, Object.keys(out).length)
          }
          for (const title of engine.SECTION_TITLES) {
            const body = out[title]
            checked++
            if (!body || body.length === 0) fail(`${kind} ${title} 비어 있음`)
            if (body !== body.trim()) fail(`${kind} ${title} 앞뒤 공백`)
            if (body.length >= 1500) fail(`${kind} ${title} 길이`, body.length)
            if (/[0-9]/.test(body)) fail(`${kind} ${title} 숫자`, body)
            for (const word of BANNED) {
              if (body.includes(word)) fail(`${kind} ${title} '${word}'`, body)
            }
          }
          if (score && (score.adjusted < 1 || score.adjusted > 10)) fail(`${kind} 점수 범위`, score.adjusted)
        }
      }
    }
  }
    if (sample.expect) {
      const out = engine.sections(assessment, {
        persona: domain.PERSONA_BY_KEY.BALANCED,
        level: domain.LEVEL_BY_KEY.BALANCED,
        tone: 'MENTOR',
        depth: domain.DEPTH_BY_KEY.STANDARD,
          score: domain.evaluate(
          assessment.nima.aesthetic,
          assessment.evidence,
          domain.PERSONA_BY_KEY.BALANCED,
          domain.LEVEL_BY_KEY.BALANCED,
        ),
      })
      const hit = engine.SECTION_TITLES.filter((title) => out[title].includes(sample.expect)).length
      if (hit < 6) fail(`${kind} ${sample.name} 피사체 언급`, `${hit}/10 항목`)
    }
  }

  // 가장 긴 항목과 가장 짧은 항목을 남겨, 문장을 늘리거나 줄일 때 감이 잡히게 한다.
  const plain = engine.assess(core.nima, core.measure, core.subjects)
  const summaryOut = engine.sections(plain, {
    persona: domain.PERSONA_BY_KEY.BALANCED,
    level: domain.LEVEL_BY_KEY.BALANCED,
    tone: 'MENTOR',
    depth: domain.DEPTH_BY_KEY.STANDARD,
    score: domain.evaluate(
      plain.nima.aesthetic,
      plain.evidence,
      domain.PERSONA_BY_KEY.BALANCED,
      domain.LEVEL_BY_KEY.BALANCED,
    ),
  })
  const lengths = engine.SECTION_TITLES.map((title) => `${title}:${summaryOut[title].length}`)
  console.log(`${kind} (밝기 ${core.measure.luminance.toFixed(0)}, 점수 ${domain
    .evaluate(plain.nima.aesthetic, plain.evidence, domain.PERSONA_BY_KEY.BALANCED, domain.LEVEL_BY_KEY.BALANCED)
    .adjusted.toFixed(2)}) ${lengths.join(' ')}`)
}

console.log(`checked ${checked} sections, failures ${failures}`)
process.exit(failures === 0 ? 0 : 1)
