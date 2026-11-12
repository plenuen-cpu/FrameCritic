import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import ScoreHero from './components/ScoreHero'
import MeasurementPanel from './components/MeasurementPanel'
import { analyzeImage, loadImage, type Analysis } from './lib/analyzer'
import { nimaStatus, prepareNima, type NimaStatus } from './lib/nima'
import {
  SECTION_TITLES,
  assess,
  measurements,
  sections,
  verdict,
  type Assessment,
} from './lib/critic-engine'
import {
  DEPTH_BY_KEY,
  LEVEL_BY_KEY,
  PERSONA_BY_KEY,
  evaluate,
  type DepthKey,
  type ScoreResult,
} from './lib/domain'

interface Result {
  url: string
  image: HTMLImageElement
  analysis: Analysis
  assessment: Assessment
  score: ScoreResult | null
  critique: Record<string, string>
  verdict: string
}

type Stage = 'empty' | 'loaded' | 'working' | 'done'

const PERSONA = PERSONA_BY_KEY.BALANCED
const LEVEL = LEVEL_BY_KEY.BALANCED

/** 첫 화면에 보여 주는 NIMA 모델 준비 상태. */
const MODEL_LABEL: Record<NimaStatus, string> = {
  idle: '미적 점수는 브라우저에서 돌리는 NIMA 신경망이 계산합니다',
  loading: 'NIMA 신경망을 준비하는 중입니다 (모델 13MB + 실행기, 한 번만 내려받습니다)',
  ready: 'NIMA 신경망 준비 완료 · 사진은 기기 밖으로 나가지 않습니다',
  unavailable: 'NIMA 모델을 불러오지 못해 측정값 기반 추정으로 분석합니다',
}

