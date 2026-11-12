package com.framecritic.app

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.hypot

data class Analysis(val nima:NimaResult,val metrics:Metrics,val subjects:List<Subject>,val assessment:Assessment,val warnings:List<String>,val elapsedMs:Long)
data class Report(val persona:Persona,val level:Level,val tone:Tone,val depth:Depth,val score:ScoreResult?,val sections:Map<String,String>,val failure:String?) {
    fun text(analysis:Analysis):String=buildString {
        appendLine("FrameCritic · ${persona.title} · ${level.title} · ${tone.title} · ${depth.title}")
        appendLine("사진가의 미학에서 영감을 받은 분석이며 본인의 평가가 아닙니다.")
        appendLine("측정 기반 오프라인 크리틱 · NIMA + OpenCV + MediaPipe")
        appendLine("NIMA 미적 원점수: %.2f / 10 · 기술 원점수: %.2f / 10".format(analysis.nima.aesthetic,analysis.nima.technical))
        score?.let{appendLine("Persona %.2f / 10 · Level 적용 전 %.2f".format(it.adjusted,it.base))}
        for((k,v)in sections){appendLine();appendLine(k);appendLine(v)}
        failure?.let{appendLine("생성 오류: $it")}
        analysis.warnings.forEach{appendLine("주의: $it")}
        appendLine("이 리포트는 기기 안의 측정값으로 계산했으며 객관적 예술 평가로 검증된 척도가 아닙니다.")
    }
}
object Pipeline {
    /** 1~3단계 인앱 추론, 4단계는 외부 프로세스 없이 측정값 결합과 문장 생성만 수행한다. */
    suspend fun analyze(context:Context,folder:File,bitmap:Bitmap,onProgress:(String)->Unit):Analysis {
        val start=System.currentTimeMillis();val warnings=mutableListOf<String>()
        onProgress("1/4 · NIMA 확률분포 추론")
        val nima=Nima.analyze(folder,bitmap)
        onProgress("2/4 · OpenCV 측정")
        val metrics=Vision.metrics(bitmap)
        onProgress("3/4 · MediaPipe 피사체 탐지")
        val subjects=try{Vision.subjects(context,folder,bitmap)}
        catch(e:CancellationException){throw e}
        catch(e:Exception){warnings+="피사체 탐지 실패: ${e.message}";emptyList()}
        onProgress("4/4 · 미학 지표 및 관찰 근거 종합")
        val measure=Measure(
            width=bitmap.width,height=bitmap.height,
            luminance=metrics.luminance,highlights=metrics.highlights,shadows=metrics.shadows,
            contrast=metrics.contrast,localContrast=metrics.localContrast,sharpness=metrics.sharpness,
            rgb=metrics.rgb,hues=metrics.hues,histogram=metrics.histogram,
            lines=metrics.lines.map{LineCandidate(it.angle,hypot((it.x2-it.x1).toDouble(),(it.y2-it.y1).toDouble()))},
            saturation=metrics.saturation,edges=metrics.edges
        )
        val boxes=subjects.map{
            SubjectBox(it.label,it.confidence.toDouble(),it.box.centerX().toDouble(),it.box.centerY().toDouble(),it.box.width().toDouble(),it.box.height().toDouble())
        }
        val assessment=CriticEngine.assess(nima,measure,boxes)
        return Analysis(nima,metrics,subjects,assessment,warnings,System.currentTimeMillis()-start)
    }
    fun report(a:Analysis,p:Persona,l:Level,t:Tone,d:Depth):Report {
        val score=runCatching{Scoring.evaluate(a.nima.aesthetic,a.assessment.evidence,p,l)}.getOrNull()
        return try {
            val sections=CriticEngine.sections(a.assessment,score,p,l,t,d)
            check(sections.size==11) { "리포트 항목이 완성되지 않았습니다." }
            check(sections.values.all{value->value.isNotBlank()&&value.count{it in '가'..'힣'}>=3}) { "한국어 리포트 품질 확인에 실패했습니다." }
            Report(p,l,t,d,score,sections,null)
        }catch(e:CancellationException){throw e}
        catch(e:Exception){Report(p,l,t,d,score,emptyMap(),e.message?:"비평 생성 실패")}
    }
}
