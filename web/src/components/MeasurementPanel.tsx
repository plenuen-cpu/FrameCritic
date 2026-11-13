import { useState } from 'react'
import Histogram from './Histogram'
import {
  evidenceLines,
  measurements,
  type Assessment,
} from '../lib/critic-engine'
import type { NimaSource, ScoreResult } from '../lib/domain'
import { subjectWithCount } from '../lib/subject'

interface MeasurementPanelProps {
  assessment: Assessment
  score: ScoreResult | null
  nimaSource: NimaSource
  nimaMs: number
  visionSource: 'MODEL' | 'SCENE'
  visionMs: number
  elapsedMs: number
  size: { width: number; height: number }
}

/**
 * 접었을 때는 한 줄, 펼치면 모든 수치와 근거 문장을 보여준다.
 * 화면 본문에 없는 숫자는 전부 여기에만 남는다.
 */
export default function MeasurementPanel({
  assessment,
  score,
  nimaSource,
  nimaMs,
  visionSource,
  visionMs,
  elapsedMs,
  size,
}: MeasurementPanelProps) {
  const [open, setOpen] = useState(false)
  const groups = measurements(assessment, score, { source: nimaSource, modelMs: nimaMs })
  const evidence = evidenceLines(assessment)
  const nima = assessment.nima
  const modelScores = nimaSource === 'MODEL'
  const scene = assessment.scene

  return (
    <section className="border-t border-hairline pt-2">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="flex w-full items-center justify-between py-2 text-left text-[13px] text-muted transition-colors hover:text-ink"
      >
        <span>상세 측정 데이터 및 기술적 근거</span>
        <span className="text-sage">{open ? '접기' : '펼치기'}</span>
      </button>

      {open ? (
        <div className="space-y-6 pb-4 pt-2">
          {scene ? (
            <div>
              <h4 className="mb-2 text-xs tracking-widest text-sage">식별된 피사체와 장면</h4>
              <div className="divide-y divide-hairline/70">
                {[
                  ['피사체', subjectWithCount(scene)],
                  [
                    '피사체 신뢰도',
                    scene.source === 'MODEL' && scene.count > 0
                      ? `${Math.round(scene.confidence * 100)}%`
                      : '장면 근거로만 판단',
                  ],
                  ['장면', scene.sceneLabel],
                  ['피사체·배경 분리', `${Math.round(scene.separation * 100)}%`],
                  ['객체 탐지', visionSource === 'MODEL' ? `EfficientDet-Lite0 · ${visionMs}ms` : '미적용 (휴리스틱)'],
                  ['근거', scene.evidence],
                ].map(([label, value]) => (
                  <div key={label} className="flex items-baseline justify-between gap-4 py-1.5">
                    <span className="shrink-0 text-xs text-muted">{label}</span>
                    <span className="text-right text-xs tabular-nums text-ink">{value}</span>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
          {groups.map((group) => (
            <div key={group.title}>
              <h4 className="mb-2 text-xs tracking-widest text-sage">{group.title}</h4>
              <div className="divide-y divide-hairline/70">
                {group.rows.map(([label, value]) => (
                  <div key={label} className="flex items-baseline justify-between gap-4 py-1.5">
                    <span className="text-xs text-muted">{label}</span>
                    <span className="text-right text-xs tabular-nums text-ink">{value}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}

          <div>
            <h4 className="mb-3 text-xs tracking-widest text-sage">휘도 히스토그램</h4>
            <Histogram values={assessment.measure.histogram} />
            <div className="mt-1 flex justify-between text-[11px] text-muted">
              <span>어두움</span>
              <span>256구간</span>
              <span>밝음</span>
            </div>
          </div>

          <div>
            <h4 className="mb-3 text-xs tracking-widest text-sage">
              {modelScores ? 'NIMA 미적 확률분포' : '미적 추정 확률분포'}
            </h4>
            <div className="flex h-16 items-end gap-1">
              {nima.probabilities.map((value, index) => {
                const max = Math.max(...nima.probabilities, 0.0001)
                return (
                  <div key={index} className="flex flex-1 flex-col items-center gap-1">
                    <div
                      className="w-full rounded-sm bg-sage/80"
                      style={{ height: `${Math.max(2, (value / max) * 52)}px` }}
                    />
                    <span className="text-[10px] text-muted">{index + 1}</span>
                  </div>
                )
              })}
            </div>
          </div>

          <div>
            <h4 className="mb-2 text-xs tracking-widest text-sage">항목별 기술 근거</h4>
            <div className="space-y-3">
              {evidence.map(([title, body]) => (
                <div key={title}>
                  <p className="text-xs text-ink">{title}</p>
                  <p className="mt-0.5 text-xs leading-relaxed text-muted">{body}</p>
                </div>
              ))}
            </div>
          </div>

          <p className="text-[11px] leading-relaxed text-muted">
            분석 해상도 {size.width}×{size.height} · 소요 {elapsedMs < 1000 ? `${elapsedMs}ms` : `${(elapsedMs / 1000).toFixed(1)}초`} ·
            밝기 극단값은 의도된 표현일 수 있습니다. 평균 색만으로 조명색을 확정할 수 없고, 선명도 수치는 피사체와 해상도에
            따라 달라집니다. 사진은 서버로 전송되지 않았습니다.
          </p>
        </div>
      ) : null}
    </section>
  )
}
