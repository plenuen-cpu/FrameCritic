package com.framecritic.app

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10

/**
 * Deterministic offline critic engine.
 *
 * Every number here comes from NIMA (10-bin distribution), OpenCV measurements or MediaPipe
 * detection on the device. The engine composes Korean prose from those numbers only: it never
 * invents a subject, a light direction or an emotion that the measurements do not support.
 * No Android type is used, so the whole engine runs in JVM unit tests.
 */
data class LineCandidate(val angle:Double,val length:Double)

data class SubjectBox(val label:String,val confidence:Double,val cx:Double,val cy:Double,val w:Double,val h:Double) {
    val area:Double get()=w*h
    val touchesEdge:Boolean get()=cx-w/2<.02||cy-h/2<.02||cx+w/2>.98||cy+h/2>.98
}

data class Measure(
    val width:Int,val height:Int,val luminance:Double,val highlights:Double,val shadows:Double,
    val contrast:Double,val localContrast:Double,val sharpness:Double,
    val rgb:List<Double>,val hues:List<Int>,val histogram:List<Int>,
    val lines:List<LineCandidate>,val saturation:Double,val edges:Double
) {
    fun percentile(p:Double):Int {
        if(histogram.isEmpty()) return 0
        val total=histogram.sum().toDouble()
        if(total<=0.0) return 0
        var acc=0.0
        for(i in histogram.indices){acc+=histogram[i];if(acc/total>=p) return i}
        return histogram.size-1
    }
    val channelCast:Double get()=if(rgb.size<3)0.0 else (rgb.maxOrNull()?:0.0)-(rgb.minOrNull()?:0.0)
    val warmth:Double get()=if(rgb.size<3)0.0 else rgb[0]-rgb[2]
    val hueTotal:Int get()=hues.sum()
    val dominantHue:Int get() {
        var best=0
        for(i in hues.indices) if(hues[i]>hues[best]) best=i
        return best
    }
    val dominantShare:Double get() {
        val total=hueTotal
        return if(total<=0) 0.0 else hues[dominantHue].toDouble()/total
    }
    val hueVariety:Int get() {
        val total=hueTotal
        if(total<=0) return 0
        return hues.count{it.toDouble()/total>=.03}
    }
    val hueCoverage:Double get() {
        val pixels=width.toLong()*height.toLong()
        return if(pixels<=0) 0.0 else hueTotal.toDouble()/pixels
    }
    val hueEntropy:Double get() {
        val total=hueTotal
        if(total<=0) return 0.0
        var e=0.0
        for(h in hues){val p=h.toDouble()/total;if(p>0.0) e-=p*ln(p)}
        return (e/ln(12.0)).coerceIn(0.0,1.0)
    }
    val maxTilt:Double get()=lines.maxOfOrNull{abs(it.angle)}?:0.0
}

data class Assessment(
    val nima:NimaResult,
    val measure:Measure,
    val subjects:List<SubjectBox>,
    val evidence:List<Evidence>,
    val observations:Map<String,String>,
    val description:String
) {
    fun observation(axis:Axis):String=observations[axis.name].orEmpty()
    fun evidenceOf(axis:Axis):Evidence=evidence[axis.ordinal]
}

object CriticEngine {
    private val HUMAN=setOf("person","cat","dog","bird","horse","sheep","cow")
    private val HUE_NAMES=listOf("빨강","주황","노랑","연두","초록","청록","하늘","파랑","남보라","보라","자주","분홍")
    private val LABELS=mapOf(
        "person" to "인물","bicycle" to "자전거","car" to "자동차","motorcycle" to "오토바이","airplane" to "비행기",
        "bus" to "버스","train" to "기차","truck" to "트럭","boat" to "배","traffic light" to "신호등","bench" to "벤치",
        "bird" to "새","cat" to "고양이","dog" to "개","horse" to "말","sheep" to "양","cow" to "소","elephant" to "코끼리",
        "bear" to "곰","zebra" to "얼룩말","giraffe" to "기린","backpack" to "배낭","umbrella" to "우산","handbag" to "가방",
        "tie" to "넥타이","suitcase" to "여행 가방","frisbee" to "원반","skis" to "스키","snowboard" to "스노보드",
        "sports ball" to "공","kite" to "연","baseball bat" to "야구 방망이","baseball glove" to "야구 글러브",
        "skateboard" to "스케이트보드","surfboard" to "서핑보드","tennis racket" to "테니스 라켓","bottle" to "병",
        "wine glass" to "와인잔","cup" to "컵","fork" to "포크","knife" to "칼","spoon" to "숟가락","bowl" to "그릇",
        "banana" to "바나나","apple" to "사과","sandwich" to "샌드위치","orange" to "오렌지","broccoli" to "브로콜리",
        "carrot" to "당근","hot dog" to "핫도그","pizza" to "피자","donut" to "도넛","cake" to "케이크","chair" to "의자",
        "couch" to "소파","potted plant" to "화분","bed" to "침대","dining table" to "식탁","toilet" to "변기",
        "tv" to "모니터","laptop" to "노트북","mouse" to "마우스","remote" to "리모컨","keyboard" to "키보드",
        "cell phone" to "휴대폰","microwave" to "전자레인지","oven" to "오븐","toaster" to "토스터","sink" to "싱크대",
        "refrigerator" to "냉장고","book" to "책","clock" to "시계","vase" to "꽃병","scissors" to "가위",
        "teddy bear" to "곰인형","hair drier" to "헤어드라이어","toothbrush" to "칫솔"
    )
    /** Every axis is grounded in at least one direct measurement, so no axis is left without a reading. */
    private const val CONFIDENCE_FLOOR=.42

