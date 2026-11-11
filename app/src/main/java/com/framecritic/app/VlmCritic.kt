package com.framecritic.app

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real multimodal inference via Gemini HTTPS API.
 * This is CLOUD inference, not on-device. The photo is uploaded only after the user requests it.
 * The API key is never persisted in app storage or embedded in the APK.
 */
object VlmCritic {
    suspend fun critique(
        bitmap: Bitmap,
        apiKey: String,
        persona: String,
        severity: Severity,
        tone: String,
        depth: ReportDepth,
        metrics: ImageMetrics,
        nima: NimaEngine.Scores?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Gemini API 키를 입력하세요." }
            val resized = run {
                val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            }
            val bytes = ByteArrayOutputStream().use { stream ->
                check(resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)) { "JPEG 변환 실패" }
                stream.toByteArray()
            }
            val prompt = buildString {
                appendLine("당신은 전문 사진 비평가입니다. 실제 첨부 사진을 시각적으로 분석하고 한국어로 답하세요.")
                appendLine("사진에 없는 피사체나 이야기를 지어내지 마세요. 불확실하면 불확실하다고 하세요.")
                appendLine("사진가 관점: $persona (실제 인물의 발언이나 인격을 사칭하지 말고 미학적 평가 초점만 참고)")
                appendLine("평가 강도: ${severity.label}; Mild는 장점을 먼저, Harsh는 구체적인 약점을 엄격하게, Balanced는 균형 있게.")
                appendLine("문체: $tone; 분량: ${depth.label}.")
                appendLine("사진의 구도, 주제, 시선 흐름, 빛, 색, 분위기, 기술적 문제를 구분해 평가하세요.")
                appendLine("기술 측정값: 평균 휘도 %.3f, 암부 클리핑 %.3f, 하이라이트 클리핑 %.3f, 채도 %.3f, 경계 대비 %.4f.".format(metrics.brightness, metrics.darkClip, metrics.brightClip, metrics.saturation, metrics.edgeContrast))
                if (nima != null) appendLine("NIMA 원점수(절대 수정 금지): 미학 %.2f/10, 기술 %.2f/10.".format(nima.aesthetic, nima.technical))
                appendLine("구성: 한 줄 총평, 장점, 약점, 구체적 촬영/보정 제안. NIMA 점수가 없다면 추정하거나 창작하지 마세요.")
            }
            val parts = JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("temperature", 0.45).put("maxOutputTokens", if (depth == ReportDepth.EXPERT) 1500 else 900))
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 90000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey.trim())
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                check(status in 200..299) { "Gemini HTTP $status: ${response.take(400)}" }
                val candidates = JSONObject(response).optJSONArray("candidates")
                val content = candidates?.optJSONObject(0)?.optJSONObject("content")
                val answerParts = content?.optJSONArray("parts")
                val answer = buildString {
                    if (answerParts != null) for (i in 0 until answerParts.length()) {
                        val text = answerParts.optJSONObject(i)?.optString("text").orEmpty()
                        if (text.isNotBlank()) appendLine(text)
                    }
                }.trim()
                check(answer.isNotBlank()) { "VLM이 텍스트 응답을 반환하지 않았습니다." }
                answer
            } finally {
                conn.disconnect()
            }
        }
    }
}
