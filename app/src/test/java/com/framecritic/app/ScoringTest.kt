package com.framecritic.app
import org.junit.Test
import org.junit.Assert.*
class ScoringTest {
    private fun evidence()=Axis.entries.mapIndexed{i,_->Evidence(.2+i*.09,.8,"visible observation","aesthetic interpretation")}
    @Test fun rawDistributionExpectationAndSpread(){val p=FloatArray(10){.1f};val (m,s)=Scoring.distribution(p);assertEquals(5.5,m,1e-5);assertEquals(kotlin.math.sqrt(8.25),s,1e-5)}
    @Test fun levelMonotonicAndRawPreserved(){for(p in Persona.entries){val e=evidence();val g=Scoring.evaluate(5.8,e,p,Level.GENEROUS);val b=Scoring.evaluate(5.8,e,p,Level.BALANCED);val s=Scoring.evaluate(5.8,e,p,Level.STRICT);assertTrue(g.adjusted>b.adjusted);assertTrue(b.adjusted>s.adjusted);assertEquals(5.8,s.rawNima,0.0)}}
    @Test fun perspectiveActuallyChangesResult(){val e=evidence();assertNotEquals(Scoring.evaluate(6.0,e,Persona.BRESSON,Level.BALANCED).adjusted,Scoring.evaluate(6.0,e,Persona.LEITER,Level.BALANCED).adjusted,1e-5);Persona.entries.forEach{assertEquals(1.0,it.weights.sum(),1e-8)}}
    @Test(expected=IllegalArgumentException::class)fun lowConfidenceDoesNotInventScore(){Scoring.evaluate(6.0,Axis.entries.map{Evidence(.5,.1,"unclear","cannot assess")},Persona.ADAMS,Level.STRICT)}
    @Test(expected=IllegalArgumentException::class)fun invalidModelOutputRejected(){Scoring.distribution(FloatArray(10){Float.NaN})}
    @Test fun allScoresBounded(){for(n in listOf(1.0,5.0,10.0))for(v in listOf(0.0,.5,1.0))for(l in Level.entries)for(p in Persona.entries){val s=Scoring.evaluate(n,Axis.entries.map{Evidence(v,1.0,"visible","interpretation")},p,l);assertTrue(s.adjusted in 1.0..10.0)}}
}
