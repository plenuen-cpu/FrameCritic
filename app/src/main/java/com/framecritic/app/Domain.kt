package com.framecritic.app

import kotlin.math.pow
import kotlin.math.sqrt

enum class Level(val title: String,val exponent: Double) { GENEROUS("Generous",.75), BALANCED("Balanced",1.0), STRICT("Strict",1.5) }
enum class Tone(val title: String,val instruction: String) {
    MENTOR("Mentor","친절하고 건설적인 조언. 근거 없는 칭찬을 추가하지 말 것."),
    CRITIC("Critic","직설적인 미학 비평. 인신공격 없이 사진의 표현을 논할 것."),
    ANALYST("Analyst","측정값과 추론을 구분하고 객관적·기술적으로 설명할 것.")
}
enum class Depth(val title:String,val sentences:Int) { QUICK("Quick",2), STANDARD("Standard",3), EXPERT("Expert",5) }
/** Six axes the measurement engine can actually support with OpenCV, NIMA and MediaPipe output. */
enum class Axis(val title:String,val basis:String) {
    COMPOSITION("구도·기하","검출 상자의 삼분할 위치·점유율, 수평선 후보 각도"),
    LIGHTING("빛·노출","휘도 분포, 하이라이트·암부 클리핑, 전역·국소 대비"),
    COLOR("색채","채널 평균 균형, 색상 12구간 분포, 평균 채도"),
    SHARPNESS("선명도·디테일","Laplacian 분산, 에지 밀도, 국소 대비"),
    EMOTION("감정·분위기","피사체 존재·크기, 톤의 위치, 색온도 방향에서 추정"),
    TIMING("순간·타이밍","피사체 배열과 포착의 또렷함에서 추정")
}
enum class Persona(val title:String,val subtitle:String,val weights:List<Double>,val priority:String) {
    BRESSON("Henri Cartier-Bresson","순간 · 기하 · 관계",listOf(.30,.10,.05,.10,.15,.30),"순간의 제스처, 인물 간 관계와 기하학적 프레이밍부터 검토"),
    ADAMS("Ansel Adams","계조 · 빛 · 형태",listOf(.20,.35,.10,.20,.10,.05),"존 시스템에서 영감을 받아 계조 분리와 빛이 드러내는 형태, 미세한 디테일부터 검토; 절대 존 측정으로 주장하지 말 것"),
    LEITER("Saul Leiter","색채 · 중첩 · 시적 분위기",listOf(.15,.15,.30,.05,.25,.10),"색채의 관계, 반사와 중첩, 시적 분위기를 우선; 가림과 흐림을 자동 감점하지 말 것"),
    FAN_HO("Fan Ho","명암 · 실루엣 · 공간",listOf(.25,.30,.05,.10,.15,.15),"빛과 그림자의 기하학, 실루엣, 공간 깊이부터 검토")
}
data class Evidence(val score:Double,val confidence:Double,val observation:String,val interpretation:String) {
    init { require(score.isFinite() && score in 0.0..1.0);require(confidence.isFinite() && confidence in 0.0..1.0);require(observation.isNotBlank() && interpretation.isNotBlank()) }
}
data class ScoreResult(val rawNima:Double,val weighted:Double,val base:Double,val adjusted:Double,val coverage:Double,val contributions:List<Double>)
data class NimaResult(val aesthetic:Double,val technical:Double,val deviation:Double,val probabilities:List<Float>)
object Scoring {
    fun distribution(values:FloatArray): Pair<Double,Double> {
        require(values.size==10 && values.all { it.isFinite() && it>=0 })
        val sum=values.sum().toDouble();require(sum in .98..1.02)
        val mean=values.indices.sumOf{(it+1)*values[it]/sum}
        return mean to sqrt(values.indices.sumOf{(it+1-mean).pow(2)*values[it]/sum})
    }
    fun evaluate(nima:Double,evidence:List<Evidence>,persona:Persona,level:Level):ScoreResult {
        require(nima.isFinite() && nima in 1.0..10.0 && evidence.size==Axis.entries.size)
        val w=persona.weights;val coverage=w.indices.sumOf { w[it]*evidence[it].confidence }
        require(coverage>=.35) { "측정 근거의 신뢰도가 낮아 Persona 점수를 산출하지 않습니다." }
        val c=w.indices.map { w[it]*evidence[it].confidence*evidence[it].score/coverage }
        val weighted=c.sum();val base=.4*((nima-1)/9)+.6*weighted
        return ScoreResult(nima,1+9*weighted,1+9*base,1+9*base.coerceIn(0.0,1.0).pow(level.exponent),coverage,c)
    }
}
