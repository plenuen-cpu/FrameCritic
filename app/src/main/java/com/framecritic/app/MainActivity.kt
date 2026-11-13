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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Ink=Color(0xFFECECE7)
private val Accent=Color(0xFFC4DAA8)
private val CardBg=Color(0xFF1A1C1A)
private val Hairline=Color(0xFF2A2C2A)

/** 로컬 빌드에서 쓸 기본 빌드 번호. CI 빌드는 릴리스 태그와 같은 런 번호를 쓴다. */
const val BUILD_NUMBER=66

/** 화면과 오류 기록에 쓰는 버전 표기. 릴리스 태그(offline-v2-N)의 N과 같은 번호를 보여준다. */
fun appVersion(context:Context):String {
    val info=runCatching{context.packageManager.getPackageInfo(context.packageName,0)}.getOrNull()
    val build=if(BuildConfig.CI_RUN_NUMBER>0) BuildConfig.CI_RUN_NUMBER else BUILD_NUMBER
    return "v${info?.versionName?:"2.2.0"} (Build $build)"
}

/** 전역 비정상 종료 로거. 스택 트레이스를 파일에 남기고 다음 실행에서 보여준 뒤 지운다. */
object CrashLog {
    private const val NAME="crash.txt"
    private const val LIMIT=200_000
    private fun file(context:Context)=File(context.filesDir,NAME)
    fun install(context:Context){
        val app=context.applicationContext
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{thread,error->
            runCatching{file(app).writeText(describe(app,thread,error))}
            if(previous!=null)previous.uncaughtException(thread,error)
            else{android.os.Process.killProcess(android.os.Process.myPid());kotlin.system.exitProcess(10)}
        }
    }
    fun read(context:Context):String?=runCatching{
        val stored=file(context)
        if(!stored.exists()||stored.length()==0L)null else stored.readText().take(LIMIT).trim().ifBlank{null}
    }.getOrNull()
    fun clear(context:Context){runCatching{file(context).delete()}}
    private fun describe(context:Context,thread:Thread,error:Throwable):String {
        val text=buildString{
            appendLine("FrameCritic 비정상 종료 기록")
            appendLine("시각 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date())}")
            appendLine("스레드 ${thread.name}")
            appendLine("기기 Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            val info=runCatching{context.packageManager.getPackageInfo(context.packageName,0)}.getOrNull()
            appendLine("앱 ${info?.versionName.orEmpty()} (${info?.longVersionCode?:0L}) · ${if(BuildConfig.CI_RUN_NUMBER>0)"Build ${BuildConfig.CI_RUN_NUMBER}" else "로컬 빌드"}")
            appendLine("메모리 한도 ${Runtime.getRuntime().maxMemory()/1024/1024}MB")
            appendLine()
            val trace=StringWriter()
            PrintWriter(trace).use{error.printStackTrace(it)}
            append(trace.toString())
        }
        return text.take(LIMIT)
    }
}
class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        val previousCrash=CrashLog.read(this)
        val interruptedStage=StageLog.read(this)
        val version=appVersion(this)
        setContent{
            var crash by remember{mutableStateOf(previousCrash)}
            var stage by remember{mutableStateOf(interruptedStage)}
            MaterialTheme(colorScheme=darkColorScheme(primary=Accent,background=Color(0xFF101110),surface=Color(0xFF1C1E1C),onSurface=Ink,onBackground=Ink)){
                Surface(Modifier.fillMaxSize()){CriticScreen(version)}
            }
            crash?.let{record->CrashDialog("이전 실행에서 앱이 종료되었습니다",record,"아래 기록을 복사해 보내주시면 종료 원인을 바로 확인할 수 있습니다."){CrashLog.clear(this);crash=null}}
            stage?.let{record->CrashDialog("직전 분석이 도중에 끊겼습니다",record,"예외 기록 없이 프로세스가 종료되었습니다(네이티브 오류 또는 시스템 메모리 정리). 어느 단계였는지 아래에 남아 있습니다."){StageLog.clear(this);stage=null}}
        }
    }
}
@Composable fun CrashDialog(title:String,record:String,hint:String,onClose:()->Unit){
    val context=LocalContext.current
    AlertDialog(onDismissRequest=onClose,title={Text(title)},
        text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){
            Text(hint,fontSize=12.sp,color=Color.Gray)
            Text(record,fontSize=11.sp)
        }},
        confirmButton={
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                TextButton(onClick={
                    runCatching{
                        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("FrameCritic",record))
                    }
                    Toast.makeText(context,"기록을 클립보드에 복사했습니다",Toast.LENGTH_SHORT).show()
                }){Text("클립보드 복사")}
                TextButton(onClick=onClose){Text("닫기")}
            }
        })
}

