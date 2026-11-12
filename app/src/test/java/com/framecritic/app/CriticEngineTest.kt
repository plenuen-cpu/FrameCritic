package com.framecritic.app
import org.junit.Test
import org.junit.Assert.*

/**
 * 2.1.0의 두 가지 계약:
 *  1) 화면에 보이는 비평 본문에는 수치도, 기계적 어휘도 나오지 않는다.
 *  2) 모든 원시 측정값은 measurements()의 '상세 측정 데이터' 블록에만 남는다.
 */
class CriticEngineTest {
    private fun histogram(mean:Double):List<Int> {
        val h=IntArray(256)
        for(i in h.indices) h[i]=(200.0*Math.exp(-((i-mean)*(i-mean))/(2*45.0*45.0))).toInt()+1
        return h.toList()
    }
    private fun measure(lum:Double,contrast:Double,sharp:Double,saturation:Double,edges:Double,cast:Double,hues:List<Int>):Measure=
        Measure(1280,853,lum,.004,.006,contrast,contrast*.35,sharp,
            listOf(120.0+cast/2,120.0,120.0-cast/2),hues,histogram(lum),
            listOf(LineCandidate(1.2,.6),LineCandidate(.8,.4)),saturation,edges)
    private val subjects=listOf(SubjectBox("person",.82,.34,.62,.18,.42),SubjectBox("chair",.51,.72,.55,.12,.2))
    private val hues=IntArray(12){if(it==1) 700 else 60}.toList()
    private val normal=measure(118.0,44.0,180.0,.22,.03,5.0,hues)
    private val flat=measure(58.0,13.0,30.0,.07,.005,13.0,hues)
    private val blown=measure(212.0,26.0,90.0,.10,.012,2.0,listOf(10,20,700,40,10,10,10,10,10,20,10,10))
    private val nima= NimaResult(6.1,5.7,1.9,List(10){.1f})

    /** 본문에 남으면 안 되는 기계적 어휘와 수치 표기. */
    private val banned=listOf("관찰되었","관측","Laplacian","COCO","휘도","255","검출","측정","NIMA","히스토그램","클리핑","분산")

    private fun assertHumanProse(value:String,where:String) {
        assertTrue(where,value.isNotBlank())
        assertFalse("$where: 수치가 남아 있습니다 → $value",value.any{it.isDigit()})
        banned.forEach{word->assertFalse("$where: '$word'가 남아 있습니다 → $value",value.contains(word))}
        assertFalse(where,value.contains("null"))
        assertFalse(where,value.contains("NaN"))
        assertTrue(where,value.length<1500)
    }

    @Test fun evidenceIsBoundedAndComplete() {
        for(m in listOf(normal,flat,blown)) for(s in listOf(emptyList(),subjects)) {
            val a=CriticEngine.assess(nima,m,s)
            assertEquals(Axis.entries.size,a.evidence.size)
            a.evidence.forEach{assertTrue(it.score in 0.0..1.0);assertTrue(it.confidence in 0.0..1.0);assertTrue(it.observation.isNotBlank());assertTrue(it.interpretation.isNotBlank())}
            assertTrue(a.description.isNotBlank())
        }
    }

    @Test fun everyOptionCombinationProducesEverySection() {
        val a=CriticEngine.assess(nima,normal,subjects)
        for(p in Persona.entries) for(l in Level.entries) for(t in Tone.entries) for(d in Depth.entries) {
            val s=Scoring.evaluate(a.nima.aesthetic,a.evidence,p,l)
            val sections=CriticEngine.sections(a,s,p,l,t,d)
            assertEquals(CriticEngine.SECTION_TITLES.size,sections.size)
            assertEquals(CriticEngine.SECTION_TITLES,sections.keys.toList())
            sections.forEach{(k,v)->assertEquals("$p/$l/$t/$d $k",v,v.trim())}
        }
    }

    /** 핵심 계약: 비평 본문은 사람의 문장이고, 수치는 본문에 없다. */
    @Test fun critiqueBodyCarriesNoNumbersOrMachineWords() {
        for(m in listOf(normal,flat,blown)) for(s in listOf(emptyList(),subjects)) for(p in Persona.entries) for(l in Level.entries) for(t in Tone.entries) for(d in Depth.entries) {
            val a=CriticEngine.assess(nima,m,s)
            val sections=CriticEngine.sections(a,Scoring.evaluate(a.nima.aesthetic,a.evidence,p,l),p,l,t,d)
            sections.forEach{(title,body)->assertHumanProse(body,"$p/$l/$t/$d $title")}
        }
    }

