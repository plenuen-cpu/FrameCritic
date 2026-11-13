package com.framecritic.app

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Deterministic offline critic engine.
 *
 * Every number here comes from NIMA (10-bin distribution), OpenCV measurements or MediaPipe
 * detection on the device. The engine composes Korean prose from those numbers only: it never
 * invents a subject, a light direction or an emotion that the measurements do not support.
 *
 * 2.1.0 구조:
 *  - 화면에 보이는 비평 문장에는 수치와 기계적 어휘를 쓰지 않는다. 사진을 보는 사람의 언어로
 *    빛, 색, 구도, 질감, 분위기를 말한다.
 *  - 모든 원시 측정값은 measurements()가 돌려주는 '상세 측정 데이터 및 기술적 근거' 블록에만 남는다.
 *  - 같은 사진이면 같은 문장이 나오도록 문장 선택은 측정값에서 계산한 시드로만 결정한다.
 * No Android type is used, so the whole engine runs in JVM unit tests.
 */
data class LineCandidate(val angle: Double, val length: Double)

data class SubjectBox(val label: String, val confidence: Double, val cx: Double, val cy: Double, val w: Double, val h: Double) {
    val area: Double get() = w * h
    val touchesEdge: Boolean get() = cx - w / 2 < .02 || cy - h / 2 < .02 || cx + w / 2 > .98 || cy + h / 2 > .98
}

data class Measure(
    val width: Int, val height: Int, val luminance: Double, val highlights: Double, val shadows: Double,
    val contrast: Double, val localContrast: Double, val sharpness: Double,
    val rgb: List<Double>, val hues: List<Int>, val histogram: List<Int>,
    val lines: List<LineCandidate>, val saturation: Double, val edges: Double
) {
    fun percentile(p: Double): Int {
        if (histogram.isEmpty()) return 0
        val total = histogram.sum().toDouble()
        if (total <= 0.0) return 0
        var acc = 0.0
        for (i in histogram.indices) { acc += histogram[i]; if (acc / total >= p) return i }
        return histogram.size - 1
    }
    val channelCast: Double get() = if (rgb.size < 3) 0.0 else (rgb.maxOrNull() ?: 0.0) - (rgb.minOrNull() ?: 0.0)
    val warmth: Double get() = if (rgb.size < 3) 0.0 else rgb[0] - rgb[2]
    val hueTotal: Int get() = hues.sum()
    val dominantHue: Int get() {
        var best = 0
        for (i in hues.indices) if (hues[i] > hues[best]) best = i
        return best
    }
    val dominantShare: Double get() {
        val total = hueTotal
        return if (total <= 0) 0.0 else hues[dominantHue].toDouble() / total
    }
    val hueVariety: Int get() {
        val total = hueTotal
        if (total <= 0) return 0
        return hues.count { it.toDouble() / total >= .03 }
    }
    val hueCoverage: Double get() {
        val pixels = width.toLong() * height.toLong()
        return if (pixels <= 0) 0.0 else hueTotal.toDouble() / pixels
    }
    val hueEntropy: Double get() {
        val total = hueTotal
        if (total <= 0) return 0.0
        var e = 0.0
        for (h in hues) { val p = h.toDouble() / total; if (p > 0.0) e -= p * ln(p) }
        return (e / ln(12.0)).coerceIn(0.0, 1.0)
    }
    val maxTilt: Double get() = lines.maxOfOrNull { abs(it.angle) } ?: 0.0
}

data class Assessment(
    val nima: NimaResult,
    val measure: Measure,
    val subjects: List<SubjectBox>,
    /** 사진의 주인공과 장면. 읽지 못했으면 null이다. */
    val scene: SubjectScene?,
    val evidence: List<Evidence>,
    val observations: Map<String, String>,
    val description: String
) {
    fun observation(axis: Axis): String = observations[axis.name].orEmpty()
    fun evidenceOf(axis: Axis): Evidence = evidence[axis.ordinal]
}

/** 상세 측정 데이터 블록의 한 묶음. 화면과 복사 텍스트가 같은 내용을 쓴다. */
data class MeasureGroup(val title: String, val rows: List<Pair<String, String>>)

object CriticEngine {
    private val HUMAN = setOf("person", "cat", "dog", "bird", "horse", "sheep", "cow")
    private val HUE_NAMES = listOf("빨강", "주황", "노랑", "연두", "초록", "청록", "하늘", "파랑", "남보라", "보라", "자주", "분홍")
    private val LABELS = mapOf(
        "person" to "인물", "bicycle" to "자전거", "car" to "자동차", "motorcycle" to "오토바이", "airplane" to "비행기",
        "bus" to "버스", "train" to "기차", "truck" to "트럭", "boat" to "배", "traffic light" to "신호등", "bench" to "벤치",
        "bird" to "새", "cat" to "고양이", "dog" to "개", "horse" to "말", "sheep" to "양", "cow" to "소", "elephant" to "코끼리",
        "bear" to "곰", "zebra" to "얼룩말", "giraffe" to "기린", "backpack" to "배낭", "umbrella" to "우산", "handbag" to "가방",
        "tie" to "넥타이", "suitcase" to "여행 가방", "frisbee" to "원반", "skis" to "스키", "snowboard" to "스노보드",
        "sports ball" to "공", "kite" to "연", "baseball bat" to "야구 방망이", "baseball glove" to "야구 글러브",
        "skateboard" to "스케이트보드", "surfboard" to "서핑보드", "tennis racket" to "테니스 라켓", "bottle" to "병",
        "wine glass" to "와인잔", "cup" to "컵", "fork" to "포크", "knife" to "칼", "spoon" to "숟가락", "bowl" to "그릇",
        "banana" to "바나나", "apple" to "사과", "sandwich" to "샌드위치", "orange" to "오렌지", "broccoli" to "브로콜리",
        "carrot" to "당근", "hot dog" to "핫도그", "pizza" to "피자", "donut" to "도넛", "cake" to "케이크", "chair" to "의자",
        "couch" to "소파", "potted plant" to "화분", "bed" to "침대", "dining table" to "식탁", "toilet" to "변기",
        "tv" to "모니터", "laptop" to "노트북", "mouse" to "마우스", "remote" to "리모컨", "keyboard" to "키보드",
        "cell phone" to "휴대폰", "microwave" to "전자레인지", "oven" to "오븐", "toaster" to "토스터", "sink" to "싱크대",
        "refrigerator" to "냉장고", "book" to "책", "clock" to "시계", "vase" to "꽃병", "scissors" to "가위",
        "teddy bear" to "곰인형", "hair drier" to "헤어드라이어", "toothbrush" to "칫솔"
    )
    /** Every axis is grounded in at least one direct measurement, so no axis is left without a reading. */
    private const val CONFIDENCE_FLOOR = .42

    /** 화면 본문에 보여줄 비평 항목. 수치와 기술 용어는 이 목록이 아니라 measurements()에만 담는다. */
    val SECTION_TITLES = listOf("종합 크리틱", "빛과 노출", "색채", "구도", "선명도와 질감", "분위기와 순간", "잘 살아난 점", "더 다듬을 점", "촬영 제안", "보정 제안")

