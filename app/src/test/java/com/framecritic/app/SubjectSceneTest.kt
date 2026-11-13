package com.framecritic.app

import org.junit.Assert.*
import org.junit.Test

/**
 * 피사체·장면 식별이 비평 본문에 실제로 반영되는지 확인한다.
 *
 *  1) 갈래마다 화면에 쓸 이름(인물·반려견·자동차·바다·산·그릇)이 여러 항목의 본문에 나온다.
 *  2) 그렇게 만든 본문도 2.1.0의 계약(수치·계측 어휘 없음, 길이 상한)을 지킨다.
 *  3) 장면 근거가 없거나 탐지가 비어도 중립 폴백으로 끝난다.
 */
class SubjectSceneTest {
    private fun histogram(mean: Double): List<Int> {
        val h = IntArray(256)
        for (i in h.indices) h[i] = (200.0 * Math.exp(-((i - mean) * (i - mean)) / (2 * 45.0 * 45.0))).toInt() + 1
        return h.toList()
    }
    private fun measure(lum: Double = 118.0, contrast: Double = 44.0, sharp: Double = 180.0) =
        Measure(1280, 853, lum, .004, .006, contrast, contrast * .35, sharp,
            listOf(120.0, 120.0, 120.0), IntArray(12) { if (it == 1) 700 else 60 }.toList(), histogram(lum),
            listOf(LineCandidate(1.2, .6), LineCandidate(.8, .4)), .22, .03)
    private val nima = NimaResult(6.1, 5.7, 1.9, List(10) { .1f })
    private val banned = listOf("관찰되었", "관측", "Laplacian", "COCO", "휘도", "255", "검출", "측정", "NIMA", "히스토그램", "클리핑", "분산")

    private fun scene(
        category: SubjectCategory,
        subject: String,
        count: Int = 0,
        confidence: Double = 0.0,
        scene: SceneContext = SceneContext.INDOOR,
        label: String = "실내",
        source: String = "MODEL"
    ) = SubjectScene(category, subject, count, confidence, scene, label, .3, source, "테스트 근거")

    private val scenes = listOf(
        scene(SubjectCategory.PERSON, "인물", 1, .93),
        scene(SubjectCategory.ANIMAL, "반려견", 2, .81, SceneContext.NATURE, "들판"),
        scene(SubjectCategory.VEHICLE, "자동차", 1, .77, SceneContext.OUTDOOR, "야외"),
        scene(SubjectCategory.LANDSCAPE_WATER, "바다", 0, 0.0, SceneContext.WATER, "바다", "SCENE"),
        scene(SubjectCategory.LANDSCAPE_NATURE, "산", 0, 0.0, SceneContext.NATURE, "산", "SCENE"),
        scene(SubjectCategory.OBJECT_STILL, "그릇", 1, .55)
    )

    @Test fun everyCategoryNamesItsSubjectAcrossSections() {
        val m = measure()
        for (s in scenes) {
            val a = CriticEngine.safeAssess(nima, m, emptyList(), s)
            val out = CriticEngine.sections(a, Scoring.evaluate(6.1, a.evidence, Persona.BALANCED, Level.BALANCED),
                Persona.BALANCED, Level.BALANCED, Tone.MENTOR, Depth.STANDARD)
            val hit = out.values.count { it.contains(s.subject) }
            assertEquals(out.size, CriticEngine.SECTION_TITLES.size)
            assertTrue("${s.subject}: ${hit}개 항목에만 나옵니다", hit >= 6)
        }
    }

    @Test fun subjectProseKeepsTheNoNumbersContract() {
        val m = measure()
        for (s in scenes) for (depth in Depth.entries) {
            val a = CriticEngine.safeAssess(nima, m, emptyList(), s)
            val out = CriticEngine.sections(a, Scoring.evaluate(6.1, a.evidence, Persona.BALANCED, Level.BALANCED),
                Persona.BALANCED, Level.BALANCED, Tone.MENTOR, depth)
            for ((title, body) in out) {
                assertTrue("$title 비어 있음", body.isNotBlank())
                assertFalse("$title: 수치 → $body", body.any { it.isDigit() })
                banned.forEach { word -> assertFalse("$title: '$word' → $body", body.contains(word)) }
                assertTrue("$title 길이 ${body.length}", body.length < 1500)
            }
        }
    }

    @Test fun readingPrefersDetectedSubjectOverScene() {
        val boxes = listOf(SubjectBox("dog", .81, .35, .55, .28, .4), SubjectBox("dog", .74, .62, .52, .3, .38))
        val s = SubjectReading.read(boxes, null, .22, 118.0, 2, .18, "MODEL")
        assertEquals(SubjectCategory.ANIMAL, s.category)
        assertEquals("반려견", s.subject)
        assertEquals(2, s.count)
        assertTrue(s.evidence.contains("신뢰도"))
    }

    @Test fun landscapeSceneWinsOverMisfiredObject() {
        val boxes = listOf(SubjectBox("bed", .61, .5, .64, .5, .4))
        val cues = SceneCues(
            BandStats(242.0, 184.0, 128.0, 195.0, 28.0, 0.0, 0.0, .47),
            BandStats(120.0, 100.0, 90.0, 110.0, 41.0, .05, .1, .0),
            BandStats(79.0, 69.0, 67.0, 70.0, 35.0, .05, 0.0, .0),
            false
        )
        val s = SubjectReading.read(boxes, cues, .31, 127.0, 0, 1.0, "MODEL")
        assertEquals(SubjectCategory.LANDSCAPE_WATER, s.category)
        assertEquals(0, s.count)
    }

    @Test fun emptyDetectionFallsBackToSceneWithoutClaimingASubject() {
        val s = SubjectReading.read(emptyList(), null, .2, 100.0, 0, 0.0, "SCENE")
        assertEquals(SubjectCategory.GENERAL, s.category)
        assertEquals(0, s.count)
        assertTrue(s.evidence.contains("픽셀 근거"))
    }

    @Test fun assessmentWithoutSceneStaysNeutral() {
        val a = CriticEngine.safeAssess(nima, measure(), emptyList())
        assertNull(a.scene)
        val out = CriticEngine.sections(a, null, Persona.BALANCED, Level.BALANCED, Tone.MENTOR, Depth.QUICK)
        assertEquals(CriticEngine.SECTION_TITLES.size, out.size)
    }
}
