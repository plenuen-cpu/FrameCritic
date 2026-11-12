package com.framecritic.app
import org.junit.Test
import org.junit.Assert.*
class ScoringTest {
    private fun evidence()=Axis.entries.mapIndexed{i,_->Evidence(.2+i*.09,.8,"visible observation","aesthetic interpretation")}
    private fun flat(v:Double)=Axis.entries.map{Evidence(v,1.0,"visible","interpretation")}
    @Test fun rawDistributionExpectationAndSpread(){val p=FloatArray(10){.1f};val (m,s)=Scoring.distribution(p);assertEquals(5.5,m,1e-5);assertEquals(kotlin.math.sqrt(8.25),s,1e-5)}
    @Test fun levelMonotonicAndRawPreserved(){for(p in Persona.entries){val e=evidence();val g=Scoring.evaluate(5.8,e,p,Level.GENEROUS);val b=Scoring.evaluate(5.8,e,p,Level.BALANCED);val s=Scoring.evaluate(5.8,e,p,Level.STRICT);assertTrue(g.adjusted>b.adjusted);assertTrue(b.adjusted>s.adjusted);assertEquals(5.8,s.rawNima,0.0)}}
    @Test fun perspectiveActuallyChangesResult(){val e=evidence();assertNotEquals(Scoring.evaluate(6.0,e,Persona.BRESSON,Level.BALANCED).adjusted,Scoring.evaluate(6.0,e,Persona.LEITER,Level.BALANCED).adjusted,1e-5);Persona.entries.forEach{assertEquals(1.0,it.weights.sum(),1e-8)}}
    @Test(expected=IllegalArgumentException::class)fun lowConfidenceDoesNotInventScore(){Scoring.evaluate(6.0,Axis.entries.map{Evidence(.5,.1,"unclear","cannot assess")},Persona.ADAMS,Level.STRICT)}
    @Test(expected=IllegalArgumentException::class)fun invalidModelOutputRejected(){Scoring.distribution(FloatArray(10){Float.NaN})}
    @Test fun allScoresBounded(){for(n in listOf(1.0,5.0,10.0))for(v in listOf(0.0,.5,1.0))for(l in Level.entries)for(p in Persona.entries){val s=Scoring.evaluate(n,Axis.entries.map{Evidence(v,1.0,"visible","interpretation")},p,l);assertTrue(s.adjusted in 1.0..10.0)}}

    /** 부스팅 곡선은 양 끝을 그대로 두고 중간만 위로 끌어올린다. */
    @Test fun boostKeepsBothEnds(){
        Level.entries.forEach{l->assertEquals(0.0,Scoring.curve(0.0,l),1e-9);assertEquals(1.0,Scoring.curve(1.0,l),1e-9)}
        for(x in listOf(.2,.4,.5,.6,.8)) assertTrue("x=$x",Scoring.curve(x,Level.BALANCED)>x)
    }

    /** 흔한 5점대 사진은 7점 초중반, 잘 찍힌 6점대 사진은 7점 후반~8점 초반으로 올라온다. */
    @Test fun pleasantScoreBandsAfterBoost(){
        val ordinary=Scoring.evaluate(5.5,flat(.5),Persona.BALANCED,Level.BALANCED)
        assertEquals(5.5,ordinary.base,1e-6)
        assertTrue("5점대 사진이 ${ordinary.adjusted}점",ordinary.adjusted in 7.0..7.4)
        val good=Scoring.evaluate(8.0,flat(.5),Persona.BALANCED,Level.BALANCED)
        assertEquals(6.5,good.base,1e-6)
        assertTrue("6점대 사진이 ${good.adjusted}점",good.adjusted in 7.6..8.1)
        val excellent=Scoring.evaluate(9.0,flat(.8),Persona.BALANCED,Level.BALANCED)
        assertTrue("8점대 후반으로 ${excellent.adjusted}점",excellent.adjusted>8.5)
    }
}