    // ---------- Korean particles and formatting ----------
    private fun koLabel(label:String):String=LABELS[label.lowercase()]?:label
    private fun hasFinalConsonant(word:String):Boolean {
        if(word.isEmpty()) return false
        val c=word.last()
        return c in '가'..'힣' && (c.code-'가'.code)%28!=0
    }
    private fun eun(word:String)=word+(if(hasFinalConsonant(word)) "은" else "는")
    private fun wa(word:String)=word+(if(hasFinalConsonant(word)) "과" else "와")
    private fun f1(v:Double)=String.format(Locale.US,"%.1f",v)
    private fun f2(v:Double)=String.format(Locale.US,"%.2f",v)
    private fun p1(v:Double)=String.format(Locale.US,"%.1f%%",v*100)
    private fun p2(v:Double)=String.format(Locale.US,"%.2f%%",v*100)
    private fun p0(v:Double)=String.format(Locale.US,"%.0f%%",v*100)
    /** "180.0으로" / "44.5로": the particle follows the spoken last digit. */
    private fun numRo(v:Double):String {
        val text=f1(v)
        val last=text.last()
        return text+(if(last in "013678") "으로" else "로")
    }
    private fun clamp01(v:Double)=v.coerceIn(0.0,1.0)
    private fun finite(v:Double,fallback:Double)=if(v.isFinite())v else fallback
    private fun ramp(v:Double,low:Double,high:Double)=if(high<=low) 0.0 else clamp01((v-low)/(high-low))
    private fun band(score:Double)=when{score>=.72->2;score>=.45->1;else->0}
    private fun nearestThirds(cx:Double,cy:Double):Double {
        var best=1.0
        for(tx in listOf(1.0/3.0,2.0/3.0)) for(ty in listOf(1.0/3.0,2.0/3.0)) {
            val d=hypot(cx-tx,cy-ty)
            if(d<best) best=d
        }
        return best
    }

    // ---------- assessment ----------
    /** 한 항목의 계산이 실패해도 다른 항목의 근거는 남기고, 그 항목만 중립값으로 둔다. */
    private fun neutralAx()=Ax(.5,CONFIDENCE_FLOOR,"이 항목의 측정값을 읽지 못했습니다.","측정값 해석에 실패해 중립값으로 처리했습니다.")

    fun assess(nima:NimaResult,measure:Measure,subjects:List<SubjectBox>):Assessment {
        val sorted=subjects
            .filter{it.confidence.isFinite()&&it.cx.isFinite()&&it.cy.isFinite()&&it.w.isFinite()&&it.h.isFinite()}
            .sortedByDescending{it.area}
        val builders=listOf<()->Ax>(
            {composition(measure,sorted)},
            {lighting(measure)},
            {color(measure)},
            {sharpness(measure)},
            {emotion(nima,measure,sorted)},
            {timing(measure,sorted)}
        )
        val axes=builders.map{build->runCatching(build).getOrElse{neutralAx()}}
        val evidence=axes.mapIndexed{i,ax->
            val title=Axis.entries.getOrElse(i){Axis.entries.last()}.title
            Evidence(
                finite(ax.score,.5).coerceIn(.05,.95),
                finite(ax.confidence,CONFIDENCE_FLOOR).coerceIn(CONFIDENCE_FLOOR,1.0),
                ax.observation.ifBlank{"$title 항목의 관찰 문장을 만들지 못했습니다."},
                ax.interpretation.ifBlank{"측정값 해석에 실패해 중립값으로 처리했습니다."}
            )
        }
        val observations=Axis.entries.mapIndexed{i,axis->
            axis.name to (evidence.getOrNull(i)?.observation?:"${axis.title} 항목의 관찰을 만들지 못했습니다.")
        }.toMap()
        val description=runCatching{describe(measure,sorted,evidence)}
            .getOrElse{"측정값을 문장으로 정리하지 못했습니다. 아래 항목별 관찰을 참고하십시오."}
        return Assessment(nima,measure,sorted,evidence,observations,description)
    }

    /** 어떤 입력이나 내부 오류에도 앱을 종료시키지 않고 Assessment를 돌려준다. */
    fun safeAssess(nima:NimaResult,measure:Measure,subjects:List<SubjectBox>):Assessment=
        runCatching{assess(nima,measure,subjects)}.getOrElse{neutralAssessment(nima,measure,subjects)}

    private fun neutralAssessment(nima:NimaResult,measure:Measure,subjects:List<SubjectBox>):Assessment {
        val evidence=Axis.entries.map{axis->
            Evidence(.5,CONFIDENCE_FLOOR,"${axis.title} 항목의 측정값을 정리하지 못했습니다.","측정값 해석에 실패해 중립값으로 처리했습니다. 관점이나 깊이를 바꾸어 다시 시도해 주십시오.")
        }
        val observations=Axis.entries.mapIndexed{i,axis->axis.name to evidence[i].observation}.toMap()
        return Assessment(nima,measure,subjects,evidence,observations,"측정값을 문장으로 정리하는 중 문제가 생겨 항목별 관찰만 남겼습니다.")
    }

    private data class Ax(val score:Double,val confidence:Double,val observation:String,val interpretation:String)

