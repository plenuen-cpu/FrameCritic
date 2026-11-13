import { SUBJECT_TITLES, type SubjectScene } from '../lib/domain'
import { subjectWithCount } from '../lib/subject'

interface SubjectBadgeProps {
  scene: SubjectScene
  source: 'MODEL' | 'SCENE'
  ms: number
}

/**
 * 무엇을 보고 쓴 비평인지 먼저 밝힌다.
 * 탐지기로 읽은 피사체는 신뢰도와 함께, 장면은 픽셀 근거로만 읽었다고 적는다.
 */
export default function SubjectBadge({ scene, source, ms }: SubjectBadgeProps) {
  const percent = Math.round(scene.confidence * 100)
  const detected = source === 'MODEL' && scene.count > 0
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-hairline bg-panel px-4 py-3 text-[13px]">
      <span className="text-[11px] tracking-[0.18em] text-sage">식별된 피사체</span>
      <span className="text-ink">
        {detected ? (
          <>
            {subjectWithCount(scene)}
            <span className="ml-1 text-muted">(신뢰도 {percent}%)</span>
          </>
        ) : (
          <>{SUBJECT_TITLES[scene.category]} 추정</>
        )}
      </span>
      <span className="text-muted">/</span>
      <span className="text-[11px] tracking-[0.18em] text-sage">장면</span>
      <span className="text-ink">
        {scene.sceneLabel}
        <span className="ml-1 text-muted">
          {detected ? '' : '(픽셀 근거)'}
        </span>
      </span>
      <span className="ml-auto text-[11px] text-muted">
        {source === 'MODEL' ? `객체 탐지 ${Math.max(1, ms)}ms` : '객체 탐지기 미적용'}
      </span>
    </div>
  )
}
