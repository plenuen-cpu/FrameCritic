package com.framecritic.app

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val Ink=Color(0xFFECECE7)
private val Accent=Color(0xFFC4DAA8)
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{
    MaterialTheme(colorScheme=darkColorScheme(primary=Accent,background=Color(0xFF101110),surface=Color(0xFF1C1E1C),onSurface=Ink,onBackground=Ink)){
        Surface(Modifier.fillMaxSize()){CriticScreen()}
    }
}}}
@Composable fun <T> Choice(title:String,items:List<T>,selected:T,label:(T)->String,enabled:Boolean,onSelect:(T)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(title,color=Color.Gray,fontSize=12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items.forEach{item->FilterChip(selected=item==selected,onClick={onSelect(item)},enabled=enabled,label={Text(label(item))})}
    }
}
@Composable fun CriticScreen(vm:CriticViewModel=viewModel()){
    val s by vm.state.collectAsStateWithLifecycle();val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let(vm::load)}
    val scope=rememberCoroutineScope();var sharing by remember{mutableStateOf(false)};var info by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            Column{Text("FRAMECRITIC",fontSize=24.sp,fontWeight=FontWeight.Medium,letterSpacing=2.sp);Text("사진을 보고, 근거를 읽다",fontSize=12.sp,color=Color.Gray)}
            TextButton(onClick={info=true}){Text("모델 정보")}
        }
        if(s.bitmap==null){
            Box(Modifier.fillMaxWidth().height(280.dp).background(Color(0xFF191B19),RoundedCornerShape(8.dp)).clickable(enabled=!s.busy){picker.launch("image/*")},contentAlignment=Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Text("+",fontSize=40.sp,color=Accent);Text("사진 불러오기");Text("모든 분석은 기기 안에서",fontSize=12.sp,color=Color.Gray)}
            }
        }else{
            PhotoAnalysis(s.bitmap!!,s.analysis,s.report)
            TextButton(onClick={picker.launch("image/*")},enabled=!s.busy){Text("다른 사진 선택")}
        }
        Text("PHOTOGRAPHIC PERSPECTIVE",fontSize=11.sp,letterSpacing=1.sp,color=Color.Gray)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Persona.entries.forEach{p->
                val selected=p==s.persona
                Column(Modifier.width(205.dp).border(1.dp,if(selected)Accent else Color(0xFF343634),RoundedCornerShape(8.dp)).clickable(enabled=!s.busy){vm.setOptions(p=p)}.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(p.title,fontSize=15.sp,color=if(selected)Accent else Ink,fontWeight=FontWeight.Medium)
                    Text(p.subtitle,fontSize=12.sp,color=Color.Gray)
                }
            }
        }
        Text("사진가의 미학에서 영감을 받은 관점입니다. 본인의 평가나 발언이 아닙니다.",fontSize=11.sp,color=Color.Gray)
        Choice("평가 엄격도",Level.entries,s.level,{it.title},!s.busy){vm.setOptions(l=it)}
        Choice("표현 방식 · 점수와 독립",Tone.entries,s.tone,{it.title},!s.busy){vm.setOptions(t=it)}
        Choice("리포트 깊이",Depth.entries,s.depth,{it.title},!s.busy){vm.setOptions(d=it)}
        Button(onClick={vm.analyze()},enabled=s.bitmap!=null&&!s.busy,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(8.dp)){
            Text(if(s.analysis!=null)"선택한 관점으로 리포트 생성" else "사진 분석하기")
        }
        if(s.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(s.progress,fontSize=13.sp);TextButton(onClick=vm::cancel){Text("취소")}}
        s.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        s.analysis?.let{a->
            HorizontalDivider(color=Color.DarkGray)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                ScoreTile("NIMA 미적 원점수",a.nima.aesthetic)
                ScoreTile("Persona · ${s.level.title}",s.report?.score?.adjusted)
            }
            Text("기술 원점수 %.2f / 10 · NIMA 분포 표준편차 %.2f".format(a.nima.technical,a.nima.deviation),fontSize=12.sp,color=Color.Gray)
            Text("NIMA 1–10점 확률분포",fontSize=12.sp)
            Row(Modifier.fillMaxWidth().height(62.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.Bottom){
                a.nima.probabilities.forEachIndexed{i,v->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.fillMaxWidth().height((v*110).coerceAtLeast(1f).dp).background(Accent));Text("${i+1}",fontSize=10.sp,color=Color.Gray)}}
            }
            s.report?.let{report->
                report.score?.let{score->
                    Text("점수 산정",fontWeight=FontWeight.Medium)
                    Text("NIMA 40% + 사진가 관점 60%. 사진가 관점은 항목 가중치 × VLM 신뢰도로 정규화합니다.\n엄격도 보정: 1 + 9 × x^γ (x는 0~1 기본점수, γ는 Generous 0.75 / Balanced 1 / Strict 1.5).",fontSize=12.sp,color=Color.Gray)
                    Text("관점 점수 %.2f · 보정 전 %.2f → %.2f\n가중 신뢰도 %.0f%% · 경험적 평가 함수이며 인간 심사위원 점수로 검증된 척도가 아닙니다.".format(score.weighted,score.base,score.adjusted,score.coverage*100),fontSize=12.sp)
                    a.scene?.let{scene->
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                            Level.entries.forEach{l->Text("${l.title} %.2f".format(Scoring.evaluate(a.nima.aesthetic,scene.evidence,report.persona,l).adjusted),fontSize=12.sp)}
                        }
                        Persona.entries.forEach{p->val other=runCatching{Scoring.evaluate(a.nima.aesthetic,scene.evidence,p,report.level)}.getOrNull();Text("${p.title}: ${other?.adjusted?.let{"%.2f".format(it)}?:"신뢰도 부족"}",fontSize=12.sp,color=Color.Gray)}
                        Axis.entries.forEachIndexed{i,axis->
                            Text("${axis.title} · 가중치 ${(report.persona.weights[i]*100).roundToInt()}% · VLM ${"%.2f".format(scene.evidence[i].score)} · 신뢰도 ${(scene.evidence[i].confidence*100).roundToInt()}%",fontSize=11.sp,color=Color.Gray)
                        }
                    }
                }
                report.failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                report.sections.forEach{(heading,body)->Text(heading,fontSize=17.sp,fontWeight=FontWeight.Medium);Text(body,fontSize=14.sp,lineHeight=23.sp)}
                if(report.sections.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={val cm=context.getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("FrameCritic",report.text(a)));Toast.makeText(context,"리포트를 복사했습니다",Toast.LENGTH_SHORT).show()}){Text("텍스트 복사")}
                    OutlinedButton(enabled=!sharing,onClick={sharing=true;scope.launch{try{val file=withContext(Dispatchers.IO){renderShare(context,s.bitmap!!,report.text(a))};val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("FrameCritic",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"리포트 공유"))}catch(e:Exception){Toast.makeText(context,e.message,Toast.LENGTH_LONG).show()}finally{sharing=false}}}){Text(if(sharing)"만드는 중" else "이미지 공유")}
                }
            }
            Text("측정 결과 · OpenCV",fontSize=17.sp,fontWeight=FontWeight.Medium)
            Text("휘도 %.1f/255 · 하이라이트 %.2f%% · 암부 %.2f%%\n전역 대비 %.2f · 국소 대비 %.2f · Laplacian 분산 %.2f\n평균 RGB %s".format(a.metrics.luminance,a.metrics.highlights*100,a.metrics.shadows*100,a.metrics.contrast,a.metrics.localContrast,a.metrics.sharpness,a.metrics.rgb.joinToString{"%.1f".format(it)}),fontSize=13.sp,lineHeight=21.sp)
            Histogram(a.metrics.histogram)
            Text("밝기 극단값은 의도된 표현일 수 있습니다. RGB 평균으로 조명색을 확정할 수 없고, 선명도 수치는 피사체·해상도에 따라 달라집니다.",fontSize=11.sp,color=Color.Gray)
            Text("MediaPipe 탐지 ${a.subjects.size}개 · 사진 분석 ${a.elapsedMs/1000}초",fontSize=12.sp,color=Color.Gray)
            a.subjects.forEach{Text("${it.label} · 신뢰도 ${(it.confidence*100).roundToInt()}% · 프레임 ${(it.box.width()*it.box.height()*100).roundToInt()}%",fontSize=12.sp)}
            a.warnings.forEach{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
            if(a.scene==null)TextButton(onClick={vm.analyze(retryVision=true)},enabled=!s.busy){Text("사진 이해 재시도")}
            a.scene?.let{scene->
                var expanded by remember{mutableStateOf(false)}
                TextButton(onClick={expanded=!expanded}){Text(if(expanded)"VLM 원문 접기" else "VLM 근거 원문 보기")}
                if(expanded){Text(scene.description,fontSize=12.sp);Axis.entries.forEachIndexed{i,axis->Text("${axis.title}\n관찰: ${scene.evidence[i].observation}\n해석: ${scene.evidence[i].interpretation}",fontSize=12.sp)}}
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if(info)AlertDialog(onDismissRequest={info=false},confirmButton={TextButton(onClick={info=false}){Text("닫기")}},title={Text("내장 모델 · 오프라인")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){
        Text("NIMA MobileNet · AVA / TID2013\nSmolVLM2-500M Q8 · 사진 이해\nQwen3-0.6B Q8 · 한국어 작성\nEfficientDet-Lite0 · MediaPipe 탐지\nOpenCV 4.12 · 영상 측정\nllama.cpp · ARM64 CPU\n\n모델 약 1.20GB. 최초 실행 시 APK 내부 파일을 풀어 앱 전용 공간에 보관합니다. 인터넷 다운로드는 없습니다. 사진을 외부로 보내지 않습니다.\n\nSmolVLM2는 영어 중심 소형 모델입니다. 한국어 편집 모델을 거쳐도 오인식·번역·비평 오류가 생길 수 있습니다. 시각적 주목도 모델은 포함하지 않았습니다.\n\n가중치·OpenCV·MediaPipe: Apache 2.0 (NIMA는 배포자 표기 기준). llama.cpp: MIT. 출처와 전문은 아래와 프로젝트 docs에 포함됩니다.",fontSize=13.sp)
        val notices=remember{context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use{it.readText()}}
        Text(notices,fontSize=11.sp)
    }})
}
@Composable fun ScoreTile(label:String,score:Double?){Column{Text(label,fontSize=11.sp,color=Color.Gray);Text(score?.let{"%.2f".format(it)}?:"—",fontSize=35.sp,fontWeight=FontWeight.Light);Text("/ 10",fontSize=10.sp,color=Color.Gray)}}
@Composable fun Histogram(values:List<Int>){Canvas(Modifier.fillMaxWidth().height(70.dp)){val max=values.maxOrNull()?.coerceAtLeast(1)?:1;values.forEachIndexed{i,n->val x=i*size.width/256;drawLine(Color.Gray,Offset(x,size.height),Offset(x,size.height-size.height*n/max),size.width/256)}}}
@Composable fun PhotoAnalysis(bitmap:Bitmap,analysis:Analysis?,report:Report?){
    var grid by remember{mutableStateOf(false)};var subjects by remember{mutableStateOf(true)};var lines by remember{mutableStateOf(false)};var clips by remember{mutableStateOf(false)};var detail by remember(bitmap){mutableStateOf<String?>(null)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height).pointerInput(bitmap,analysis,report,clips,subjects,lines){detectTapGestures{pos->
            val x=(pos.x/size.width).coerceIn(0f,1f);val y=(pos.y/size.height).coerceIn(0f,1f)
            val subject=if(subjects)analysis?.subjects?.firstOrNull{it.box.contains(x,y)}else null
            val clip=if(clips)analysis?.metrics?.clipMap?.getPixel((x*(bitmap.width-1)).toInt(),(y*(bitmap.height-1)).toInt())else null
            detail=when{subject!=null->"${subject.label} · 신뢰도 ${(subject.confidence*100).roundToInt()}%\n중심 (${(subject.box.centerX()*100).roundToInt()}%, ${(subject.box.centerY()*100).roundToInt()}%) · 프레임 ${(subject.box.width()*subject.box.height()*100).roundToInt()}%\n${report?.sections?.get("구도").orEmpty()}\n구도 비평은 사진 전체에 대한 해석입니다."
                clip!=null&&clip!=0->"선택 영역: ${if((clip and 0x00FF0000)!=0)"하이라이트" else "암부"} 극단값\n${report?.sections?.get("노출").orEmpty()}\n클리핑 여부만으로 결함을 확정하지 않습니다."
                else->"${report?.sections?.get("구도")?:"선택 영역에 연결된 탐지 근거가 없습니다."}\n직선 표시는 수평선 후보이며 실제 지평선으로 확정하지 않습니다."}
        }}){
            Image(bitmap.asImageBitmap(),contentDescription="분석 대상 사진",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
            if(clips&&analysis!=null)Image(analysis.metrics.clipMap.asImageBitmap(),contentDescription="빨강: 하이라이트, 파랑: 암부",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
            Canvas(Modifier.matchParentSize()){
                if(grid)for(i in 1..2){drawLine(Color.White.copy(alpha=.5f),Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height));drawLine(Color.White.copy(alpha=.5f),Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3))}
                if(subjects)analysis?.subjects?.forEach{s->drawRect(Accent,Offset(s.box.left*size.width,s.box.top*size.height),Size(s.box.width()*size.width,s.box.height()*size.height),style=Stroke(2.dp.toPx()))}
                if(lines)analysis?.metrics?.lines?.forEach{drawLine(Color.Yellow,Offset(it.x1*size.width,it.y1*size.height),Offset(it.x2*size.width,it.y2*size.height),2.dp.toPx())}
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(grid,{grid=!grid},label={Text("삼분할")});FilterChip(subjects,{subjects=!subjects},label={Text("피사체")});FilterChip(lines,{lines=!lines},label={Text("수평선 후보")});FilterChip(clips,{clips=!clips},label={Text("클리핑")})
        }
        if(analysis!=null)Text("표시된 영역을 터치하면 측정값과 관련 비평을 볼 수 있습니다.",fontSize=11.sp,color=Color.Gray)
        detail?.let{Text(it,fontSize=12.sp,color=Accent)}
    }
}
private fun renderShare(context:Context,photo:Bitmap,text:String):File {
    val width=900;val margin=40;val photoHeight=(820.0*photo.height/photo.width).toInt()
    val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(235,235,230);textSize=26f}
    val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,width-margin*2).setLineSpacing(10f,1f).build()
    val image=Bitmap.createBitmap(width,photoHeight+layout.height+margin*3,Bitmap.Config.ARGB_8888)
    try {
        val c=android.graphics.Canvas(image);c.drawColor(android.graphics.Color.rgb(16,17,16));c.drawBitmap(photo,null,Rect(margin,margin,width-margin,margin+photoHeight),Paint(Paint.FILTER_BITMAP_FLAG));c.save();c.translate(margin.toFloat(),(photoHeight+margin*2).toFloat());layout.draw(c);c.restore()
        val dir=File(context.cacheDir,"reports").apply{mkdirs()};dir.listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>24*60*60*1000}?.forEach{it.delete()}
        return File(dir,"FrameCritic-${System.currentTimeMillis()}.png").also{file->file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
    }finally{image.recycle()}
}
