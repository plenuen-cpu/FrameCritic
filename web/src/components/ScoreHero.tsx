interface ScoreHeroProps {
  score: number | null
  verdict: string
}

export default function ScoreHero({ score, verdict }: ScoreHeroProps) {
  return (
    <section className="flex flex-col gap-2 border-b border-hairline pb-6">
      <div className="flex items-end gap-2">
        <span className="font-display text-[64px] font-light leading-none tracking-tight text-ink">
          {score === null || !Number.isFinite(score) ? '—' : score.toFixed(1)}
        </span>
        <span className="pb-2 text-sm text-muted">/ 10</span>
      </div>
      <p className="text-lg text-ink">{verdict}</p>
      <p className="text-xs leading-relaxed text-muted">
        사진 한 장을 10점 만점으로 본 경험적 점수입니다. 객관적 예술 평가로 검증된 척도가 아니며,
        같은 사진이면 같은 점수와 같은 문장이 나옵니다.
      </p>
    </section>
  )
}
