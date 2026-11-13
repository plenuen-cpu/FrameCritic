/**
 * 피사체 인식 단위 검증.
 *
 * 탐지 결과 목록을 손으로 만들어, 인물 우선 판정·가축 오탐 방지·2인 카운트가
 * 실제로 그렇게 나오는지 확인한다. 브라우저나 모델 없이 subject.ts의 판정만 돌린다.
 *
 * 사용:
 *   npx tsc src/lib/domain.ts src/lib/subject.ts --outDir .verify --module commonjs \
 *     --target es2020 --moduleResolution node --skipLibCheck
 *   node scripts/subject-check.cjs
 */
const path = require('path')

const OUT = path.join(__dirname, '..', '.verify')
const domain = require(path.join(OUT, 'domain.js'))
const subject = require(path.join(OUT, 'subject.js'))

let failures = 0
let checked = 0
function check(name, condition, detail) {
  checked++
  if (!condition) {
    failures++
    console.log('FAIL', name, detail === undefined ? '' : detail)
  }
}

function cue(r, g, b, texture) {
  const luminance = 0.299 * r + 0.587 * g + 0.114 * b
  return { r, g, b, luminance, texture, green: 0, blue: 0, bright: luminance >= 200 ? 0.4 : 0 }
}
/** 하늘도 물도 초록도 아닌 닫힌 공간. */
const indoor = {
  top: cue(70, 66, 62, 18),
  middle: cue(74, 70, 66, 22),
  bottom: cue(60, 56, 52, 20),
  hasHorizon: false,
}
/** 초록이 넓은 야외. */
const nature = {
  top: cue(120, 150, 110, 26),
  middle: { ...cue(60, 120, 60, 38), green: 0.55 },
  bottom: { ...cue(70, 110, 55, 34), green: 0.5 },
  hasHorizon: false,
}
/** 하늘이 밝고 아래가 푸른 물가. */
const water = {
  top: { ...cue(150, 190, 235, 22), bright: 0.5, blue: 0.6 },
  middle: cue(90, 120, 140, 20),
  bottom: { ...cue(40, 70, 120, 18), blue: 0.7 },
  hasHorizon: true,
}
/** 회색 건물과 직선이 많은 도심. */
const urban = {
  top: cue(120, 120, 122, 30),
  middle: cue(110, 110, 112, 34),
  bottom: cue(96, 96, 98, 30),
  hasHorizon: false,
}

const box = (label, score, cx, cy, w, h) => ({ label, score, cx, cy, w, h })

function read(detections, cues, saturation = 0.3, luminance = 110, lineCount = 0, source = 'MODEL') {
  return subject.readSubject(detections, cues, saturation, luminance, lineCount, 0.2, source)
}

// 1. 실내에서 사람 둘 옆에 '소' 오탐 상자가 크게 잡혀도 인물 둘로 읽는다.
{
  const scene = read(
    [
      box('person', 0.88, 0.3, 0.55, 0.3, 0.6),
      box('person', 0.62, 0.66, 0.55, 0.28, 0.58),
      box('cow', 0.46, 0.5, 0.5, 0.6, 0.5),
    ],
    indoor,
  )
  check('두 사람 + 소 오탐 → 인물', scene.category === domain.SubjectCategory.PERSON, scene.category)
  check('두 사람 + 소 오탐 → 2명', scene.count === 2, scene.count)
  check('두 사람 배지 표기', subject.subjectWithCount(scene) === '인물 2명', subject.subjectWithCount(scene))
  check('두 사람 근거 표기', scene.evidence.includes('인물 2명'), scene.evidence)
}

// 2. 사람 상자가 작아도, 면적이 큰 소 상자에 주인공을 빼앗기지 않는다.
{
  const scene = read(
    [box('person', 0.72, 0.46, 0.5, 0.18, 0.3), box('cow', 0.44, 0.5, 0.5, 0.8, 0.9)],
    indoor,
  )
  check('큰 소 상자 vs 작은 인물 → 인물', scene.category === domain.SubjectCategory.PERSON, scene.category)
  check('큰 소 상자 vs 작은 인물 → count 1', scene.count === 1, scene.count)
}

// 3. 야외 들판의 소는 이름을 얻는다.
{
  const scene = read([box('cow', 0.66, 0.5, 0.55, 0.6, 0.5)], nature)
  check('야외 소 0.66 → 소', scene.category === domain.SubjectCategory.ANIMAL && scene.subject === '소', scene.subject)
}

// 4. 실내에서 낮은 신뢰도의 소는 이름을 얻지 못한다.
{
  const scene = read([box('cow', 0.62, 0.5, 0.55, 0.6, 0.5)], indoor)
  check('실내 소 0.62 → 소 아님', scene.category !== domain.SubjectCategory.ANIMAL, scene.subject)
}

// 5. 반려견·고양이는 일상 신뢰도에서 그대로 인정한다.
{
  const dogs = read([box('dog', 0.66, 0.4, 0.55, 0.3, 0.4), box('dog', 0.47, 0.68, 0.55, 0.28, 0.38)], indoor)
  check('반려견 두 마리', dogs.category === domain.SubjectCategory.ANIMAL && dogs.count === 2, dogs.count)
  const cat = read([box('cat', 0.58, 0.5, 0.5, 0.6, 0.7)], indoor)
  check('고양이', cat.subject === '고양이', cat.subject)
}

// 6. 반려견 사진에 약하게 잡힌 사람은 주인공을 가져가지 않는다.
{
  const scene = read([box('dog', 0.66, 0.5, 0.55, 0.5, 0.5), box('person', 0.32, 0.8, 0.4, 0.15, 0.3)], indoor)
  check('반려견 사진의 약한 인물 → 반려견', scene.subject === '반려견', scene.subject)
}

// 7. 인물 셋은 '여러 명'으로 묶는다.
{
  const scene = read(
    [
      box('person', 0.9, 0.25, 0.55, 0.24, 0.6),
      box('person', 0.66, 0.5, 0.55, 0.24, 0.6),
      box('person', 0.58, 0.75, 0.55, 0.24, 0.6),
    ],
    indoor,
  )
  check('인물 셋 count', scene.count === 3, scene.count)
  check('인물 셋 표기', subject.subjectWithCount(scene) === '인물 여러 명', subject.subjectWithCount(scene))
}

// 8. 물가 풍경에서 물결을 침대로 부르면 장면을 믿는다.
{
  const scene = read([box('bed', 0.61, 0.5, 0.64, 0.5, 0.4)], water)
  check('물가의 침대 오탐 → 풍경', scene.category === domain.SubjectCategory.LANDSCAPE_WATER, scene.category)
}

// 9. 도심의 자동차는 탈것으로 읽는다.
{
  const scene = read([box('car', 0.72, 0.5, 0.55, 0.5, 0.4)], urban)
  check('도심 자동차', scene.category === domain.SubjectCategory.VEHICLE, scene.category)
}

// 10. 탐지가 비면 장면으로 남고, 이름을 단정하지 않는다.
{
  const scene = read([], indoor, 0.3, 110, 0, 'SCENE')
  check('빈 탐지 → 장면', scene.count === 0 && scene.source === 'SCENE', scene.category)
}

console.log(`checked ${checked} subject cases, failures ${failures}`)
process.exit(failures === 0 ? 0 : 1)
