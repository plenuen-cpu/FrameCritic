package com.framecritic.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

private val personas = listOf("Bresson", "Adams", "Leiter", "Fan Ho")
private val levels = Severity.entries.map { it.label }
private val depths = ReportDepth.entries.map { it.label }
private val tones = listOf("Mentor", "Critic", "Analyst")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { CriticScreen() } }
    }
}

@Composable
private fun CriticScreen() {
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var persona by remember { mutableStateOf(personas[0]) }
    var level by remember { mutableStateOf(levels[1]) }
    var depth by remember { mutableStateOf(depths[1]) }
    var tone by remember { mutableStateOf(tones[1]) }
    var showMask by remember { mutableStateOf(false) }
    var modelFile by remember { mutableStateOf<java.io.File?>(null) }
    var modelImporting by remember { mutableStateOf(false) }
    var vlmResult by remember { mutableStateOf<Result<String>?>(null) }
    var vlmLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { imageUri = it }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            modelImporting = true
            scope.launch {
                try {
                    val target = java.io.File(context.filesDir, "gemma3n_vision.task")
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val temporary = java.io.File(context.filesDir, "gemma3n_vision.tmp")
                            temporary.outputStream().use { input.copyTo(it) }
                            if (target.exists()) target.delete()
                            check(temporary.renameTo(target)) { "모델 저장 실패" }
                        } ?: error("모델 파일을 열 수 없습니다.")
                    }
                    modelFile = target
                } catch (error: Exception) {
                    vlmResult = Result.failure(error)
                } finally {
                    modelImporting = false
                }
            }
        }
    }
    val bitmap = remember(imageUri) {
        imageUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    val metrics = remember(bitmap) { bitmap?.let { ImageAnalyzer.analyze(it) } }
    val nima by produceState<Result<NimaEngine.Scores>?>(initialValue = null, bitmap) {
        value = null
        if (bitmap != null) value = withContext(Dispatchers.Default) { NimaEngine.score(context, bitmap) }
    }
    val sceneLabels by produceState<Result<List<Pair<String, Float>>>?>(initialValue = null, bitmap) {
        value = null
        if (bitmap != null) value = SceneLabels.detect(bitmap)
    }
    val personOverlay by produceState<Result<android.graphics.Bitmap>?>(initialValue = null, bitmap, showMask) {
        value = null
        if (bitmap != null && showMask) value = PersonMask.overlay(bitmap)
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101114)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("FrameCritic", style = MaterialTheme.typography.headlineLarge)
        Text("Photographic critique · on-device roadmap", color = Color.LightGray)
        Button(onClick = { picker.launch("image/*") }) { Text("사진 선택") }
        if (bitmap != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("인물 마스크 보기")
                Switch(checked = showMask, onCheckedChange = { showMask = it })
            }
            val shown = if (showMask) personOverlay?.getOrNull() ?: bitmap else bitmap
            Image(shown.asImageBitmap(), contentDescription = "선택한 사진", modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit)
        }
        if (showMask && personOverlay?.isFailure == true) {
            Text("인물 마스크 생성 실패: " + (personOverlay?.exceptionOrNull()?.message ?: "오류"), color = Color(0xFFFFC107))
        }
        Text("Photographer Persona", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(personas, persona) { persona = it }
        Text("Critic Level", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(levels, level) { level = it }
        Text("Report Depth", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(depths, depth) { depth = it }
        Text("Critic Tone", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(tones, tone) { tone = it }
        HorizontalDivider()
        Text("Analysis report", style = MaterialTheme.typography.titleLarge)
        if (metrics == null) Text("사진을 선택해 주세요.", color = Color.LightGray)
        else {
            Text("평균 휘도: %.1f%% · 평균 채도: %.1f%%".format(metrics.brightness * 100, metrics.saturation * 100))
            Text("암부 클리핑: %.1f%% · 하이라이트 클리핑: %.1f%%".format(metrics.darkClip * 100, metrics.brightClip * 100))
            Text("경계 대비 지표: %.4f".format(metrics.edgeContrast))
            val severity = Severity.entries.first { it.label == level }
            val reportDepth = ReportDepth.entries.first { it.label == depth }
            val report = CriticModes.evaluate(metrics, nima?.getOrNull(), severity, reportDepth, persona)
            Text(report.headline, style = MaterialTheme.typography.titleMedium)
            Text("Critic Score (기술 기준): %.1f / 100".format(report.criterionScore))
            report.findings.forEach { Text("• $it", color = Color.LightGray) }
            report.recommendations.forEach { Text("→ $it", color = Color.LightGray) }
            Text(report.disclosure, color = Color(0xFFFFC107))
            OutlinedButton(onClick = {
                val reportText = buildString {
                    appendLine("FrameCritic")
                    appendLine("Persona: $persona · Severity: $level · Depth: $depth")
                    appendLine(report.headline)
                    appendLine("Critic Score (technical): %.1f / 100".format(report.criterionScore))
                    nima?.getOrNull()?.let { appendLine("NIMA aesthetic: %.2f / 10 · technical: %.2f / 10".format(it.aesthetic, it.technical)) }
                    report.findings.forEach { appendLine("- $it") }
                    report.recommendations.forEach { appendLine("→ $it") }
                    appendLine(report.disclosure)
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, reportText)
                }
                context.startActivity(Intent.createChooser(intent, "비평 리포트 공유"))
            }) { Text("리포트 복사·공유") }
            Text("비평 말투: $tone", color = Color.LightGray)
            when {
                sceneLabels == null -> Text("장면 객체 분류 중...", color = Color.LightGray)
                sceneLabels!!.isSuccess -> Text("감지된 장면 태그: " + sceneLabels!!.getOrThrow().joinToString { it.first + " (" + (it.second * 100).toInt() + "%)" }, color = Color.LightGray)
                else -> Text("장면 태그 분석 실패: " + (sceneLabels!!.exceptionOrNull()?.message ?: "알 수 없는 오류"), color = Color(0xFFFFC107))
            }
            when {
                nima == null -> Text("NIMA 모델 분석 중...", color = Color.LightGray)
                nima!!.isSuccess -> {
                    val scores = nima!!.getOrThrow()
                    Text("NIMA 미학: %.2f / 10 · 기술: %.2f / 10".format(scores.aesthetic, scores.technical))
                }
                else -> Text("NIMA 추론 실패: " + (nima!!.exceptionOrNull()?.message ?: "알 수 없는 오류"), color = Color(0xFFFFC107))
            }
            Text("사진가별 해설은 규칙 기반입니다. VLM은 아직 연결되지 않았습니다.", color = Color.LightGray)
        }
        if (bitmap != null && metrics != null) {
            HorizontalDivider()
            Text("온디바이스 VLM · Gemma 3n", style = MaterialTheme.typography.titleLarge)
            Text("사진과 프롬프트는 외부 서버로 전송하지 않습니다. 호환되는 Gemma 3n 멀티모달 .task 모델을 최초 1회 가져와야 합니다.", color = Color.LightGray)
            OutlinedButton(enabled = !modelImporting && !vlmLoading, onClick = { modelPicker.launch(arrayOf("*/*")) }) {
                Text(if (modelImporting) "모델 가져오는 중..." else "로컬 Gemma 3n .task 모델 선택")
            }
            Text(if (modelFile != null) "모델 준비됨: " + (modelFile!!.length() / 1048576) + " MiB" else "모델 미설치", color = Color.LightGray)
            Button(enabled = !vlmLoading && modelFile != null, onClick = {
                val selectedBitmap = bitmap
                val selectedMetrics = metrics
                val selectedModel = modelFile ?: return@Button
                val selectedSeverity = Severity.entries.first { it.label == level }
                val selectedDepth = ReportDepth.entries.first { it.label == depth }
                val selectedPersona = persona
                val selectedTone = tone
                val selectedNima = nima?.getOrNull()
                vlmLoading = true
                vlmResult = null
                scope.launch {
                    try {
                        vlmResult = VlmCritic.critique(context, selectedBitmap, selectedModel, selectedPersona, selectedSeverity, selectedTone, selectedDepth, selectedMetrics, selectedNima)
                    } finally {
                        vlmLoading = false
                    }
                }
            }) { Text(if (vlmLoading) "기기에서 VLM 추론 중..." else "온디바이스 사진 비평 생성") }
            vlmResult?.fold(
                onSuccess = { Text(it, color = Color.White) },
                onFailure = { Text("VLM 오류: " + (it.message ?: "알 수 없는 오류"), color = Color(0xFFFFC107)) }
            )
        }
        Text("선택 기준: $persona · $level · $depth · $tone", color = Color(0xFFB0BEC5))
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { option ->
                    FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(option) })
                }
            }
        }
    }
}