/**
 * 원터치 화면.
 * 사진을 고르고 버튼을 한 번 누르면, 점수와 자연스러운 비평 카드가 바로 이어진다.
 * 관점·엄격도·톤·깊이 같은 선택지는 화면에서 없애고, 기본값(균형 잡힌 시선 · 멘토 톤)으로 돈다.
 * 수치는 맨 아래 '상세 측정 데이터 및 기술적 근거'를 펼쳤을 때만 보인다.
 */
@Composable fun CriticScreen(version:String="",vm:CriticViewModel=viewModel()){
    val s by vm.state.collectAsStateWithLifecycle();val context=LocalContext.current
    val view=LocalView.current
    DisposableEffect(s.busy){view.keepScreenOn=s.busy;onDispose{view.keepScreenOn=false}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let(vm::load)}
    val scope=rememberCoroutineScope();var sharing by remember{mutableStateOf(false)};var info by remember{mutableStateOf(false)}
    /** 결과를 세 줄 요약으로만 볼지, 열 항목 전체를 볼지. 기본은 짧은 쪽이다. */
    var brief by remember{mutableStateOf(true)}
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            Column{Text("FRAMECRITIC",fontSize=24.sp,fontWeight=FontWeight.Medium,letterSpacing=2.sp);Text("$version · 사진을 보고, 근거를 읽다",fontSize=12.sp,color=Color.Gray)}
            TextButton(onClick={info=true}){Text("모델 정보")}
        }
        if(s.bitmap==null){
            Box(Modifier.fillMaxWidth().height(300.dp).background(CardBg,RoundedCornerShape(14.dp)).clickable(enabled=!s.busy){picker.launch("image/*")},contentAlignment=Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text("+",fontSize=44.sp,color=Accent)
                    Text("사진 불러오기",fontSize=16.sp)
                    Text("빛과 색, 구도를 한 장에서 읽어 냅니다",fontSize=12.sp,color=Color.Gray)
                    Text("모든 분석은 기기 안에서",fontSize=11.sp,color=Color.Gray)
                }
            }
        }else{
            PhotoAnalysis(s.bitmap!!,s.analysis,s.report)
            TextButton(onClick={picker.launch("image/*")},enabled=!s.busy,contentPadding=PaddingValues(0.dp)){Text("다른 사진 선택",fontSize=13.sp,color=Color.Gray)}
        }
        Button(onClick={vm.analyze()},enabled=s.bitmap!=null&&!s.busy,modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(12.dp)){
            Text(if(s.analysis==null)"사진 분석하기" else "다시 분석하기",fontSize=16.sp,fontWeight=FontWeight.Medium)
        }
        if(s.busy){
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(s.progress,fontSize=12.sp,color=Color.Gray)
            TextButton(onClick=vm::cancel,contentPadding=PaddingValues(0.dp)){Text("취소",fontSize=13.sp)}
        }
        s.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
        s.analysis?.let{analysis->
            HorizontalDivider(color=Hairline)
            ScoreHero(analysis,s.report?.score)
            s.report?.let{report->
                val quick=remember(report){runCatching{CriticEngine.quickSummary(analysis.assessment,report.score)}.getOrNull()}
                quick?.let{QuickSummaryCard(it)}
                report.failure?.let{Text(it,fontSize=12.sp,color=Color.Gray)}
                if(report.sections.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    FilterChip(brief,{brief=true},label={Text("핵심 요약",fontSize=12.sp)})
                    FilterChip(!brief,{brief=false},label={Text("상세 비평",fontSize=12.sp)})
                }
                if(!brief)report.sections.forEach{(title,body)->CritiqueCard(title,body)}
                else if(report.sections.isNotEmpty())Text("열 항목의 자세한 비평은 '상세 비평'에서 볼 수 있습니다.",fontSize=12.sp,color=Color.Gray)
                if(report.sections.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={
                        val cm=context.getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager
                        val copy=runCatching{report.text(analysis)}.getOrElse{"리포트 텍스트를 만들지 못했습니다."}
                        cm.setPrimaryClip(ClipData.newPlainText("FrameCritic",copy))
                        Toast.makeText(context,"리포트를 복사했습니다",Toast.LENGTH_SHORT).show()
                    }){Text("텍스트 복사",fontSize=13.sp)}
                    OutlinedButton(enabled=!sharing,onClick={
                        sharing=true
                        scope.launch{
                            try{
                                val file=withContext(Dispatchers.IO){renderShare(context,s.bitmap!!,runCatching{report.text(analysis)}.getOrElse{"리포트 텍스트를 만들지 못했습니다."})}
                                val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("FrameCritic",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"리포트 공유"))
                            }catch(e:OutOfMemoryError){Toast.makeText(context,"공유 이미지를 만들 메모리가 부족합니다.",Toast.LENGTH_LONG).show()}
                            catch(e:Throwable){Toast.makeText(context,e.message,Toast.LENGTH_LONG).show()}
                            finally{sharing=false}
                        }
                    }){Text(if(sharing)"만드는 중" else "이미지 공유",fontSize=13.sp)}
                }
            }
            MeasurementSection(analysis,s.report?.score)
        }
        Spacer(Modifier.height(16.dp))
    }
    if(info)AlertDialog(onDismissRequest={info=false},confirmButton={TextButton(onClick={info=false}){Text("닫기")}},title={Text("내장 모델 · 오프라인")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){
        Text("앱 $version · 측정 기반 오프라인 크리틱",fontSize=13.sp,color=MaterialTheme.colorScheme.primary)
        Text("NIMA MobileNet · AVA / TID2013 미적·기술 분포\nEfficientDet-Lite0 · MediaPipe 피사체 탐지\nOpenCV 4.12 · 영상 측정\nFrameCritic 측정 크리틱 엔진 · 기기 내 결정론적 문장 생성\n\n내장 모델 약 17MB. 최초 실행 시 TFLite 모델 3개만 앱 전용 공간으로 복사합니다. 외부 추론 프로세스나 인터넷 다운로드가 없고, 사진을 외부로 보내지 않습니다.\n\n비평 문장은 측정값에서 규칙으로 생성합니다. 같은 사진이면 같은 결과가 나오며, 측정되지 않은 피사체·의도·감정을 사실처럼 추가하지 않습니다. 원시 수치는 맨 아래 '상세 측정 데이터 및 기술적 근거'에만 남깁니다.\n\n가중치·OpenCV·MediaPipe: Apache 2.0 (NIMA는 배포자 표기 기준). 출처와 전문은 아래와 프로젝트 docs에 포함됩니다.",fontSize=13.sp)
        val notices=remember{context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use{it.readText()}}
        Text(notices,fontSize=11.sp)
    }})
}

