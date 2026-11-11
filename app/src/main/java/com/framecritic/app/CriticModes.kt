package com.framecritic.app

import kotlin.math.roundToInt

/** The NIMA score is always raw; critique severity never changes it. */
enum class Severity(val label: String) {
    MILD("Mild"), BALANCED("Balanced"), HARSH("Harsh")
}
enum class ReportDepth(val label: String) {
    QUICK("Quick"), STANDARD("Standard"), EXPERT("Expert")
}
data class CriticResult(
    val rawAesthetic: Double?,
    val rawTechnical: Double?,
    val criterionScore: Double?,
    val headline: String,
    val findings: List<String>,
    val recommendations: List<String>,
    val disclosure: String
)
object CriticModes {
    fun evaluate(
        metrics: ImageMetrics,
        scores: NimaEngine.Scores?,
        severity: Severity,
        depth: ReportDepth,
        persona: String
    ): CriticResult {
        val bright = metrics.brightClip
        val dark = metrics.darkClip
        val edges = metrics.edgeContrast
        val findings = mutableListOf<String>()
        val advice = mutableListOf<String>()
        val clipLimit = when (severity) {
            Severity.MILD -> .12
            Severity.BALANCED -> .06
            Severity.HARSH -> .025
        }
        val darkLimit = when (severity) {
            Severity.MILD -> .25
            Severity.BALANCED -> .15
            Severity.HARSH -> .08
        }
        if (bright > clipLimit) {
            findings += "밝은 영역 손실 추정: %.1f%% (평가 기준 %.1f%%)".format(bright * 100, clipLimit * 100)
            advice += "하이라이트를 낮추고 밝은 부분의 디테일을 확인하세요."
        }
        if (dark > darkLimit) {
            findings += "어두운 영역 손실 추정: %.1f%% (평가 기준 %.1f%%)".format(dark * 100, darkLimit * 100)
            advice += "암부를 올리기 전 의도적인 실루엣인지 판단하세요."
        }
        if (edges < when(severity) { Severity.MILD -> .015; Severity.BALANCED -> .025; Severity.HARSH -> .04 }) {
            findings += "국소 경계 대비가 평가 기준보다 낮습니다."
            advice += "주 피사체의 초점과 흔들림을 원본 확대 화면에서 확인하세요."
        }
        if (findings.isEmpty()) findings += "선택한 강도의 픽셀 기반 경고 기준을 초과한 항목이 없습니다."
        val emphasis = when (persona) {
            "Bresson" -> "구도와 순간의 관계"
            "Adams" -> "계조와 톤의 정교함"
            "Leiter" -> "색채와 프레임의 중첩"
            else -> "명암과 형태의 리듬"
        }
        val title = when (severity) {
            Severity.MILD -> "장점을 유지하며 개선할 부분을 살펴봅니다."
            Severity.BALANCED -> "장점과 결함을 동일한 기준으로 점검합니다."
            Severity.HARSH -> "엄격한 기술 기준으로 결함을 우선 점검합니다."
        }
        val all = if (depth == ReportDepth.QUICK) findings.take(2) else findings
        val recommendations = if (depth == ReportDepth.QUICK) advice.take(1) else advice
        val extra = if (depth == ReportDepth.EXPERT) listOf(
            "평균 휘도: %.3f, 채도: %.3f, 경계 대비: %.4f".format(metrics.brightness, metrics.saturation, metrics.edgeContrast),
            "선택한 사진가의 평가 초점: $emphasis. 현재 엔진은 장면 의미를 인식하지 못합니다."
        ) else emptyList()
        // Transparent, threshold-based technical rubric. Independent of raw NIMA.
        val penalty = (
            (bright / clipLimit).coerceAtMost(3.0) * 5 +
            (dark / darkLimit).coerceAtMost(3.0) * 4 +
            (if (edges < .02) 8.0 else 0.0)
        )
        val rubric = (100.0 - penalty).coerceIn(0.0, 100.0)
        return CriticResult(
            rawAesthetic = scores?.aesthetic,
            rawTechnical = scores?.technical,
            criterionScore = rubric,
            headline = title,
            findings = all + extra,
            recommendations = recommendations,
            disclosure = "Critic Score는 임계값 기반 기술 지표입니다. Mild/Harsh는 평가 기준만 변경하며 NIMA 원점수는 변경하지 않습니다. 의미·서사·미학에 대한 VLM 평가는 아직 제공되지 않습니다."
        )
    }
}
