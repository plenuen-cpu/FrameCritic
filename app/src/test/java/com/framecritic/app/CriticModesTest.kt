package com.framecritic.app

import org.junit.Assert.*
import org.junit.Test

class CriticModesTest {
    private val sample = ImageMetrics(
        brightness = 0.5, darkClip = 0.11, brightClip = 0.04,
        saturation = 0.4, edgeContrast = 0.03
    )
    @Test fun severityChangesTechnicalScoreNotRawNima() {
        val raw = NimaEngine.Scores(6.2, 7.1)
        val mild = CriticModes.evaluate(sample, raw, Severity.MILD, ReportDepth.STANDARD, "Adams")
        val harsh = CriticModes.evaluate(sample, raw, Severity.HARSH, ReportDepth.STANDARD, "Adams")
        assertEquals(6.2, mild.rawAesthetic!!, 0.001)
        assertEquals(mild.rawAesthetic, harsh.rawAesthetic)
        assertTrue(harsh.criterionScore!! < mild.criterionScore!!)
    }
    @Test fun missingNimaNeverCreatesFakeScores() {
        val result = CriticModes.evaluate(sample, null, Severity.BALANCED, ReportDepth.QUICK, "Bresson")
        assertNull(result.rawAesthetic)
        assertNull(result.rawTechnical)
    }
    @Test fun quickReportIsShorterThanExpert() {
        val quick = CriticModes.evaluate(sample, null, Severity.HARSH, ReportDepth.QUICK, "Leiter")
        val expert = CriticModes.evaluate(sample, null, Severity.HARSH, ReportDepth.EXPERT, "Leiter")
        assertTrue(expert.findings.size > quick.findings.size)
    }
}
