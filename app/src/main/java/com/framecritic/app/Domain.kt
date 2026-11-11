package com.framecritic.app

import kotlin.math.pow
import kotlin.math.sqrt

enum class Level(val title: String,val exponent: Double) { GENEROUS("Generous",.75), BALANCED("Balanced",1.0), STRICT("Strict",1.5) }
enum class Tone(val title: String,val instruction: String) {
    MENTOR("Mentor","친절하고 건설적인 조언. 근거 없는 칭찬을 추가하지 말 것."),
    CRITIC("Critic","직설적인 미학 비평. 인신공격 없이 사진의 표현을 논할 것."),
    ANALYST("Analyst","측정값과 추론을 구분하고 객관적·기술적으로 설명할 것.")
}
enum class Depth(val title:String,val tokens:Int) { QUICK("Quick",1800), STANDARD("Standard",2600), EXPERT("Expert",3600) }
enum class Axis(val title:String,val rubric:String) {
    MOMENT("순간·관계","Gesture, timing and visible relationship between subjects. Do not invent an event."),
    GEOMETRY("구도·기하","Deliberate framing, alignment, balance and placement, including successful asymmetry."),
    TONAL("계조","Useful tonal separation and detail appropriate to intent, not histogram width alone."),
    LIGHT("빛·형태","Light direction and shadow shape that articulate subjects."),
    COLOR("색채","Coherent relationships between colors; monochrome can be successful."),
    LAYERS("중첩·추상","Purposeful reflections, occlusion, abstraction and layered framing."),
    DEPTH("공간·실루엣","Readable spatial depth, negative space and subject separation."),
    EXPRESSION("표현·집중","Visual focus and expressive coherence. Do not infer originality from one image.")
}
enum class Persona(val title:String,val subtitle:String,val weights:List<Double>,val priority:String) {
    BRESSON("Henri Cartier-Bresson","순간 · 기하 · 관계",listOf(.30,.27,.04,.05,.03,.07,.09,.15),"순간의 제스처, 인물 간 관계와 기하학적 프레이밍부터 검토"),
    ADAMS("Ansel Adams","계조 · 빛 · 형태",listOf(.02,.12,.32,.25,.04,.03,.12,.10),"존 시스템에서 영감을 받아 계조 분리와 빛이 드러내는 형태부터 검토; 절대 존 측정으로 주장하지 말 것"),
    LEITER("Saul Leiter","색채 · 중첩 · 시적 분위기",listOf(.05,.10,.04,.08,.29,.27,.05,.12),"색채의 관계, 반사와 중첩, 추상적 프레이밍을 우선; 가림과 흐림을 자동 감점하지 말 것"),
    FAN_HO("Fan Ho","명암 · 실루엣 · 공간",listOf(.07,.20,.14,.26,.02,.04,.19,.08),"빛과 그림자의 기하학, 실루엣, 공간 깊이부터 검토")
}
data class Evidence(val score:Double,val confidence:Double,val observation:String,val interpretation:String) {
    init { require(score.isFinite() && score in 0.0..1.0);require(confidence.isFinite() && confidence in 0.0..1.0);require(observation.isNotBlank() && interpretation.isNotBlank()) }
}
data class ScoreResult(val rawNima:Double,val weighted:Double,val base:Double,val adjusted:Double,val coverage:Double,val contributions:List<Double>)
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
        require(coverage>=.35) { "VLM 평가 근거의 신뢰도가 낮아 Persona 점수를 산출하지 않습니다." }
        val c=w.indices.map { w[it]*evidence[it].confidence*evidence[it].score/coverage }
        val weighted=c.sum();val base=.4*((nima-1)/9)+.6*weighted
        return ScoreResult(nima,1+9*weighted,1+9*base,1+9*base.coerceIn(0.0,1.0).pow(level.exponent),coverage,c)
    }
}