    private fun composition(m:Measure,subjects:List<SubjectBox>):Ax {
        val main=subjects.firstOrNull()
        val crowded=subjects.count{it.touchesEdge&&it.area>.05}
        val tiltScore=if(m.lines.isEmpty()) 0.0 else 1.0-ramp(m.maxTilt,1.0,9.0)
        val placement=if(main==null) 0.5 else 1.0-ramp(nearestThirds(main.cx,main.cy),.02,.32)
        val room=1.0-ramp(crowded.toDouble(),0.0,2.0)
        val size=if(main==null) 0.5 else 1.0-ramp(abs(main.area-.18),.10,.42)
        val score=if(main==null) .46+.10*tiltScore else .18+.44*placement+.16*room+.12*size+.10*tiltScore
        val confidence=when {
            subjects.isNotEmpty()&&m.lines.isNotEmpty()->.86
            subjects.isNotEmpty()->.74
            m.lines.isNotEmpty()->.56
            else->.44
        }
        val labels=subjects.take(3).joinToString(", "){ "${koLabel(it.label)} ${f2(it.confidence)}" }
        val subjectText=if(main==null) "COCO 범주 피사체는 검출되지 않았습니다. 빈 프레임이라는 뜻은 아닙니다."
        else "피사체 ${subjects.size}개가 검출되었습니다($labels). 가장 큰 피사체는 중심 (${p0(main.cx)}, ${p0(main.cy)})에서 프레임의 ${p1(main.area)}를 차지하고, 삼분할 교차점에서 프레임 폭의 ${p0(nearestThirds(main.cx,main.cy))}만큼 떨어져 있습니다."
        val lineText=if(m.lines.isEmpty()) "수평선 후보는 검출되지 않았습니다."
        else "수평선 후보 ${m.lines.size}개의 최대 기울기는 ${f1(m.maxTilt)}°입니다."
        val edgeText=if(crowded>0) " 가장자리에 닿은 피사체가 ${crowded}개 있습니다." else ""
        val observation="$subjectText$edgeText $lineText"
        val interpretation=if(main==null) when(band(score)) {
            2->"검출된 피사체가 없어도 선과 면의 배열이 안정적이어서 프레임이 흔들리지 않습니다."
            1->"검출된 피사체가 없어 구도는 선과 여백의 배열로만 읽힙니다. 수평선 후보가 없다면 기울기를 판단할 근거도 없습니다."
            else->"검출된 피사체가 없고 수평선 후보도 적어, 프레임 안의 질서를 확인할 근거가 부족합니다."
        } else when(band(score)) {
            2->"주요 피사체가 시선이 머무는 지점에 놓여 프레임이 대상을 놓치지 않고, 여백이 피사체를 밀어내지 않고 호흡을 만듭니다."
            1->"구성은 무난하게 정리되어 있으나 피사체 위치가 화면 중앙과 삼분할 사이에 걸쳐 있어 시선의 착지점이 다소 흐립니다."
            else->"피사체가 가장자리에 붙거나 삼분할 격자에서 멀어, 프레임이 대상을 담아내기보다 흘려보내는 인상을 줍니다."
        }
        return Ax(score,confidence,observation,interpretation)
    }

    private fun lighting(m:Measure):Ax {
        val exp=m.luminance/255.0
        val placement=1.0-ramp(abs(exp-.46),.02,.34)
        val clip=m.highlights+m.shadows
        val clipScore=1.0-ramp(clip,.01,.12)
        val separation=ramp(m.contrast,12.0,52.0)
        val local=ramp(m.localContrast,3.0,20.0)
        val score=.30*(.45+.55*placement)+.24*clipScore+.28*separation+.18*local
        val observation="휘도 평균 ${f1(m.luminance)}/255, 중간값 ${m.percentile(.5)}, 5퍼센타일 ${m.percentile(.05)}, 95퍼센타일 ${m.percentile(.95)}입니다. 하이라이트 ${p2(m.highlights)}, 암부 ${p2(m.shadows)}가 잘렸고, 전역 대비 ${f1(m.contrast)}, 국소 대비 ${f1(m.localContrast)}입니다."
        val interpretation=when(band(score)) {
            2->"중간톤에 노출이 놓이고 양 끝의 손실이 적어 밝은 부분과 어두운 부분의 결이 함께 살아 있습니다."
            1->"노출은 읽히지만 계조의 폭이 좁아 빛이 형태를 설명하기보다 평평한 면으로 남습니다."
            else->"하이라이트와 암부가 함께 잘려 톤의 정보가 손실되었고, 빛이 형태를 만들어내는 힘이 약합니다."
        }
        val confidence=if(clip<.02) .92 else .88
        return Ax(score,confidence,observation,interpretation)
    }

    private fun color(m:Measure):Ax {
        val castScore=1.0-ramp(m.channelCast,6.0,30.0)
        val hueScore=if(m.hueCoverage<.05) .50 else ramp(m.hueEntropy,.35,.85)
        val satScore=1.0-ramp(abs(m.saturation-.22),.06,.30)
        var score=.34*castScore+.34*hueScore+.32*(.35+.65*satScore)
        if(m.saturation<.10) score-=.08
        val hueText=if(m.hueTotal<=0) "유채색 픽셀이 거의 없어 색상 분포를 읽기 어렵습니다."
        else "색상 12구간 중 ${m.hueVariety}개에 분포가 있고 ${HUE_NAMES[m.dominantHue]} 계열이 ${p0(m.dominantShare)}로 가장 넓습니다."
        val observation="평균 RGB는 ${f1(m.rgb.getOrElse(0){0.0})}/${f1(m.rgb.getOrElse(1){0.0})}/${f1(m.rgb.getOrElse(2){0.0})}, 채널 간 차이는 ${f1(m.channelCast)}, 평균 채도는 ${p1(m.saturation)}입니다. $hueText"
        val interpretation=when {
            m.saturation<.12->"채도가 낮아 색의 관계보다 명암의 관계로 읽히는 사진입니다. 색을 의도적으로 뺀 결과일 수 있으므로 채도만으로 감점하지 않았습니다."
            m.channelCast>12.0->"채널이 한 방향으로 기울어 화면 전체의 색온도가 한쪽으로 밀려 있습니다. 피사체의 고유색이 배경과 구분되는지 확인할 지점입니다."
            band(score)==2->"채널 균형이 고르고 색상이 몇 개의 축으로 정리되어 색이 서로를 지지합니다."
            band(score)==1->"색은 자연스럽지만 주조색과 보조색의 관계가 느슨해 화면의 색 방향이 흐립니다."
            else->"주조색이 뚜렷하지 않고 색의 배열이 흩어져, 화면의 색 방향을 잡기 어렵습니다."
        }
        val confidence=if(m.saturation<.12||m.hueCoverage<.05) .62 else .74
        return Ax(score,confidence,observation,interpretation)
    }

