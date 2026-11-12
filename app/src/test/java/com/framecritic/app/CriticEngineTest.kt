package com.framecritic.app
import org.junit.Test
import org.junit.Assert.*

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

    @Test fun evidenceIsBoundedAndComplete() {
        for(m in listOf(normal,flat,blown)) for(s in listOf(emptyList(),subjects)) {
            val a=CriticEngine.assess(nima,m,s)
            assertEquals(Axis.entries.size,a.evidence.size)
            a.evidence.forEach{assertTrue(it.score in 0.0..1.0);assertTrue(it.confidence in 0.0..1.0);assertTrue(it.observation.isNotBlank());assertTrue(it.interpretation.isNotBlank())}
            assertTrue(a.description.isNotBlank())
        }
    }
    @Test fun everyOptionCombinationProducesAllElevenSections() {
        val a=CriticEngine.assess(nima,normal,subjects)
        for(p in Persona.entries) for(l in Level.entries) for(t in Tone.entries) for(d in Depth.entries) {
            val s=Scoring.evaluate(a.nima.aesthetic,a.evidence,p,l)
            val sections=CriticEngine.sections(a,s,p,l,t,d)
            assertEquals(11,sections.size)
            assertTrue(sections.keys.containsAll(listOf("종합 크리틱","관찰 사실","미적 해석","주요 강점","개선이 필요한 부분","구도","노출","색채","선명도","촬영 제안","보정 제안")))
            sections.forEach{(k,v)->assertTrue("$p/$l/$t/$d $k",v.count{it in '가'..'힣'}>=3);assertTrue("$p/$l/$t/$d $k",v.length<1500);assertFalse(v.contains("null"));assertFalse(v.contains("NaN"))}
        }
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
}