    // ---------- Korean particles and formatting ----------
    private fun koLabel(label: String): String = LABELS[label.lowercase()] ?: label
    private fun hasFinalConsonant(word: String): Boolean {
        if (word.isEmpty()) return false
        val c = word.last()
        return c in '가'..'힣' && (c.code - '가'.code) % 28 != 0
    }
    private fun eun(word: String) = word + (if (hasFinalConsonant(word)) "은" else "는")
    private fun wa(word: String) = word + (if (hasFinalConsonant(word)) "과" else "와")
    private fun iGa(word: String) = word + (if (hasFinalConsonant(word)) "이" else "가")
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun p1(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)
    private fun p2(v: Double) = String.format(Locale.US, "%.2f%%", v * 100)
    private fun p0(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
    /** "180.0으로" / "44.5로": the particle follows the spoken last digit. */
    private fun numRo(v: Double): String {
        val text = f1(v)
        val last = text.last()
        return text + (if (last in "013678") "으로" else "로")
    }
    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
    private fun finite(v: Double, fallback: Double) = if (v.isFinite()) v else fallback
    private fun ramp(v: Double, low: Double, high: Double) = if (high <= low) 0.0 else clamp01((v - low) / (high - low))
    private fun band(score: Double) = when { score >= .72 -> 2; score >= .45 -> 1; else -> 0 }
    private fun nearestThirds(cx: Double, cy: Double): Double {
        var best = 1.0
        for (tx in listOf(1.0 / 3.0, 2.0 / 3.0)) for (ty in listOf(1.0 / 3.0, 2.0 / 3.0)) {
            val d = hypot(cx - tx, cy - ty)
            if (d < best) best = d
        }
        return best
    }
    /** 같은 사진이면 같은 문장이 나오도록, 측정값에서만 문장 변형 시드를 만든다. */
    private fun seedOf(m: Measure): Int {
        val raw = m.luminance * 7 + m.contrast * 3 + m.sharpness + m.saturation * 97 + m.edges * 1000 + m.width
        return if (raw.isFinite()) raw.roundToInt() else 0
    }
    private fun pick(seed: Int, vararg items: String): String = items[Math.floorMod(seed, items.size)]
    private fun toneSeed(seed: Int, salt: Int) = Math.floorMod(seed + salt * 37, 1_000_003)
    /** 히스토그램이 비어 있어도(중립 측정값) 톤 분류가 가능하도록 평균 휘도로 대신한다. */
    private fun medianTone(m: Measure): Int = if (m.histogram.isNotEmpty() && m.histogram.sum() > 0) m.percentile(.5) else m.luminance.roundToInt()

    // ---------- assessment ----------
    /** 한 항목의 계산이 실패해도 다른 항목의 근거는 남기고, 그 항목만 중립값으로 둔다. */
    private fun neutralAx() = Ax(.5, CONFIDENCE_FLOOR, "이 항목의 측정값을 읽지 못했습니다.", "이 부분은 이번 측정에서 판단할 근거를 얻지 못했습니다.")

    fun assess(nima: NimaResult, measure: Measure, subjects: List<SubjectBox>, scene: SubjectScene? = null): Assessment {
        val sorted = subjects
            .filter { it.confidence.isFinite() && it.cx.isFinite() && it.cy.isFinite() && it.w.isFinite() && it.h.isFinite() }
            .sortedByDescending { it.area }
        val builders = listOf<() -> Ax>(
            { composition(measure, sorted) },
            { lighting(measure) },
            { color(measure) },
            { sharpness(measure) },
            { emotion(nima, measure, sorted) },
            { timing(measure, sorted) }
        )
        val axes = builders.map { build -> runCatching(build).getOrElse { neutralAx() } }
        val evidence = axes.mapIndexed { i, ax ->
            val title = Axis.entries.getOrElse(i) { Axis.entries.last() }.title
            Evidence(
                finite(ax.score, .5).coerceIn(.05, .95),
                finite(ax.confidence, CONFIDENCE_FLOOR).coerceIn(CONFIDENCE_FLOOR, 1.0),
                ax.observation.ifBlank { "$title 항목의 관찰 값을 만들지 못했습니다." },
                ax.interpretation.ifBlank { "이 부분은 이번 측정에서 판단할 근거를 얻지 못했습니다." }
            )
        }
        val observations = Axis.entries.mapIndexed { i, axis ->
            axis.name to (evidence.getOrNull(i)?.observation ?: "${axis.title} 항목의 관찰을 만들지 못했습니다.")
        }.toMap()
        val description = runCatching { describe(measure, sorted) }
            .getOrElse { "측정값 요약을 만들지 못했습니다. 아래 항목별 근거를 참고하십시오." }
        return Assessment(nima, measure, sorted, scene, evidence, observations, description)
    }

    /** 어떤 입력이나 내부 오류에도 앱을 종료시키지 않고 Assessment를 돌려준다. */
    fun safeAssess(nima: NimaResult, measure: Measure, subjects: List<SubjectBox>, scene: SubjectScene? = null): Assessment =
        runCatching { assess(nima, measure, subjects, scene) }.getOrElse { neutralAssessment(nima, measure, subjects, scene) }

    private fun neutralAssessment(nima: NimaResult, measure: Measure, subjects: List<SubjectBox>, scene: SubjectScene? = null): Assessment {
        val evidence = Axis.entries.map { axis ->
            Evidence(.5, CONFIDENCE_FLOOR, "${axis.title} 항목의 측정값을 정리하지 못했습니다.", "이 부분은 이번 측정에서 판단할 근거를 얻지 못했습니다.")
        }
        val observations = Axis.entries.mapIndexed { i, axis -> axis.name to evidence[i].observation }.toMap()
        return Assessment(nima, measure, subjects, scene, evidence, observations, "측정값 요약을 만들지 못해 항목별 근거만 남겼습니다.")
    }

    private data class Ax(val score: Double, val confidence: Double, val observation: String, val interpretation: String)

    // ---------- measurements (numbers only live here) ----------
    private fun composition(m: Measure, subjects: List<SubjectBox>): Ax {
        val main = subjects.firstOrNull()
        // 가장 큰 피사체가 화면을 크게 채우면 가장자리에 닿는 것이 자연스럽다. 그 상자는 조임으로 세지 않는다.
        val crowded = subjects.count { it.touchesEdge && it.area > .05 && !(it === main && it.area >= .3) }
        val tiltScore = if (m.lines.isEmpty()) 0.0 else 1.0 - ramp(m.maxTilt, 1.0, 9.0)
        val placement = if (main == null) 0.5 else 1.0 - ramp(nearestThirds(main.cx, main.cy), .02, .32)
        val room = 1.0 - ramp(crowded.toDouble(), 0.0, 2.0)
        val size = if (main == null) 0.5 else 1.0 - ramp(abs(main.area - .18), .10, .42)
        val score = if (main == null) .46 + .10 * tiltScore else .18 + .44 * placement + .16 * room + .12 * size + .10 * tiltScore
        val confidence = when {
            subjects.isNotEmpty() && m.lines.isNotEmpty() -> .86
            subjects.isNotEmpty() -> .74
            m.lines.isNotEmpty() -> .56
            else -> .44
        }
        val labels = subjects.take(3).joinToString(", ") { "${koLabel(it.label)} ${f2(it.confidence)}" }
        val subjectText = if (main == null) "COCO 범주 피사체는 검출되지 않았습니다. 빈 프레임이라는 뜻은 아닙니다."
        else "피사체 ${subjects.size}개가 검출되었습니다($labels). 가장 큰 피사체는 중심 (${p0(main.cx)}, ${p0(main.cy)})에서 프레임의 ${p1(main.area)}를 차지하고, 삼분할 교차점에서 프레임 폭의 ${p0(nearestThirds(main.cx, main.cy))}만큼 떨어져 있습니다."
        val lineText = if (m.lines.isEmpty()) "수평선 후보는 검출되지 않았습니다."
        else "수평선 후보 ${m.lines.size}개의 최대 기울기는 ${f1(m.maxTilt)}°입니다."
        val edgeText = if (crowded > 0) " 가장자리에 닿은 피사체가 ${crowded}개 있습니다." else ""
        val observation = "$subjectText$edgeText $lineText"
        val interpretation = if (main == null) when (band(score)) {
            2 -> "대상 없이도 면과 빛의 배열이 화면을 지탱합니다. 여백이 사진의 주제가 된 드문 경우입니다."
            1 -> "여백이 주인공이 된 화면이라, 중심을 잡아 줄 요소가 하나만 더 있으면 인상이 완성됩니다."
            else -> "화면 안에서 눈이 머물 자리를 찾기 어려워, 사진의 인상이 흩어집니다."
        } else when (band(score)) {
            2 -> "시선이 한 번에 장면을 읽게 되는, 계산된 여백의 힘입니다."
            1 -> "장면은 읽히지만 중심이 흐려, 보는 사람이 어디에 시선을 둘지 잠시 망설이게 됩니다."
            else -> "프레임이 대상을 붙잡지 못해, 사진의 인상이 가장자리로 흘러 나갑니다."
        }
        return Ax(score, confidence, observation, interpretation)
    }

    private fun lighting(m: Measure): Ax {
        val exp = m.luminance / 255.0
        val placement = 1.0 - ramp(abs(exp - .46), .02, .34)
        val clip = m.highlights + m.shadows
        val clipScore = 1.0 - ramp(clip, .01, .12)
        val separation = ramp(m.contrast, 12.0, 52.0)
        val local = ramp(m.localContrast, 3.0, 20.0)
        val score = .30 * (.45 + .55 * placement) + .24 * clipScore + .28 * separation + .18 * local
        val observation = "휘도 평균 ${f1(m.luminance)}/255, 중간값 ${m.percentile(.5)}, 5퍼센타일 ${m.percentile(.05)}, 95퍼센타일 ${m.percentile(.95)}입니다. 하이라이트 ${p2(m.highlights)}, 암부 ${p2(m.shadows)}가 잘렸고, 전역 대비 ${f1(m.contrast)}, 국소 대비 ${f1(m.localContrast)}입니다."
        val interpretation = when (band(score)) {
            2 -> "빛이 형태를 설명하고 있어, 사진을 보는 동안 장면의 공기까지 느껴집니다."
            1 -> "빛이 고르게 퍼진 대신 깊이가 옅어, 장면이 평평하게 읽힙니다."
            else -> "빛이 담고 있던 정보가 양 끝에서 사라져, 형태를 설명하는 힘이 약해졌습니다."
        }
        val confidence = if (clip < .02) .92 else .88
        return Ax(score, confidence, observation, interpretation)
    }

    private fun color(m: Measure): Ax {
        val castScore = 1.0 - ramp(m.channelCast, 6.0, 30.0)
        val hueScore = if (m.hueCoverage < .05) .50 else ramp(m.hueEntropy, .35, .85)
        val satScore = 1.0 - ramp(abs(m.saturation - .22), .06, .30)
        var score = .34 * castScore + .34 * hueScore + .32 * (.35 + .65 * satScore)
        if (m.saturation < .10) score -= .08
        val hueText = if (m.hueTotal <= 0) "유채색 픽셀이 거의 없어 색상 분포를 읽기 어렵습니다."
        else "색상 12구간 중 ${m.hueVariety}개에 분포가 있고 ${HUE_NAMES[m.dominantHue]} 계열이 ${p0(m.dominantShare)}로 가장 넓습니다."
        val observation = "평균 RGB는 ${f1(m.rgb.getOrElse(0) { 0.0 })}/${f1(m.rgb.getOrElse(1) { 0.0 })}/${f1(m.rgb.getOrElse(2) { 0.0 })}, 채널 간 차이는 ${f1(m.channelCast)}, 평균 채도는 ${p1(m.saturation)}입니다. $hueText"
        val interpretation = when {
            m.saturation < .12 -> "색을 덜어낸 화면은 형태와 빛으로 말을 걸지만, 색의 온기로 감정을 만드는 힘은 줄어듭니다."
            m.channelCast > 12.0 -> "색온도가 한쪽으로 기울어 있어, 피사체의 고유색이 배경과 구분되는지 확인할 지점입니다."
            band(score) == 2 -> "색이 서로를 지지하며 하나의 온도로 정리되어, 화면이 차분하게 가라앉습니다."
            band(score) == 1 -> "색은 자연스럽지만 방향이 뚜렷하지 않아, 분위기를 색이 끌어 주지는 못합니다."
            else -> "색이 흩어져 화면의 인상이 하나로 모이지 않습니다."
        }
        val confidence = if (m.saturation < .12 || m.hueCoverage < .05) .62 else .74
        return Ax(score, confidence, observation, interpretation)
    }

    private fun sharpness(m: Measure): Ax {
        val sharpScore = ramp(log10(1.0 + m.sharpness), log10(16.0), log10(251.0))
        val edgeScore = ramp(m.edges, .004, .09)
        val score = .16 + .58 * sharpScore + .26 * edgeScore
        val observation = "Laplacian 분산 ${f1(m.sharpness)}, 에지 밀도 ${p2(m.edges)}, 국소 대비 ${f1(m.localContrast)}이며 분석 해상도는 ${m.width}×${m.height}입니다."
        val interpretation = when (band(score)) {
            2 -> "초점이 놓인 자리가 분명해, 보는 눈이 자연스럽게 그곳으로 갑니다."
            1 -> "형태는 읽히지만 표면의 결이 눌려 있어, 질감의 밀도가 아쉽습니다."
            else -> "질감보다 분위기로 읽히는 사진입니다. 의도된 흐림이라면 그 자체로 표현이 됩니다."
        }
        return Ax(score, .82, observation, interpretation)
    }

    private fun emotion(nima: NimaResult, m: Measure, subjects: List<SubjectBox>): Ax {
        val human = subjects.any { it.label.lowercase() in HUMAN }
        val main = subjects.firstOrNull()
        val p50 = medianTone(m)
        val observedMedian = m.percentile(.5)
        var s = .44
        if (subjects.isNotEmpty()) s += .08
        if (human) s += .06
        if (main != null && main.area in .04..0.55) s += .05
        if (p50 < 85) s += .05
        if (m.contrast >= 40) s += .05
        if (m.saturation >= .28) s += .04
        if (m.channelCast >= 10) s += .03
        if (m.contrast < 16) s -= .07
        if (subjects.isEmpty()) s -= .05
        val mainText = main?.let { "가장 큰 피사체(${koLabel(it.label)})가 프레임의 ${p1(it.area)}를 차지합니다" }
            ?: "COCO 범주 피사체는 검출되지 않았습니다"
        val keyText = when {
            p50 < 70 -> "저조도(low-key) 톤"
            p50 > 170 -> "고조도(high-key) 톤"
            else -> "중간톤"
        }
        val tempText = when {
            m.channelCast < 6 -> "색온도 치우침이 거의 없는 중성"
            m.warmth > 0 -> "붉은 쪽으로 기운 따뜻한"
            else -> "푸른 쪽으로 기운 차가운"
        }
        val observation = "$keyText(중간값 휘도 $observedMedian)이고, $mainText. 채널 차 ${f1(m.channelCast)}의 $tempText 색입니다. 감정은 측정값이 아니므로 이 지표들에서 추정합니다."
        val interpretation = when (band(s)) {
            2 -> "장면의 온도가 한 방향으로 모여 있어, 사진을 오래 들여다보게 하는 힘이 있습니다."
            1 -> "분위기는 읽히지만 인상이 중립적이어서, 감정의 온도가 한 단계 더 올라가면 좋겠습니다."
            else -> "장면이 정보를 담는 데 머물러 있어, 보는 사람의 마음을 건드리는 지점은 적습니다."
        }
        val confidence = .40 + (if (human) .12 else if (subjects.isNotEmpty()) .06 else 0.0) + (if (nima.deviation < 2.0) .06 else 0.0)
        return Ax(s, confidence, observation, interpretation)
    }

    private fun timing(m: Measure, subjects: List<SubjectBox>): Ax {
        val human = subjects.any { it.label.lowercase() in HUMAN }
        var s = .46
        if (subjects.isNotEmpty()) s += .06
        if (human) s += .08
        if (m.sharpness >= 120) s += .04
        if (m.localContrast >= 14) s += .04
        if (subjects.size > 6) s -= .05
        val observation = if (subjects.isEmpty()) "피사체가 검출되지 않아 배열을 읽을 대상이 없습니다. Laplacian 분산 ${f1(m.sharpness)}, 국소 대비 ${f1(m.localContrast)}입니다."
        else "피사체 ${subjects.size}개가 정지 배열로 잡혀 있습니다. Laplacian 분산 ${f1(m.sharpness)}, 국소 대비 ${f1(m.localContrast)}로 포착의 또렷함을 가늠합니다."
        val interpretation = when (band(s)) {
            2 -> "포착이 또렷해 장면이 살아 있는 순간을 붙잡은 인상이 있습니다."
            1 -> "제스처나 움직임의 흔적이 드러나지 않아, 순간의 긴장은 약합니다."
            else -> "장면이 일어나기 전이나 지나간 뒤를 담은 듯, 정지된 공기가 남습니다."
        }
        val confidence = .30 + (if (subjects.isNotEmpty()) .10 else 0.0) + (if (human) .06 else 0.0)
        return Ax(s, confidence, observation, interpretation)
    }

    private fun describe(m: Measure, subjects: List<SubjectBox>): String {
        val main = subjects.firstOrNull()
        val toneText = when {
            m.percentile(.5) < 70 -> "어두운 톤"
            m.percentile(.5) > 170 -> "밝은 톤"
            else -> "중간 톤"
        }
        val subjectText = if (main == null) "COCO 범주 피사체는 검출되지 않았습니다."
        else "피사체 ${subjects.size}개가 검출되었고, 가장 큰 피사체는 ${koLabel(main.label)}이며 프레임의 ${p1(main.area)}를 차지합니다."
        val sharpText = when (band(ramp(log10(1.0 + m.sharpness), log10(16.0), log10(251.0)))) {
            2 -> "디테일이 또렷한 편입니다."
            1 -> "디테일은 무난한 수준입니다."
            else -> "디테일이 무딘 편입니다."
        }
        val hueText = if (m.hueTotal <= 0) "유채색이 거의 없습니다." else "주조색은 ${HUE_NAMES[m.dominantHue]} 계열이고 평균 채도는 ${p1(m.saturation)}입니다."
        return "휘도 평균 ${f1(m.luminance)}/255의 $toneText 사진입니다. $subjectText $hueText Laplacian 분산은 ${numRo(m.sharpness)} $sharpText"
    }

    // ---------- 자연스러운 비평 문장 (수치 없음) ----------
    /**
     * 화면에 보이는 글은 측정값에서 계산한 조건과 시드로만 고른다. 같은 사진이면 같은 문장이 나오고,
     * 이 글에는 수치도 계측 어휘도 쓰지 않는다. 모든 원시 값은 measurements() 블록에만 남는다.
     * 한 항목은 4~9문장으로 늘어나며, 깊이(Quick·Standard·Expert)가 문장 수를 정한다.
     */
    private fun say(seed: Int, salt: Int, vararg items: String): String =
        items[Math.floorMod(seed + salt * 37, items.size)]

    private fun toneEnd(tone: Tone, body: String): String = when (tone) {
        Tone.MENTOR -> "$body 좋겠습니다."
        Tone.CRITIC -> "$body 낫습니다."
        Tone.ANALYST -> "$body 화면에 드러난 요소와 맞습니다."
    }

    // ---------- 빛과 노출 ----------
    /** 톤의 위치. 화면이 어디에서 숨을 쉬고 있는지 먼저 말한다. */
    private fun lightTone(m: Measure, seed: Int): String {
        val p50 = medianTone(m)
        return when {
            p50 < 70 -> say(seed, 11,
                "화면은 낮은 빛 속에 잠겨 있습니다. 어둠이 먼저 다가오고, 그 위로 밝은 면이 드문드문 떠올라 시선이 자연스럽게 그쪽으로 모입니다.",
                "장면 전체가 어스름한 톤에 놓여 있습니다. 어두운 면이 화면의 대부분을 차지하고, 남은 빛이 그 사이를 조용히 지나갑니다.")
            p50 > 170 -> say(seed, 12,
                "화면은 밝은 쪽으로 활짝 열려 있습니다. 빛이 넓게 퍼져 장면이 가볍게 떠 있는 듯하고, 그림자는 인상을 눌러 주는 정도로만 남아 있습니다.",
                "빛이 화면을 가득 채웁니다. 한낮의 공기처럼 산뜻하게 읽히고, 어두운 면은 형태를 구분해 주는 역할에 머물러 있습니다.")
            else -> say(seed, 13,
                "빛은 화면의 중간 어디쯤에서 균형을 잡고 있습니다. 어두운 면과 밝은 면이 서로의 자리를 나누어 장면이 담담하게 펼쳐집니다.",
                "화면의 밝기는 무난한 자리에 놓여 있습니다. 빛이 한쪽으로 치우치지 않아 인상이 차분하고, 눈이 편하게 머무를 수 있습니다.")
        }
    }

    /** 계조의 폭. 밝은 끝과 어두운 끝이 어디서 닫히는지. */
    private fun lightRange(m: Measure, seed: Int): String = when {
        m.highlights > .02 && m.shadows > .04 -> say(seed, 21,
            "다만 가장 밝은 부분과 가장 어두운 부분이 함께 닫혀 있습니다. 눈이 부드럽게 넘어갈 중간 계조가 비어 있어, 장면이 두 덩어리로 갈라져 보입니다.",
            "밝은 끝과 어두운 끝이 동시에 막혀 있습니다. 그 사이를 이어 줄 계조가 얇아, 형태가 서로 다른 온도로 떨어져 있습니다.")
        m.highlights > .02 -> say(seed, 22,
            "가장 밝은 면은 흰색으로 눌려 하나의 덩어리로 뭉쳤습니다. 그 안에 있던 결은 더 이상 읽히지 않고, 빛의 존재만 남았습니다.",
            "밝은 쪽이 끝까지 열리며 하얗게 타 버렸습니다. 밝은 면이 넓을수록 그 안의 정보는 빠르게 사라집니다.")
        m.shadows > .04 -> say(seed, 23,
            "어두운 면은 검정으로 가라앉아 그 안의 모양이 지워졌습니다. 그림자가 무게를 얻지 못하고, 형태 대신 구멍처럼 비어 있습니다.",
            "어두운 쪽이 바닥까지 닫혀 있습니다. 그림자 속에 남았어야 할 형태가 사라져, 어둠이 이야기의 여백이 아니라 빈 공간이 되었습니다.")
        m.contrast >= 40 -> say(seed, 24,
            "밝은 곳과 어두운 곳의 낙차가 크고, 어느 쪽도 눌리지 않았습니다. 빛이 지나온 자리가 또렷하게 남아 장면에 무게가 실립니다.",
            "명암의 폭이 넓게 열려 있습니다. 밝은 면은 밝은 채로, 어두운 면은 어두운 채로 각자의 결을 지키고 있습니다.")
        m.contrast < 18 -> say(seed, 25,
            "밝은 곳과 어두운 곳의 차이가 완만합니다. 명암이 부드럽게 이어져 장면이 한 폭의 고른 면처럼 펼쳐집니다.",
            "빛의 낙차가 잦아들어 화면 전체가 비슷한 밝기로 흐릅니다. 편안하지만, 눈을 붙잡는 힘은 약한 편입니다.")
        else -> say(seed, 26,
            "밝은 곳과 어두운 곳이 고르게 살아 있습니다. 빛이 형태의 윤곽을 감싸고, 그림자가 그 윤곽을 다시 붙잡아 줍니다.",
            "명암이 양쪽으로 충분히 열려 있어, 장면의 앞뒤가 자연스럽게 구분됩니다.")
    }

    /** 가장 밝은 면의 결. 빛이 닿은 표면이 어떻게 남았는지. */
    private fun lightTexture(m: Measure, seed: Int): String = when {
        m.highlights > .02 -> say(seed, 31,
            "가장 밝은 면에서 표면의 결이 지워진 대신 윤곽만 남아, 그 자리는 빛이 있다는 사실만 알려 줍니다.",
            "밝은 면이 하나의 흰 덩어리로 합쳐져, 빛이 닿은 물체의 재질보다 빛 자체가 먼저 보입니다.")
        m.shadows < .02 && m.contrast >= 28 -> say(seed, 32,
            "밝은 끝의 계조가 마지막까지 남아 있습니다. 빛이 닿은 면이 흰 종이처럼 평평해지지 않고, 유리처럼 은은하게 반짝입니다.",
            "가장 밝은 부분에서도 표면의 결이 살아 있어, 빛이 부딪힌 자리에서 눈이 미끄러지지 않고 잠시 머뭅니다.")
        else -> say(seed, 33,
            "밝은 면이 끝까지 무너지지 않아, 빛이 닿은 곳에도 형태가 조용히 남아 있습니다.",
            "밝은 부분이 계단처럼 부드럽게 내려와, 빛의 세기가 표면을 따라 천천히 옮겨 가는 것이 느껴집니다.")
    }

    /** 어두운 면의 결. 그림자가 형태를 감싸는지 삼키는지. */
    private fun lightShadow(m: Measure, seed: Int): String = when {
        m.shadows > .04 -> say(seed, 41,
            "어두운 면의 계조가 끊겨, 그림자가 형태를 감싸는 대신 통째로 삼켰습니다.",
            "그림자 속에서 형태가 이어지지 않아, 어두운 면은 다음 장면으로 넘어가는 여백처럼 읽힙니다.")
        else -> say(seed, 42,
            "어두운 면에도 계조가 남아 있습니다. 그림자가 형태를 감싸며 무게를 갖고, 밝은 면과 서로를 밀어 줍니다.",
            "그림자가 검정으로 닫히지 않아, 어두운 곳에서도 사물의 윤곽이 천천히 드러납니다.")
    }

    /** 빛이 형태를 빚는 방식. */
    private fun lightModel(m: Measure, seed: Int): String = when {
        m.contrast >= 38 && m.localContrast >= 12 -> say(seed, 51,
            "빛이 면과 면 사이에 경계를 그어 주어, 형태가 서로를 밀어내며 공간에 서 있습니다.",
            "밝은 면과 어두운 면이 교차하며 덩어리를 나누고, 그 경계에서 사물의 부피가 생겨납니다.")
        m.localContrast < 8 -> say(seed, 52,
            "빛이 넓게 퍼져 면과 면의 경계가 흐려졌습니다. 형태는 부피보다 분위기로 읽히고, 사물의 윤곽은 배경 속으로 부드럽게 풀립니다.",
            "국소적인 밝기 차가 얕아, 표면이 가진 요철이 거의 드러나지 않습니다. 사진은 입체보다 평면에 가깝게 다가옵니다.")
        else -> say(seed, 53,
            "빛이 형태를 세우는 힘은 절제되어 있습니다. 윤곽은 남아 있으나 부피감은 은은하게만 드러납니다.",
            "빛이 사물의 표면을 훑고 지나가며, 면의 방향에 따라 밝기가 조금씩 달라집니다.")
    }

    /** 화면에 드러난 빛의 성질. 방향은 단정하지 않고 인상만 말한다. */
    private fun lightQuality(m: Measure, seed: Int): String = when {
        m.contrast >= 35 -> say(seed, 61,
            "빛은 단단한 쪽에 가깝습니다. 밝음과 어둠의 경계가 분명해, 장면이 또렷한 목소리로 말합니다.",
            "빛이 곧게 떨어지는 듯한 인상입니다. 그림자의 가장자리가 서 있어 형태가 흔들리지 않습니다.")
        else -> say(seed, 62,
            "빛은 부드럽게 스미는 쪽에 가깝습니다. 경계가 무르게 풀려 장면 전체가 한 호흡으로 이어집니다.",
            "빛이 사방에서 감싸는 듯한 인상입니다. 그림자의 테두리가 부드러워 사물이 배경에서 급하게 떨어져 나오지 않습니다.")
    }

    /** 빛 항목의 해석. 밴드에 따라 확신의 정도를 바꾼다. */
    private fun lightReading(m: Measure, score: Double): String = when (band(score)) {
        2 -> "빛이 형태를 설명하고, 형태가 다시 빛을 받쳐 주는 관계가 화면에서 완성되어 있습니다. 사진을 보는 동안 장면의 공기까지 함께 느껴지고, 시선은 밝은 면과 어두운 면 사이를 천천히 오갑니다."
        1 -> if (m.highlights > .02 || m.shadows > .04)
            "빛은 장면을 읽게 해 주지만, 양 끝에서 조금씩 정보를 잃었습니다. 그 손실이 사진의 인상을 흐리지 않고 오히려 여백처럼 작동한다면 지금의 노출도 하나의 선택이 됩니다."
        else
            "빛은 안정적으로 자리를 잡았지만, 아직 장면을 밀어 올리는 힘은 부족합니다. 밝은 면과 어두운 면이 서로를 살리는 지점이 한 군데만 더 생겨도 사진의 깊이가 달라집니다."
        else -> "빛이 담고 있던 정보가 양 끝에서 사라져, 형태를 설명하는 힘이 약해졌습니다. 다만 지금의 어두운 분포 자체를 표현으로 삼는 방법도 남아 있습니다."
    }

    private fun lightAdvice(m: Measure, tone: Tone, strong: Boolean): String {
        val body = when {
            strong -> "지금의 노출을 그대로 두고, 밝은 면이 먼저 타 버리지 않게만 지키는 편이"
            m.highlights > .02 -> "노출을 반 스톱쯤 낮춰 가장 밝은 면의 결을 되찾는 편이"
            m.shadows > .04 -> "어두운 면을 살짝 들어 올리거나, 그림자가 향하는 방향을 바꿔 보는 편이"
            m.contrast < 18 -> "측광을 피사체에 맞추고 배경과의 밝기 차이를 만드는 편이"
            else -> "노출을 고정한 채 밝은 쪽과 어두운 쪽의 균형을 한 번 더 확인하는 편이"
        }
        return toneEnd(tone, body)
    }

    // ---------- 색채 ----------
    /** 색온도. 화면 전체가 어느 쪽으로 기울었는지. */
    private fun colorTemperature(m: Measure, seed: Int): String = when {
        m.warmth > 6.0 -> say(seed, 71,
            "화면 전체에 따뜻한 기운이 감돕니다. 노을이나 백열등 아래의 공기처럼 장면이 부드럽게 감싸이고, 사물의 윤곽도 함께 눅눅해집니다.",
            "붉은 기운이 넓게 퍼져 장면에 온기가 돕니다. 빛의 색이 사진의 정서를 먼저 결정하고 있습니다.")
        m.warmth < -6.0 -> say(seed, 72,
            "푸른 기운이 화면을 가라앉힙니다. 차분하고 서늘한 공기가 남아, 장면이 조금 멀리서 바라본 것처럼 읽힙니다.",
            "푸른 쪽으로 기울어 장면이 한결 차분해졌습니다. 색의 온도가 낮아지면서 형태가 사색적인 인상을 얻습니다.")
        else -> say(seed, 73,
            "색온도가 한쪽으로 치우치지 않아 중성에 가깝습니다. 사물의 고유색이 비교적 정직하게 남아 있습니다.",
            "색온도가 고르게 잡혀 있습니다. 화면의 색이 자연스럽고, 특정 색만 튀어 오르지 않습니다.")
    }

    /** 주조색과 보조색의 관계. 색의 구성. */
    private fun colorPigment(m: Measure, seed: Int): String = when {
        m.hueTotal <= 0 -> say(seed, 81,
            "유채색이 거의 남지 않은 화면입니다. 색을 덜어낸 대신 밝기와 형태만으로 장면을 구성했고, 그 선택이 사진을 단단하게 만듭니다.",
            "화면이 무채색으로 정리되어 있습니다. 색의 소란 없이 계조와 윤곽만 남아, 인상이 차분하게 가라앉습니다.")
        m.saturation < .12 -> say(seed, 82,
            "색은 옅게 깔려 있습니다. 색이 주장하지 않으므로 빛과 형태가 먼저 읽히고, 사진은 담백한 인상을 남깁니다.",
            "채도가 낮게 눌려 있어 색이 배경처럼 물러나 있습니다. 그 덕분에 화면의 분위기가 과하지 않습니다.")
        m.dominantShare >= .45 -> say(seed, 83,
            "한 가지 색이 화면의 절반 이상을 차지합니다. 주조색이 분명해 사진의 인상이 한 번에 정리되고, 다른 색은 그 옆에서 조용히 보조합니다.",
            "주조색이 화면을 넓게 덮고 있습니다. 색이 하나로 모이면서 장면 전체가 같은 온도로 정돈됩니다.")
        m.hueVariety >= 8 -> say(seed, 84,
            "여러 색이 고루 섞여 있습니다. 화면이 분주하고, 색과 색이 서로의 자리를 다투는 가운데 시선이 이리저리 옮겨 갑니다.",
            "색의 수가 많아 화면이 활기를 띱니다. 다만 색이 각자 말을 하려 해서, 인상이 하나로 모이기까지는 시간이 걸립니다.")
        else -> say(seed, 85,
            "몇 가지 색이 서로의 자리를 나누고 있습니다. 색의 수가 절제되어 화면이 단정하고 읽기 편합니다.",
            "색이 적당히 나뉘어 있어 주조색과 보조색의 관계가 무리 없이 읽힙니다.")
    }

    /** 채도의 온도와 감정적 공기. */
    private fun colorAir(m: Measure, seed: Int): String = when {
        m.saturation >= .42 -> say(seed, 91,
            "색의 기운이 진하게 살아 있습니다. 채도가 높은 색들이 서로를 밀어내며 화면에 생기를 넣고, 사진이 먼저 말을 겁니다.",
            "색이 힘 있게 살아 있어 장면이 또렷합니다. 다만 진한 색이 여럿 겹치면 눈이 쉴 자리가 줄어듭니다.")
        m.saturation < .12 -> say(seed, 92,
            "색의 온기가 낮아 화면이 사색적으로 흐릅니다. 감정이 색에서 오기보다 밝기와 형태에서 옵니다.",
            "색이 절제된 만큼 장면의 온도가 낮습니다. 차분함과 함께 조금 쓸쓸한 공기가 남습니다.")
        else -> say(seed, 93,
            "채도가 무난한 자리에 있어 색이 과하지도 무디지도 않습니다. 화면의 온도가 편안하게 유지됩니다.",
            "색의 세기가 적당해, 사진이 차분하면서도 생기를 잃지 않습니다.")
    }

    private fun colorReading(m: Measure, score: Double): String = when {
        m.saturation < .12 -> "색을 덜어낸 화면은 형태와 빛으로 말을 겁니다. 다만 색의 온기로 감정을 만드는 힘은 줄어들어, 사진이 조금 더 멀게 느껴집니다."
        m.channelCast > 14.0 -> if (m.warmth > 0)
            "색온도가 붉은 쪽으로 기울어 있습니다. 장면 전체가 따뜻해지는 대신, 피사체의 고유색이 배경과 구분되는지 한 번 확인할 지점이 생깁니다."
        else
            "색온도가 푸른 쪽으로 기울어 있습니다. 차분한 인상은 얻었지만, 피사체의 고유색이 배경 속에 잠기지 않았는지 살펴볼 만합니다."
        band(score) == 2 -> "색이 서로를 지지하며 하나의 온도로 정리되어 있습니다. 화면이 차분하게 가라앉고, 색을 오래 바라봐도 피로하지 않습니다."
        band(score) == 1 -> "색은 자연스럽지만 방향이 뚜렷하지 않습니다. 분위기를 색이 끌어 주지는 못하고, 빛과 구도가 그 몫을 대신하고 있습니다."
        else -> "색이 흩어져 화면의 인상이 하나로 모이지 않습니다. 어떤 색이 주인인지 정해 주면 사진의 목소리가 달라집니다."
    }

    private fun colorAdvice(m: Measure, tone: Tone, strong: Boolean): String {
        val body = when {
            strong -> "지금의 색 균형을 그대로 두고, 색을 더 진하게 만들지 않는 편이"
            m.channelCast > 8.0 && m.warmth > 0 -> "화이트밸런스를 조금 차갑게 옮겨 피사체의 고유색을 되찾는 편이"
            m.channelCast > 8.0 -> "화이트밸런스를 조금 따뜻하게 옮겨 장면의 온기를 살리는 편이"
            m.saturation > .42 -> "색을 한 단계 낮추고 주조색 하나를 남기는 편이"
            m.hueVariety >= 8 -> "색이 많은 화면이므로 주조색 두 개로 정리하는 편이"
            else -> "광원의 색을 그대로 두고 피사체의 고유색을 살리는 편이"
        }
        return toneEnd(tone, body)
    }

    // ---------- 구도 ----------
    /** 피사체가 없을 때의 화면 읽기. */
    private fun compNoSubject(m: Measure, seed: Int): String = say(seed, 101,
        "뚜렷하게 자리 잡은 피사체 없이, 화면의 면과 빛이 구도를 이룹니다. 눈이 머물 대상을 정해 주지 않으므로 여백과 밝기의 배치가 곧 장면의 뼈대가 됩니다.",
        "눈에 띄게 잡히는 대상이 없어, 여백과 빛의 덩어리가 화면을 나눕니다. 이 경우 사진은 무엇을 보여 주는가보다 어떻게 나뉘어 있는가로 읽힙니다.")

    /** 피사체의 자리. 화면의 힘 있는 자리에 놓였는지. */
    private fun compPlacement(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val main = subjects.firstOrNull()
        if (main == null) return compNoSubject(m, seed)
        val label = koLabel(main.label)
        val d = nearestThirds(main.cx, main.cy)
        return when {
            d <= .10 -> say(seed, 102,
                "${eun(label)} 화면의 힘 있는 자리에 놓여 있습니다. 시선이 자연스럽게 그곳에 닿고, 남은 여백이 그 자리를 밀어 주어 화면이 답답하지 않습니다.",
                "${eun(label)} 격자의 교차점 가까이에 자리해, 눈이 한 번에 닿습니다. 주변의 빈 공간이 그 자리를 받쳐 주어 사진의 중심이 흔들리지 않습니다.")
            d <= .18 -> say(seed, 103,
                "${eun(label)} 중심과 힘 있는 자리 사이에 걸쳐 있습니다. 시선의 착지점이 조금 흐려져, 사진을 보는 동안 눈이 미세하게 오갑니다.",
                "${eun(label)} 화면의 정확한 중심도, 힘 있는 자리도 아닌 곳에 놓였습니다. 그 어중간함이 의도된 긴장이라면 살아나지만, 아니라면 중심이 흐릿하게 남습니다.")
            else -> say(seed, 104,
                "${eun(label)} 화면의 힘 있는 자리에서 벗어나 있습니다. 눈이 중심을 잡지 못하고 화면 가장자리를 서성입니다.",
                "${eun(label)} 중심에서 멀리 밀려나 있습니다. 여백이 넓어진 만큼 사진의 무게중심도 함께 밀려났습니다.")
        }
    }

    /** 화면을 가로지르는 선. 수평선 후보의 안정감과 기울기. */
    private fun compLines(m: Measure, seed: Int): String = when {
        m.lines.isEmpty() -> say(seed, 111,
            "화면을 가로지르는 뚜렷한 선이 없어, 시선을 이끄는 방향은 색과 밝기의 흐름에 맡겨져 있습니다.",
            "기준이 되는 선이 잡히지 않습니다. 장면의 질서는 선이 아니라 면의 크기와 밝기 차이로만 만들어집니다.")
        m.maxTilt > 3.0 -> say(seed, 112,
            "화면을 가로지르는 선이 살짝 기울어 있습니다. 그 기울기가 장면을 미묘하게 흔들리게 하고, 사진의 인상에 작은 긴장을 더합니다.",
            "가로선이 수평에서 조금 벗어나 있습니다. 의도된 기울기라면 장면에 속도를 주지만, 아니라면 사진이 조용히 흔들립니다.")
        else -> say(seed, 113,
            "화면을 가로지르는 선이 반듯합니다. 장면의 뼈대가 흔들리지 않아, 사진을 보는 동안 발밑이 단단한 느낌이 듭니다.",
            "가로선이 수평을 지키고 있어 안정감이 먼저 옵니다. 그 위에 놓인 요소들이 마음 놓고 움직일 수 있습니다.")
    }

    /** 여백과 프레임의 조임. 긴장과 이완. */
    private fun compSpace(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val main = subjects.firstOrNull()
        val crowded = subjects.count { it.touchesEdge && it.area > .05 && !(it === main && it.area >= .3) }
        return when {
            crowded >= 2 -> say(seed, 121,
                "여러 요소가 화면 가장자리에 닿아 있습니다. 프레임이 조여들어 화면이 답답하게 느껴지고, 시선이 밖으로 나갈 자리를 찾지 못합니다.",
                "가장자리를 채운 요소들이 프레임을 붙잡고 있습니다. 화면이 꽉 찬 대신 숨 쉴 여백이 줄어, 사진이 다소 빽빽합니다.")
            crowded == 1 -> say(seed, 122,
                "한 요소가 화면 가장자리에 닿아 있습니다. 그 접점이 프레임을 살짝 밀어내는 긴장을 만들고, 사진에 조심스러운 긴장감을 더합니다.",
                "가장자리에 걸린 요소가 하나 있습니다. 그 요소가 잘린 채 남아 있어, 장면이 화면 밖으로 이어지는 느낌을 줍니다.")
            main != null && main.area >= .45 -> say(seed, 123,
                "피사체가 화면을 크게 차지합니다. 배경이 뒤로 물러나고 대상 자체가 사진의 전부가 되며, 여백은 거의 남지 않습니다.",
                "대상이 프레임을 넓게 덮고 있습니다. 사진이 대상을 향해 바짝 다가선 만큼, 배경의 정보는 크게 줄었습니다.")
            main != null && main.area <= .05 -> say(seed, 124,
                "피사체는 작게 자리하고 여백이 화면의 주인공이 됩니다. 사진이 대상을 보여 주기보다 대상이 놓인 공간을 보여 줍니다.",
                "작은 대상 하나가 넓은 여백에 놓여 있습니다. 그 적요가 사진의 인상을 만들고, 보는 사람의 시선이 여백을 천천히 훑게 합니다.")
            else -> say(seed, 125,
                "여백과 대상의 비율이 무난하게 나뉘어 있습니다. 화면이 답답하지도, 비어 보이지도 않습니다.",
                "프레임 안의 요소가 적당한 간격으로 놓여 있습니다. 눈이 대상을 본 뒤 여백으로 넘어가는 흐름이 자연스럽습니다.")
        }
    }

    private fun compReading(m: Measure, subjects: List<SubjectBox>, score: Double): String {
        val main = subjects.firstOrNull()
        return when {
            main == null && band(score) == 2 -> "대상 없이도 면과 빛의 배열이 화면을 지탱합니다. 여백이 사진의 주제가 된 드문 경우로, 무엇이 없는지를 보여 주는 구성입니다."
            main == null && band(score) == 1 -> "여백이 주인공이 된 화면입니다. 중심을 잡아 줄 요소가 하나만 더 있으면 인상이 완성되지만, 지금도 조용한 리듬은 남아 있습니다."
            main == null -> "화면 안에서 눈이 머물 자리를 찾기 어렵습니다. 사진의 인상이 흩어지고, 보는 사람이 무엇을 봐야 할지 스스로 정해야 합니다."
            band(score) == 2 -> "시선이 한 번에 장면을 읽습니다. 계산된 여백의 힘이 느껴지고, 눈이 화면을 훑는 순서가 자연스럽게 정해져 있습니다."
            band(score) == 1 -> "장면은 읽히지만 중심이 흐립니다. 보는 사람이 어디에 시선을 둘지 잠시 망설이게 되고, 그 망설임이 사진의 인상을 조금 흐립니다."
            else -> "프레임이 대상을 붙잡지 못합니다. 사진의 인상이 가장자리로 흘러 나가고, 화면이 하나의 장면으로 묶이지 않습니다."
        }
    }

    private fun compAdvice(m: Measure, subjects: List<SubjectBox>, tone: Tone, strong: Boolean): String {
        val main = subjects.firstOrNull()
        val body = when {
            strong -> "지금의 배치를 그대로 두고, 같은 자리에서 한두 걸음만 옮겨 다른 여백의 비율도 시험해 보는 편이"
            main == null -> "화면에 머물 요소를 하나 정해 힘 있는 자리에 두고, 여백이 그 요소를 밀어 주도록 잡는 편이"
            nearestThirds(main.cx, main.cy) > .14 -> "가장 큰 피사체를 격자의 교차점 쪽으로 반 걸음 옮기는 편이"
            subjects.any { it.touchesEdge && it.area > .05 } -> "가장자리에 닿은 요소를 안으로 들이거나 과감히 잘라내는 편이"
            m.maxTilt > 3.0 -> "수평선을 반듯하게 맞추는 편이"
            else -> "지금의 배치를 유지하되 여백을 한쪽으로 몰아 긴장을 만드는 편이"
        }
        return toneEnd(tone, body)
    }

    // ---------- 선명도와 질감 ----------
    private fun sharpFocus(m: Measure, seed: Int): String = when (band(ramp(log10(1.0 + m.sharpness), log10(16.0), log10(251.0)))) {
        2 -> say(seed, 131,
            "윤곽이 또렷하고 표면의 결이 살아 있습니다. 초점이 놓인 자리가 분명해, 보는 눈이 자연스럽게 그곳으로 갑니다.",
            "디테일이 촘촘하게 살아 있어 표면이 또렷하게 읽힙니다. 사진을 가까이 들여다볼수록 정보가 더 나옵니다.")
        1 -> say(seed, 132,
            "형태는 분명하지만 표면의 결이 조금 눌려 있습니다. 전체적인 초점은 잡혀 있으나, 미세한 결이 얕게 죽어 있습니다.",
            "윤곽은 읽히는데 표면의 요철이 흐릿합니다. 사진이 형태는 전하지만 촉감은 전하지 못합니다.")
        else -> say(seed, 133,
            "윤곽이 부드럽게 풀려 있습니다. 초점이 얕게 풀리며 화면이 부드러운 인상으로 덮여 있고, 의도된 흐림이라면 그 자체로 표현이 됩니다.",
            "질감보다 분위기로 읽히는 사진입니다. 선명함이 빠진 자리를 빛과 색이 대신 채우고 있습니다.")
    }

    private fun sharpTexture(m: Measure, seed: Int): String = when {
        m.sharpness >= 120 && m.localContrast >= 12 -> say(seed, 141,
            "표면의 결이 손끝에 닿을 듯 남아 있습니다. 돌의 거칠기, 옷감의 주름, 벽의 얼룩이 각자의 촉감으로 구분됩니다.",
            "질감의 밀도가 고릅니다. 같은 밝기 안에서도 표면이 다른 재질로 읽혀, 사진이 손으로 만질 수 있는 것처럼 느껴집니다.")
        m.sharpness >= 120 -> say(seed, 142,
            "윤곽은 선명한데 표면의 미세한 결은 조금 얕습니다. 형태가 먼저 서고 재질은 그 뒤에서 조용히 따라옵니다.",
            "초점은 맞았지만 질감의 층위가 얇습니다. 사진이 사물의 모양은 정확히 전하되, 만졌을 때의 감촉은 덜 전합니다.")
        m.sharpness < 60 -> say(seed, 143,
            "표면의 결이 흐려져 있습니다. 질감이 정보로 남기보다 분위기로 번져, 사진이 회화에 가까운 인상을 띱니다.",
            "미세한 결이 뭉개져 표면이 매끈하게 정리되었습니다. 사물의 재질보다 빛의 흐름이 먼저 보입니다.")
        else -> say(seed, 144,
            "표면의 결이 무난하게 남아 있습니다. 사물의 재질이 읽히고, 사진이 지나치게 날카롭지도 흐릿하지도 않습니다.",
            "질감이 적당히 살아 있어, 사진이 형태와 촉감 사이에서 균형을 잡고 있습니다.")
    }

    private fun sharpSpace(m: Measure, subjects: List<SubjectBox>, seed: Int): String = when {
        subjects.isEmpty() -> say(seed, 150,
            "대상이 없어 깊이는 표면의 결로만 만들어집니다. 밀도가 진한 구역과 옅은 구역이 나뉘며 화면에 조용한 원근이 생깁니다.",
            "대상이 없으니 앞뒤의 구분은 질감이 대신합니다. 결이 섬세한 곳이 앞으로, 매끈한 곳이 뒤로 물러나 사진 안에 거리가 생깁니다.")
        m.localContrast >= 14 -> say(seed, 151,
            "앞에 선 사물과 뒤로 물러난 배경의 밀도 차이가 분명합니다. 그 차이가 공간의 깊이를 만들고, 사진 안에 앞뒤가 생깁니다.",
            "질감이 진한 곳과 옅은 곳이 나뉘어 있습니다. 그 대비가 거리감을 만들어, 화면이 평면이 아니라 공간으로 읽힙니다.")
        m.localContrast < 8 -> say(seed, 152,
            "국소적인 밀도 차이가 얕아, 앞뒤가 한 면처럼 붙어 보입니다. 사진이 깊이보다 평면의 무늬처럼 다가옵니다.",
            "질감의 층이 얇아 배경이 뒤로 물러나지 못합니다. 대상과 배경이 같은 거리에 놓인 것처럼 읽힙니다.")
        else -> say(seed, 153,
            "질감의 차이가 은은하게 남아 있어, 앞뒤가 완전히 붙지도 완전히 떨어지지도 않습니다.",
            "표면의 밀도가 조금씩 달라지며 공간의 결이 만들어집니다. 사진이 깊이를 과장하지 않고 조용히 보여 줍니다.")
    }

    private fun sharpReading(m: Measure, score: Double): String = when (band(score)) {
        2 -> "선명도가 사진의 인상을 단단하게 받쳐 주고 있습니다. 초점이 놓인 자리에서 정보가 충분히 나와, 보는 사람이 표면을 따라 눈을 옮길 수 있습니다."
        1 -> "기본은 갖추었지만 질감이 사진의 무기가 되지는 못합니다. 초점이 닿은 곳의 결이 조금만 더 살아나면 인상이 한 단계 또렷해집니다."
        else -> if (m.sharpness >= 60) "선명도가 낮은 대신 화면이 부드러운 인상으로 덮여 있습니다. 의도된 흐림이라면 표현이 되지만, 아니라면 사진의 정보가 크게 줄어든 상태입니다."
        else "흐림이 사진 전체를 덮고 있습니다. 빛과 색의 인상은 남지만, 사물의 윤곽과 재질은 거의 전달되지 않습니다."
    }

    private fun sharpAdvice(m: Measure, tone: Tone, strong: Boolean): String {
        val body = when {
            strong -> "과한 샤프닝 없이 지금의 초점과 조리개를 유지하는 편이"
            m.sharpness < 60.0 -> "셔터 속도를 초점거리의 역수보다 빠르게 잡고, 삼각대나 벽에 기대어 흔들림을 줄이는 편이"
            m.localContrast < 12.0 -> "조리개를 반 스톱 조여 국소 대비를 올리는 편이"
            m.edges < .01 -> "초점 위치를 피사체의 눈이나 윤곽으로 옮기는 편이"
            else -> "초점을 피사체의 핵심부에 고정하고 흔들림을 줄이는 편이"
        }
        return toneEnd(tone, body)
    }

    // ---------- 분위기와 순간 ----------
    private fun moodTone(m: Measure, seed: Int): String {
        val p50 = medianTone(m)
        return when {
            p50 < 70 -> say(seed, 161,
                "어두운 톤이 장면을 감싸고 있어 고요한 공기가 남습니다. 화면이 소리를 낮추고, 보는 사람도 함께 낮은 목소리로 말하게 됩니다.",
                "낮게 가라앉은 빛이 차분한 기운을 만듭니다. 장면이 서두르지 않고, 사진을 오래 들여다보게 합니다.")
            p50 > 170 -> say(seed, 162,
                "밝은 톤이 화면을 열어 주어 가볍고 산뜻한 인상입니다. 장면이 경쾌하게 읽히고, 사진에서 바람이 지나가는 듯합니다.",
                "밝은 빛이 화면을 채워 장면이 환하게 열려 있습니다. 무게보다 가벼움이 먼저 느껴집니다.")
            else -> say(seed, 163,
                "중간 톤이 중심을 잡아 안정된 인상을 줍니다. 빛과 어둠이 고르게 섞여 담담한 공기가 흐릅니다.",
                "화면의 밝기가 무난한 자리에 놓여, 장면이 과장 없이 제자리에서 말합니다.")
        }
    }

    private fun moodPresence(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val human = subjects.any { it.label.lowercase() in HUMAN }
        return when {
            human -> say(seed, 171,
                "사람이 있다는 것만으로 장면에 온도가 생깁니다. 시선이 인물의 표정과 몸짓을 따라가고, 사진이 사건을 품은 이야기로 바뀝니다.",
                "화면 안의 인물이 분위기를 끌고 갑니다. 사람의 존재가 빛과 색을 하나의 정서로 묶어 주고, 사진이 관찰에서 경험으로 넘어갑니다.")
            subjects.isNotEmpty() -> say(seed, 172,
                "화면 안의 대상이 분위기를 만들고, 그 주변의 여백이 호흡을 더합니다. 대상이 말을 걸고 여백이 그 말을 받아 주는 구조입니다.",
                "대상 하나가 장면의 중심에 서 있고, 배경이 그 뒤에서 조용히 받쳐 줍니다. 사진의 시선이 흩어지지 않습니다.")
            else -> say(seed, 173,
                "특정 대상 없이 빛과 색만으로 분위기가 만들어집니다. 장면이 무엇을 담았다기보다 어떤 공기를 담았다고 말하는 사진입니다.",
                "대상이 비어 있는 대신 공간 자체가 주제가 됩니다. 사진이 장소의 온도를 보여 주고, 보는 사람이 그 안에 서 보게 합니다.")
        }
    }

    private fun moodStillness(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val human = subjects.any { it.label.lowercase() in HUMAN }
        return when {
            human && m.sharpness >= 120 -> say(seed, 181,
                "움직임이 있을 법한 장면인데 포착이 또렷합니다. 순간을 붙잡은 인상이 남고, 사진이 시간을 잠시 붙들고 있습니다.",
                "인물의 몸짓이 흐려지지 않고 남아 있습니다. 지나가는 순간을 정확히 붙잡아, 장면이 정지 화면이 아니라 한 장면의 정점처럼 읽힙니다.")
            subjects.size > 6 -> say(seed, 182,
                "여러 요소가 한꺼번에 담겨 장면이 분주합니다. 시선의 초점이 조금 흩어지지만, 그 분주함 자체가 장소의 성격을 보여 줍니다.",
                "화면 안에 사건이 여럿 겹쳐 있습니다. 한 장면에 여러 이야기가 들어와, 사진을 볼 때마다 다른 곳에 눈이 갑니다.")
            m.sharpness >= 120 -> say(seed, 183,
                "정지된 장면이 또렷하게 담겨, 시간이 잠시 멈춘 듯한 공기가 있습니다. 사진이 장면을 붙잡아 두는 힘을 갖고 있습니다.",
                "포착이 또렷해 장면의 결이 그대로 남았습니다. 사진을 보는 동안 시간이 조금 느리게 흐르는 느낌이 듭니다.")
            else -> say(seed, 184,
                "장면이 잔잔하게 가라앉아, 지나간 뒤의 공기를 담은 듯합니다. 사진이 사건보다 그 뒤에 남은 정적을 보여 줍니다.",
                "큰 움직임 없이 정돈된 장면이라, 긴장보다 안온한 기운이 남습니다. 사진이 사람을 재촉하지 않습니다.")
        }
    }

    private fun moodReading(a: Assessment): String {
        val emotionStrong = band(a.evidence[Axis.EMOTION.ordinal].score) >= 2
        val timingStrong = band(a.evidence[Axis.TIMING.ordinal].score) >= 2
        return when {
            emotionStrong && timingStrong -> "분위기와 순간이 함께 살아 있어, 장면이 정지된 이미지가 아니라 하나의 사건처럼 읽힙니다. 사진을 덮은 뒤에도 그 공기가 잠시 따라옵니다."
            emotionStrong -> "분위기가 먼저 다가오고, 순간의 긴장은 뒤에서 조용히 받쳐 주고 있습니다. 사진이 정서로 기억되는 쪽에 가깝습니다."
            timingStrong -> "순간이 또렷하게 붙잡혀 있어, 장면의 공기가 사진 안에 머물러 있습니다. 다만 그 순간을 받쳐 줄 정서의 온도는 조금 더 올라갈 여지가 있습니다."
            else -> "화면은 차분하지만 감정의 온도가 낮습니다. 장면이 정보를 담는 데 머물러 있어, 보는 사람의 마음을 오래 붙잡지는 못합니다."
        }
    }

    private fun moodAdvice(tone: Tone, human: Boolean, empty: Boolean): String {
        val body = when {
            human -> "인물의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하고, 표정이 정점에 이르는 순간을 한 박자 기다리는 편이"
            empty -> "장면에 온도를 줄 요소를 하나 더 넣거나, 지금의 여백을 끝까지 밀어 적요를 주제로 삼는 편이"
            else -> "피사체의 주변에 남은 여백의 크기를 한 번 바꿔 보고, 정서가 살아나는 쪽을 고르는 편이"
        }
        return toneEnd(tone, body)
    }

    // ---------- 항목별 조언 (촬영·보정) ----------
    /** 촬영 단계에서 바로 쓸 수 있는 조언. 끝맺음은 tone이 정한다. */
    private fun shootAdvice(axis: Axis, a: Assessment, tone: Tone): String {
        val m = a.measure
        val main = a.subjects.firstOrNull()
        val strong = band(a.evidence[axis.ordinal].score) == 2
        val body = when (axis) {
            Axis.COMPOSITION -> when {
                strong -> "지금의 배치를 그대로 두고, 같은 자리에서 카메라를 반 걸음 옮겨 여백의 비율만 바꿔 한 장 더 담는 편이"
                main == null -> "화면에 머물 요소를 하나 정해 격자의 교차점에 두고, 그 요소와 배경 사이의 거리를 의식해 프레임을 잡는 편이"
                nearestThirds(main.cx, main.cy) > .14 -> "가장 큰 피사체를 격자의 교차점 쪽으로 반 걸음 옮기고, 그 자리에서 한 장 더 누르는 편이"
                a.subjects.any { it.touchesEdge && it.area > .05 } -> "가장자리에 닿은 요소를 안으로 들이거나 과감히 잘라내고, 프레임 안에 숨 쉴 여백을 남기는 편이"
                m.maxTilt > 3.0 -> "수평선을 뷰파인더의 격자에 맞춰 반듯하게 잡는 편이"
                else -> "지금의 배치를 유지하되 여백을 한쪽으로 몰아 화면에 긴장을 만드는 편이"
            }
            Axis.LIGHTING -> when {
                strong -> "지금의 노출을 유지하고, 밝은 면이 먼저 타 버리지 않는지 화면에서 확인하며 누르는 편이"
                m.highlights > .02 -> "노출을 반 스톱쯤 낮추거나 밝은 부분을 기준으로 측광해, 가장 밝은 면의 결을 남기는 편이"
                m.shadows > .04 -> "어두운 면을 기준으로 측광하거나 반사판 대신 밝은 벽을 활용해, 그림자 속 형태를 살리는 편이"
                m.contrast < 18.0 -> "측광을 피사체에 맞추고, 배경과의 밝기 차이가 생기는 방향으로 반 걸음 움직이는 편이"
                else -> "같은 장면을 노출만 반 스톱씩 다르게 몇 장 담아, 가장 마음에 드는 균형을 고르는 편이"
            }
            Axis.COLOR -> when {
                strong -> "지금의 색 균형을 유지하고, 광원이 바뀌지 않는 짧은 시간 안에 몇 장을 더 담는 편이"
                m.channelCast > 8.0 && m.warmth > 0 -> "화이트밸런스를 자동에 맡기지 말고 차가운 쪽으로 한 단계 옮겨, 피사체의 고유색을 살리는 편이"
                m.channelCast > 8.0 -> "화이트밸런스를 따뜻한 쪽으로 한 단계 옮겨, 장면의 온기를 되찾는 편이"
                m.saturation > .42 -> "색이 강한 요소를 하나 덜어내고, 주조색 하나가 중심을 잡게 하는 편이"
                m.hueVariety >= 8 -> "색이 많은 장면에서는 주조색 두 개를 정하고, 나머지는 프레임 밖으로 밀어내는 편이"
                else -> "광원의 색을 그대로 두고, 같은 장면을 빛의 방향이 달라지는 시간에 한 번 더 담는 편이"
            }
            Axis.SHARPNESS -> when {
                strong -> "지금의 조리개와 초점을 유지하고, 같은 구도에서 한 장만 더 여유 있게 담는 편이"
                m.sharpness < 60.0 -> "셔터 속도를 초점거리의 역수보다 빠르게 잡고, 호흡을 멈춘 뒤 반셔터로 초점을 고정하는 편이"
                m.localContrast < 12.0 -> "조리개를 반 스톱 조여 피사체의 결을 세우고, 배경은 자연스럽게 물러나게 두는 편이"
                m.edges < .01 -> "초점을 피사체의 눈이나 윤곽에 두고, 초점이 잠긴 것을 확인한 뒤 누르는 편이"
                else -> "초점을 피사체의 핵심부에 고정하고, 카메라를 몸에 붙여 흔들림을 줄이는 편이"
            }
            Axis.EMOTION -> when {
                strong -> "지금의 톤 방향을 유지하며, 피사체와 배경의 온도 차이가 살아 있는 순간을 한 장 더 담는 편이"
                a.subjects.isEmpty() -> "장면에 온도를 줄 요소를 하나 더 넣거나, 반대로 여백만 남겨 적요를 주제로 삼는 편이"
                else -> "피사체의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하는 편이"
            }
            Axis.TIMING -> if (a.subjects.any { it.label.lowercase() in HUMAN })
                "제스처가 정점에 이르기 직전부터 연속으로 담아, 가장 또렷한 한 장을 고르는 편이"
            else "장면의 움직임이 멈추는 지점을 기다렸다가 누르는 편이"
        }
        return toneEnd(tone, body)
    }

    /** 보정 단계에서 할 수 있는 일. 본문에는 수치를 쓰지 않는다. */
    private fun editAdvice(axis: Axis, a: Assessment, tone: Tone): String {
        val m = a.measure
        val strong = band(a.evidence[axis.ordinal].score) == 2
        val body = when (axis) {
            Axis.LIGHTING -> when {
                strong -> "지금의 톤을 그대로 두고, 밝은 끝과 어두운 끝만 조금 다듬는 편이"
                m.highlights > .02 -> "하이라이트를 낮춰 흰색으로 눌린 결을 되살리고, 톤 커브의 어깨를 부드럽게 만드는 편이"
                m.shadows > .04 -> "어두운 쪽을 들어 올리되 검정은 남겨, 그림자 속 형태를 꺼내는 편이"
                m.contrast < 20.0 -> "검정 지점을 조금 내려 계조의 폭을 넓히고, 중간 톤을 살짝 눌러 깊이를 만드는 편이"
                else -> "노출과 대비를 그대로 두고, 밝은 면과 어두운 면의 균형만 확인하는 편이"
            }
            Axis.COLOR -> when {
                strong -> "지금의 색을 그대로 두고, 채도를 더 올리지 않는 편이"
                m.channelCast > 8.0 && m.warmth > 0 -> "색온도를 차가운 쪽으로 조금 옮기고, 피사체의 고유색이 돌아오는지 확인하는 편이"
                m.channelCast > 8.0 -> "색온도를 따뜻한 쪽으로 조금 옮겨 장면의 온기를 살리는 편이"
                m.saturation > .42 -> "채도를 한 단계 낮추고 주조색이 도드라지게 만드는 편이"
                else -> "색조의 균형을 그대로 두고, 색이 서로 섞이지 않게 채도만 미세하게 정리하는 편이"
            }
            Axis.COMPOSITION -> when {
                strong -> "지금의 프레임을 그대로 두고, 크롭으로 여백을 조금만 정리하는 편이"
                a.subjects.isEmpty() -> "크롭으로 화면을 나누어 면과 빛의 비율을 다시 잡는 편이"
                nearestThirds(a.subjects.first().cx, a.subjects.first().cy) > .14 -> "크롭으로 가장 큰 피사체를 힘 있는 자리로 옮기는 편이"
                m.maxTilt > 3.0 -> "회전으로 수평선을 반듯하게 맞추는 편이"
                else -> "크롭으로 가장자리 여백을 조금 덜어내 화면을 조이는 편이"
            }
            Axis.SHARPNESS -> when {
                strong -> "샤프닝을 더하지 않고 지금의 선명도를 유지하는 편이"
                m.sharpness < 60.0 -> "약한 샤프닝과 노이즈 정리로 윤곽을 세우되, 과하게 만들지 않는 편이"
                m.localContrast < 12.0 -> "부분 대비를 살짝 올려 표면의 결을 세우는 편이"
                else -> "초점이 놓인 곳만 국소적으로 다듬고, 배경은 그대로 두는 편이"
            }
            Axis.EMOTION -> "톤의 방향을 한쪽으로 정리하고, 색온도를 조금 옮겨 장면의 온도를 맞추는 편이"
            Axis.TIMING -> "지금은 결과를 크게 바꾸기 어려우므로, 대비와 채도를 미세하게 조정해 인상만 정돈하는 편이"
        }
        return toneEnd(tone, body)
    }

    /** 깊은 리포트에서 근거의 범위를 밝히는 문장. 수치 없이 한계만 말한다. */
    private fun reflection(axis: Axis, a: Assessment): String = when (axis) {
        Axis.COMPOSITION -> "프레임 밖의 상황은 알 수 없으므로, 화면 안의 배치만을 근거로 삼았습니다. 같은 구도라도 무엇이 잘렸는지에 따라 의미는 달라집니다."
        Axis.LIGHTING -> "빛이 어느 방향에서 왔는지, 주광과 부광이 어떻게 나뉘었는지는 한 장으로 확정할 수 없습니다. 화면에 드러난 명암의 관계만 읽었습니다."
        Axis.COLOR -> "평균적인 색만으로 조명의 색을 확정할 수는 없습니다. 화면에 보이는 색의 관계를 기준으로 삼았고, 실제 광원은 달랐을 수 있습니다."
        Axis.SHARPNESS -> "같은 정도의 선명함이라도 피사체와 해상도에 따라 의미가 달라집니다. 의도적으로 흐린 사진일 수도 있습니다."
        Axis.EMOTION -> "감정은 값으로 확정할 수 없으므로, 화면의 빛과 색에서 읽히는 인상만 적었습니다. 같은 사진이 다른 사람에게는 다른 온도로 다가옵니다."
        Axis.TIMING -> "한 장의 정지된 이미지로 결정적 순간이었는지 확정하지는 않았습니다. 장면의 배열에서 읽히는 인상만 남겼습니다."
    }

    /** 잘 살아난 점에서 쓰는 깊은 칭찬. 구체적인 시각적 이유를 함께 붙인다. */
    private fun praise(axis: Axis, a: Assessment, seed: Int): String {
        val m = a.measure
        val main = a.subjects.firstOrNull()
        val strong = band(a.evidence[axis.ordinal].score) >= 2
        return when (axis) {
            Axis.COMPOSITION -> when {
                main == null && strong -> "피사체 없이도 화면이 무너지지 않습니다. 여백의 크기와 밝기의 위치가 서로를 견제해, 장면이 스스로 균형을 잡고 있습니다."
                main == null -> "대상 없이 화면을 채우려 하지 않은 절제가 있습니다. 여백이 사진의 주제로 남아, 보는 사람이 그 빈 곳에 자기 생각을 놓을 수 있습니다."
                nearestThirds(main.cx, main.cy) <= .10 -> "가장 큰 피사체가 힘 있는 자리에 놓여 시선이 한 번에 닿습니다. 남은 여백이 그 자리를 밀어 주어 화면이 답답하지 않고, 눈이 사진 위를 걷는 동안 걸리는 곳이 없습니다."
                m.maxTilt <= 3.0 -> "화면을 가로지르는 선이 반듯해 장면의 뼈대가 흔들리지 않습니다. 그 안정 위에서 대상이 마음 놓고 자리를 잡고 있습니다."
                else -> "프레임 안의 요소가 서로의 자리를 침범하지 않습니다. 각자 숨 쉴 공간을 가진 덕분에 화면이 조용하게 정돈되었습니다."
            }
            Axis.LIGHTING -> when {
                m.highlights <= .02 && m.shadows <= .04 && m.contrast >= 30 -> "밝은 면과 어두운 면이 각자의 결을 지킨 채 마주하고 있습니다. 빛이 형태를 설명하고 형태가 다시 빛을 받쳐, 사진이 입체로 서 있습니다."
                m.contrast >= 40 -> "명암의 낙차가 크게 열려 있어 장면에 무게가 실립니다. 밝은 곳에서 어두운 곳으로 눈이 옮겨 갈 때, 빛이 지나온 자리가 또렷하게 남아 있습니다."
                else -> "빛이 과하지 않게 정돈되어 있습니다. 어두운 면이 검정으로 닫히지 않고, 밝은 면도 흰색으로 타지 않아 사진이 편안하게 읽힙니다."
            }
            Axis.COLOR -> when {
                m.hueTotal <= 0 -> "색을 덜어낸 선택이 사진을 단단하게 만듭니다. 계조와 윤곽만으로 장면을 세워, 인상이 유행과 무관하게 남습니다."
                m.dominantShare >= .45 && m.saturation < .42 -> "주조색이 화면을 넓게 덮으면서도 채도가 절제되어 있습니다. 색이 하나로 모여 장면 전체가 같은 온도로 정돈되고, 눈이 편안해집니다."
                m.channelCast <= 8.0 -> "색온도가 고르게 잡혀 사물의 고유색이 정직하게 남아 있습니다. 색이 서로를 지지해, 화면을 오래 바라봐도 피로하지 않습니다."
                else -> "색의 방향이 한쪽으로 정리되어 있습니다. 장면이 하나의 공기를 공유하고, 그 통일감이 사진의 인상을 만들어냅니다."
            }
            Axis.SHARPNESS -> when {
                m.sharpness >= 120 && m.localContrast >= 12 -> "표면의 결이 손끝에 닿을 듯 남아 있습니다. 돌의 거칠기와 옷감의 주름이 각자의 촉감으로 구분되어, 사진이 눈이 아니라 손으로 기억됩니다."
                m.sharpness >= 120 -> "초점이 놓인 자리가 분명해 보는 눈이 자연스럽게 그곳으로 갑니다. 사진이 어디를 봐야 하는지 스스로 알려 주고 있습니다."
                else -> "선명함 대신 부드러움이 사진의 성격이 되었습니다. 질감이 정보로 남기보다 분위기로 번져, 장면이 조용히 다가옵니다."
            }
            Axis.EMOTION -> when {
                a.subjects.any { it.label.lowercase() in HUMAN } -> "사람이 있다는 것만으로 장면에 온도가 생깁니다. 인물의 표정과 몸짓이 빛과 색을 하나의 정서로 묶어, 사진이 관찰에서 경험으로 넘어갑니다."
                a.subjects.isNotEmpty() -> "대상 하나가 장면의 중심을 잡고, 주변의 여백이 그 대상을 받쳐 줍니다. 사진의 시선이 흩어지지 않아 인상이 오래 남습니다."
                else -> "빈 공간이 사진의 주제가 되었습니다. 무엇이 없는지를 보여 주는 구성이 장면에 적요를 만들고, 보는 사람을 그 안에 세웁니다."
            }
            Axis.TIMING -> when {
                a.subjects.any { it.label.lowercase() in HUMAN } && m.sharpness >= 120 -> "움직임이 있을 법한 장면을 또렷하게 붙잡았습니다. 순간이 흐려지지 않고 남아, 사진이 정지 화면이 아니라 장면의 정점처럼 읽힙니다."
                m.sharpness >= 120 -> "포착이 또렷해 장면의 결이 그대로 남았습니다. 시간이 잠시 멈춘 듯한 공기가 사진 안에 머물러 있습니다."
                else -> "장면이 잔잔하게 가라앉아 지나간 뒤의 공기를 담았습니다. 서두르지 않는 시간이 사진의 성격이 되었습니다."
            }
        }.let { if (strong) it else modest(axis, a) }
    }

    /** 강점이 크지 않은 항목에서도 사실대로 말하는 낮은 목소리의 문장. 없던 칭찬을 만들지 않는다. */
    private fun modest(axis: Axis, a: Assessment): String {
        val m = a.measure
        return when (axis) {
            Axis.COMPOSITION -> "구도는 크게 흔들리지 않습니다. 눈이 걸리는 곳이 없고, 화면이 무난하게 정돈되어 사진의 뼈대가 버티고 있습니다."
            Axis.LIGHTING -> "빛이 과하지 않게 정돈되어 있습니다. 밝은 면과 어두운 면이 서로를 지우지 않아, 사진이 편안하게 읽힙니다."
            Axis.COLOR -> "색이 크게 어긋나지 않습니다. 튀는 색 없이 화면의 온도가 유지되어, 사진의 인상이 흩어지지 않습니다."
            Axis.SHARPNESS -> if (m.sharpness >= 60) "형태를 읽는 데는 부족함이 없습니다. 윤곽이 남아 있어 사진이 무엇을 담았는지 분명합니다." else "선명함은 낮지만 윤곽이 완전히 사라지지는 않았습니다. 사진의 형태는 여전히 읽힙니다."
            Axis.EMOTION -> "분위기가 담담하게 전해집니다. 강하게 붙잡지는 않지만, 장면의 공기가 남아 있습니다."
            Axis.TIMING -> "장면이 정돈된 채로 남아 있습니다. 움직임의 정점은 아니어도, 사진이 담으려 한 순간은 분명합니다."
        }
    }

    /** 더 다듬을 점에서 쓰는 정중한 제언. */
    private fun refine(axis: Axis, a: Assessment, seed: Int): String {
        val m = a.measure
        val main = a.subjects.firstOrNull()
        return when (axis) {
            Axis.COMPOSITION -> when {
                a.subjects.isEmpty() -> "화면에 머물 요소를 하나 정해 주면 구도가 생깁니다. 지금은 여백이 넓은 만큼, 그 여백을 받쳐 줄 중심이 아쉽습니다."
                a.subjects.any { it.touchesEdge && it.area > .05 } -> "가장자리에 닿은 요소가 프레임을 조여 화면이 다소 답답합니다. 그 요소를 안으로 들이거나 과감히 덜어내면 여백이 숨을 쉬게 됩니다."
                m.maxTilt > 3.0 -> "수평선이 살짝 기울어 장면이 미묘하게 흔들립니다. 반듯하게 맞추는 것만으로도 사진이 한결 안정됩니다."
                main != null && nearestThirds(main.cx, main.cy) > .14 -> "가장 큰 피사체가 힘 있는 자리에서 조금 벗어나 있습니다. 반 걸음 옮기는 것만으로 시선의 착지점이 분명해집니다."
                else -> "지금의 배치는 안정적이지만 인상이 조금 평범합니다. 여백을 한쪽으로 몰아 긴장을 만들면 사진에 표정이 생깁니다."
            }
            Axis.LIGHTING -> when {
                m.highlights > .02 && m.shadows > .04 -> "밝은 끝과 어두운 끝이 함께 닫혀 있습니다. 노출을 조금 조정해 양쪽의 결을 남기면, 사진이 담고 있던 정보가 되살아납니다."
                m.highlights > .02 -> "가장 밝은 면이 흰색으로 눌려 표면의 결이 사라졌습니다. 노출을 반 스톱 낮추면 빛의 질감이 돌아옵니다."
                m.shadows > .04 -> "어두운 면이 검정으로 뭉개져 그림자 속 형태가 지워졌습니다. 어두운 쪽을 살짝 열어 주면 장면의 깊이가 살아납니다."
                m.contrast < 18.0 -> "명암의 낙차가 완만해 장면이 평평하게 읽힙니다. 빛의 방향이 다른 시간을 기다리거나 측광을 바꾸면 입체감이 생깁니다."
                else -> "빛은 안정적이지만 장면을 밀어 올리는 힘이 조금 부족합니다. 밝은 면과 어두운 면이 만나는 지점을 한 번 더 의식해 보십시오."
            }
            Axis.COLOR -> when {
                m.channelCast > 12.0 -> "색온도가 한쪽으로 기울어 피사체의 고유색이 배경에 잠길 수 있습니다. 화이트밸런스를 중립 쪽으로 옮기면 색이 제자리를 찾습니다."
                m.saturation > .42 -> "채도가 높아 색이 서로 경쟁합니다. 한 단계 낮추고 주조색 하나를 남기면 인상이 정리됩니다."
                m.hueVariety >= 8 -> "색의 수가 많아 화면이 분주합니다. 주조색 두 개로 정리하면 사진의 목소리가 선명해집니다."
                m.saturation < .12 -> "색의 온기가 낮아 장면이 조금 멀게 느껴집니다. 채도를 미세하게 올리거나, 반대로 완전한 무채색으로 밀어 정리를 끝내는 방법도 있습니다."
                else -> "색은 자연스럽지만 방향이 뚜렷하지 않습니다. 어떤 색이 주인인지 정해 주면 사진의 인상이 한 번에 잡힙니다."
            }
            Axis.SHARPNESS -> when {
                m.sharpness < 60.0 -> "질감이 눌려 사물의 재질이 전달되지 않습니다. 촬영할 때 흔들림을 줄이거나, 보정에서 약한 샤프닝으로 윤곽을 세울 수 있습니다."
                m.localContrast < 12.0 -> "표면의 요철이 얕아 사진이 평면에 가깝게 읽힙니다. 조리개를 조이거나 부분 대비를 올리면 결이 살아납니다."
                else -> "선명도는 충분하지만 초점이 놓인 자리가 사진의 이야기와 어긋날 수 있습니다. 가장 중요한 곳에 초점이 닿았는지 확인해 보십시오."
            }
            Axis.EMOTION -> if (a.subjects.isEmpty())
                "장면이 정보를 담는 데 머물러 있습니다. 온도를 줄 요소를 하나 더하거나, 여백을 끝까지 밀어 적요를 주제로 삼으면 인상이 달라집니다."
            else "분위기는 읽히지만 인상이 중립적입니다. 피사체의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하면 감정의 온도가 올라갑니다."
            Axis.TIMING -> "장면이 일어나기 전이나 지나간 뒤를 담은 듯 정지된 공기가 남습니다. 움직임이 정점에 이르는 순간을 노리면 사진이 살아납니다."
        }
    }

    // ---------- 종합 크리틱의 문단 재료 ----------
    /** 프레임의 호흡. 명암과 계조가 화면을 어떻게 열고 닫는지. */
    private fun frameBreath(m: Measure, seed: Int): String = when {
        m.contrast >= 40 && m.highlights <= .02 && m.shadows <= .04 -> say(seed, 211,
            "프레임은 크게 숨을 들이쉰 듯 열려 있습니다. 밝은 면과 어두운 면이 서로를 밀어내며 화면에 깊이를 만들고, 눈이 그 사이를 오가는 것만으로도 사진이 읽힙니다.",
            "화면의 호흡이 깊습니다. 밝음과 어둠이 각자의 자리를 지키며 넓게 열려 있어, 사진을 보는 동안 장면의 공기가 함께 움직입니다.")
        m.contrast < 18 -> say(seed, 212,
            "프레임의 호흡은 짧고 고릅니다. 명암의 낙차가 완만해 화면이 한 폭의 면처럼 펼쳐지고, 사진은 강하게 말하기보다 조용히 머뭅니다.",
            "화면이 잔잔하게 가라앉아 있습니다. 빛의 낙차가 크지 않아 장면 전체가 같은 온도로 이어지고, 눈이 급하게 움직이지 않습니다.")
        m.highlights > .02 || m.shadows > .04 -> say(seed, 213,
            "프레임은 한쪽으로 기울어 숨을 내쉬고 있습니다. 밝은 끝이나 어두운 끝에서 정보가 빠져나가, 화면의 무게가 남은 쪽으로 쏠립니다.",
            "화면의 양 끝이 조금씩 닫혀 있습니다. 그 닫힘이 사진을 차분하게 만들기도 하지만, 장면이 담고 있던 결의 일부도 함께 지웁니다.")
        else -> say(seed, 214,
            "프레임의 호흡은 고르게 이어집니다. 밝은 곳과 어두운 곳이 서로의 자리를 나누어, 사진을 보는 동안 눈이 편하게 머뭅니다.",
            "화면이 무리 없이 열려 있습니다. 빛과 그림자가 서로를 방해하지 않아, 장면이 제 속도로 읽힙니다.")
    }

    /** 빛과 색이 한 화면에서 어우러지는 방식. */
    private fun lightColorUnion(m: Measure, seed: Int): String = when {
        m.warmth > 6.0 && m.contrast >= 30 -> say(seed, 221,
            "따뜻한 색과 단단한 명암이 같은 방향을 가리킵니다. 빛이 장면을 데우고 그림자가 그 온기를 붙잡아, 사진 전체가 하나의 공기를 공유합니다.",
            "붉은 기운이 깊은 그림자 위에 얹혀 있습니다. 색이 빛을 따라가며 장면의 온도를 만들고, 사진이 저녁의 공기처럼 읽힙니다.")
        m.warmth < -6.0 && m.contrast >= 30 -> say(seed, 222,
            "푸른 색과 단단한 명암이 만나 장면이 서늘하게 서 있습니다. 빛이 형태를 세우고 색이 그 형태에 온도를 낮춰, 사진이 사색적인 인상을 얻습니다.")
        m.saturation < .12 && m.contrast >= 30 -> say(seed, 223,
            "색을 덜어낸 화면에서 빛과 형태가 주인공이 됩니다. 명암의 낙차가 사진의 구조를 만들고, 색의 부재가 오히려 장면을 단단하게 합니다.")
        m.saturation >= .28 && m.contrast < 22 -> say(seed, 224,
            "색은 살아 있는데 빛의 낙차가 완만해, 화면이 색의 무늬처럼 펼쳐집니다. 형태보다 색의 흐름이 먼저 보이고, 사진이 회화에 가까워집니다.")
        else -> say(seed, 225,
            "빛과 색이 서로를 방해하지 않고 나란히 놓여 있습니다. 한쪽이 다른 쪽을 압도하지 않아, 사진이 균형 잡힌 인상으로 남습니다.",
            "빛은 형태를 받치고 색은 분위기를 맡습니다. 두 요소가 각자의 몫을 다해, 화면이 한 번에 읽힙니다.")
    }

    /** 시선이 머무는 자리. */
    private fun gazeAnchor(a: Assessment, seed: Int): String {
        val main = a.subjects.firstOrNull()
        val m = a.measure
        return when {
            main != null && main.area >= .45 -> say(seed, 231,
                "시선은 대상 자체에 붙어 있습니다. 화면을 크게 덮은 피사체가 다른 모든 요소를 뒤로 물리고, 사진은 그 대상에 바짝 다가선 관찰이 됩니다.",
                "눈이 먼저 닿는 곳은 화면을 가득 채운 대상입니다. 배경은 거의 정보를 주지 않고, 사진의 무게가 한곳에 모여 있습니다.")
            main != null && main.area <= .05 -> say(seed, 232,
                "시선은 작은 대상에 닿은 뒤 넓은 여백으로 흘러갑니다. 그 이동 자체가 사진의 리듬이 되고, 여백이 대상보다 큰 목소리로 말합니다.",
                "작은 피사체 하나가 넓은 공간에 놓여 있습니다. 눈은 그 점을 확인한 뒤 주변을 천천히 훑고, 사진은 그 느린 시선을 기다려 줍니다.")
            main != null -> say(seed, 233,
                "시선은 가장 큰 대상에서 출발해 주변으로 퍼집니다. 대상과 여백의 비율이 무난해, 눈이 화면을 한 번에 읽고 오래 머물 자리를 스스로 정합니다.")
            m.lines.isNotEmpty() -> say(seed, 234,
                "뚜렷한 대상이 없어 시선은 화면을 가로지르는 선을 따라갑니다. 선의 방향이 사진의 흐름을 만들고, 눈이 그 흐름을 따라 화면 끝까지 이동합니다.",
                "시선을 붙잡는 대상은 없지만, 화면을 가로지르는 선이 눈길을 이끕니다. 사진은 무엇을 보는 사진이 아니라 어디로 가는 사진이 됩니다.")
            else -> say(seed, 235,
                "시선을 붙잡는 대상도, 이끄는 선도 없습니다. 눈이 화면을 스스로 훑어야 하고, 사진은 그 자유를 보는 사람에게 넘깁니다.",
                "머물 자리가 정해지지 않은 화면입니다. 밝기와 면의 배치만이 눈의 순서를 정하고, 그 순서는 사람마다 달라집니다.")
        }
    }

    /** 공간의 깊이. 앞뒤가 얼마나 나뉘는지. */
    private fun spatialDepth(m: Measure, subjects: List<SubjectBox>, seed: Int): String = when {
        m.localContrast >= 14 && subjects.size >= 2 -> say(seed, 241,
            "앞뒤의 밀도 차이가 분명해 공간이 여러 겹으로 나뉩니다. 가까운 것은 결이 진하게, 먼 것은 옅게 남아 사진 안에 거리가 생깁니다.",
            "화면 안에 앞과 뒤가 있습니다. 질감이 살아 있는 쪽이 앞으로 나오고, 부드러워진 쪽이 뒤로 물러나 장면이 평면에 머물지 않습니다.")
        m.localContrast < 8 -> say(seed, 242,
            "다만 앞뒤의 밀도 차이가 얕아 공간이 한 겹으로 붙어 있습니다. 사진이 깊이보다 무늬처럼 읽히고, 장면이 조금 평평합니다.")
        subjects.isEmpty() -> say(seed, 243,
            "대상이 없어 깊이는 빛의 분포로만 만들어집니다. 밝은 면이 앞으로, 어두운 면이 뒤로 물러나며 화면에 조용한 원근이 생깁니다.")
        else -> say(seed, 244,
            "공간의 깊이는 과장되지 않았습니다. 앞뒤가 완전히 붙지도, 완전히 떨어지지도 않아 장면이 자연스러운 거리로 읽힙니다.")
    }

    private fun direction(tone: Tone, depth: Depth): String = when (tone) {
        Tone.MENTOR -> if (depth == Depth.QUICK) "아래에서 빛과 색, 구도를 차례로 짚었습니다. 오늘은 한 가지만 의식해 보셔도 충분합니다."
        else "아래에서 빛과 색, 구도와 질감을 하나씩 짚었습니다. 한 번에 모두 바꾸지 않으셔도 됩니다. 다음 장에서 한 가지만 의식해 보시면 차이가 보입니다."
        Tone.CRITIC -> if (depth == Depth.QUICK) "아래 항목은 화면에 실제로 드러난 것만 적었습니다. 감상보다 근거를 먼저 확인하십시오."
        else "아래 항목은 화면에 실제로 드러난 것만 적었습니다. 취향의 문제와 사진의 문제를 구분해 읽으시면 이 비평이 더 유용해집니다."
        Tone.ANALYST -> if (depth == Depth.QUICK) "아래 항목은 화면에서 본 요소와 그 해석을 구분해 적었습니다."
        else "아래 항목은 화면에서 본 요소와 그 해석을 구분해 적었습니다. 해석은 단정이 아니라 가능성으로 읽어 주십시오."
    }

    private fun closingFor(tone: Tone, depth: Depth): String = when (depth) {
        Depth.QUICK -> ""
        else -> when (tone) {
            Tone.MENTOR -> "사진은 한 장으로 완성되지 않습니다. 지금의 장면을 다시 만났을 때 기억나는 한 가지만 가져가셔도 좋겠습니다."
            Tone.CRITIC -> "좋은 사진은 흠이 없는 사진이 아니라, 무엇을 선택했는지 분명한 사진입니다. 이 장에서 그 선택이 어디였는지 다시 보십시오."
            Tone.ANALYST -> "같은 장면을 조건만 바꿔 몇 장 담으면, 어떤 요소가 결과를 움직였는지 스스로 확인할 수 있습니다."
        }
    }

    // ---------- report sections ----------
    /** 문장 생성 중 어떤 오류가 나도 모든 항목이 채워진 리포트를 돌려준다. */
    fun sections(a: Assessment, score: ScoreResult?, persona: Persona, level: Level, tone: Tone, depth: Depth): Map<String, String> =
        try { buildSections(a, score, persona, level, tone, depth) }
        catch (e: Throwable) { fallbackSections(a, tone) }

    private fun fallbackSections(a: Assessment, tone: Tone): Map<String, String> =
        SECTION_TITLES.associateWith { name ->
            when (name) {
                "종합 크리틱" -> "비평 문장을 엮는 중 문제가 생겨 이번에는 짧게 남깁니다. 사진의 빛과 색, 구도를 먼저 눈으로 확인해 보십시오."
                else -> "이 항목의 문장을 끝까지 만들지 못했습니다. 사진을 다시 분석하면 대부분의 항목이 채워집니다."
            }
        }

    private fun buildSections(a: Assessment, score: ScoreResult?, persona: Persona, level: Level, tone: Tone, depth: Depth): Map<String, String> {
        val ev = a.evidence
        val resolved = score ?: runCatching { Scoring.evaluate(a.nima.aesthetic, ev, persona, level) }.getOrNull()
        val contributions = resolved?.contributions ?: ev.indices.map { persona.weights[it] * ev[it].score }
        val ranked = Axis.entries.indices.sortedWith(
            compareByDescending<Int> { ev[it].score }.thenByDescending { persona.weights[it] }
        )
        val top = ranked.take(2)
        val weak = ranked.takeLast(2).reversed()
        val s10 = resolved?.adjusted ?: (1.0 + 9.0 * contributions.sum().coerceIn(0.0, 1.0))
        val limit = depth.sentences
        val out = linkedMapOf<String, String>()
        out["종합 크리틱"] = summary(a, s10, tone, depth, top, weak)
        out["빛과 노출"] = lightSection(a, tone, limit)
        out["색채"] = colorSection(a, tone, limit)
        out["구도"] = compositionSection(a, tone, limit)
        out["선명도와 질감"] = sharpnessSection(a, tone, limit)
        out["분위기와 순간"] = moodSection(a, tone, limit)
        out["잘 살아난 점"] = strengths(a, top, limit)
        out["더 다듬을 점"] = improvements(a, level, tone, weak, limit)
        out["촬영 제안"] = shooting(a, tone, depth, weak)
        out["보정 제안"] = editing(a, tone, depth, weak)
        return out
    }

    private fun join(items: List<String>, limit: Int): String =
        items.asSequence().filter { it.isNotBlank() }.take(limit).joinToString(" ")

    private class Sentences(private val limit: Int) {
        private val items = mutableListOf<String>()
        fun add(text: String?) {
            if (text.isNullOrBlank() || items.size >= limit) return
            val clean = text.trim()
            if (clean.isNotEmpty()) items += clean
        }
        fun addAll(texts: List<String>) { texts.forEach { add(it) } }
        fun build(): String = items.joinToString(" ")
    }

    /** 점수대에 따른 한 줄 인상. 점수 카드 아래에 바로 놓인다. */
    fun verdict(a: Assessment, s10: Double): String {
        val top = Axis.entries.indices.maxByOrNull { a.evidence[it].score } ?: 0
        val flavour = when (Axis.entries[top]) {
            Axis.COMPOSITION -> "구도가 반듯한"
            Axis.LIGHTING -> "빛이 살아 있는"
            Axis.COLOR -> "색이 정돈된"
            Axis.SHARPNESS -> "질감이 또렷한"
            Axis.EMOTION -> "분위기가 서린"
            Axis.TIMING -> "순간을 붙잡은"
        }
        val quality = when {
            s10 >= 9.0 -> "흠을 찾기 어려운 한 장"
            s10 >= 8.2 -> "완성도가 높은 한 장"
            s10 >= 7.4 -> "균형이 좋은 한 장"
            s10 >= 6.6 -> "안정적으로 정리된 한 장"
            s10 >= 5.8 -> "가능성이 보이는 한 장"
            else -> "다듬을 여지가 큰 한 장"
        }
        return "$flavour, $quality"
    }

    private fun openingFor(s10: Double): String = when {
        s10 >= 9.0 -> "완성도가 단단한 한 장입니다. 흠을 찾기보다 유지할 이유를 먼저 보게 되고, 화면을 오래 바라봐도 인상이 흐트러지지 않습니다."
        s10 >= 8.2 -> "완성도가 높은 한 장입니다. 빛과 색이 서로를 받쳐 주고, 구도가 그 위에서 흔들리지 않습니다."
        s10 >= 7.4 -> "균형이 좋은 한 장입니다. 큰 흔들림 없이 화면이 자기 자리를 잡고 있어, 사진의 목소리가 또렷하게 들립니다."
        s10 >= 6.6 -> "안정적으로 정리된 한 장입니다. 지금의 기본을 조금만 더 밀면 인상이 한 단계 또렷해집니다."
        s10 >= 5.8 -> "가능성이 보이는 한 장입니다. 방향은 맞고, 손볼 지점도 분명합니다."
        else -> "아직 다듬을 여지가 큰 한 장입니다. 다만 출발점은 분명하고, 어디서부터 손대면 되는지도 보입니다."
    }

    /** 문단에 문장을 고르게 나눠 담는다. 4·6·9문장에서 세 문단이 모두 살아남게 한다. */
    private fun quota(limit: Int, parts: Int): IntArray {
        val q = IntArray(parts) { 1 }
        var left = (limit - parts).coerceAtLeast(0)
        var index = 0
        while (left > 0) { q[index % parts]++; index++; left-- }
        return q
    }

    private fun summary(a: Assessment, s10: Double, tone: Tone, depth: Depth, top: List<Int>, weak: List<Int>): String {
        val m = a.measure
        val seed = seedOf(m)
        val best = Axis.entries[top[0]]
        val weakest = Axis.entries[weak[0]]
        val voice = SubjectReading.voice(a.scene, seed)
        val opening = if (voice != null) "${voice.opening} ${openingFor(s10)}" else openingFor(s10)
        val first = mutableListOf(
            opening,
            frameBreath(m, seed),
            lightColorUnion(m, seed)
        )
        val second = mutableListOf(
            gazeAnchor(a, seed),
            spatialDepth(m, a.subjects, seed),
            highlight(best, a, seed)
        )
        val third = mutableListOf(
            "${eun(weakest.title)} 이 사진에서 먼저 살펴볼 지점입니다. " + shortNote(weakest),
            direction(tone, depth),
            closingFor(tone, depth)
        )
        val q = quota(depth.sentences, 3)
        val parts = listOf(first, second, third)
        return parts.indices
            .map { i -> parts[i].take(q[i]).filter { it.isNotBlank() }.joinToString(" ") }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    /** 종합 크리틱에서 가장 나은 항목을 짚는 문장. '잘 살아난 점'의 칭찬과 다른 문장을 쓴다. */
    private fun highlight(axis: Axis, a: Assessment, seed: Int): String = when (band(a.evidence[axis.ordinal].score)) {
        2 -> when (axis) {
            Axis.COMPOSITION -> "무엇보다 구도가 눈에 편합니다. 화면이 한 번에 읽히고, 어디를 봐야 할지 망설일 필요가 없습니다."
            Axis.LIGHTING -> "무엇보다 빛이 좋습니다. 밝은 면과 어두운 면이 서로를 살려 주어, 사진을 보는 동안 장면의 공기가 함께 움직입니다."
            Axis.COLOR -> "무엇보다 색이 차분합니다. 화면의 온도가 한 방향으로 모여 있어, 눈이 편안하게 오래 머뭅니다."
            Axis.SHARPNESS -> "무엇보다 질감이 살아 있습니다. 표면의 결이 눈에 잡혀, 사진이 손에 닿을 듯 가깝게 느껴집니다."
            Axis.EMOTION -> "무엇보다 분위기가 남습니다. 사진을 덮은 뒤에도 장면의 공기가 잠시 이어집니다."
            Axis.TIMING -> "무엇보다 순간이 살아 있습니다. 장면이 정지해 있지 않고, 한 박자 움직일 준비를 하고 있습니다."
        }
        1 -> say(seed, 251,
            "눈에 띄는 강점은 크지 않지만, ${eun(axis.title)} 무난하게 자리를 잡고 있어 사진이 무너지지 않습니다.",
            "이 사진을 한 문장으로 말한다면 ${wa(axis.title)} 관련된 지점이 가장 먼저 떠오릅니다. 다만 아직 사진의 인상을 결정할 만큼 밀어 올라가지는 않았습니다.")
        else -> if (a.subjects.isEmpty()) say(seed, 252,
            "뚜렷한 강점을 짚기는 어렵습니다. 그래도 화면이 완전히 비어 있지는 않아, 빛의 분포를 다시 보면 시작점이 보입니다.",
            "강점을 꼽기 어려운 한 장입니다. 그래도 사진이 실패한 것은 아니고, 아직 인상을 만들 재료가 정리되지 않은 상태입니다.")
        else "뚜렷한 강점을 짚기는 어렵습니다. 그래도 ${axis.title} 쪽에서 시작해 볼 수 있습니다."
    }

    /** 종합 크리틱에서 약한 항목을 짚는 한 줄. '더 다듬을 점'·촬영 제안과 같은 문장을 쓰지 않는다. */
    private fun shortNote(axis: Axis): String = when (axis) {
        Axis.COMPOSITION -> "구도를 한 걸음만 더 정리해도 사진의 인상이 크게 달라집니다."
        Axis.LIGHTING -> "빛의 균형을 한 번 더 보면 사진이 한 단계 깊어집니다."
        Axis.COLOR -> "색의 방향을 하나 정하면 인상이 훨씬 선명해집니다."
        Axis.SHARPNESS -> "질감을 조금만 더 살리면 사진이 단단해집니다."
        Axis.EMOTION -> "분위기에 온기를 더하면 사진이 오래 남습니다."
        Axis.TIMING -> "순간을 반 박자 더 기다리면 장면이 살아납니다."
    }

    // ---------- 빛과 노출 ----------
    private fun lightSection(a: Assessment, tone: Tone, limit: Int): String {
        val m = a.measure
        val seed = seedOf(m)
        val score = a.evidence[Axis.LIGHTING.ordinal].score
        val reading = lightReading(m, score)
        val advice = lightAdvice(m, tone, band(score) == 2)
        val subjectLight = SubjectReading.voice(a.scene, seed)?.light ?: lightTexture(m, seed)
        val items = if (limit <= 4) listOf(lightTone(m, seed), subjectLight, reading, advice)
        else listOf(
            lightTone(m, seed), lightRange(m, seed), subjectLight, lightShadow(m, seed),
            reading, advice, lightModel(m, seed), lightQuality(m, seed), reflection(Axis.LIGHTING, a)
        )
        return join(items, limit)
    }

    // ---------- 색채 ----------
    /** 색이 형태를 따라가는지, 형태를 넘어 번지는지. */
    private fun colorUnion(m: Measure, seed: Int): String = when {
        m.edges >= .02 && m.sharpness >= 90 -> say(seed, 261,
            "색은 형태를 따라갑니다. 사물의 경계에서 색도 함께 멈춰, 색과 윤곽이 같은 자리에서 정리됩니다.",
            "색의 경계가 형태의 경계와 맞물려 있습니다. 사물이 색으로 구분되고, 색이 형태를 설명하는 구조입니다.")
        m.edges < .01 -> say(seed, 262,
            "색이 형태의 경계를 넘어 번집니다. 사물과 배경이 같은 색으로 이어져, 화면이 색의 흐름으로만 나뉩니다.",
            "색의 경계가 흐려 형태와 색이 서로 섞여 있습니다. 사진이 사물의 윤곽보다 색의 공기를 먼저 보여 줍니다.")
        else -> say(seed, 263,
            "색과 형태가 서로를 과하게 밀지 않습니다. 색이 형태를 따라가는 정도가 적당해, 화면이 읽기 편합니다.")
    }

    /** 색의 무게. 어느 색이 화면을 끌고 가는지. */
    private fun colorWeight(m: Measure, seed: Int): String = when {
        m.hueTotal <= 0 || m.saturation < .12 -> say(seed, 270,
            "색이 옅게 깔려 있어 색의 무게는 거의 느껴지지 않습니다. 사진의 인상은 색이 아니라 밝기와 형태가 끌고 갑니다.",
            "색이 뒤로 물러나 있어, 어떤 색이 주인인지 묻기 어려운 화면입니다. 사진의 중심은 계조와 윤곽이 대신 잡고 있습니다.")
        m.dominantShare >= .45 -> say(seed, 271,
            "화면의 무게는 한 색에 실려 있습니다. 그 색이 사진의 성격을 거의 혼자 결정하고, 나머지 색은 그 옆에서 조용히 받칩니다.",
            "주조색이 화면의 대부분을 차지해, 사진을 기억할 때도 그 색이 먼저 떠오릅니다.")
        m.hueVariety >= 8 -> say(seed, 272,
            "색의 무게가 여러 곳에 나뉘어 있습니다. 어느 색도 화면을 독점하지 못해, 시선이 색과 색 사이를 오갑니다.",
            "여러 색이 각자의 무게를 갖고 있습니다. 화면이 풍성하지만, 사진의 중심을 색이 대신 잡아 주지는 못합니다.")
        else -> say(seed, 273,
            "색의 무게가 고르게 나뉘어 있습니다. 주조색과 보조색이 서로의 자리를 인정해, 화면이 안정적으로 읽힙니다.",
            "색이 한쪽으로 쏠리지 않아, 사진의 인상이 특정 색에 묶이지 않습니다.")
    }

    private fun colorSection(a: Assessment, tone: Tone, limit: Int): String {
        val m = a.measure
        val seed = seedOf(m)
        val score = a.evidence[Axis.COLOR.ordinal].score
        val reading = colorReading(m, score)
        val advice = colorAdvice(m, tone, band(score) == 2)
        val subjectColor = SubjectReading.voice(a.scene, seed)?.color ?: colorPigment(m, seed)
        val items = if (limit <= 4) listOf(colorTemperature(m, seed), subjectColor, reading, advice)
        else listOf(
            colorTemperature(m, seed), subjectColor, colorAir(m, seed), colorUnion(m, seed),
            reading, advice, colorWeight(m, seed), colorPixel(m, seed), reflection(Axis.COLOR, a)
        )
        return join(items, limit)
    }

    /** 색이 화면에서 차지하는 넓이와 밀도. */
    private fun colorPixel(m: Measure, seed: Int): String = when {
        m.hueCoverage < .35 -> say(seed, 281,
            "유채색이 화면의 일부에만 남아 있습니다. 색이 있는 곳과 없는 곳이 나뉘어, 색 자체가 화면의 표시가 됩니다.",
            "색은 화면 전체가 아니라 일부 구역에만 깔려 있습니다. 그 국소성이 사진의 시선을 색 있는 쪽으로 모읍니다.")
        m.saturation >= .30 -> say(seed, 282,
            "색이 화면 전반에 고르게 살아 있습니다. 사진을 볼 때 색의 밀도가 먼저 느껴지고, 형태는 그 뒤를 따릅니다.",
            "색이 넓게 퍼져 있어 화면의 온도가 일정합니다. 어디를 봐도 색이 함께 있어, 사진이 한 색조로 덮여 있습니다.")
        else -> say(seed, 283,
            "색이 화면 전체에 얕게 깔려 있습니다. 색이 주장하지 않으므로 빛과 형태가 앞에 서고, 사진은 차분한 인상을 남깁니다.",
            "색의 밀도가 낮아 화면이 담백합니다. 색을 빼고 남은 것이 사진의 성격이 되었습니다.")
    }

    // ---------- 구도 ----------
    /** 화면의 좌우 무게. */
    private fun compBalance(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val main = subjects.firstOrNull() ?: return say(seed, 291,
            "대상이 없어 무게는 밝기의 분포가 정합니다. 밝은 면이 화면의 어느 쪽에 놓였는지에 따라 사진의 중심이 옮겨 갑니다.")
        val offset = abs(main.cx - .5)
        return when {
            offset <= .08 -> say(seed, 292,
                "무게가 화면의 가운데에 놓여 있습니다. 안정적이지만, 좌우 어느 쪽으로도 기울지 않아 사진이 정적입니다.",
                "중심이 화면 한가운데에 잡혀 있습니다. 단정한 대신, 좌우의 긴장은 거의 남지 않습니다.")
            offset <= .22 -> say(seed, 293,
                "무게가 한쪽으로 살짝 기울어 있습니다. 그 기울기가 화면에 방향을 주고, 사진이 조용히 움직이기 시작합니다.",
                "좌우의 무게가 완전히 같지는 않습니다. 기운 쪽으로 시선이 먼저 가고, 반대쪽 여백이 그 무게를 받쳐 줍니다.")
            else -> say(seed, 294,
                "무게가 한쪽으로 크게 쏠려 있습니다. 반대편에 남은 여백이 넓어, 사진이 그 빈 곳을 향해 열려 있습니다.",
                "화면의 균형이 한쪽으로 기울어 있습니다. 의도된 치우침이라면 긴장이 되지만, 아니라면 사진이 넘어질 듯 불안합니다.")
        }
    }

    /** 시선이 지나가는 길. */
    private fun compFlow(m: Measure, subjects: List<SubjectBox>, seed: Int): String = when {
        subjects.size >= 3 -> say(seed, 301,
            "여러 대상이 놓인 자리가 시선의 경로를 만듭니다. 눈이 한 대상에서 다음 대상으로 옮겨 가며 화면을 읽고, 그 순서가 사진의 리듬이 됩니다.",
            "대상이 흩어져 있어 시선이 정해진 길 없이 움직입니다. 사진을 볼 때마다 다른 순서로 읽히는 것이 이 화면의 성격입니다.")
        m.lines.isNotEmpty() && subjects.isNotEmpty() -> say(seed, 302,
            "화면을 가로지르는 선과 피사체의 자리가 함께 시선의 길을 만듭니다. 눈이 선을 따라가다 피사체에서 멈추고, 사진이 그 자리에서 완성됩니다.",
            "선이 시선을 이끌고 피사체가 그 끝에 놓여 있습니다. 사진이 보는 사람에게 어디를 보아야 하는지 조용히 알려 줍니다.")
        else -> say(seed, 303,
            "시선의 길이 뚜렷하지 않습니다. 눈이 화면을 스스로 훑어야 하고, 사진은 그 자유를 보는 사람에게 맡깁니다.",
            "정해진 동선 없이 시선이 화면을 떠돕니다. 밝기와 면의 배치만이 눈의 순서를 정합니다.")
    }

    /** 프레임이 대상을 담는 방식. */
    private fun compFraming(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val main = subjects.firstOrNull()
        return when {
            main == null -> say(seed, 311,
                "프레임은 대상을 담기보다 공간을 담았습니다. 무엇을 넣을지보다 무엇을 비울지로 결정한 화면입니다.")
            main.touchesEdge -> say(seed, 312,
                "가장 큰 피사체가 프레임의 가장자리에 닿아 있습니다. 잘린 몫이 있어 사진이 화면 밖을 상상하게 하고, 그 상상이 장면을 넓힙니다.",
                "대상의 일부가 프레임 밖에 남아 있습니다. 잘림이 사진을 닫힌 화면이 아니라 이어지는 장면으로 만듭니다.")
            main.area >= .30 -> say(seed, 313,
                "프레임이 대상에 바짝 다가서 있습니다. 배경의 정보는 줄고 대상의 존재가 커져, 사진이 관찰보다 대면에 가까워집니다.")
            else -> say(seed, 314,
                "프레임이 대상을 여유 있게 담았습니다. 대상과 주변의 관계가 함께 읽혀, 사진이 장소의 맥락을 남깁니다.",
                "대상 둘레에 공간이 남아 있습니다. 그 여유가 사진을 서두르지 않게 만들고, 장면이 제 속도로 읽힙니다.")
        }
    }

    private fun compositionSection(a: Assessment, tone: Tone, limit: Int): String {
        val m = a.measure
        val seed = seedOf(m)
        val score = a.evidence[Axis.COMPOSITION.ordinal].score
        val reading = compReading(m, a.subjects, score)
        val advice = compAdvice(m, a.subjects, tone, band(score) == 2)
        val subjectComposition = SubjectReading.voice(a.scene, seed)?.composition ?: compBalance(m, a.subjects, seed)
        val items = if (limit <= 4) listOf(
            compPlacement(m, a.subjects, seed), subjectComposition, reading, advice
        ) else listOf(
            compPlacement(m, a.subjects, seed), compLines(m, seed), compSpace(m, a.subjects, seed),
            subjectComposition, reading, advice, compFlow(m, a.subjects, seed),
            compFraming(m, a.subjects, seed), reflection(Axis.COMPOSITION, a)
        )
        return join(items, limit)
    }

    // ---------- 선명도와 질감 ----------
    /** 윤곽선의 성격. */
    private fun sharpEdge(m: Measure, seed: Int): String = when {
        m.edges >= .04 -> say(seed, 321,
            "화면에 세밀한 윤곽이 많습니다. 작은 요소들이 각자의 경계를 갖고 있어, 사진을 가까이 볼수록 읽을 것이 늘어납니다.",
            "윤곽이 촘촘하게 살아 있어 화면이 복잡하고 풍성합니다. 다만 눈이 쉴 빈 면은 적습니다.")
        m.edges < .01 -> say(seed, 322,
            "윤곽선이 적어 화면이 단순합니다. 큰 면이 넓게 이어져, 사진이 형태보다 덩어리로 읽힙니다.",
            "세밀한 경계가 거의 없어 화면이 매끈합니다. 사진이 복잡한 장면보다 조용한 면을 담았습니다.")
        else -> say(seed, 323,
            "윤곽선이 적당히 남아 있습니다. 사물의 경계가 읽히되 화면이 어수선해지지 않을 만큼입니다.")
    }

    /** 결이 화면 전체에 고르게 있는지. */
    private fun sharpEven(m: Measure, seed: Int): String = when {
        m.localContrast >= 14 -> say(seed, 331,
            "결이 화면 전반에 고르게 살아 있습니다. 어느 구역을 보아도 표면의 요철이 남아 있어, 사진이 부분마다 다른 정보를 줍니다.",
            "국소적인 밝기 차가 여러 곳에서 살아 있습니다. 사진을 훑을 때마다 새로운 결이 눈에 들어옵니다.")
        m.localContrast < 8 -> say(seed, 332,
            "결이 화면 전체에서 얕습니다. 표면의 요철이 거의 드러나지 않아, 사진이 전체적으로 매끈하고 평평하게 읽힙니다.",
            "국소적인 차이가 적어, 사진의 정보가 큰 덩어리로만 전달됩니다.")
        else -> say(seed, 333,
            "결이 화면 전체에 무난하게 퍼져 있습니다. 어떤 구역은 진하고 어떤 구역은 옅어, 사진에 자연스러운 기복이 생깁니다.")
    }

    /** 흐림이 의도일 수 있다는 단서. */
    private fun sharpIntent(m: Measure, seed: Int): String = when {
        m.sharpness < 60 && m.contrast >= 30 -> say(seed, 341,
            "흐림이 있어도 명암은 살아 있어, 사진이 정보를 잃은 것이 아니라 다른 방식으로 말하고 있습니다. 의도된 흐림이라면 충분히 표현이 됩니다.",
            "선명함을 포기한 대신 빛과 어둠의 대비가 남아 있습니다. 사진이 재질을 보여 주기보다 공기를 보여 주는 쪽을 택한 것처럼 읽힙니다.")
        m.sharpness >= 150 -> say(seed, 342,
            "선명함이 사진의 인상을 강하게 끌고 갑니다. 다만 지나치게 날카로우면 인물의 피부나 하늘의 결이 거칠게 드러날 수 있습니다.",
            "디테일이 넘치는 대신 화면이 조금 긴장되어 있습니다. 선명함을 한 단계 낮추면 사진이 부드러워질 여지도 있습니다.")
        else -> say(seed, 343,
            "선명도는 사진의 인상을 방해하지 않습니다. 표현의 중심은 선명함이 아니라 빛과 색에 있습니다.")
    }

    private fun sharpnessSection(a: Assessment, tone: Tone, limit: Int): String {
        val m = a.measure
        val seed = seedOf(m)
        val score = a.evidence[Axis.SHARPNESS.ordinal].score
        val reading = sharpReading(m, score)
        val advice = sharpAdvice(m, tone, band(score) == 2)
        val subjectTexture = SubjectReading.voice(a.scene, seed)?.texture ?: sharpTexture(m, seed)
        val items = if (limit <= 4) listOf(sharpFocus(m, seed), subjectTexture, reading, advice)
        else listOf(
            sharpFocus(m, seed), subjectTexture, sharpSpace(m, a.subjects, seed), reading,
            advice, sharpEdge(m, seed), sharpEven(m, seed), sharpIntent(m, seed), reflection(Axis.SHARPNESS, a)
        )
        return join(items, limit)
    }

    // ---------- 분위기와 순간 ----------
    /** 사진을 덮은 뒤에 남는 것. */
    private fun moodAfter(m: Measure, seed: Int): String = when {
        m.contrast >= 35 && m.saturation >= .20 -> say(seed, 351,
            "사진을 덮은 뒤에도 장면의 온도가 잠시 남습니다. 강한 명암과 살아 있는 색이 함께 기억에 남아, 인상이 쉽게 지워지지 않습니다.",
            "한 장을 본 뒤에도 화면의 공기가 따라옵니다. 빛과 색이 함께 만든 인상이 오래 머뭅니다.")
        m.contrast < 20 || m.saturation < .12 -> say(seed, 352,
            "사진을 덮으면 인상이 빠르게 옅어집니다. 화면이 담백한 대신 기억에 오래 붙는 장면은 적습니다.",
            "남는 것이 적은 사진입니다. 편안하지만, 다시 떠올릴 이유도 함께 적습니다.")
        else -> say(seed, 353,
            "사진을 덮으면 장면의 분위기가 은은하게 남습니다. 강하게 붙잡지는 않지만, 한 번쯤 다시 보고 싶어지는 인상입니다.",
            "기억에 남는 방식이 조용합니다. 화면을 다시 떠올리면 빛의 위치부터 생각나고, 그다음에 형태가 따라옵니다.")
    }

    /** 장면이 품은 이야기. */
    private fun moodStory(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val human = subjects.any { it.label.lowercase() in HUMAN }
        return when {
            human -> say(seed, 361,
                "사람이 있으면 사진은 이야기를 품습니다. 누가 어디에 서 있고 무엇을 보고 있는지가 장면의 앞뒤를 상상하게 만듭니다.",
                "인물이 장면에 사건을 만듭니다. 사진이 무엇을 찍었는지보다 무슨 일이 있었는지를 먼저 묻게 됩니다.")
            subjects.size >= 3 -> say(seed, 362,
                "여러 대상이 놓인 자리가 작은 사건처럼 읽힙니다. 누가 어디에 있는지가 관계를 상상하게 하고, 사진이 한 장면의 기록이 됩니다.")
            else -> say(seed, 363,
                "이야기보다 장소의 성격이 남습니다. 사진이 사건을 담았다기보다 어떤 공기가 있었는지를 기록합니다.",
                "특별한 사건 없이도 장면이 하나의 이야기처럼 읽힙니다. 무엇이 일어나지 않았다는 사실이 오히려 사진의 주제가 됩니다.")
        }
    }

    private fun moodSection(a: Assessment, tone: Tone, limit: Int): String {
        val m = a.measure
        val seed = seedOf(m)
        val human = a.subjects.any { it.label.lowercase() in HUMAN }
        val reading = moodReading(a)
        val advice = moodAdvice(tone, human, a.subjects.isEmpty())
        val subjectMood = SubjectReading.voice(a.scene, seed)?.mood ?: moodAfter(m, seed)
        val items = if (limit <= 4) listOf(
            moodTone(m, seed), subjectMood, reading, advice
        ) else listOf(
            moodTone(m, seed), moodPresence(m, a.subjects, seed), subjectMood,
            moodStillness(m, a.subjects, seed), moodStory(m, a.subjects, seed), reading, advice,
            moodBreath(m, a.subjects, seed), reflection(Axis.EMOTION, a)
        )
        return join(items, limit)
    }

    /** 장면의 정적과 움직임의 기운. */
    private fun moodBreath(m: Measure, subjects: List<SubjectBox>, seed: Int): String = when {
        subjects.isEmpty() && m.contrast < 24 -> say(seed, 371,
            "화면은 아주 조용합니다. 대상도 움직임도 없이 빛만 남아, 사진이 소리를 낮춘 방처럼 읽힙니다.",
            "장면에 사람의 기척이 없습니다. 빈 공간과 빛만 남아, 사진이 머무는 시간이 길어집니다.")
        m.sharpness >= 150 && m.edges >= .03 -> say(seed, 372,
            "화면에 움직임의 기운이 남아 있습니다. 요소가 많고 또렷해, 정지된 사진인데도 장면이 조금씩 움직이는 것처럼 느껴집니다.")
        subjects.size >= 2 -> say(seed, 373,
            "대상들 사이에 긴장이 남아 있습니다. 서로의 거리와 방향이 관계를 만들고, 사진이 그 관계의 한 순간을 붙잡고 있습니다.")
        else -> say(seed, 374,
            "장면은 차분하지만 완전히 멈추지는 않았습니다. 빛의 방향이나 대상의 자세에서 다음 순간을 짐작하게 됩니다.")
    }

    // ---------- 잘 살아난 점 ----------
    private fun strengthFrame(top: List<Int>, a: Assessment): String {
        val first = Axis.entries[top[0]]
        val second = Axis.entries[top[1]]
        val best = a.evidence[top[0]].score
        val secondScore = a.evidence[top[1]].score
        return when {
            best >= .72 && secondScore >= .60 -> "이 사진은 ${wa(first.title)} ${iGa(second.title)} 함께 받쳐 주고 있습니다. 두 축이 같은 방향을 가리켜 인상이 흩어지지 않습니다."
            best >= .55 -> "가장 나은 지점은 ${first.title}이고, ${eun(second.title)} 그다음으로 안정적입니다."
            best >= .45 -> "두드러진 강점은 크지 않지만, 상대적으로 나은 쪽은 ${wa(first.title)} ${second.title}입니다."
            else -> "뚜렷한 강점을 짚기는 어렵습니다. 그래도 ${first.title} 쪽에 가능성이 남아 있습니다."
        }
    }

    private fun strengths(a: Assessment, top: List<Int>, limit: Int): String {
        val seed = seedOf(a.measure)
        val first = Axis.entries[top[0]]
        val second = Axis.entries[top[1]]
        val best = a.evidence[top[0]].score
        val secondScore = a.evidence[top[1]].score
        val items = mutableListOf(strengthFrame(top, a))
        SubjectReading.voice(a.scene, seed)?.let { items += it.praise }
        items += praise(first, a, seed)
        items += praise(second, a, seed)
        if (best >= .72 && secondScore >= .60) items += "두 지점이 같은 방향을 가리키고 있어, 사진의 인상이 한 번에 정리됩니다. 강점이 여럿 흩어진 사진보다, 둘이 겹친 사진이 기억에 오래 남습니다."
        items += "기억에 남는 사진은 대개 한 가지 인상이 분명합니다. 이 장에서는 그 인상이 ${first.title} 쪽에서 옵니다."
        items += "지금의 강점을 유지한 채 다른 요소를 손보면, 사진의 인상이 흔들리지 않고 조금씩 단단해집니다."
        items += "다음 장에서 이 장면을 다시 만난다면, ${first.title}에서 온 인상을 먼저 기억하시고 같은 조건을 한 번 더 만들어 보십시오."
        return join(items, limit)
    }

    // ---------- 더 다듬을 점 ----------
    private fun improvements(a: Assessment, level: Level, tone: Tone, weak: List<Int>, limit: Int): String {
        val seed = seedOf(a.measure)
        val first = Axis.entries[weak[0]]
        val second = Axis.entries[weak[1]]
        val items = mutableListOf(
            when (level) {
                Level.GENEROUS -> "지금 값에서 조금만 밀어 올리면 좋아질 지점은 ${wa(first.title)} ${second.title}입니다."
                Level.BALANCED -> "지금 흐름에서 한 번 더 다듬을 지점은 ${wa(first.title)} ${second.title}입니다."
                Level.STRICT -> "완성도를 막고 있는 지점은 ${wa(first.title)} ${second.title}입니다."
            },
            refine(first, a, seed),
            refine(second, a, seed)
        )
        SubjectReading.voice(a.scene, seed)?.let { items.add(1, it.refine) }
        items += "둘 중 하나만 고르라면 ${first.title} 쪽을 먼저 손보시는 편이 좋습니다. 사진의 인상이 가장 크게 흔들리는 곳이 그쪽입니다."
        items += "이 지점들은 사진을 다시 찍지 않아도 일부는 보정으로 정리할 수 있습니다. 다만 원본에 남아 있지 않은 정보를 만들어 낼 수는 없습니다."
        items += when (tone) {
            Tone.MENTOR -> "한 번에 모두 바꾸지 않아도 됩니다. 다음 장에서 한 가지만 시도해 보시면 차이가 보입니다."
            Tone.CRITIC -> "이 두 지점을 고치지 않으면 다른 요소를 손봐도 인상은 크게 달라지지 않습니다."
            Tone.ANALYST -> "두 지점은 근거 신뢰도가 비교적 높은 항목이라, 고친 뒤 효과를 확인하기 쉽습니다."
        }
        items += "지금의 부족함은 사진의 실패가 아니라 아직 결정되지 않은 부분입니다. 어느 쪽으로 밀지 정하는 순간 사진이 달라집니다."
        items += "사진을 다시 볼 때는 화면을 절반쯤 멀리 두고 보십시오. 가까이서 보이지 않던 균형의 문제가 그 거리에서 드러납니다."
        return join(items, limit)
    }

    // ---------- 촬영 제안 ----------
    private fun principal(a: Assessment): SubjectBox? = a.subjects.firstOrNull()

    /** 카메라를 어디에 둘지. */
    private fun shotAngle(a: Assessment, tone: Tone): String {
        val main = principal(a)
        val body = when {
            main == null -> "지금의 높이를 유지하되, 화면의 가로와 세로를 바꿔 같은 장면을 한 번 더 담는 편이"
            main.cy > .62 -> "지금보다 카메라를 조금 내려 눈높이를 낮추고, 대상을 살짝 올려다보는 각도로 담는 편이"
            main.cy < .38 -> "카메라를 조금 들어 대상을 내려다보는 각도로 담아, 배경을 바닥처럼 깔아 보는 편이"
            else -> "지금의 눈높이를 유지하되 좌우로 반 걸음 움직여 배경의 방향을 바꿔 보는 편이"
        }
        return toneEnd(tone, body)
    }

    /** 얼마나 다가설지. */
    private fun shotDistance(a: Assessment, tone: Tone): String {
        val main = principal(a)
        val body = when {
            main == null -> "한 걸음 물러나 장면 전체를 조금 더 담아 보고, 다시 반 걸음 다가가 두 장을 비교하는 편이"
            main.area >= .35 -> "한 걸음 물러나 대상을 조금 덜 채우고, 주변의 맥락을 함께 담는 편이"
            main.area <= .08 -> "두 걸음 다가가 대상을 키우고, 주변 여백의 비율을 다시 잡는 편이"
            else -> "지금의 거리를 유지하되, 대상과 배경 사이의 간격이 달라지는 위치를 한 번 더 찾아보는 편이"
        }
        return toneEnd(tone, body)
    }

    /** 노출을 어떻게 잡을지. */
    private fun shotExposure(a: Assessment, tone: Tone): String {
        val m = a.measure
        val body = when {
            m.highlights > .02 -> "밝은 부분을 기준으로 측광하고 노출을 반 스톱 낮춰, 가장 밝은 면의 결을 남기는 편이"
            m.shadows > .04 -> "어두운 부분을 기준으로 측광해 그림자 속 형태를 살리고, 밝은 면은 그대로 두는 편이"
            m.contrast < 18 -> "측광을 피사체에 맞추고 배경과의 밝기 차이가 생기는 방향으로 움직이는 편이"
            else -> "지금의 측광을 유지하되, 같은 장면을 노출만 반 스톱씩 다르게 세 장 담아 고르는 편이"
        }
        return toneEnd(tone, body)
    }

    private fun shooting(a: Assessment, tone: Tone, depth: Depth, weak: List<Int>): String {
        val first = Axis.entries[weak[0]]
        val second = Axis.entries[weak[1]]
        val voice = SubjectReading.voice(a.scene, seedOf(a.measure))
        val items = mutableListOf(
            when (tone) {
                Tone.MENTOR -> "다음 장에서는 이렇게 잡아 보시면 좋겠습니다."
                Tone.CRITIC -> "촬영 단계에서 지켜야 할 것은 이 두 가지입니다."
                Tone.ANALYST -> "촬영 제안은 근거가 약한 두 항목을 보완하는 순서로 적었습니다."
            },
            shootAdvice(first, a, tone),
            shootAdvice(second, a, tone),
            shotAngle(a, tone),
            shotDistance(a, tone),
            shotExposure(a, tone)
        )
        voice?.let { items.add(3, it.shoot) }
        items += "같은 장면에서 이 세 가지만 바꿔 몇 장을 겹쳐 찍으면, 어떤 선택이 이 사진에 맞는지 스스로 확인할 수 있습니다."
        items += "촬영은 한 번에 완성되지 않습니다. 지금의 구도를 기준점으로 두고, 한 요소씩만 바꿔 비교하는 편이 배우는 속도가 빠릅니다."
        items += "장면을 떠나기 전에 화면을 한 번 더 확인하십시오. 프레임 가장자리와 초점이 놓인 자리만 보아도 절반은 정리됩니다."
        return join(items, depth.sentences)
    }

    // ---------- 보정 제안 ----------
    private fun editing(a: Assessment, tone: Tone, depth: Depth, weak: List<Int>): String {
        val m = a.measure
        val first = Axis.entries[weak[0]]
        val second = Axis.entries[weak[1]]
        val voice = SubjectReading.voice(a.scene, seedOf(m))
        val items = mutableListOf(
            when (tone) {
                Tone.MENTOR -> "보정은 원본이 담고 있는 정보 안에서 마무리하시면 좋겠습니다."
                Tone.CRITIC -> "보정으로 없는 디테일을 만들 수는 없습니다. 원본에 남은 정보 안에서만 손대십시오."
                Tone.ANALYST -> "보정은 화면의 밝기와 색 값을 바꾸는 작업이므로, 바꾼 뒤의 균형을 다시 확인하십시오."
            },
            editAdvice(first, a, tone),
            editAdvice(second, a, tone)
        )
        voice?.let { items.add(3, it.edit) }
        if (m.highlights > .02) items += "밝은 쪽은 하이라이트 슬라이더를 낮추고, 톤 커브의 어깨를 부드럽게 만들어 흰색으로 눌린 결을 되살리는 편이 좋겠습니다."
        if (m.shadows > .03) items += "어두운 쪽은 섀도우를 살짝 들어 올리되, 검정 지점은 남겨 두어야 그림자가 뜨지 않습니다."
        if (m.contrast < 20.0) items += "검정 지점을 조금 내리고 중간 톤을 살짝 눌러 계조의 폭을 넓히면, 사진이 한 단계 깊어집니다."
        if (m.channelCast > 8.0) items += if (m.warmth > 0) "색온도를 차가운 쪽으로 조금 옮겨 피사체의 고유색을 되찾는 편이 좋습니다." else "색온도를 따뜻한 쪽으로 조금 옮겨 장면의 온기를 살리는 편이 좋습니다."
        if (m.saturation > .42) items += "채도를 한 단계 낮추고 주조색 하나를 남기면 색이 서로 다투지 않습니다."
        if (m.sharpness < 60.0) items += "노이즈가 눈에 띄면 색 노이즈 정리를 먼저 하고, 그다음 윤곽을 세우는 순서가 좋습니다. 순서를 바꾸면 거친 결이 함께 살아납니다."
        items += "보정은 화면 전체의 균형을 보며 조금씩 움직이고, 단계마다 원본과 비교해 보십시오. 한 번에 크게 움직이면 되돌릴 기준을 잃습니다."
        items += "마지막에는 크롭을 한 번 더 확인하십시오. 가장자리의 자잘한 요소를 조금 덜어내는 것만으로 화면이 정돈됩니다."
        items += "보정을 마친 뒤 하루 정도 지나 다시 보면, 손이 과했는지 스스로 판단하기 쉬워집니다."
        return join(items, depth.sentences)
    }

    // ---------- 상세 측정 데이터 및 기술적 근거 ----------
    /** 화면 아래 접이식 블록과 복사 텍스트에 함께 쓰는 수치 근거. */
    fun measurements(a: Assessment, score: ScoreResult?): List<MeasureGroup> {
        val m = a.measure
        val main = a.subjects.firstOrNull()
        val groups = mutableListOf<MeasureGroup>()
        groups += MeasureGroup("평가 모델", listOf(
            "NIMA 미적 원점수" to "${f2(a.nima.aesthetic)} / 10",
            "NIMA 기술 원점수" to "${f2(a.nima.technical)} / 10",
            "확률분포 표준편차" to f2(a.nima.deviation),
            "분석 해상도" to "${m.width}×${m.height}"
        ))
        groups += MeasureGroup("빛과 계조", listOf(
            "휘도 평균" to "${f1(m.luminance)} / 255",
            "휘도 중간값" to "${m.percentile(.5)}",
            "5 · 95 퍼센타일" to "${m.percentile(.05)} · ${m.percentile(.95)}",
            "하이라이트 클리핑" to p2(m.highlights),
            "암부 클리핑" to p2(m.shadows),
            "전역 대비" to f1(m.contrast),
            "국소 대비" to f1(m.localContrast)
        ))
        groups += MeasureGroup("색채", listOf(
            "평균 RGB" to "${f1(m.rgb.getOrElse(0) { 0.0 })} / ${f1(m.rgb.getOrElse(1) { 0.0 })} / ${f1(m.rgb.getOrElse(2) { 0.0 })}",
            "채널 간 차이" to f1(m.channelCast),
            "평균 채도" to p1(m.saturation),
            "색상 구간" to "${m.hueVariety} / 12",
            "주조색 비중" to if (m.hueTotal <= 0) "—" else "${HUE_NAMES[m.dominantHue]} ${p0(m.dominantShare)}"
        ))
        groups += MeasureGroup("선명도", listOf(
            "Laplacian 분산" to f1(m.sharpness),
            "에지 밀도" to p2(m.edges),
            "국소 대비" to f1(m.localContrast)
        ))
        val compositionRows = mutableListOf<Pair<String, String>>(
            "검출 피사체" to "${a.subjects.size}개",
            "수평선 후보" to "${m.lines.size}개",
            "최대 기울기" to "${f1(m.maxTilt)}°"
        )
        if (main != null) {
            compositionRows += "가장 큰 피사체" to "${koLabel(main.label)} · 신뢰도 ${p0(main.confidence)}"
            compositionRows += "중심 위치" to "${p0(main.cx)} · ${p0(main.cy)}"
            compositionRows += "프레임 점유" to p1(main.area)
            compositionRows += "삼분할 거리" to p0(nearestThirds(main.cx, main.cy))
        }
        groups += MeasureGroup("구도 · 피사체", compositionRows)
        groups += MeasureGroup("항목별 근거", Axis.entries.mapIndexed { i, axis ->
            axis.title to "근거 ${f2(a.evidence[i].score)} · 신뢰도 ${p0(a.evidence[i].confidence)}"
        })
        if (score != null) {
            groups += MeasureGroup("점수 계산", listOf(
                "관점 점수" to f2(score.weighted),
                "보정 전" to f2(score.base),
                "부스팅 후" to f2(score.adjusted),
                "가중 신뢰도" to p0(score.coverage),
                "산식" to "NIMA 40% + 근거 60%, 1 + 9·x^γ",
                "γ (Generous·Balanced·Strict)" to "${f2(Level.GENEROUS.exponent)} · ${f2(Level.BALANCED.exponent)} · ${f2(Level.STRICT.exponent)}"
            ))
        }
        return groups
    }

    /** 항목별 기술 관찰. 접이식 블록 안에서 근거 문장으로 보여준다. */
    fun evidenceLines(a: Assessment): List<Pair<String, String>> = Axis.entries.mapIndexed { i, axis ->
        axis.title to a.evidence[i].observation
    }
}