    private fun sharpness(m:Measure):Ax {
        val sharpScore=ramp(log10(1.0+m.sharpness),log10(16.0),log10(251.0))
        val edgeScore=ramp(m.edges,.004,.09)
        val score=.16+.58*sharpScore+.26*edgeScore
        val observation="Laplacian 분산 ${f1(m.sharpness)}, 에지 밀도 ${p2(m.edges)}, 국소 대비 ${f1(m.localContrast)}이며 분석 해상도는 ${m.width}×${m.height}입니다."
        val interpretation=when(band(score)) {
            2->"에지와 국소 대비가 살아 있어 질감이 또렷하게 읽히고 초점이 놓인 곳이 분명합니다."
            1->"전체적으로 읽히지만 미세 디테일이 눌려 있어 질감의 밀도가 낮습니다."
            else->"에지가 뭉개져 형태의 윤곽이 흐리고 초점이 어디에 놓였는지 판단하기 어렵습니다."
        }
        return Ax(score,.82,observation,interpretation)
    }

    private fun emotion(nima:NimaResult,m:Measure,subjects:List<SubjectBox>):Ax {
        val human=subjects.any{it.label.lowercase() in HUMAN}
        val main=subjects.firstOrNull()
        val p50=m.percentile(.5)
        var s=.44
        if(subjects.isNotEmpty()) s+=.08
        if(human) s+=.06
        if(main!=null&&main.area in .04..0.55) s+=.05
        if(p50<85) s+=.05
        if(m.contrast>=40) s+=.05
        if(m.saturation>=.28) s+=.04
        if(m.channelCast>=10) s+=.03
        if(m.contrast<16) s-=.07
        if(subjects.isEmpty()) s-=.05
        val mainText=main?.let{"가장 큰 피사체(${koLabel(it.label)})가 프레임의 ${p1(it.area)}를 차지합니다"}
            ?: "COCO 범주 피사체는 검출되지 않았습니다"
        val keyText=when {
            p50<70->"저조도(low-key) 톤"
            p50>170->"고조도(high-key) 톤"
            else->"중간톤"
        }
        val tempText=when {
            m.channelCast<6->"색온도 치우침이 거의 없는 중성"
            m.warmth>0->"붉은 쪽으로 기운 따뜻한"
            else->"푸른 쪽으로 기운 차가운"
        }
        val observation="$keyText(중간값 휘도 ${p50})이고, $mainText. 채널 차 ${f1(m.channelCast)}의 $tempText 색입니다. 감정은 측정값이 아니므로 이 지표들에서 추정합니다."
        val interpretation=when(band(s)) {
            2->"피사체의 존재와 톤의 방향이 함께 작동해 장면의 온도가 느껴지고, 보는 사람이 감정으로 이어 붙일 여지가 있습니다."
            1->"분위기는 읽히지만 톤과 색이 감정의 방향을 결정하지는 못해 중립적인 인상에 머뭅니다."
            else->"장면의 정서보다 정보 전달에 가까워, 보는 사람의 감정을 건드리는 힘이 약합니다."
        }
        val confidence=.40+(if(human) .12 else if(subjects.isNotEmpty()) .06 else 0.0)+(if(nima.deviation<2.0) .06 else 0.0)
        return Ax(s,confidence,observation,interpretation)
    }

    private fun timing(m:Measure,subjects:List<SubjectBox>):Ax {
        val human=subjects.any{it.label.lowercase() in HUMAN}
        var s=.46
        if(subjects.isNotEmpty()) s+=.06
        if(human) s+=.08
        if(m.sharpness>=120) s+=.04
        if(m.localContrast>=14) s+=.04
        if(subjects.size>6) s-=.05
        val observation=if(subjects.isEmpty()) "피사체가 검출되지 않아 배열을 읽을 대상이 없습니다. Laplacian 분산 ${f1(m.sharpness)}, 국소 대비 ${f1(m.localContrast)}입니다."
        else "피사체 ${subjects.size}개가 정지 배열로 잡혀 있습니다. Laplacian 분산 ${f1(m.sharpness)}, 국소 대비 ${f1(m.localContrast)}로 포착의 또렷함을 가늠합니다."
        val interpretation=when(band(s)) {
            2->"포착이 또렷해 순간을 붙잡은 인상이 있습니다. 다만 한 장의 정지 이미지로 결정적 순간을 확정할 수는 없습니다."
            1->"장면은 정돈되어 있으나 제스처나 움직임의 흔적이 드러나지 않아 순간의 긴장은 약합니다."
            else->"정지된 배열로 남아 있어 장면이 일어나기 전이나 지나간 뒤를 담은 듯합니다."
        }
        val confidence=.30+(if(subjects.isNotEmpty()) .10 else 0.0)+(if(human) .06 else 0.0)
        return Ax(s,confidence,observation,interpretation)
    }

    private fun describe(m:Measure,subjects:List<SubjectBox>,evidence:List<Evidence>):String {
        val main=subjects.firstOrNull()
        val toneText=when {
            m.percentile(.5)<70->"어두운 톤"
            m.percentile(.5)>170->"밝은 톤"
            else->"중간 톤"
        }
        val subjectText=if(main==null) "COCO 범주 피사체는 검출되지 않았습니다."
        else "피사체 ${subjects.size}개가 검출되었고, 가장 큰 피사체는 ${koLabel(main.label)}이며 프레임의 ${p1(main.area)}를 차지합니다."
        val sharpText=when(band(evidence[Axis.SHARPNESS.ordinal].score)) {
            2->"디테일이 또렷한 편입니다."
            1->"디테일은 무난한 수준입니다."
            else->"디테일이 무딘 편입니다."
        }
        val hueText=if(m.hueTotal<=0) "유채색이 거의 없습니다." else "주조색은 ${HUE_NAMES[m.dominantHue]} 계열이고 평균 채도는 ${p1(m.saturation)}입니다."
        return "휘도 평균 ${f1(m.luminance)}/255의 $toneText 사진입니다. $subjectText $hueText Laplacian 분산은 ${numRo(m.sharpness)} $sharpText"
    }

