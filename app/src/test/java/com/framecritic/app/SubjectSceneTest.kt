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

    /** 하늘도 물도 초록도 아닌 닫힌 공간. 실내로 읽히게 만드는 띠 근거다. */
    private fun indoorCues() = SceneCues(
        BandStats(70.0, 66.0, 62.0, 68.0, 18.0, 0.0, 0.0, 0.0),
        BandStats(74.0, 70.0, 66.0, 71.0, 22.0, 0.0, 0.0, 0.0),
        BandStats(60.0, 56.0, 52.0, 57.0, 20.0, 0.0, 0.0, 0.0),
        false
    )

    private fun outdoorCues() = SceneCues(
        BandStats(120.0, 150.0, 110.0, 133.0, 26.0, .1, 0.0, 0.0),
        BandStats(60.0, 120.0, 60.0, 96.0, 38.0, .55, 0.0, 0.0),
        BandStats(70.0, 110.0, 55.0, 90.0, 34.0, .5, 0.0, 0.0),
        false
    )

    @Test fun personWinsOverLargeLivestockBoxIndoors() {
        val boxes = listOf(SubjectBox("person", .72, .46, .5, .18, .3), SubjectBox("cow", .44, .5, .5, .8, .9))
        val s = SubjectReading.read(boxes, indoorCues(), .3, 110.0, 0, .2, "MODEL")
        assertEquals(SubjectCategory.PERSON, s.category)
        assertEquals(1, s.count)
    }

    @Test fun twoPeopleAreCountedTogether() {
        val boxes = listOf(
            SubjectBox("person", .88, .3, .55, .3, .6),
            SubjectBox("person", .62, .66, .55, .28, .58),
            SubjectBox("cow", .46, .5, .5, .6, .5)
        )
        val s = SubjectReading.read(boxes, indoorCues(), .3, 110.0, 0, .2, "MODEL")
        assertEquals(SubjectCategory.PERSON, s.category)
        assertEquals(2, s.count)
        assertEquals("인물 2명", SubjectReading.subjectWithCount(s))
        assertTrue(s.evidence.contains("인물 2명"))
    }

    @Test fun threePeopleReadAsSeveral() {
        val boxes = listOf(
            SubjectBox("person", .9, .25, .55, .24, .6),
            SubjectBox("person", .66, .5, .55, .24, .6),
            SubjectBox("person", .58, .75, .55, .24, .6)
        )
        val s = SubjectReading.read(boxes, indoorCues(), .3, 110.0, 0, .2, "MODEL")
        assertEquals(3, s.count)
        assertEquals("인물 여러 명", SubjectReading.subjectWithCount(s))
    }

    @Test fun indoorCowAtLowConfidenceIsNotNamed() {
        val boxes = listOf(SubjectBox("cow", .62, .5, .55, .6, .5))
        val s = SubjectReading.read(boxes, indoorCues(), .3, 110.0, 0, .2, "MODEL")
        assertNotEquals(SubjectCategory.ANIMAL, s.category)
    }

    @Test fun outdoorCowIsNamed() {
        val boxes = listOf(SubjectBox("cow", .66, .5, .55, .6, .5))
        val s = SubjectReading.read(boxes, outdoorCues(), .3, 120.0, 0, .2, "MODEL")
        assertEquals(SubjectCategory.ANIMAL, s.category)
        assertEquals("소", s.subject)
    }

    @Test fun dogPhotoKeepsDogOverWeakPerson() {
        val boxes = listOf(SubjectBox("dog", .66, .5, .55, .5, .5), SubjectBox("person", .32, .8, .4, .15, .3))
        val s = SubjectReading.read(boxes, indoorCues(), .3, 110.0, 0, .2, "MODEL")
        assertEquals("반려견", s.subject)
    }

    @Test fun quickSummaryIsThreeShortLinesWithoutNumbers() {
        val a = CriticEngine.safeAssess(nima, measure(), emptyList(), scene(SubjectCategory.PERSON, "인물", 2, .86))
        val score = Scoring.evaluate(6.1, a.evidence, Persona.BALANCED, Level.BALANCED)
        val quick = CriticEngine.quickSummary(a, score)
        for (body in listOf(quick.headline, quick.strength, quick.advice)) {
            assertTrue("빈 요약", body.isNotBlank())
            assertFalse("요약에 숫자 → $body", body.any { it.isDigit() })
            banned.forEach { word -> assertFalse("요약에 '$word' → $body", body.contains(word)) }
            assertTrue("요약이 너무 길다 → ${body.length}", body.length <= 320)
        }
        assertTrue(quick.headline.endsWith("."))
        assertTrue(quick.headline, quick.headline.contains("두 사람"))
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