/** 점수 카드. 큰 숫자와 한 줄 인상만 두고, 산식은 아래 측정 근거로 내린다. */
@Composable fun ScoreHero(analysis:Analysis,score:ScoreResult?){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(verticalAlignment=Alignment.Bottom){
            Text(score?.adjusted?.takeIf{it.isFinite()}?.let{"%.1f".format(it)}?:"—",fontSize=62.sp,fontWeight=FontWeight.Light)
            Text("/ 10",fontSize=14.sp,color=Color.Gray,modifier=Modifier.padding(bottom=12.dp,start=4.dp))
        }
        Text(score?.let{CriticEngine.verdict(analysis.assessment,it.adjusted)}?:"",fontSize=16.sp)
        Text("사진 한 장을 10점 만점으로 본 경험적 점수입니다.",fontSize=11.sp,color=Color.Gray)
        analysis.scene?.let{scene->
            val detected=scene.source=="MODEL"&&scene.count>0
            Text(
                if(detected) "식별된 피사체 · ${SubjectReading.subjectWithCount(scene)} (신뢰도 ${roundSafe(scene.confidence*100.0)}%) · 장면 ${scene.sceneLabel}"
                else "식별된 장면 · ${scene.sceneLabel} (픽셀 근거)",
                fontSize=12.sp,color=Accent,modifier=Modifier.padding(top=4.dp)
            )
        }
    }
}