    // ---------- report sections ----------
    private fun facts(a:Assessment):List<String> {
        val m=a.measure
        val main=a.subjects.firstOrNull()
        val list=mutableListOf<String>()
        list+="NIMA 미적 원점수 ${f2(a.nima.aesthetic)}/10, 기술 원점수 ${f2(a.nima.technical)}/10, 확률분포 표준편차 ${f2(a.nima.deviation)}입니다."
        list+="휘도 평균 ${f1(m.luminance)}/255, 중간값 ${m.percentile(.5)}, 5퍼센타일 ${m.percentile(.05)}, 95퍼센타일 ${m.percentile(.95)}입니다."
        list+="하이라이트(Y≥250) ${p2(m.highlights)}, 암부(Y≤5) ${p2(m.shadows)}, 전역 대비 ${f1(m.contrast)}, 국소 대비 ${f1(m.localContrast)}입니다."
        list+="Laplacian 분산 ${f1(m.sharpness)}, 에지 밀도 ${p2(m.edges)}입니다."
        list+="평균 RGB ${f1(m.rgb.getOrElse(0){0.0})}/${f1(m.rgb.getOrElse(1){0.0})}/${f1(m.rgb.getOrElse(2){0.0})}, 채널 간 차이 ${f1(m.channelCast)}, 평균 채도 ${p1(m.saturation)}입니다."
        if(m.hueTotal>0) list+="색상 12구간 중 ${m.hueVariety}개에 분포가 있고 ${HUE_NAMES[m.dominantHue]} 계열이 ${p0(m.dominantShare)}로 가장 넓습니다."
        if(main!=null) list+="피사체 ${a.subjects.size}개, 가장 큰 피사체는 중심 (${p0(main.cx)}, ${p0(main.cy)}), 프레임 점유 ${p1(main.area)}, 삼분할 교차점과의 거리 ${p0(nearestThirds(main.cx,main.cy))}입니다."
        else list+="COCO 범주 피사체는 검출되지 않았습니다(빈 프레임이라는 뜻은 아닙니다)."
        if(m.lines.isNotEmpty()) list+="수평선 후보 ${m.lines.size}개, 최대 기울기 ${f1(m.maxTilt)}°입니다."
        return list
    }

    private fun keySentence(a:Assessment,axis:Axis):String {
        val m=a.measure
        return when(axis) {
            Axis.COMPOSITION->a.subjects.firstOrNull()?.let{"삼분할 이탈 ${p0(nearestThirds(it.cx,it.cy))}, 수평선 최대 기울기 ${f1(m.maxTilt)}°입니다."} ?: "피사체는 검출되지 않았고 수평선 후보는 ${m.lines.size}개입니다."
            Axis.LIGHTING->"휘도 ${f1(m.luminance)}/255, 클리핑 합계 ${p2(m.highlights+m.shadows)}, 전역 대비 ${f1(m.contrast)}입니다."
            Axis.COLOR->"채널 차 ${f1(m.channelCast)}, 색상 구간 ${m.hueVariety}/12, 평균 채도 ${p1(m.saturation)}입니다."
            Axis.SHARPNESS->"Laplacian 분산 ${f1(m.sharpness)}, 에지 밀도 ${p2(m.edges)}입니다."
            Axis.EMOTION->"피사체 ${a.subjects.size}개, 중간값 휘도 ${m.percentile(.5)}, 채도 ${p1(m.saturation)}에서 추정했습니다."
            Axis.TIMING->"피사체 ${a.subjects.size}개, Laplacian 분산 ${f1(m.sharpness)}에서 추정했습니다."
        }
    }

    /** 리포트가 반드시 채워야 하는 11개 항목. Pipeline이 이 목록과 대조해 빈 항목을 보정한다. */
    val SECTION_TITLES=listOf("종합 크리틱","관찰 사실","미적 해석","주요 강점","개선이 필요한 부분","구도","노출","색채","선명도","촬영 제안","보정 제안")

    /** 문장 생성 중 어떤 오류가 나도 11개 항목이 채워진 리포트를 돌려준다. */
    fun sections(a:Assessment,score:ScoreResult?,persona:Persona,level:Level,tone:Tone,depth:Depth):Map<String,String> =
        try{buildSections(a,score,persona,level,tone,depth)}
        catch(e:Throwable){fallbackSections(a,persona,level,tone)}

    private fun fallbackSections(a:Assessment,persona:Persona,level:Level,tone:Tone):Map<String,String> {
        val facts=runCatching{facts(a).joinToString(" ")}.getOrElse{"측정값을 요약하지 못했습니다."}
        val opening="측정값을 문장으로 엮는 중 문제가 생겨 항목별 서술을 간단히 대신합니다. ${persona.title} 관점, 엄격도 ${level.title}, ${tone.title} 어조를 요청하셨습니다."
        return SECTION_TITLES.associateWith{name->
            when(name) {
                "종합 크리틱"->opening
                "관찰 사실"->facts
                else->"$name 항목은 이번 측정값으로 완성하지 못했습니다. 관점이나 깊이를 바꾸어 다시 시도해 주십시오."
            }
        }
    }

