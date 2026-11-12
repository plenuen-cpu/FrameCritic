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

    fun assess(nima: NimaResult, measure: Measure, subjects: List<SubjectBox>): Assessment {
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
        return Assessment(nima, measure, sorted, evidence, observations, description)
    }

    /** 어떤 입력이나 내부 오류에도 앱을 종료시키지 않고 Assessment를 돌려준다. */
    fun safeAssess(nima: NimaResult, measure: Measure, subjects: List<SubjectBox>): Assessment =
        runCatching { assess(nima, measure, subjects) }.getOrElse { neutralAssessment(nima, measure, subjects) }

    private fun neutralAssessment(nima: NimaResult, measure: Measure, subjects: List<SubjectBox>): Assessment {
        val evidence = Axis.entries.map { axis ->
            Evidence(.5, CONFIDENCE_FLOOR, "${axis.title} 항목의 측정값을 정리하지 못했습니다.", "이 부분은 이번 측정에서 판단할 근거를 얻지 못했습니다.")
        }
        val observations = Axis.entries.mapIndexed { i, axis -> axis.name to evidence[i].observation }.toMap()
        return Assessment(nima, measure, subjects, evidence, observations, "측정값 요약을 만들지 못해 항목별 근거만 남겼습니다.")
    }

    private data class Ax(val score: Double, val confidence: Double, val observation: String, val interpretation: String)

    // ---------- measurements (numbers only live here) ----------
    private fun composition(m: Measure, subjects: List<SubjectBox>): Ax {
        val main = subjects.firstOrNull()
        val crowded = subjects.count { it.touchesEdge && it.area > .05 }
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
    private fun lightVisual(m: Measure, seed: Int): String {
        val p50 = medianTone(m)
        val head = when {
            p50 < 70 -> pick(seed, "화면은 어둠 쪽에 머물러 있습니다", "장면 전체가 낮은 빛 속에 놓여 있습니다")
            p50 > 170 -> pick(seed, "화면은 밝은 쪽으로 열려 있습니다", "빛이 넓게 퍼진 장면입니다")
            else -> pick(seed, "빛은 중간 어디쯤에서 균형을 잡고 있습니다", "화면의 밝기는 무난한 자리에 놓여 있습니다")
        }
        val clip = when {
            m.highlights > .02 && m.shadows > .04 -> pick(seed + 1, "가장 밝은 부분과 가장 어두운 부분이 함께 눌려 있습니다", "밝은 끝과 어두운 끝이 동시에 닫혀 있습니다")
            m.highlights > .02 -> pick(seed + 2, "가장 밝은 부분이 흰색으로 눌려 있습니다", "하이라이트가 하얗게 타 버려 밝은 면이 하나의 덩어리로 뭉쳤습니다")
            m.shadows > .04 -> pick(seed + 3, "어두운 부분이 검정으로 뭉개져 있습니다", "암부가 검정으로 가라앉아 어두운 면 사이의 차이가 사라졌습니다")
            m.contrast >= 40 -> pick(seed + 4, "밝은 곳과 어두운 곳의 낙차가 크고, 어느 쪽도 눌리지 않았습니다", "빛과 그림자의 폭이 넓게 열려 있습니다")
            m.contrast < 18 -> pick(seed + 5, "밝은 곳과 어두운 곳의 차이가 완만합니다", "명암이 부드럽게 이어져 장면이 한 폭의 면처럼 펼쳐집니다")
            else -> pick(seed + 6, "밝은 곳과 어두운 곳이 고르게 살아 있습니다", "빛이 부드럽게 퍼지며 형태의 윤곽을 감싸고 있습니다")
        }
        return "$head. $clip."
    }

    private fun colorVisual(m: Measure, seed: Int): String {
        val warm = when { m.warmth > 5.0 -> 1; m.warmth < -5.0 -> 2; else -> 0 }
        val head = when (warm) {
            1 -> pick(seed, "화면 전체에 따뜻한 기운이 감돌아 장면이 부드럽게 감쌉니다", "붉은 기운이 넓게 퍼져 장면에 온기가 돕니다")
            2 -> pick(seed, "푸른 기운이 화면을 가라앉혀 차분하고 서늘한 공기가 남습니다", "푸른 쪽으로 기울어 장면이 한결 차분하게 읽힙니다")
            else -> pick(seed, "색온도가 한쪽으로 치우치지 않아 중성에 가깝습니다", "색온도가 고르게 잡혀 있어 장면의 색이 자연스럽습니다")
        }
        val body = when {
            m.hueTotal <= 0 -> "유채색이 거의 없어 화면이 무채색으로 정리되어 있습니다."
            m.saturation < .12 -> "색이 옅게 깔려 있어 화면이 담백합니다."
            m.dominantShare >= .45 -> "${HUE_NAMES[m.dominantHue]} 계열이 화면의 절반 이상을 차지합니다."
            m.hueVariety >= 8 -> "여러 색이 고루 섞여 있어 화면이 분주합니다."
            m.hueVariety <= 3 -> "쓰인 색의 수가 적어 화면이 단정합니다."
            m.saturation >= .42 -> "색의 기운이 진하게 살아 있습니다."
            else -> "몇 가지 색이 서로의 자리를 나누고 있습니다."
        }
        return "$head. $body"
    }

    private fun compositionVisual(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val main = subjects.firstOrNull()
        if (main == null) {
            val head = pick(seed, "뚜렷하게 자리 잡은 피사체 없이, 화면의 면과 빛이 구도를 이룹니다", "눈에 띄게 잡히는 피사체가 없어 여백과 빛의 배치가 장면을 만듭니다")
            val line = when {
                m.lines.isEmpty() -> "기준이 되는 선도 뚜렷하지 않아, 화면의 질서는 색과 밝기의 덩어리로만 읽힙니다."
                m.maxTilt > 3.0 -> "화면을 가로지르는 선이 살짝 기울어 장면이 미묘하게 흔들립니다."
                else -> "화면을 가로지르는 선이 반듯해 장면의 뼈대가 흔들리지 않습니다."
            }
            return "$head. $line"
        }
        val label = koLabel(main.label)
        val d = nearestThirds(main.cx, main.cy)
        val placement = when {
            d <= .10 -> "${eun(label)} 화면의 힘 있는 자리에 놓여 시선이 자연스럽게 머뭅니다"
            d <= .18 -> "${eun(label)} 중심과 힘 있는 자리 사이에 걸쳐 있어 시선의 착지점이 조금 흐립니다"
            else -> "${eun(label)} 화면의 힘 있는 자리에서 벗어나 있어 눈이 중심을 잡기 어렵습니다"
        }
        val extras = mutableListOf<String>()
        if (subjects.count { it.touchesEdge && it.area > .05 } > 0) extras += "가장자리에 닿은 요소가 프레임을 조여 화면이 다소 답답하게 느껴집니다"
        if (subjects.size >= 3) extras += "여러 대상이 함께 있어 시선이 한곳에 머물지 못합니다"
        if (main.area >= .45) extras += "피사체가 화면을 크게 차지해 배경보다 대상 자체에 시선이 집중됩니다"
        else if (main.area <= .05) extras += "피사체는 작게 자리해 여백이 화면의 주인공이 됩니다"
        if (m.maxTilt > 3.0) extras += "화면을 가로지르는 선이 살짝 기울어 장면이 미묘하게 흔들립니다"
        return if (extras.isEmpty()) "$placement." else "$placement. ${extras.first()}."
    }

    private fun sharpnessVisual(a: Assessment, seed: Int): String = when (band(a.evidence[Axis.SHARPNESS.ordinal].score)) {
        2 -> pick(seed, "윤곽이 또렷하고 표면의 결이 살아 있습니다.", "디테일이 촘촘하게 살아 있어 표면이 또렷하게 읽힙니다.")
        1 -> pick(seed, "형태는 분명하지만 표면의 결이 조금 눌려 있습니다.", "전체적인 초점은 잡혀 있으나 미세한 결이 얕게 죽어 있습니다.")
        else -> pick(seed, "윤곽이 부드럽게 풀려 있습니다.", "초점이 얕게 풀리며 화면이 부드러운 인상으로 덮여 있습니다.")
    }

    private fun moodVisual(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val p50 = medianTone(m)
        val head = when {
            p50 < 70 -> pick(seed, "어두운 톤이 장면을 감싸고 있어 고요한 공기가 남습니다", "낮게 가라앉은 빛이 차분한 기운을 만듭니다")
            p50 > 170 -> pick(seed, "밝은 톤이 화면을 열어 주어 가볍고 산뜻한 인상입니다", "밝은 빛이 화면을 채워 장면이 경쾌하게 읽힙니다")
            else -> pick(seed, "중간 톤이 중심을 잡아 안정된 인상을 줍니다", "빛과 어둠이 고르게 섞여 담담한 공기가 흐릅니다")
        }
        val human = subjects.any { it.label.lowercase() in HUMAN }
        val body = when {
            human -> "사람이 있다는 것만으로 장면에 온도가 생기고, 시선이 인물의 표정과 몸짓을 따라갑니다."
            subjects.isNotEmpty() -> "화면 안의 대상이 분위기를 만들고, 그 주변의 여백이 호흡을 더합니다."
            else -> "특정 대상 없이 빛과 색만으로 분위기가 만들어집니다."
        }
        return "$head. $body"
    }

    private fun momentVisual(m: Measure, subjects: List<SubjectBox>, seed: Int): String {
        val human = subjects.any { it.label.lowercase() in HUMAN }
        return when {
            human && m.sharpness >= 120 -> "움직임이 있을 법한 장면인데 포착이 또렷해, 순간을 붙잡은 인상이 남습니다."
            subjects.size > 6 -> "여러 요소가 한꺼번에 담겨 장면이 분주하고, 시선의 초점은 조금 흩어져 있습니다."
            m.sharpness >= 120 -> "정지된 장면이 또렷하게 담겨, 시간이 잠시 멈춘 듯한 공기가 있습니다."
            else -> pick(seed, "장면이 잔잔하게 가라앉아 지나간 뒤의 공기를 담은 듯합니다.", "큰 움직임 없이 정돈된 장면이라 긴장보다 안온한 기운이 남습니다.")
        }
    }

    private fun visual(axis: Axis, a: Assessment, seed: Int): String {
        val m = a.measure
        return when (axis) {
            Axis.COMPOSITION -> compositionVisual(m, a.subjects, seed)
            Axis.LIGHTING -> lightVisual(m, seed)
            Axis.COLOR -> colorVisual(m, seed)
            Axis.SHARPNESS -> sharpnessVisual(a, seed)
            Axis.EMOTION -> moodVisual(m, a.subjects, seed)
            Axis.TIMING -> momentVisual(m, a.subjects, seed)
        }
    }

    /** 짧은 강점 한 줄. 밴드가 낮으면 칭찬을 꾸며 내지 않고 표현을 낮춘다. */
    private fun praiseShort(axis: Axis, a: Assessment): String {
        val b = band(a.evidence[axis.ordinal].score)
        val strong = when (axis) {
            Axis.COMPOSITION -> "구도가 편안해서 화면을 보는 동안 눈이 걸리지 않습니다."
            Axis.LIGHTING -> "빛이 형태를 감싸고 있어 장면이 입체적으로 읽힙니다."
            Axis.COLOR -> "색의 온도가 한 방향으로 정리되어 인상이 깔끔합니다."
            Axis.SHARPNESS -> "질감이 살아 있어 눈이 표면을 따라 움직입니다."
            Axis.EMOTION -> "장면에 온도가 있어 사진을 오래 들여다보게 됩니다."
            Axis.TIMING -> "순간의 공기가 살아 있어 장면이 정지해 있지 않습니다."
        }
        val mid = when (axis) {
            Axis.COMPOSITION -> "구도는 무난하게 정돈되어 있습니다."
            Axis.LIGHTING -> "빛은 안정적으로 자리를 잡고 있습니다."
            Axis.COLOR -> "색이 크게 흔들리지 않고 자연스럽습니다."
            Axis.SHARPNESS -> "형태를 읽는 데는 부족함이 없습니다."
            Axis.EMOTION -> "장면의 분위기가 담담하게 전해집니다."
            Axis.TIMING -> "장면이 정돈된 채로 남아 있습니다."
        }
        val weak = when (axis) {
            Axis.COMPOSITION -> "구도에 아직 손볼 여지가 남아 있습니다."
            Axis.LIGHTING -> "빛이 조금 더 살아나면 좋겠습니다."
            Axis.COLOR -> "색의 방향을 잡으면 인상이 달라집니다."
            Axis.SHARPNESS -> "질감이 살아나면 사진이 달라집니다."
            Axis.EMOTION -> "분위기에 온기를 더할 여지가 있습니다."
            Axis.TIMING -> "순간을 조금 더 붙잡을 여지가 있습니다."
        }
        return if (b == 2) strong else if (b == 1) mid else weak
    }

    /** 짧은 개선 한 줄. 촬영 제안과 반복되지 않도록 방향만 말한다. */
    private fun fixShort(axis: Axis, a: Assessment): String {
        val m = a.measure
        return when (axis) {
            Axis.COMPOSITION -> when {
                a.subjects.isEmpty() -> "화면에 머물 요소를 하나 정해 주면 구도가 생깁니다."
                a.measure.maxTilt > 3.0 -> "수평선을 반듯하게 맞추면 화면이 한결 안정됩니다."
                else -> "시선이 머물 자리를 하나로 정해 주면 화면이 훨씬 편해집니다."
            }
            Axis.LIGHTING -> when {
                m.highlights > .02 -> "밝은 부분의 결을 조금 더 남기면 빛의 질감이 살아납니다."
                m.shadows > .04 -> "어두운 부분을 살짝 열어 주면 그림자 속의 형태가 드러납니다."
                else -> "빛의 낙차를 한 단계 키우면 장면에 깊이가 생깁니다."
            }
            Axis.COLOR -> when {
                m.channelCast > 12.0 -> "색온도를 중립 쪽으로 옮기면 피사체의 색이 제자리를 찾습니다."
                m.hueVariety >= 8 -> "주조색 두 개로 정리하면 인상이 선명해집니다."
                else -> "주조색 하나를 정하고 나머지 색을 뒤로 물리면 색의 방향이 생깁니다."
            }
            Axis.SHARPNESS -> "초점이 놓이는 자리를 더 또렷하게 잡으면 질감이 살아납니다."
            Axis.EMOTION -> "장면에 온기를 더할 요소를 하나 넣거나 덜어내 보십시오."
            Axis.TIMING -> "움직임이 정점에 이르는 순간을 노리면 장면이 살아납니다."
        }
    }

    /** 촬영·보정에서 바로 쓸 수 있는 조언. 문장 끝은 tone이 결정한다. */
    private fun advice(axis: Axis, a: Assessment, tone: Tone): String {
        val m = a.measure
        val main = a.subjects.firstOrNull()
        val strong = band(a.evidence[axis.ordinal].score) == 2
        val body = when (axis) {
            Axis.COMPOSITION -> when {
                strong -> "지금의 배치를 그대로 두고 같은 자리에서 한두 걸음만 옮겨 다른 여백의 비율도 시험하는 편이"
                main == null -> "화면에 머물 요소를 하나 정해 힘 있는 자리에 두고, 여백이 그 요소를 밀어 주도록 잡는 편이"
                nearestThirds(main.cx, main.cy) > .14 -> "가장 큰 피사체를 격자의 교차점 쪽으로 반 걸음 옮기는 편이"
                a.subjects.any { it.touchesEdge && it.area > .05 } -> "가장자리에 닿은 요소를 안으로 들이거나 과감히 잘라내는 편이"
                m.maxTilt > 3.0 -> "수평선을 반듯하게 맞추는 편이"
                else -> "지금의 배치를 유지하되 여백을 한쪽으로 몰아 긴장을 만드는 편이"
            }
            Axis.LIGHTING -> when {
                strong -> "지금의 노출을 유지하되 밝은 부분이 먼저 날아가지 않게 두는 편이"
                m.highlights > .02 -> "노출을 반 스톱쯤 낮춰 밝은 부분의 결을 되찾는 편이"
                m.shadows > .04 -> "어두운 부분을 살짝 들어 올리거나 그림자가 향하는 방향을 바꾸는 편이"
                m.contrast < 18.0 -> "측광을 피사체에 맞추고 배경과의 밝기 차를 만드는 편이"
                else -> "노출을 고정하고 밝은 쪽과 어두운 쪽의 균형을 다시 확인하는 편이"
            }
            Axis.COLOR -> when {
                strong -> "지금의 색 균형을 유지하고 색을 더 진하게 만들지 않는 편이"
                m.channelCast > 8.0 && m.warmth > 0 -> "화이트밸런스를 조금 차갑게 두어 피사체의 고유색을 살리는 편이"
                m.channelCast > 8.0 -> "화이트밸런스를 조금 따뜻하게 두어 장면의 온기를 살리는 편이"
                m.saturation > .42 -> "색을 한 단계 낮추고 주조색 하나를 남기는 편이"
                m.hueVariety >= 8 -> "색이 많은 화면이므로 주조색 두 개로 정리하는 편이"
                else -> "광원의 색을 그대로 두고 피사체의 고유색을 살리는 편이"
            }
            Axis.SHARPNESS -> when {
                strong -> "과한 샤프닝 없이 지금의 초점과 조리개를 유지하는 편이"
                m.sharpness < 60.0 -> "셔터 속도를 초점거리의 역수보다 빠르게 잡고 삼각대나 벽에 기대는 편이"
                m.localContrast < 12.0 -> "조리개를 반 스톱 조여 국소 대비를 올리는 편이"
                m.edges < .01 -> "초점 위치를 피사체의 눈이나 윤곽으로 옮기는 편이"
                else -> "초점을 피사체의 핵심부에 고정하고 흔들림을 줄이는 편이"
            }
            Axis.EMOTION -> when {
                strong -> "지금의 톤 방향을 유지하며 피사체와 배경의 온도 차를 살리는 편이"
                a.subjects.isEmpty() -> "장면에 온도를 줄 요소를 하나 더 넣거나 덜어내는 편이"
                else -> "피사체의 시선과 주변의 빈 공간이 함께 읽히도록 프레임을 정리하는 편이"
            }
            Axis.TIMING -> if (a.subjects.any { it.label.lowercase() in HUMAN }) "제스처가 정점에 이르는 순간까지 기다렸다가 누르는 편이" else "장면의 움직임이 멈추는 지점을 골라 누르는 편이"
        }
        return when (tone) {
            Tone.MENTOR -> "$body 좋겠습니다."
            Tone.CRITIC -> "$body 낫습니다."
            Tone.ANALYST -> "$body 화면에 드러난 요소와 맞습니다."
        }
    }

    /** 깊은 리포트에서만 덧붙이는 한계 문장. 수치 없이 근거의 범위를 밝힌다. */
    private fun reflection(axis: Axis, a: Assessment): String = when (axis) {
        Axis.COMPOSITION -> "프레임 밖의 상황은 알 수 없으므로, 화면 안의 배치만을 근거로 삼았습니다."
        Axis.LIGHTING -> "빛의 방향과 질은 한 장으로 확정할 수 없어, 드러난 명암의 관계만 읽었습니다."
        Axis.COLOR -> "평균 색만으로 조명의 색을 확정할 수는 없습니다. 화면에 보이는 색의 관계를 기준으로 했습니다."
        Axis.SHARPNESS -> "같은 수치라도 피사체와 해상도에 따라 의미가 달라집니다. 의도된 흐림일 수도 있습니다."
        Axis.EMOTION -> "감정은 값으로 확정할 수 없으므로, 화면의 빛과 색에서 읽히는 인상만 적었습니다."
        Axis.TIMING -> "한 장의 정지 이미지로 결정적 순간을 확정하지는 않았습니다."
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
        out["빛과 노출"] = axisSection(a, Axis.LIGHTING, tone, limit)
        out["색채"] = axisSection(a, Axis.COLOR, tone, limit)
        out["구도"] = axisSection(a, Axis.COMPOSITION, tone, limit)
        out["선명도와 질감"] = axisSection(a, Axis.SHARPNESS, tone, limit)
        out["분위기와 순간"] = moodSection(a, tone, limit)
        out["잘 살아난 점"] = strengths(a, top, limit)
        out["더 다듬을 점"] = improvements(a, level, tone, weak, limit)
        out["촬영 제안"] = shooting(a, tone, depth, weak)
        out["보정 제안"] = editing(a, tone, depth, weak)
        return out
    }

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
        s10 >= 9.0 -> "완성도가 단단한 한 장입니다. 흠을 찾기보다 유지할 이유를 먼저 보게 됩니다."
        s10 >= 8.2 -> "완성도가 높은 한 장입니다. 빛과 색이 서로를 받쳐 주고 있습니다."
        s10 >= 7.4 -> "균형이 좋은 한 장입니다. 큰 흔들림 없이 화면이 자기 자리를 잡고 있습니다."
        s10 >= 6.6 -> "안정적으로 정리된 한 장입니다. 지금의 기본을 조금만 더 밀면 인상이 또렷해집니다."
        s10 >= 5.8 -> "가능성이 보이는 한 장입니다. 방향은 맞고, 손볼 지점이 분명합니다."
        else -> "아직 다듬을 여지가 큰 한 장입니다. 다만 출발점은 분명합니다."
    }

    private fun summary(a: Assessment, s10: Double, tone: Tone, depth: Depth, top: List<Int>, weak: List<Int>): String {
        val t = Sentences(depth.sentences)
        val best = Axis.entries[top[0]]
        val weakest = Axis.entries[weak[0]]
        t.add(openingFor(s10))
        t.add(highlight(best, a))
        t.add("${eun(weakest.title)} 이 사진에서 먼저 살펴볼 지점입니다. " + shortNote(weakest))
        if (depth == Depth.EXPERT) t.add("사진 한 장으로 작가의 의도나 독창성을 확정하지는 않았습니다. 화면에 드러난 요소만 근거로 삼았습니다.")
        if (depth != Depth.EXPERT) t.add(when (tone) {
            Tone.MENTOR -> "아래에서 빛과 색, 구도를 하나씩 짚었습니다. 오늘은 한 가지만 의식해 보셔도 충분합니다."
            Tone.CRITIC -> "아래 항목은 화면에 실제로 드러난 것만 적었습니다. 감상보다 근거를 먼저 확인하십시오."
            Tone.ANALYST -> "아래 항목은 화면에서 관찰한 요소와 그 해석을 구분해 적었습니다."
        })
        return t.build()
    }

    /** 종합 크리틱에서 가장 나은 항목을 짚는 문장. '잘 살아난 점'과 같은 문장을 쓰지 않는다. */
    private fun highlight(axis: Axis, a: Assessment): String = when (band(a.evidence[axis.ordinal].score)) {
        2 -> when (axis) {
            Axis.COMPOSITION -> "무엇보다 구도가 눈에 편합니다. 화면이 한 번에 읽힙니다."
            Axis.LIGHTING -> "무엇보다 빛이 좋습니다. 밝은 면과 어두운 면이 서로를 살려 주고 있습니다."
            Axis.COLOR -> "무엇보다 색이 차분합니다. 화면의 온도가 한 방향으로 모여 있습니다."
            Axis.SHARPNESS -> "무엇보다 질감이 살아 있습니다. 표면의 결이 눈에 잡힙니다."
            Axis.EMOTION -> "무엇보다 분위기가 남습니다. 사진을 덮은 뒤에도 장면의 공기가 이어집니다."
            Axis.TIMING -> "무엇보다 순간이 살아 있습니다. 장면이 정지해 있지 않습니다."
        }
        1 -> "눈에 띄는 강점은 크지 않지만, ${eun(axis.title)} 무난하게 자리를 잡고 있습니다."
        else -> "뚜렷한 강점을 짚기는 어렵습니다. 그래도 ${axis.title} 쪽에서 시작해 볼 수 있습니다."
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

    private fun axisSection(a: Assessment, axis: Axis, tone: Tone, limit: Int): String {
        val t = Sentences(limit)
        val seed = seedOf(a.measure)
        t.add(visual(axis, a, toneSeed(seed, axis.ordinal)))
        t.add(a.evidence[axis.ordinal].interpretation)
        t.add(advice(axis, a, tone))
        if (limit >= 5) t.add(reflection(axis, a))
        return t.build()
    }

    private fun moodSection(a: Assessment, tone: Tone, limit: Int): String {
        val t = Sentences(limit)
        val m = a.measure
        val seed = seedOf(m)
        t.add(moodVisual(m, a.subjects, toneSeed(seed, 5)))
        t.add(momentVisual(m, a.subjects, toneSeed(seed, 6)))
        val emotionStrong = band(a.evidence[Axis.EMOTION.ordinal].score) >= 2
        val timingStrong = band(a.evidence[Axis.TIMING.ordinal].score) >= 2
        t.add(when {
            emotionStrong && timingStrong -> "분위기와 순간이 함께 살아 있어, 장면이 정지된 이미지가 아니라 하나의 사건처럼 읽힙니다."
            emotionStrong -> "분위기가 먼저 다가오고, 순간의 긴장은 뒤에서 조용히 받쳐 주고 있습니다."
            timingStrong -> "순간이 또렷하게 붙잡혀 있어, 장면의 공기가 사진 안에 머물러 있습니다."
            else -> "화면은 차분하지만, 감정의 온도가 한 단계 더 올라가면 사진이 오래 기억에 남습니다."
        })
        if (limit >= 5) t.add(reflection(Axis.EMOTION, a))
        return t.build()
    }

    private fun strengths(a: Assessment, top: List<Int>, limit: Int): String {
        val t = Sentences(limit)
        val first = Axis.entries[top[0]]
        val second = Axis.entries[top[1]]
        val best = a.evidence[top[0]].score
        val secondScore = a.evidence[top[1]].score
        t.add(when {
            best >= .72 && secondScore >= .60 -> "이 사진은 ${wa(first.title)} ${iGa(second.title)} 함께 받쳐 주고 있습니다."
            best >= .55 -> "가장 나은 지점은 ${first.title}이고, ${eun(second.title)} 그다음으로 안정적입니다."
            best >= .45 -> "두드러진 강점은 크지 않지만, 상대적으로 나은 쪽은 ${wa(first.title)} ${second.title}입니다."
            else -> "뚜렷한 강점을 짚기는 어렵습니다. 그래도 ${first.title} 쪽에 가능성이 남아 있습니다."
        })
        t.add(praiseShort(first, a))
        if (limit > 2) t.add(praiseShort(second, a))
        if (limit >= 5 && best >= .72 && secondScore >= .60) t.add("두 지점이 같은 방향을 가리키고 있어, 사진의 인상이 한 번에 정리됩니다.")
        return t.build()
    }

    private fun improvements(a: Assessment, level: Level, tone: Tone, weak: List<Int>, limit: Int): String {
        val t = Sentences(limit)
        val first = Axis.entries[weak[0]]
        val second = Axis.entries[weak[1]]
        t.add(when (level) {
            Level.GENEROUS -> "지금 값에서 조금만 밀어 올리면 좋아질 지점은 ${wa(first.title)} ${second.title}입니다."
            Level.BALANCED -> "지금 흐름에서 한 번 더 다듬을 지점은 ${wa(first.title)} ${second.title}입니다."
            Level.STRICT -> "완성도를 막고 있는 지점은 ${wa(first.title)} ${second.title}입니다."
        })
        t.add(fixShort(first, a))
        if (limit > 2) t.add(fixShort(second, a))
        if (limit >= 5) t.add(when (tone) {
            Tone.MENTOR -> "한 번에 모두 바꾸지 않아도 됩니다. 다음 장에서 한 가지만 시도해 보시면 차이가 보입니다."
            Tone.CRITIC -> "이 두 지점을 고치지 않으면 다른 요소를 손봐도 인상은 크게 달라지지 않습니다."
            Tone.ANALYST -> "두 지점은 근거 신뢰도가 비교적 높은 항목이라 개선 효과를 확인하기 쉽습니다."
        })
        return t.build()
    }

    /** 촬영 단계에서 할 수 있는 일. 항목별 비평의 조언과 다른 문장을 쓴다. */
    private fun shootAdvice(axis: Axis, a: Assessment, tone: Tone): String {
        val body = when (axis) {
            Axis.COMPOSITION -> "한 걸음 옮겨 여백의 비율을 바꿔 보고 가장 편한 지점에서 누르는 편이"
            Axis.LIGHTING -> "같은 장면을 빛이 다른 쪽에서 한 번 더 담아 보고 밝은 면이 살아 있는 쪽을 고르는 편이"
            Axis.COLOR -> "광원이 바뀌면 색도 함께 바뀌므로, 빛이 다른 순간을 곁들여 담아 두는 편이"
            Axis.SHARPNESS -> "호흡을 멈추고 반셔터로 초점을 고정한 뒤 찍는 편이"
            Axis.EMOTION -> "장면을 한 번 더 돌러보고 마음이 먼저 반응한 요소를 프레임에 남기는 편이"
            Axis.TIMING -> if (a.subjects.any { it.label.lowercase() in HUMAN }) "제스처가 정점에 이르기 직전부터 연속으로 담는 편이" else "장면이 멈추는 순간을 기다렸다 누르는 편이"
        }
        return when (tone) {
            Tone.MENTOR -> "$body 좋겠습니다."
            Tone.CRITIC -> "$body 낫습니다."
            Tone.ANALYST -> "$body 화면에 드러난 요소와 맞습니다."
        }
    }

    private fun shooting(a: Assessment, tone: Tone, depth: Depth, weak: List<Int>): String {
        val t = Sentences(depth.sentences)
        val first = Axis.entries[weak[0]]
        val second = Axis.entries[weak[1]]
        t.add(when (tone) {
            Tone.MENTOR -> "다음 장에서는 이렇게 잡아 보시면 좋겠습니다."
            Tone.CRITIC -> "촬영 단계에서 지켜야 할 것은 이 두 가지입니다."
            Tone.ANALYST -> "촬영 제안은 근거가 약한 두 항목을 보완하는 순서로 적었습니다."
        })
        t.add(shootAdvice(first, a, tone))
        if (depth != Depth.QUICK) t.add(shootAdvice(second, a, tone))
        if (depth == Depth.EXPERT) t.add("같은 장면에서 위치와 노출만 바꿔 몇 장을 겹쳐 찍어 보면, 어떤 선택이 이 사진에 맞는지 스스로 확인할 수 있습니다.")
        return t.build()
    }

    private fun editing(a: Assessment, tone: Tone, depth: Depth, weak: List<Int>): String {
        val m = a.measure
        val t = Sentences(depth.sentences)
        val steps = mutableListOf<String>()
        if (m.highlights > .02) steps += "가장 밝은 부분을 조금 낮춰 흰색으로 눌린 결을 되살리기"
        if (m.shadows > .03) steps += "어두운 부분을 살짝 들어 올리되 검정은 남겨 두기"
        if (m.contrast < 20.0) steps += "검정 지점을 조금 내려 계조의 폭 넓히기"
        if (m.channelCast > 8.0) steps += if (m.warmth > 0) "화이트밸런스를 조금 차갑게 옮겨 피사체의 색 찾기" else "화이트밸런스를 조금 따뜻하게 옮겨 장면의 온기 살리기"
        if (m.saturation > .42) steps += "색을 한 단계 낮춰 주조색이 도드라지게 만들기"
        if (m.sharpness < 60.0) steps += "약한 샤프닝과 노이즈 정리로 윤곽 세우기"
        if (band(a.evidence[weak[0]].score) <= 1) when (Axis.entries[weak[0]]) {
            Axis.COMPOSITION -> steps += "크롭으로 피사체를 힘 있는 자리로 옮기기"
            Axis.SHARPNESS -> if (m.sharpness >= 60.0) steps += "부분 대비를 살짝 올려 윤곽 보강하기"
            else -> {}
        }
        t.add(when (tone) {
            Tone.MENTOR -> "보정은 원본이 담고 있는 정보 안에서 마무리하시면 좋겠습니다."
            Tone.CRITIC -> "보정으로 없는 디테일을 만들 수는 없습니다. 원본에 남은 정보 안에서만 손대십시오."
            Tone.ANALYST -> "보정은 화면의 밝기와 색 값을 바꾸는 작업이므로, 바꾼 뒤의 균형을 다시 확인하십시오."
        })
        if (steps.isEmpty()) {
            t.add("지금은 손대지 않는 편이 좋습니다. 원본의 톤과 색이 이미 균형을 이루고 있습니다.")
        } else {
            t.add(steps.take(if (depth == Depth.QUICK) 2 else if (depth == Depth.STANDARD) 3 else 5).joinToString(", ") + " 순서를 권합니다.")
        }
        if (depth == Depth.EXPERT) t.add("보정은 화면 전체의 균형을 보며 조금씩 움직이고, 단계마다 원본과 비교해 보십시오.")
        return t.build()
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