/** 한눈에 보는 핵심 요약. 긴 열 항목을 읽지 않아도 되게 세 줄로 줄인다. */
@Composable fun QuickSummaryCard(summary:CriticEngine.QuickSummary){
    Column(Modifier.fillMaxWidth().background(CardBg,RoundedCornerShape(14.dp)).border(1.dp,Accent.copy(alpha=.35f),RoundedCornerShape(14.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("한눈에 보는 핵심 요약",fontSize=12.sp,color=Accent,letterSpacing=1.sp)
        SummaryLine("📌 한 줄 총평",summary.headline)
        SummaryLine("✨ 가장 돋보이는 점",summary.strength)
        SummaryLine("💡 원포인트 조언",summary.advice)
    }
}
@Composable private fun SummaryLine(label:String,body:String){
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
        Text(label,fontSize=11.sp,color=Color.Gray)
        Text(body,fontSize=15.sp,lineHeight=24.sp)
    }
}

/** 비평 한 항목. 제목과 본문만 있는 카드. */
@Composable fun CritiqueCard(title:String,body:String){
    Column(Modifier.fillMaxWidth().background(CardBg,RoundedCornerShape(14.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(title,fontSize=12.sp,color=Accent,letterSpacing=1.sp)
        Text(body,fontSize=15.sp,lineHeight=25.sp)
    }
}

/** 접었을 때는 한 줄, 펼치면 모든 수치와 근거 문장을 보여준다. */
@Composable fun MeasurementSection(analysis:Analysis,score:ScoreResult?){
    var open by remember(analysis){mutableStateOf(false)}
    HorizontalDivider(color=Hairline)
    Row(Modifier.fillMaxWidth().clickable{open=!open}.padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
        Text("상세 측정 데이터 및 기술적 근거",fontSize=13.sp,color=Color.Gray)
        Text(if(open)"접기" else "펼치기",fontSize=13.sp,color=Accent)
    }
    if(open){
        CriticEngine.measurements(analysis.assessment,score).forEach{group->
            Text(group.title,fontSize=12.sp,color=Accent,modifier=Modifier.padding(top=12.dp,bottom=2.dp))
            group.rows.forEach{(label,value)->
                Row(Modifier.fillMaxWidth().padding(vertical=3.dp)){
                    Text(label,fontSize=12.sp,color=Color.Gray,modifier=Modifier.weight(1f))
                    Text(value,fontSize=12.sp)
                }
            }
        }
        Text("NIMA 1–10점 확률분포",fontSize=12.sp,color=Accent,modifier=Modifier.padding(top=12.dp,bottom=4.dp))
        val heights=remember(analysis.nima.probabilities){nimaBarHeights(analysis.nima.probabilities)}
        if(heights.size==10){
            Row(Modifier.fillMaxWidth().height(62.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.Bottom){
                heights.forEachIndexed{i,h->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.fillMaxWidth().height(h.dp).background(Accent));Text("${i+1}",fontSize=10.sp,color=Color.Gray)}}
            }
        }else Text("확률분포를 그리지 못했습니다. 사진을 다시 선택해 주세요.",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        Text("휘도 히스토그램",fontSize=12.sp,color=Accent,modifier=Modifier.padding(top=12.dp))
        Histogram(analysis.metrics.histogram)
        Text("항목별 기술 관찰",fontSize=12.sp,color=Accent,modifier=Modifier.padding(top=12.dp))
        CriticEngine.evidenceLines(analysis.assessment).forEach{(title,body)->
            Text(title,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
            Text(body,fontSize=12.sp,color=Color.Gray,lineHeight=19.sp)
        }
        Text("MediaPipe 탐지 ${analysis.subjects.size}개 · 분석 ${if(analysis.elapsedMs<1000) "${analysis.elapsedMs}ms" else "%.1f초".format(analysis.elapsedMs/1000.0)}",fontSize=12.sp,color=Color.Gray,modifier=Modifier.padding(top=12.dp))
        analysis.scene?.let{scene->Text("피사체 근거: ${scene.evidence}",fontSize=12.sp,color=Color.Gray)}
        analysis.subjects.forEach{Text("${it.label} · 신뢰도 ${roundSafe(it.confidence*100.0)}% · 프레임 ${roundSafe(it.box.width()*it.box.height()*100.0)}%",fontSize=12.sp,color=Color.Gray)}
        analysis.warnings.forEach{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        Text("밝기 극단값은 의도된 표현일 수 있습니다. RGB 평균으로 조명색을 확정할 수 없고, 선명도 수치는 피사체·해상도에 따라 달라집니다.",fontSize=11.sp,color=Color.Gray,modifier=Modifier.padding(top=8.dp))
    }
}

/** Compose 레이아웃에 NaN·무한값이 닿지 않도록 막대 높이를 1~44dp로 정리한다. */
private fun nimaBarHeights(probabilities:List<Float>):List<Float> {
    if(probabilities.size!=10) return emptyList()
    val values=probabilities.map{ if(it.isFinite()&&it>0f) it else 0f }
    val maxP=values.maxOrNull()?.takeIf{it>0f}?:1f
    return values.map{ if(it>0f) (it/maxP*44f).coerceIn(1f,44f) else 1f }
}
private fun roundSafe(v:Double):Int=if(v.isFinite())v.roundToInt() else 0
@Composable fun Histogram(values:List<Int>){
    val bars=remember(values){values.map{it.coerceAtLeast(0)}.take(256)}
    if(bars.isNotEmpty())Canvas(Modifier.fillMaxWidth().height(70.dp)){
        runCatching{
            if(!size.width.isFinite()||!size.height.isFinite()||size.width<=0f||size.height<=0f) return@runCatching
            val max=bars.maxOrNull()?.coerceAtLeast(1)?:1
            val step=size.width/256f
            val strokeW=step.coerceAtLeast(1f)
            bars.forEachIndexed{i,n->
                val x=i*step
                if(!x.isFinite()) return@runCatching
                drawLine(Color.Gray,Offset(x,size.height),Offset(x,size.height-size.height*n/max),strokeW)
            }
        }
    }
}
@Composable fun PhotoAnalysis(bitmap:Bitmap,analysis:Analysis?,report:Report?){
    var grid by remember{mutableStateOf(false)};var subjects by remember{mutableStateOf(true)};var lines by remember{mutableStateOf(false)};var clips by remember{mutableStateOf(false)};var detail by remember(bitmap){mutableStateOf<String?>(null)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        val ratio=(if(bitmap.height>0)bitmap.width.toFloat()/bitmap.height else 1f).let{if(it.isFinite()&&it>0f)it.coerceIn(0.01f,100f) else 1f}
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).pointerInput(bitmap,analysis,report,clips,subjects,lines){detectTapGestures{pos->
            val x=(if(size.width>0f)pos.x/size.width else 0f).let{if(it.isFinite())it.coerceIn(0f,1f) else 0f}
            val y=(if(size.height>0f)pos.y/size.height else 0f).let{if(it.isFinite())it.coerceIn(0f,1f) else 0f}
            val subject=if(subjects)analysis?.subjects?.firstOrNull{it.box.contains(x,y)}else null
            val clip=if(clips)analysis?.metrics?.clipMap?.takeIf{!it.isRecycled&&it.width>0&&it.height>0&&!bitmap.isRecycled&&bitmap.width>0&&bitmap.height>0}?.let{map->
                val px=(x*(bitmap.width-1)).toInt().coerceIn(0,map.width-1)
                val py=(y*(bitmap.height-1)).toInt().coerceIn(0,map.height-1)
                runCatching{map.getPixel(px,py)}.getOrNull()
            }else null
            detail=when{subject!=null->"${subject.label} · 신뢰도 ${roundSafe(subject.confidence*100.0)}%\n중심 (${roundSafe(subject.box.centerX()*100.0)}%, ${roundSafe(subject.box.centerY()*100.0)}%) · 프레임 ${roundSafe(subject.box.width()*subject.box.height()*100.0)}%\n${report?.sections?.get("구도").orEmpty()}\n구도 비평은 사진 전체에 대한 해석입니다."
                clip!=null&&clip!=0->"선택 영역: ${if((clip and 0x00FF0000)!=0)"하이라이트" else "암부"} 극단값\n${report?.sections?.get("빛과 노출").orEmpty()}\n클리핑 여부만으로 결함을 확정하지 않습니다."
                else->"${report?.sections?.get("구도")?:"선택 영역에 연결된 탐지 근거가 없습니다."}\n직선 표시는 수평선 후보이며 실제 지평선으로 확정하지 않습니다."
            }
        }}){
            SafeBitmapImage(bitmap,"분석 대상 사진",Modifier.fillMaxSize())
            if(clips&&analysis!=null)analysis.metrics.clipMap?.let{SafeBitmapImage(it,"빨강: 하이라이트, 파랑: 암부",Modifier.fillMaxSize())}
            Canvas(Modifier.matchParentSize()){
                runCatching{
                    if(!size.width.isFinite()||!size.height.isFinite()||size.width<=0f||size.height<=0f) return@runCatching
                    val strokeW=2.dp.toPx().coerceAtLeast(0.5f)
                    if(grid)for(i in 1..2){drawLine(Color.White.copy(alpha=.5f),Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height),1f);drawLine(Color.White.copy(alpha=.5f),Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3),1f)}
                    if(subjects)analysis?.subjects?.forEach{s->
                        val left=s.box.left;val top=s.box.top;val right=s.box.right;val bottom=s.box.bottom
                        if(left.isFinite()&&top.isFinite()&&right.isFinite()&&bottom.isFinite()&&right>left&&bottom>top)
                            drawRect(Accent,Offset(left*size.width,top*size.height),Size((right-left)*size.width,(bottom-top)*size.height),style=Stroke(strokeW))
                    }
                    if(lines)analysis?.metrics?.lines?.forEach{line->
                        if(line.x1.isFinite()&&line.y1.isFinite()&&line.x2.isFinite()&&line.y2.isFinite())
                            drawLine(Color.Yellow,Offset(line.x1*size.width,line.y1*size.height),Offset(line.x2*size.width,line.y2*size.height),strokeW)
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(grid,{grid=!grid},label={Text("삼분할",fontSize=12.sp)});FilterChip(subjects,{subjects=!subjects},label={Text("피사체",fontSize=12.sp)});FilterChip(lines,{lines=!lines},label={Text("수평선 후보",fontSize=12.sp)});FilterChip(clips,{clips=!clips},label={Text("클리핑",fontSize=12.sp)})
        }
        if(analysis!=null)Text("표시된 영역을 터치하면 그 자리의 측정값과 관련 비평을 볼 수 있습니다.",fontSize=11.sp,color=Color.Gray)
        detail?.let{Text(it,fontSize=12.sp,color=Accent)}
    }
}
/**
 * recycle된 비트맵을 Compose Canvas에 넘기면 "Canvas: trying to use a recycled bitmap" 예외로
 * 앱이 종료된다. 그리기 직전에 상태를 다시 확인하는 페인터로 감싸, 해제된 이미지는 그 프레임을 건너뛴다.
 */
private class GuardedBitmapPainter(private val bitmap:Bitmap):Painter() {
    private val image:ImageBitmap?=runCatching{ if(bitmap.isRecycled) null else bitmap.asImageBitmap() }.getOrNull()
    override val intrinsicSize:Size=image?.takeIf{it.width>0&&it.height>0}?.let{Size(it.width.toFloat(),it.height.toFloat())}?:Size.Zero
    override fun DrawScope.onDraw() {
        val drawn=image?:return
        if(bitmap.isRecycled) return
        val width=size.width.roundToInt();val height=size.height.roundToInt()
        if(width<=0||height<=0) return
        runCatching{drawImage(drawn,dstOffset=IntOffset.Zero,dstSize=IntSize(width,height),filterQuality=FilterQuality.Low)}
    }
}
@Composable private fun SafeBitmapImage(bitmap:Bitmap,description:String,modifier:Modifier){
    val painter=remember(bitmap){GuardedBitmapPainter(bitmap)}
    Image(painter=painter,contentDescription=description,modifier=modifier,contentScale=ContentScale.FillBounds)
}
private fun renderShare(context:Context,photo:Bitmap,text:String):File {
    check(!photo.isRecycled) { "사진이 해제되어 공유 이미지를 만들 수 없습니다. 사진을 다시 불러와 주세요." }
    val width=900;val margin=40;val photoHeight=(820.0*photo.height/photo.width).toInt().coerceIn(1,2000)
    val drawnWidth=(photoHeight.toDouble()*photo.width/photo.height).toInt().coerceIn(1,820)
    val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(235,235,230);textSize=26f}
    val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,width-margin*2).setLineSpacing(10f,1f).build()
    val image=Bitmap.createBitmap(width,photoHeight+layout.height+margin*3,Bitmap.Config.ARGB_8888)
    try {
        val c=android.graphics.Canvas(image);c.drawColor(android.graphics.Color.rgb(16,17,16));c.drawBitmap(photo,null,Rect((width-drawnWidth)/2,margin,(width+drawnWidth)/2,margin+photoHeight),Paint(Paint.FILTER_BITMAP_FLAG));c.save();c.translate(margin.toFloat(),(photoHeight+margin*2).toFloat());layout.draw(c);c.restore()
        val dir=File(context.cacheDir,"reports").apply{mkdirs()};dir.listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>24*60*60*1000}?.forEach{it.delete()}
        return File(dir,"FrameCritic-${System.currentTimeMillis()}.png").also{file->file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
    }finally{image.recycle()}
}