    private fun buildSections(a:Assessment,score:ScoreResult?,persona:Persona,level:Level,tone:Tone,depth:Depth):Map<String,String> {
        val ev=a.evidence
        val resolved=score?:runCatching{Scoring.evaluate(a.nima.aesthetic,ev,persona,level)}.getOrNull()
        val contributions=resolved?.contributions?:ev.indices.map{persona.weights[it]*ev[it].score}
        val ranked=Axis.entries.indices.sortedWith(
            compareByDescending<Int>{ev[it].score}.thenByDescending{persona.weights[it]}
        )
        val top=ranked.take(2)
        val weak=ranked.takeLast(2).reversed()
        val focus=persona.weights.indices.maxByOrNull{persona.weights[it]}?:top[0]
        val s10=resolved?.adjusted ?: (1.0+9.0*contributions.sum().coerceIn(0.0,1.0))
        val limit=depth.sentences
        val out=linkedMapOf<String,String>()
        out["종합 크리틱"]=summary(a,resolved,s10,persona,level,tone,depth,top,weak,focus)
        out["관찰 사실"]=observed(a,limit)
        out["미적 해석"]=interpreted(a,persona,depth,top,weak)
        out["주요 강점"]=strengths(a,level,top,limit)
        out["개선이 필요한 부분"]=improvements(a,level,tone,weak,limit)
        out["구도"]=axisSection(a,Axis.COMPOSITION,level,tone,limit)
        out["노출"]=axisSection(a,Axis.LIGHTING,level,tone,limit)
        out["색채"]=axisSection(a,Axis.COLOR,level,tone,limit)
        out["선명도"]=axisSection(a,Axis.SHARPNESS,level,tone,limit)
        out["촬영 제안"]=shooting(a,persona,level,tone,depth,weak)
        out["보정 제안"]=editing(a,level,tone,depth,weak)
        return out
    }

    private class Sentences(private val limit:Int) {
        private val items=mutableListOf<String>()
        fun add(text:String?) {
            if(text.isNullOrBlank()||items.size>=limit) return
            val clean=text.trim()
            if(clean.isNotEmpty()) items+=clean
        }
        fun addAll(texts:List<String>) { texts.forEach{add(it)} }
        fun build():String=items.joinToString(" ")
    }

    private fun lens(persona:Persona)=when(persona) {
        Persona.BRESSON->"순간과 기하의 관점에서 보면"
        Persona.ADAMS->"계조와 빛의 관점에서 보면"
        Persona.LEITER->"색채와 분위기의 관점에서 보면"
        Persona.FAN_HO->"명암과 실루엣의 관점에서 보면"
    }

    private fun verdictLine(a:Assessment,s10:Double,tone:Tone):String {
        val b=when{s10>=7.6->3;s10>=6.2->2;s10>=4.8->1;else->0}
        val m=a.measure
        return when(tone) {
            Tone.MENTOR->when(b) {
                3->"측정된 값으로 보면 이 사진은 완성도가 높은 장입니다. 지금의 노출과 색 균형을 기준으로 삼을 만합니다."
                2->"측정된 값으로 보면 이 사진은 균형이 잡힌 장입니다. 큰 결함 없이 기본이 서 있습니다."
                1->"측정된 값으로 보면 이 사진은 기본기를 갖춘 장입니다. 방향은 맞고, 손볼 지점이 분명합니다."
                else->"측정된 값으로 보면 이 사진은 아직 다듬을 것이 많은 장입니다. 다만 출발점은 분명합니다."
            }
            Tone.CRITIC->when(b) {
                3->"측정된 값으로 볼 때 이 사진은 상당히 완성도가 높습니다. 흠을 찾기보다 유지할 이유를 먼저 봐야 합니다."
                2->"측정된 값으로 볼 때 이 사진은 무난합니다. 다만 인상은 중간 정도에 머뭅니다."
                1->"측정된 값으로 볼 때 이 사진은 기본기는 있으나 결정적인 장면은 아닙니다."
                else->"측정된 값으로 볼 때 이 사진은 지금 상태로 완성도를 논하기 어렵습니다."
            }
            Tone.ANALYST->"휘도 평균 ${f1(m.luminance)}/255, 전역 대비 ${f1(m.contrast)}, Laplacian 분산 ${f1(m.sharpness)}의 측정을 종합하면 이 사진은 ${listOf("다듬을 것이 많은","기본기를 갖춘","균형이 잡힌","완성도가 높은")[b]} 편에 속합니다."
        }
    }

    private fun summary(a:Assessment,score:ScoreResult?,s10:Double,persona:Persona,level:Level,tone:Tone,depth:Depth,top:List<Int>,weak:List<Int>,focus:Int):String {
        val t=Sentences(depth.sentences)
        t.add(verdictLine(a,s10,tone))
        val strongest=Axis.entries[top[0]]
        val weakest=Axis.entries[weak[0]]
        val strongWord=when{band(a.evidence[strongest.ordinal].score)==2->"가장 설득력 있는 부분";band(a.evidence[strongest.ordinal].score)==1->"상대적으로 나은 부분";else->"그나마 덜 흔들리는 부분"}
        val weakWord=when(level) {
            Level.STRICT->"완성도를 막는 지점"
            Level.GENEROUS->"더 키울 수 있는 지점"
            Level.BALANCED->"개선할 지점"
        }
        t.add("${lens(persona)} ${eun(strongest.title)} ${strongWord}이고, ${eun(weakest.title)} ${weakWord}입니다.")
        if(score!=null) {
            val focusAxis=Axis.entries[focus]
            t.add("${persona.title} 관점 가중치를 적용한 점수는 ${f2(score.weighted)}/10이고, 엄격도 ${level.title} 보정 후 ${f2(score.adjusted)}/10입니다(가중 신뢰도 ${p0(score.coverage)}). 이 관점에서 가장 큰 비중을 두는 항목은 ${focusAxis.title}입니다.")
        }
        if(depth==Depth.EXPERT) t.add("이 점수는 NIMA 원점수 40%와 측정 근거 60%를 합친 경험적 함수이며, 인간 심사위원 점수로 검증된 척도가 아닙니다.")
        t.add(when(tone) {
            Tone.MENTOR->"아래 근거를 따라가면 어디를 먼저 손보면 좋을지 순서가 보입니다."
            Tone.CRITIC->"판단의 근거는 아래 수치입니다. 감상보다 수치를 먼저 확인하십시오."
            Tone.ANALYST->"아래 항목은 측정값과 그로부터의 해석을 구분해 적었습니다."
        })
        return t.build()
    }

    private fun observed(a:Assessment,limit:Int):String {
        val t=Sentences(limit)
        t.addAll(facts(a))
        return t.build()
    }

