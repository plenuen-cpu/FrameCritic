import { useEffect, useRef } from 'react'

interface HistogramProps {
  values: number[]
}

/** 256구간 휘도 히스토그램. 막대 높이는 가장 높은 구간에 맞춰 정규화한다. */
export default function Histogram({ values }: HistogramProps) {
  const ref = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return
    const bars = values.slice(0, 256)
    const width = canvas.clientWidth || 320
    const height = canvas.clientHeight || 72
    const dpr = window.devicePixelRatio || 1
    canvas.width = Math.max(1, Math.round(width * dpr))
    canvas.height = Math.max(1, Math.round(height * dpr))
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, width, height)
    const max = Math.max(1, ...bars)
    const step = width / 256
    ctx.fillStyle = 'rgba(196, 218, 168, 0.82)'
    bars.forEach((value, index) => {
      const barHeight = (value / max) * (height - 4)
      ctx.fillRect(index * step, height - barHeight, Math.max(step - 0.4, 0.6), barHeight)
    })
  }, [values])

  return <canvas ref={ref} className="h-20 w-full" aria-label="휘도 히스토그램" role="img" />
}