    /** 계약의 반대편: 수치는 버려지지 않고 상세 측정 블록에 그대로 남는다. */
    @Test fun measurementsKeepEveryRawValue() {
        val a=CriticEngine.assess(nima,normal,subjects)
        val score=Scoring.evaluate(a.nima.aesthetic,a.evidence,Persona.BALANCED,Level.BALANCED)
        val groups=CriticEngine.measurements(a,score)
        val titles=groups.map{it.title}
        assertTrue(titles.containsAll(listOf("평가 모델","빛과 계조","색채","선명도","구도 · 피사체","항목별 근거","점수 계산")))
        val text=groups.joinToString(" "){"${it.title} "+it.rows.joinToString(" "){row->row.first+" "+row.second}}
        listOf("Laplacian 분산","휘도 평균","255","NIMA 미적 원점수","0.55","신뢰도","삼분할 거리").forEach{assertTrue("$it 가 상세 데이터에 없습니다",text.contains(it))}
        assertEquals(Axis.entries.size,CriticEngine.evidenceLines(a).size)
        CriticEngine.evidenceLines(a).forEach{(title,body)->assertTrue(title.isNotBlank());assertTrue(body.isNotBlank())}
    }

    @Test fun scoringStaysAvailableForEveryPersonaAndImage() {
        for(m in listOf(normal,flat,blown)) for(s in listOf(emptyList(),subjects)) for(p in Persona.entries) for(l in Level.entries) {
            val a=CriticEngine.assess(nima,m,s)
            val r=Scoring.evaluate(a.nima.aesthetic,a.evidence,p,l)
            assertTrue(r.adjusted in 1.0..10.0)
        }
    }

    @Test fun engineIsDeterministic() {
        val a=CriticEngine.assess(nima,normal,subjects)
        val first=CriticEngine.sections(a,Scoring.evaluate(6.1,a.evidence,Persona.LEITER,Level.BALANCED),Persona.LEITER,Level.BALANCED,Tone.CRITIC,Depth.EXPERT)
        val second=CriticEngine.sections(a,Scoring.evaluate(6.1,a.evidence,Persona.LEITER,Level.BALANCED),Persona.LEITER,Level.BALANCED,Tone.CRITIC,Depth.EXPERT)
        assertEquals(first,second)
    }

    @Test fun strictnessAndPersonaChangeTextButNotEvidence() {
        val a=CriticEngine.assess(nima,normal,subjects)
        val strict=CriticEngine.sections(a,Scoring.evaluate(6.1,a.evidence,Persona.ADAMS,Level.STRICT),Persona.ADAMS,Level.STRICT,Tone.MENTOR,Depth.STANDARD)
        val generous=CriticEngine.sections(a,Scoring.evaluate(6.1,a.evidence,Persona.ADAMS,Level.GENEROUS),Persona.ADAMS,Level.GENEROUS,Tone.MENTOR,Depth.STANDARD)
        assertNotEquals(strict,generous)
        val leiter=CriticEngine.sections(a,Scoring.evaluate(6.1,a.evidence,Persona.LEITER,Level.BALANCED),Persona.LEITER,Level.BALANCED,Tone.MENTOR,Depth.STANDARD)
        assertNotEquals(leiter,strict)
    }

    @Test fun highlightsClippingAndTiltAreReflected() {
        val clean=CriticEngine.assess(nima,normal,subjects).evidence[Axis.LIGHTING.ordinal]
        val clipped=CriticEngine.assess(nima,blown,subjects).evidence[Axis.LIGHTING.ordinal]
        assertTrue(clean.score>clipped.score)
    }

    /** 점수 카드에 붙는 한 줄 인상. 본문과 같은 규칙을 따른다. */
    @Test fun verdictReadsAsAPhrase() {
        for(m in listOf(normal,flat,blown)) {
            val a=CriticEngine.assess(nima,m,subjects)
            for(s in listOf(3.0,5.5,7.6,9.4)) {
                val line=CriticEngine.verdict(a,s)
                assertTrue(line.isNotBlank())
                assertFalse(line.any{it.isDigit()})
                assertTrue(line.contains("한 장"))
            }
        }
    }
}