    private fun interpreted(a:Assessment,persona:Persona,depth:Depth,top:List<Int>,weak:List<Int>):String {
        val t=Sentences(depth.sentences)
        t.add("측정값을 미학으로 옮기면 이렇습니다. ${a.evidence[top[0]].interpretation}")
        t.add(a.evidence[top[1]].interpretation)
        t.add(a.evidence[weak[0]].interpretation)
        if(depth==Depth.EXPERT) t.add("${persona.priority}에 비추어 보면 이 사진은 그 기준의 일부만 충족합니다. 사진 한 장으로 작가의 의도나 독창성을 확정하지는 않습니다.")
        return t.build()
    }

    private fun strengths(a:Assessment,level:Level,top:List<Int>,limit:Int):String {
        val t=Sentences(limit)
        val first=Axis.entries[top[0]]
        val second=Axis.entries[top[1]]
        val best=a.evidence[top[0]].score
        val secondScore=a.evidence[top[1]].score
        val intro=when {
            best>=.70&&secondScore>=.60&&level==Level.GENEROUS->"분명히 살릴 수 있는 강점은 ${wa(first.title)} ${second.title}입니다."
            best>=.70&&secondScore>=.60&&level==Level.STRICT->"상대적으로 나은 지점은 ${wa(first.title)} ${second.title}이며, 둘 다 완성도 기준을 넘지는 못합니다."
            best>=.70&&secondScore>=.60->"강점은 ${wa(first.title)} ${second.title}입니다."
            best>=.55->"가장 나은 항목은 ${first.title}이고, ${eun(second.title)} 상대적으로 덜 약한 항목입니다."
            best>=.45->"두드러진 강점은 약하지만 상대적으로 나은 항목은 ${wa(first.title)} ${second.title}입니다."
            else->"측정값에서 뚜렷한 강점은 찾기 어렵습니다. 그나마 덜 약한 항목은 ${wa(first.title)} ${second.title}입니다."
        }
        t.add(intro)
        t.add("${eun(first.title)} ${keySentence(a,first)} ${a.evidence[top[0]].interpretation}")
        t.add("${eun(second.title)} ${keySentence(a,second)} ${a.evidence[top[1]].interpretation}")
        return t.build()
    }

    private fun improvements(a:Assessment,level:Level,tone:Tone,weak:List<Int>,limit:Int):String {
        val t=Sentences(limit)
        val first=Axis.entries[weak[0]]
        val second=Axis.entries[weak[1]]
        val intro=when(level) {
            Level.GENEROUS->"지금 값을 조금만 밀어 올리면 좋아질 지점은 ${wa(first.title)} ${second.title}입니다."
            Level.BALANCED->"개선이 필요한 부분은 ${wa(first.title)} ${second.title}입니다."
            Level.STRICT->"완성도를 막는 지점은 ${wa(first.title)} ${second.title}입니다. 이 둘을 고치면 점수가 가장 크게 움직입니다."
        }
        t.add(intro)
        t.add("${eun(first.title)} ${keySentence(a,first)} ${a.evidence[weak[0]].interpretation}")
        t.add(actionFor(a,first,tone))
        if(limit>3) t.add(actionFor(a,second,tone))
        return t.build()
    }

    private fun axisSection(a:Assessment,axis:Axis,level:Level,tone:Tone,limit:Int):String {
        val t=Sentences(limit)
        val e=a.evidence[axis.ordinal]
        t.add(e.observation)
        t.add(e.interpretation)
        t.add(actionFor(a,axis,tone))
        if(level==Level.STRICT&&limit>3) t.add("이 항목은 엄격도 ${level.title} 기준에서 ${if(band(e.score)>=2)"통과" else "미달"}로 판정했습니다(근거 점수 ${f2(e.score)}, 신뢰도 ${p0(e.confidence)}).")
        return t.build()
    }

    private fun suggestion(a:Assessment,axis:Axis,tone:Tone):String {
        val m=a.measure
        val main=a.subjects.firstOrNull()
        val strong=band(a.evidence[axis.ordinal].score)==2
        val body=when(axis) {
            Axis.COMPOSITION->when {
                strong->"지금의 피사체 배치와 여백을 그대로 두는 편이"
                main==null->"피사체로 삼을 요소를 하나 정해 프레임의 삼분할 지점에 두는 편이"
                nearestThirds(main.cx,main.cy)>.14->"가장 큰 피사체를 삼분할 교차점 쪽으로 반 걸음 옮기는 편이"
                a.subjects.any{it.touchesEdge&&it.area>.05}->"가장자리에 닿은 피사체를 안으로 들이거나 과감히 잘라내는 편이"
                m.maxTilt>3.0->"수평선을 1~2° 안으로 맞추는 편이"
                else->"지금의 배치를 유지하되 여백을 한쪽으로 몰아 긴장을 만드는 편이"
            }
            Axis.LIGHTING->when {
                strong->"지금 노출을 유지하되 밝은 부분이 먼저 날아가지 않게 두는 편이"
                m.highlights>.02->"노출을 1/3스톱 낮춰 하이라이트를 살리는 편이"
                m.shadows>.04->"암부를 반 스톱 들어 올리거나 그림자 방향을 바꾸는 편이"
                m.contrast<18.0->"측광을 피사체에 맞추고 배경과의 밝기 차를 만드는 편이"
                else->"노출을 고정하고 밝은 부분과 어두운 부분의 균형을 다시 확인하는 편이"
            }
            Axis.COLOR->when {
                strong->"지금의 채널 균형을 유지하고 채도를 더 올리지 않는 편이"
                m.channelCast>8.0&&m.warmth>0->"화이트밸런스를 조금 차갑게(R−) 두는 편이"
                m.channelCast>8.0->"화이트밸런스를 조금 따뜻하게(B−) 두는 편이"
                m.saturation>.42->"채도를 낮추고 주조색 하나를 남기는 편이"
                m.hueVariety>=8->"색이 많은 화면이므로 주조색 두 개로 정리하는 편이"
                else->"광원의 색을 그대로 두고 피사체의 고유색을 살리는 편이"
            }
            Axis.SHARPNESS->when {
                strong->"과도한 샤프닝 없이 지금의 초점과 조리개를 유지하는 편이"
                m.sharpness<60.0->"셔터 속도를 초점거리의 역수보다 빠르게 잡고 삼각대나 벽에 기대는 편이"
                m.localContrast<12.0->"조리개를 반 스톱 조여 국소 대비를 올리는 편이"
                m.edges<.01->"초점 위치를 피사체의 눈이나 윤곽으로 옮기는 편이"
                else->"초점을 피사체의 핵심부에 고정하고 흔들림을 줄이는 편이"
            }
            Axis.EMOTION->when {
                strong->"지금의 톤 방향을 유지하며 피사체와 배경의 온도 차를 살리는 편이"
                a.subjects.isEmpty()->"장면에 온도를 줄 요소를 하나 더 넣거나 덜어내는 편이"
                else->"피사체의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하는 편이"
            }
            Axis.TIMING->if(a.subjects.any{it.label.lowercase() in HUMAN}) "제스처가 정점에 이르는 순간까지 기다렸다가 누르는 편이" else "장면의 움직임이 멈추는 지점을 골라 누르는 편이"
        }
        return when(tone) {
            Tone.MENTOR->"$body 좋겠습니다."
            Tone.CRITIC->"$body 낫습니다."
            Tone.ANALYST->"$body 위 측정값과 맞습니다."
        }
    }

