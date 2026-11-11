package com.framecritic.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Real offline vision-language inference using a user-supplied Gemma 3n .task model. */
object VlmCritic {
    suspend fun critique(
        context: Context,
        bitmap: Bitmap,
        modelFile: File,
        persona: String,
        severity: Severity,
        tone: String,
        depth: ReportDepth,
        metrics: ImageMetrics,
        nima: NimaEngine.Scores?
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            require(modelFile.isFile && modelFile.length() > 100_000_000) {
                "Gemma 3n 멀티모달 .task 모델을 먼저 선택하세요."
            }
            val prompt = buildString {
                appendLine("첨부 사진을 직접 보고 한국어로 사진 비평을 작성하세요.")
                appendLine("보이지 않는 물체나 상황을 지어내지 마세요.")
                appendLine("사진가의 미학적 관점: $persona. 실제 인물의 말투나 발언을 사칭하지 마세요.")
                appendLine("평가 강도: ${severity.label}. Mild는 장점을 먼저, Harsh는 결함을 엄격하게, Balanced는 균형 있게.")
                appendLine("말투: $tone. 보고서 깊이: ${depth.label}.")
                appendLine("구도, 시선 흐름, 빛, 색, 장면의 의미, 촬영과 보정 제안을 구분하세요.")
                appendLine("실측 휘도 %.3f, 암부 클리핑 %.3f, 하이라이트 클리핑 %.3f, 채도 %.3f.".format(metrics.brightness, metrics.darkClip, metrics.brightClip, metrics.saturation))
                if (nima != null) appendLine("NIMA 원점수: 미학 %.2f/10, 기술 %.2f/10. 변경하거나 재추정하지 마세요.".format(nima.aesthetic, nima.technical))
                appendLine("한 줄 총평 / 장점 / 약점 / 구체적 개선안을 작성하세요.")
            }
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .setMaxNumImages(1)
                .build()
            LlmInference.createFromOptions(context, options).use { inference ->
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(20)
                    .setTemperature(0.35f)
                    .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                    .build()
                LlmInferenceSession.createFromOptions(inference, sessionOptions).use { session ->
                    session.addQueryChunk(prompt)
                    session.addImage(BitmapImageBuilder(bitmap).build())
                    session.generateResponse().also { require(it.isNotBlank()) { "VLM 응답이 비어 있습니다." } }
                }
            }
        }
    }
}
