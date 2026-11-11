package com.framecritic.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** llama.cpp local multimodal server. Never contacts an external inference endpoint. */
object VlmCritic {
    suspend fun critique(
        context: Context, bitmap: Bitmap, modelFile: File, mmprojFile: File,
        persona: String, severity: Severity, tone: String, depth: ReportDepth,
        metrics: ImageMetrics, nima: NimaEngine.Scores?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(modelFile.isFile && mmprojFile.isFile) { "GGUF 모델과 mmproj 파일을 모두 선택하세요." }
            val executable = File(context.applicationInfo.nativeLibraryDir, "libsmolvlm-server.so")
            require(executable.isFile) { "로컬 llama.cpp 서버 바이너리가 APK에 없습니다. 빌드를 확인하세요." }
            val port = 18871
            val process = ProcessBuilder(
                executable.absolutePath,
                "-m", modelFile.absolutePath,
                "--mmproj", mmprojFile.absolutePath,
                "--host", "127.0.0.1", "--port", port.toString(),
                "-c", "2048", "-t", "4", "-ngl", "0"
            ).redirectErrorStream(true).start()
            try {
                var ready = false
                repeat(90) {
                    if (!ready) {
                        if (!process.isAlive) error("로컬 VLM 서버가 종료되었습니다. 바이너리/모델 호환성을 확인하세요.")
                        ready = runCatching {
                            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                            conn.connectTimeout = 500
                            conn.readTimeout = 500
                            try { conn.responseCode == 200 } finally { conn.disconnect() }
                        }.getOrDefault(false)
                        if (!ready) delay(1000)
                    }
                }
                check(ready) { "로컬 VLM 서버 준비 시간 초과" }
                val resized = run {
                    val factor = minOf(1.0, 1024.0 / maxOf(bitmap.width, bitmap.height))
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
                }
                val jpeg = ByteArrayOutputStream().use {
                    check(resized.compress(Bitmap.CompressFormat.JPEG, 80, it))
                    it.toByteArray()
                }
                val prompt = buildString {
                    appendLine("Describe the actual image accurately, then critique it in Korean.")
                    appendLine("Never invent subjects or events that are not visible.")
                    appendLine("Photographic perspective: $persona; severity: ${severity.label}; tone: $tone; depth: ${depth.label}.")
                    appendLine("Mild: highlight strengths; Harsh: identify concrete weaknesses. Do not alter NIMA scores.")
                    appendLine("Analyze composition, subject, lighting, colors, mood and practical improvements.")
                    appendLine("Measured luminance %.3f, shadow clipping %.3f, highlight clipping %.3f.".format(metrics.brightness, metrics.darkClip, metrics.brightClip))
                    if (nima != null) appendLine("NIMA raw aesthetic %.2f/10, technical %.2f/10.".format(nima.aesthetic, nima.technical))
                }
                val messages = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                        .put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))))))
                val body = JSONObject().put("model", modelFile.name).put("messages", messages)
                    .put("temperature", 0.3).put("max_tokens", if (depth == ReportDepth.EXPERT) 800 else 450)
                val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 180000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val status = conn.responseCode
                    val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    check(status in 200..299) { "로컬 VLM HTTP $status: ${response.take(350)}" }
                    JSONObject(response).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                        .also { check(it.isNotEmpty()) { "VLM 응답이 비어 있습니다." } }
                } finally { conn.disconnect() }
            } finally {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}