    private fun actionFor(a:Assessment,axis:Axis,tone:Tone):String=suggestion(a,axis,tone)

    private fun shooting(a:Assessment,persona:Persona,level:Level,tone:Tone,depth:Depth,weak:List<Int>):String {
        val t=Sentences(depth.sentences)
        val first=Axis.entries[weak[0]]
        val second=Axis.entries[weak[1]]
        val personaCue=when(persona) {
            Persona.BRESSON->"인물의 제스처와 배경의 선이 겹치는 순간을 기다려 한 프레임에 담는"
            Persona.ADAMS->"밝은 톤과 어두운 톤의 배치를 먼저 계획하고 그 자리에 서는"
            Persona.LEITER->"반사와 가림, 흐림을 피하지 말고 겹쳐서 시적 층을 만드는"
            Persona.FAN_HO->"역광 실루엣과 그림자의 기하가 만나는 지점을 노리는"
        }
        t.add(when(level) {
            Level.GENEROUS->"다음 촬영에서는 이런 시도를 권합니다."
            Level.BALANCED->"촬영 제안은 이렇습니다."
            Level.STRICT->"완성도를 올리려면 촬영 단계에서 다음을 지켜야 합니다."
        })
        t.add("${first.title}: ${suggestion(a,first,tone)}")
        t.add("${second.title}: ${suggestion(a,second,tone)}")
        if(depth!=Depth.QUICK) t.add("${persona.title} 관점에서는 $personaCue 쪽을 우선해 보십시오.")
        if(depth==Depth.EXPERT) t.add("측정값은 사진 한 장의 결과이고, 같은 장면에서도 노출과 위치를 바꾸면 수치가 달라집니다. 연속으로 몇 장을 찍어 비교해 보십시오.")
        return t.build()
    }

    private fun editing(a:Assessment,level:Level,tone:Tone,depth:Depth,weak:List<Int>):String {
        val m=a.measure
        val t=Sentences(depth.sentences)
        val steps=mutableListOf<String>()
        if(m.highlights>.02) steps+="하이라이트 −30~−50으로 회수"
        if(m.shadows>.03) steps+="암부 +20~+40으로 들어 올리되 검정은 남기기"
        if(m.contrast<20.0) steps+="블랙 포인트를 살짝 내려 계조 폭 확보"
        if(m.channelCast>8.0) steps+=if(m.warmth>0) "화이트밸런스에서 R을 3~6 낮추기" else "화이트밸런스에서 B를 3~6 낮추기"
        if(m.saturation>.42) steps+="채도 −5~−10으로 정리"
        if(m.sharpness<60.0) steps+="반경 1.0 이하 샤프닝 +15~+25와 노이즈 억제"
        if(band(a.evidence[weak[0]].score)<=1) when(Axis.entries[weak[0]]) {
            Axis.COMPOSITION->steps+="크롭으로 피사체를 삼분할 교차점에 재배치"
            Axis.SHARPNESS->steps+="부분 대비와 샤프닝으로 윤곽 보강"
            Axis.LIGHTING->steps+="커브로 중간톤 대비 조정"
            Axis.COLOR->steps+="부분 채도 조정으로 주조색 강화"
            else->{}
        }
        t.add(when(level) {
            Level.GENEROUS->"보정은 최소한으로, 다음 순서를 참고하십시오."
            Level.BALANCED->"보정 제안은 이 순서를 따르면 됩니다."
            Level.STRICT->"보정으로 가릴 수 없는 결함은 다시 찍어야 합니다. 남은 수정은 다음 순서로 하십시오."
        })
        if(steps.isEmpty()) {
            t.add("측정된 클리핑과 채널 치우침이 작아, 원본의 톤과 색을 그대로 두는 편이 좋습니다.")
        } else {
            t.add(steps.take(if(depth==Depth.QUICK) 2 else if(depth==Depth.STANDARD) 3 else 5).joinToString(", ")+".")
        }
        if(depth!=Depth.QUICK&&steps.isNotEmpty()) t.add(when(tone) {
            Tone.MENTOR->"과보정을 피하고 원본의 톤을 남기는 선에서 마무리하시면 좋겠습니다."
            Tone.CRITIC->"보정으로 없는 디테일을 만들 수는 없습니다. 원본에서 확보한 정보 안에서만 손대십시오."
            Tone.ANALYST->"보정은 측정값을 바꾸는 작업이므로, 바꾼 뒤의 휘도와 대비를 다시 확인하기를 권장합니다."
        })
        return t.build()
    }
}
