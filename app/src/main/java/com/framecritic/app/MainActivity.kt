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
import androidx.compose.ui.platform.LocalContext

private val personas = listOf("Bresson", "Adams", "Leiter", "Fan Ho")
private val levels = listOf("Generous", "Balanced", "Strict")
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
    var tone by remember { mutableStateOf(tones[1]) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { imageUri = it }
    val bitmap = remember(imageUri) {
        imageUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101114)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("FrameCritic", style = MaterialTheme.typography.headlineLarge)
        Text("Photographic critique · on-device roadmap", color = Color.LightGray)
        Button(onClick = { picker.launch("image/*") }) { Text("사진 선택") }
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "선택한 사진", modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit)
        }
        Text("Photographer Persona", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(personas, persona) { persona = it }
        Text("Critic Level", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(levels, level) { level = it }
        Text("Critic Tone", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(tones, tone) { tone = it }
        HorizontalDivider()
        Text("Analysis report", style = MaterialTheme.typography.titleLarge)
        Text(
            if (bitmap == null) "사진을 선택하면 이곳에 분석 결과가 표시됩니다."
            else "사진이 로드되었습니다. NIMA 및 VLM 추론 엔진은 아직 통합되지 않았습니다. 점수와 크리틱을 생성하지 않습니다.",
            color = Color.LightGray
        )
        Text("선택 기준: $persona · $level · $tone", color = Color(0xFFB0BEC5))
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