export default function App() {
  const [stage, setStage] = useState<Stage>('empty')
  const [depthKey, setDepthKey] = useState<DepthKey>('STANDARD')
  const [image, setImage] = useState<{ image: HTMLImageElement; url: string } | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const [copied, setCopied] = useState(false)
  const [modelState, setModelState] = useState<NimaStatus>(() => nimaStatus())
  const inputRef = useRef<HTMLInputElement | null>(null)

  /** NIMA 모델은 처음 화면이 뜰 때 미리 받아 둔다. 사진을 고른 뒤 기다리지 않게 하기 위해서다. */
  useEffect(() => {
    setModelState('loading')
    void prepareNima().then(() => setModelState(nimaStatus()))
  }, [])

  const fileFromEvent = (event: React.DragEvent<HTMLDivElement>) => {
    const file = event.dataTransfer.files?.[0]
    if (file) void openFile(file)
  }

  useEffect(() => {
    return () => {
      if (image) URL.revokeObjectURL(image.url)
    }
  }, [image])

  const openFile = useCallback(async (file: File) => {
    setError(null)
    setResult(null)
    setCopied(false)
    if (!file.type.startsWith('image/')) {
      setError('사진 파일을 선택해 주세요. (JPG · PNG · WebP · HEIC)')
      return
    }
    try {
      const loaded = await loadImage(file)
      setImage((previous) => {
        if (previous) URL.revokeObjectURL(previous.url)
        return loaded
      })
      setStage('loaded')
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '사진을 열지 못했습니다.')
      setStage('empty')
    }
  }, [])

  /** 사진을 재고, 그 근거로 열 개의 비평 문단을 만든다. 모두 브라우저 안에서 끝난다. */
  const run = useCallback(
    (depth: DepthKey, target?: { image: HTMLImageElement; url: string }) => {
      const source = target ?? image
      if (!source) return
      setStage('working')
      setError(null)
      window.setTimeout(() => {
        void (async () => {
          try {
            const analysis = await analyzeImage(source.image)
            const assessment = assess(analysis.nima, analysis.measure, analysis.subjects)
            const score = evaluate(assessment.nima.aesthetic, assessment.evidence, PERSONA, LEVEL)
            const depthConfig = DEPTH_BY_KEY[depth]
            const critique = sections(assessment, {
              persona: PERSONA,
              level: LEVEL,
              tone: 'MENTOR',
              depth: depthConfig,
              score,
            })
            const line = verdict(assessment, score?.adjusted ?? 0)
            setResult({
              url: source.url,
              image: source.image,
              analysis,
              assessment,
              score,
              critique,
              verdict: line,
            })
            setModelState(analysis.nimaSource === 'MODEL' ? 'ready' : nimaStatus())
            setStage('done')
          } catch (caught) {
            setError(caught instanceof Error ? caught.message : '분석 중 문제가 생겼습니다.')
            setStage('loaded')
          }
        })()
      }, 40)
    },
    [image],
  )

  const changeDepth = (next: DepthKey) => {
    setDepthKey(next)
    if (image && (stage === 'done' || stage === 'loaded')) run(next)
  }

  const reportText = useMemo(() => {
    if (!result) return ''
    const lines: string[] = ['FrameCritic · 사진 비평']
    if (result.score) {
      lines.push(`종합 점수 ${result.score.adjusted.toFixed(1)} / 10`)
      lines.push(result.verdict)
    }
    for (const title of SECTION_TITLES) {
      const body = result.critique[title]
      if (!body) continue
      lines.push('')
      lines.push(title)
      lines.push(body)
    }
    lines.push('')
    lines.push('── 상세 측정 데이터 및 기술적 근거 ──')
    for (const group of measurements(result.assessment, result.score, {
      source: result.analysis.nimaSource,
      modelMs: result.analysis.nimaMs,
    })) {
      lines.push(`[${group.title}]`)
      for (const [label, value] of group.rows) lines.push(`${label}: ${value}`)
    }
    lines.push('')
    lines.push('이 리포트는 사진의 픽셀에서 계산한 측정값으로 만들었으며, 객관적 예술 평가로 검증된 척도가 아닙니다.')
    return lines.join('\n')
  }, [result])

  const copy = async () => {
    if (!reportText) return
    try {
      await navigator.clipboard.writeText(reportText)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2200)
    } catch {
      setError('클립보드에 복사하지 못했습니다. 브라우저 권한을 확인해 주세요.')
    }
  }

  return (
    <div className="min-h-screen">
      <header className="mx-auto flex w-full max-w-page items-start justify-between gap-4 px-5 pb-6 pt-9">
        <div>
          <h1 className="font-display text-2xl font-medium tracking-[0.18em] text-ink">FRAMECRITIC</h1>
          <p className="mt-1 text-xs text-muted">사진을 보고, 근거를 읽다</p>
          <p className="mt-1 text-[11px] text-muted sm:hidden">사진은 서버로 전송되지 않습니다</p>
        </div>
        <span className="mt-1 hidden shrink-0 rounded-full border border-hairline px-3 py-1 text-[11px] text-muted sm:inline-block">
          사진은 서버로 전송되지 않습니다
        </span>
      </header>

      <main className="mx-auto w-full max-w-page space-y-6 px-5 pb-24">
        {!image ? (
          <div
            role="button"
            tabIndex={0}
            onClick={() => inputRef.current?.click()}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') inputRef.current?.click()
            }}
            onDragOver={(event) => {
              event.preventDefault()
              setDragging(true)
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(event) => {
              event.preventDefault()
              setDragging(false)
              fileFromEvent(event)
            }}
            className={`flex h-[320px] cursor-pointer flex-col items-center justify-center gap-3 rounded-2xl border transition-colors ${
              dragging ? 'border-sage bg-sage/5' : 'border-hairline bg-panel hover:border-sage/60'
            }`}
          >
            <span className="text-4xl text-sage">+</span>
            <p className="text-base text-ink">사진을 여기에 떨어뜨리거나 눌러서 선택</p>
            <p className="text-xs text-muted">빛과 색, 구도, 질감을 한 장에서 읽어 냅니다</p>
            <p className="text-[11px] text-muted">분석은 이 브라우저 안에서만 이루어집니다</p>
            <p className="text-[11px] text-sage/80">{MODEL_LABEL[modelState]}</p>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="relative overflow-hidden rounded-2xl border border-hairline bg-panel">
              <img src={image.url} alt="분석할 사진" className="w-full object-contain" />
              {result?.analysis.salienceMap ? (
                <div
                  className="pointer-events-none absolute border border-dashed border-sage/60"
                  style={{
                    left: `${result.analysis.salienceMap.x * 100}%`,
                    top: `${result.analysis.salienceMap.y * 100}%`,
                    width: `${result.analysis.salienceMap.w * 100}%`,
                    height: `${result.analysis.salienceMap.h * 100}%`,
                  }}
                >
                  <span className="absolute -top-5 left-0 bg-sage/90 px-1.5 py-0.5 text-[10px] text-viewfinder">
                    주요 영역
                  </span>
                </div>
              ) : null}
            </div>
            <div className="flex items-center justify-between">
              <button type="button" className="text-[13px] text-muted hover:text-ink" onClick={() => inputRef.current?.click()}>
                다른 사진 선택
              </button>
              <span className="text-[11px] text-muted">
                {result ? `${result.analysis.width}×${result.analysis.height} · ${result.analysis.elapsedMs}ms` : '아직 분석하지 않았습니다'}
              </span>
            </div>
          </div>
        )}

        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.[0]
            if (file) void openFile(file)
            event.target.value = ''
          }}
        />

        {image ? (
          <div className="space-y-3">
            <button
              type="button"
              className="primary-button"
              disabled={stage === 'working'}
              onClick={() => run(depthKey)}
            >
              {stage === 'working' ? '사진을 읽는 중…' : result ? '다시 분석하기' : '사진 분석하기'}
            </button>

            <div className="flex items-center justify-center gap-2 text-[12px] text-muted">
              <span>비평 깊이</span>
              <div className="flex overflow-hidden rounded-full border border-hairline">
                {(
                  [
                    ['STANDARD', '기본'],
                    ['EXPERT', '깊게'],
                  ] as [DepthKey, string][]
                ).map(([key, label]) => (
                  <button
                    key={key}
                    type="button"
                    onClick={() => changeDepth(key)}
                    className={`px-3 py-1 transition-colors ${
                      depthKey === key ? 'bg-sage text-viewfinder' : 'text-muted hover:text-ink'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
          </div>
        ) : null}

        {stage === 'working' ? (
          <div className="space-y-2">
            <div className="h-[3px] w-full overflow-hidden rounded-full bg-hairline">
              <div className="h-full w-1/2 animate-pulse rounded-full bg-sage" />
            </div>
            <p className="text-xs text-muted">픽셀을 재고, NIMA 신경망으로 미적 점수를 계산한 뒤 근거로 문장을 쓰는 중입니다.</p>
          </div>
        ) : null}

        {error ? <p className="text-[13px] text-red-400">{error}</p> : null}

        {result && stage !== 'working' ? (
          <div className="space-y-6">
            <ScoreHero score={result.score?.adjusted ?? null} verdict={result.verdict} />

            <div className="space-y-3">
              {SECTION_TITLES.map((title) => (
                <article key={title} className="card p-5">
                  <h3 className="mb-2 text-[11px] tracking-[0.18em] text-sage">{title}</h3>
                  <p className="critique-body text-[15px] leading-[1.75] text-ink">{result.critique[title]}</p>
                </article>
              ))}
            </div>

            <MeasurementPanel
              assessment={result.assessment}
              score={result.score}
              nimaSource={result.analysis.nimaSource}
              nimaMs={result.analysis.nimaMs}
              elapsedMs={result.analysis.elapsedMs}
              size={{ width: result.analysis.width, height: result.analysis.height }}
            />

            <div className="flex flex-wrap gap-2">
              <button type="button" className="ghost-button" onClick={() => void copy()}>
                {copied ? '복사했습니다' : '비평 복사하기'}
              </button>
            </div>
          </div>
        ) : null}

        <footer className="space-y-2 border-t border-hairline pt-6 text-[11px] leading-relaxed text-muted">
          <p>
            FrameCritic은 사진 한 장의 측정값과 결정론적 문장 규칙으로 비평을 씁니다. 언어 모델을 쓰지 않으며, 측정되지 않은
            피사체·의도·감정을 사실처럼 말하지 않습니다.
          </p>
          <p>
            미적·기술 점수는 idealo의 NIMA(Neural Image Assessment) MobileNet 모델을 이 브라우저에서 직접 돌려 계산합니다. 모델은 처음
            한 번만 내려받아 브라우저에 캐시되고, 사진은 기기 밖으로 나가지 않습니다.
          </p>
          <p>
            모델을 불러오지 못하는 브라우저에서는 캔버스 측정값에서 계산한 추정으로 대신하며, 상세 측정 부록에 그 사실을 적습니다. 비평
            본문은 두 경우 모두 기기 앱과 같은 결정론적 문장 규칙으로 씁니다.
          </p>
        </footer>
      </main>
    </div>
  )
}
