package com.framecritic.app
import org.junit.Assert.*
import org.junit.Test

/** 단계별 방어: 중립값과 모든 비평 항목이 어떤 경로에서도 유지되는지 확인한다. */
class PipelineFallbackTest {
    private fun analysis(metrics:Metrics=Neutral.metrics(1280,960,null)):Analysis {
        val measure=Measure(1280,960,metrics.luminance,metrics.highlights,metrics.shadows,metrics.contrast,metrics.localContrast,metrics.sharpness,
            metrics.rgb,metrics.hues,metrics.histogram,emptyList(),metrics.saturation,metrics.edges)
        val nima=Neutral.nima()
        return Analysis(nima,metrics,emptyList(),CriticEngine.safeAssess(nima,measure,emptyList()),emptyList(),0L)
    }
    private fun assertReadable(value:String,where:String) {
        assertTrue(where,value.isNotBlank())
        assertTrue(where,value.count{it in '가'..'힣'}>=3)
        assertFalse(where,value.contains("null"))
        assertFalse(where,value.contains("NaN"))
        assertTrue(where,value.length<1500)
    }

    @Test fun neutralNimaUsesMidScaleNormalisedDistribution() {
        val n=Neutral.nima()
        assertEquals(5.5,n.aesthetic,1e-9)
        assertEquals(5.0,n.technical,1e-9)
        assertEquals(1.0,n.deviation,1e-9)
        assertEquals(10,n.probabilities.size)
        assertEquals(1.0,n.probabilities.sum().toDouble(),1e-6)
        // 중립값은 점수 계산이 요구하는 범위 안에 있어야 한다.
        val scored=runCatching{Scoring.evaluate(n.aesthetic,List(Axis.entries.size){Evidence(.5,.42,"관찰","해석")},Persona.BALANCED,Level.BALANCED)}
        assertTrue("중립값으로 점수를 계산하지 못했습니다: ${scored.exceptionOrNull()?.message}",scored.isSuccess)
    }

    @Test fun neutralMetricsCarrySafeDefaults() {
        val m=Neutral.metrics(0,0,null)
        assertEquals(256,m.histogram.size)
        assertEquals(12,m.hues.size)
        assertEquals(3,m.rgb.size)
        assertTrue(m.lines.isEmpty())
        assertTrue(m.luminance.isFinite()&&m.contrast.isFinite()&&m.sharpness.isFinite()&&m.saturation.isFinite()&&m.edges.isFinite())
        assertNull(m.clipMap)
    }

    @Test fun emptySectionMapIsFilledWithEveryReadableItem() {
        val a=analysis()
        val (sections,failure)=Pipeline.completeSections(emptyMap(),a,Persona.BALANCED,Level.BALANCED,Tone.MENTOR,Depth.STANDARD)
        assertEquals(CriticEngine.SECTION_TITLES.size,sections.size)
        assertEquals(CriticEngine.SECTION_TITLES,sections.keys.toList())
        assertNotNull("항목을 보정했다면 안내 문구가 있어야 한다.",failure)
        sections.forEach{(title,body)->assertReadable(body,title)}
    }

    @Test fun brokenSectionsAreReplacedAndGoodOnesSurvive() {
        val a=analysis()
        val generated=mapOf(
            "구도" to "구도",
            "빛과 노출" to "   ",
            "색채" to "측정된 색채 분포는 안정적입니다."
        )
        val (sections,failure)=Pipeline.completeSections(generated,a,Persona.ADAMS,Level.STRICT,Tone.CRITIC,Depth.EXPERT)
        assertEquals(CriticEngine.SECTION_TITLES.size,sections.size)
        assertEquals("측정된 색채 분포는 안정적입니다.",sections["색채"])
        assertNotEquals("구도",sections["구도"])
        assertFalse(sections.values.any{it.isBlank()})
        assertNotNull(failure)
        sections.forEach{(title,body)->assertReadable(body,title)}
    }

    @Test fun completeSectionsKeepsAFullValidReportUntouched() {
        val a=analysis()
        val full=CriticEngine.sections(a.assessment,null,Persona.BALANCED,Level.BALANCED,Tone.MENTOR,Depth.STANDARD)
        val (sections,failure)=Pipeline.completeSections(full,a,Persona.BALANCED,Level.BALANCED,Tone.MENTOR,Depth.STANDARD)
        assertEquals(CriticEngine.SECTION_TITLES.size,sections.size)
        assertNull(failure)
        assertEquals(full["구도"],sections["구도"])
    }

    @Test fun reportAlwaysHasEverySectionForEveryPersonaAndLevel() {
        val a=analysis()
        for(p in Persona.entries) for(l in Level.entries) for(d in Depth.entries) {
            val report=Pipeline.report(a,p,l,Tone.MENTOR,d)
            assertEquals("$p/$l/$d",CriticEngine.SECTION_TITLES.size,report.sections.size)
            report.sections.forEach{(title,body)->assertReadable(body,"$p/$l/$d $title")}
            val text=report.text(a)
            assertTrue(text.isNotBlank())
            // 복사 텍스트에는 본문과 함께 측정 근거가 들어간다.
            assertTrue(text.contains("상세 측정 데이터 및 기술적 근거"))
            assertTrue(text.contains("NIMA 미적 원점수"))
        }
    }
}
