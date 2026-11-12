package com.framecritic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale
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
    /** 분석에 쓰는 최대 변 길이. 이보다 큰 사진은 줄여서 OpenCV·TFLite 메모리 폭증을 막는다. */
    private const val MAX_ANALYSIS_SIDE=1280

    /** 화면과 계산이 NaN·무한값을 받지 않도록 측정 결과를 유한값으로 정리한다. */
    private fun finite(v:Double,fallback:Double=0.0)=if(v.isFinite())v else fallback
    private fun sanitize(n:NimaResult):NimaResult {
        val raw=n.probabilities.map{ if(it.isFinite()&&it>0f) it else 0f }
        val sum=raw.sum()
        val probabilities=if(raw.size==10&&sum>0f) raw.map{it/sum} else List(10){.1f}
        return NimaResult(finite(n.aesthetic,5.5).coerceIn(1.0,10.0),finite(n.technical,5.0).coerceIn(1.0,10.0),finite(n.deviation).coerceIn(0.0,4.5),probabilities)
    }
    private fun sanitize(m:Metrics)=m.copy(
        histogram=if(m.histogram.size==256)m.histogram.map{it.coerceAtLeast(0)} else List(256){0},
        highlights=finite(m.highlights),shadows=finite(m.shadows),luminance=finite(m.luminance),
        contrast=finite(m.contrast),localContrast=finite(m.localContrast),sharpness=finite(m.sharpness),
        rgb=if(m.rgb.size==3)m.rgb.map{finite(it)} else List(3){0.0},
        hues=if(m.hues.size==12)m.hues.map{it.coerceAtLeast(0)} else List(12){0},
        saturation=finite(m.saturation),edges=finite(m.edges)
    )
    private fun sanitize(s:Subject):Subject? {
        val left=s.box.left;val top=s.box.top;val right=s.box.right;val bottom=s.box.bottom
        if(!(s.confidence.isFinite()&&left.isFinite()&&top.isFinite()&&right.isFinite()&&bottom.isFinite())) return null
        val l=left.coerceIn(0f,1f);val t=top.coerceIn(0f,1f);val r=right.coerceIn(0f,1f);val b=bottom.coerceIn(0f,1f)
        if(r<=l||b<=t) return null
        return Subject(s.label,s.confidence.coerceIn(0f,1f),RectF(l,t,r,b))
    }
    private fun reason(e:Throwable):String=(e.message?:e::class.java.simpleName).take(160)

    /** 분석 전체가 어떤 예외에도 결과를 돌려주도록 단계별 기본값으로 감싼다. */
    suspend fun analyze(context:Context,folder:File,bitmap:Bitmap,onProgress:(String)->Unit):Analysis {
        val start=System.currentTimeMillis()
        val work=runCatching{downscaled(bitmap)}.getOrDefault(bitmap)
        // 분석이 끝나도 work(원본 또는 축소본)는 recycle()하지 않는다. work가 화면이 그리는 원본과
        // 같은 인스턴스이면 수동 해제가 곧 다음 프레임의 Canvas RuntimeException이 된다. 해제는 GC에 맡긴다.
        try {
            return measureStages(context,folder,work,onProgress,start)
        } catch(e:CancellationException) {
            throw e
        } catch(e:Throwable) {
            // 단계별 방어를 모두 지나온 뒤에도 실패하면, 중립 측정값으로 완성된 결과를 돌려준다.
            return neutralAnalysis(work,warnings=listOf("분석 준비 중 오류(${reason(e)})가 있어 중립 측정값으로 결과를 만들었습니다."),start=start)
        }
    }

    private suspend fun measureStages(context:Context,folder:File,bitmap:Bitmap,onProgress:(String)->Unit,start:Long):Analysis {
        val warnings=mutableListOf<String>()
        onProgress("1/4 · NIMA 확률분포 추론")
        val nima=try {
            val result=Nima.analyze(folder,bitmap)
            if(result==null) {
                warnings+="NIMA 모델을 실행하지 못해 미적 5.5 · 기술 5.0의 중립 점수로 대체했습니다."
                Neutral.nima()
            } else sanitize(result)
        } catch(e:CancellationException) {
            throw e
        } catch(e:Throwable) {
            warnings+="NIMA 단계 실패(${reason(e)}) · 미적 5.5 · 기술 5.0의 중립 점수로 대체했습니다."
            Neutral.nima()
        }

        onProgress("2/4 · OpenCV 측정")
        val metrics=try {
            sanitize(Vision.metrics(bitmap))
        } catch(e:CancellationException) {
            throw e
        } catch(e:Throwable) {
            warnings+="OpenCV 측정 실패(${reason(e)}) · 기본 측정값(휘도 128, 대비 0)으로 대체했습니다."
            Neutral.metrics(bitmap.width,bitmap.height,runCatching{Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)}.getOrNull())
        }

        onProgress("3/4 · MediaPipe 피사체 탐지")
        val subjects=try {
            Vision.subjects(context,folder,bitmap).mapNotNull{subject->sanitize(subject)}
        } catch(e:CancellationException) {
            throw e
        } catch(e:OutOfMemoryError) {
            warnings+="피사체 탐지 중 메모리가 부족해 피사체 근거 없이 진행합니다."
            emptyList()
        } catch(e:Throwable) {
            warnings+="피사체 탐지 실패(${reason(e)}) · 피사체 근거 없이 진행합니다."
            emptyList()
        }

        onProgress("4/4 · 미학 지표 및 관찰 근거 종합")
        val measure=Measure(
            width=bitmap.width,height=bitmap.height,
            luminance=metrics.luminance,highlights=metrics.highlights,shadows=metrics.shadows,
            contrast=metrics.contrast,localContrast=metrics.localContrast,sharpness=metrics.sharpness,
            rgb=metrics.rgb,hues=metrics.hues,histogram=metrics.histogram,
            lines=metrics.lines.mapNotNull{line->
                if(line.x1.isFinite()&&line.y1.isFinite()&&line.x2.isFinite()&&line.y2.isFinite()&&line.angle.isFinite())
                    LineCandidate(line.angle,hypot((line.x2-line.x1).toDouble(),(line.y2-line.y1).toDouble()))
                else null
            },
            saturation=metrics.saturation,edges=metrics.edges
        )
        val boxes=subjects.map{
            SubjectBox(it.label,it.confidence.toDouble(),it.box.centerX().toDouble(),it.box.centerY().toDouble(),it.box.width().toDouble(),it.box.height().toDouble())
        }
        val assessment=try {
            CriticEngine.safeAssess(nima,measure,boxes)
        } catch(e:CancellationException) {
            throw e
        } catch(e:Throwable) {
            warnings+="관찰 근거 종합 실패(${reason(e)}) · 항목별 중립 근거로 대체했습니다."
            CriticEngine.safeAssess(Neutral.nima(),measure,emptyList())
        }
        return Analysis(nima,metrics,subjects,assessment,warnings,System.currentTimeMillis()-start)
    }

    /** 화면에 보여줄 수 있는 최소한의 결과. 어떤 경로에서도 앱이 빈 상태로 남지 않게 한다. */
    private fun neutralAnalysis(bitmap:Bitmap,warnings:List<String>,start:Long):Analysis {
        val width=runCatching{bitmap.width}.getOrDefault(0)
        val height=runCatching{bitmap.height}.getOrDefault(0)
        val nima=Neutral.nima()
        val metrics=Neutral.metrics(width,height,null)
        val measure=Measure(
            width=width,height=height,luminance=metrics.luminance,highlights=metrics.highlights,shadows=metrics.shadows,
            contrast=metrics.contrast,localContrast=metrics.localContrast,sharpness=metrics.sharpness,
            rgb=metrics.rgb,hues=metrics.hues,histogram=metrics.histogram,lines=emptyList(),
            saturation=metrics.saturation,edges=metrics.edges
        )
        return Analysis(nima,metrics,emptyList(),CriticEngine.safeAssess(nima,measure,emptyList()),warnings,System.currentTimeMillis()-start)
    }

    private fun downscaled(source:Bitmap):Bitmap {
        val width=source.width;val height=source.height
        if(width<=0||height<=0) return source
        val longest=maxOf(width,height)
        if(longest<=MAX_ANALYSIS_SIDE) return source
        val ratio=MAX_ANALYSIS_SIDE.toDouble()/longest
        val scaled=Bitmap.createScaledBitmap(source,(width*ratio).toInt().coerceAtLeast(1),(height*ratio).toInt().coerceAtLeast(1),true)
        return if(scaled===source) source else scaled
    }

    /** 숫자를 문장에 넣을 때 NaN·무한값이 새어 나가지 않게 한다. */
    private fun num(v:Double)=if(v.isFinite()) String.format(Locale.US,"%.1f",v) else "—"
    private fun pct(v:Double)=if(v.isFinite()) String.format(Locale.US,"%.1f%%",v*100) else "—"

    /** 어떤 상황에도 11개 항목이 비어 있지 않은 리포트를 만든다. */
    fun report(a:Analysis,p:Persona,l:Level,t:Tone,d:Depth):Report {
        val score=runCatching{Scoring.evaluate(a.nima.aesthetic,a.assessment.evidence,p,l)}.getOrNull()
        val generated=try {
            CriticEngine.sections(a.assessment,score,p,l,t,d)
        } catch(e:CancellationException) {
            throw e
        } catch(e:Throwable) {
            emptyMap()
        }
        val (sections,failure)=completeSections(generated,a,p,l,t,d)
        return Report(p,l,t,d,score,sections,failure)
    }

    /**
     * 엔진이 만든 항목을 11개 고정 목록과 대조해, 빠졌거나 품질 기준을 넘지 못한 항목만
     * 측정값 기반 문장으로 채운다. 반환값의 두 번째 원소는 보정이 일어났을 때의 안내 문구다.
     */
    fun completeSections(generated:Map<String,String>,a:Analysis,p:Persona,l:Level,t:Tone,d:Depth):Pair<Map<String,String>,String?> {
        val out=LinkedHashMap<String,String>()
        var repaired=false
        for(title in CriticEngine.SECTION_TITLES) {
            val text=generated[title]?.takeIf{it.isNotBlank()&&it.count{c->c in '가'..'힣'}>=3}
            if(text!=null) out[title]=text
            else { out[title]=neutralSection(title,a,p,l,t,d);repaired=true }
        }
        if(generated.keys.any{it !in CriticEngine.SECTION_TITLES}) repaired=true
        val failure=if(repaired) "일부 항목을 측정값만으로 간단히 채웠습니다. 관점이나 깊이를 바꾸어 다시 시도하면 온전한 문장을 볼 수 있습니다." else null
        return out to failure
    }

    private fun neutralSection(title:String,a:Analysis,p:Persona,l:Level,t:Tone,d:Depth):String {
        val m=a.metrics
        val tail="이번 실행에서는 이 항목의 문장을 끝까지 만들지 못해 측정된 수치만 남깁니다."
        return when(title) {
            "종합 크리틱"->"NIMA 미적 원점수 ${num(a.nima.aesthetic)}점, 기술 원점수 ${num(a.nima.technical)}점, 평균 휘도 ${num(m.luminance)}/255, 전역 대비 ${num(m.contrast)}로 측정되었습니다. ${p.title} 관점, 엄격도 ${l.title}, ${t.title} 어조, ${d.title} 깊이를 요청하셨습니다. $tail"
            "관찰 사실"->"휘도 평균 ${num(m.luminance)}/255, 하이라이트 ${pct(m.highlights)}, 암부 ${pct(m.shadows)}, Laplacian 분산 ${num(m.sharpness)}, 평균 RGB ${m.rgb.joinToString("/"){num(it)}}입니다. 검출된 피사체는 ${a.subjects.size}개, 수평선 후보는 ${m.lines.size}개입니다."
            "미적 해석"->"측정된 휘도와 대비, 색채 값을 겹쳐 보면 이 사진의 계조와 색의 관계를 읽을 수 있습니다. 다만 해석 문장을 완성하지 못했으므로 수치를 넘어선 판단은 보류합니다."
            "주요 강점"->"값으로 확인된 부분만 적으면 평균 휘도 ${num(m.luminance)}, 대비 ${num(m.contrast)}, Laplacian 분산 ${num(m.sharpness)}입니다. 이 수치가 높을수록 계조와 디테일이 분명하게 잡혔다는 뜻입니다."
            "개선이 필요한 부분"->"하이라이트 ${pct(m.highlights)}와 암부 ${pct(m.shadows)}, 전역 대비 ${num(m.contrast)}가 참고할 만한 값입니다. 수치만으로 촬영 의도나 결함을 확정하지는 않습니다. $tail"
            "구도"->"검출된 피사체 ${a.subjects.size}개와 수평선 후보 ${m.lines.size}개를 근거로 삼았습니다. 피사체가 검출되지 않았다면 빈 프레임이라는 뜻은 아닙니다."
            "노출"->"휘도 평균 ${num(m.luminance)}/255, 하이라이트 ${pct(m.highlights)}, 암부 ${pct(m.shadows)}로 측정되었습니다. 노출 판단은 이 수치를 기준으로 직접 확인하십시오."
            "색채"->"평균 RGB ${m.rgb.joinToString("/"){num(it)}}, 평균 채도 ${pct(m.saturation)}로 측정되었습니다. 채널 평균만으로 조명색을 확정하지는 않습니다."
            "선명도"->"Laplacian 분산 ${num(m.sharpness)}, 에지 밀도 ${pct(m.edges)}로 측정되었습니다. 피사체와 해상도에 따라 같은 값의 의미가 달라집니다."
            "촬영 제안"->"다음 촬영에서는 수평선과 피사체 위치를 의식해 프레임을 잡아 보십시오. 노출은 하이라이트 ${pct(m.highlights)}와 암부 ${pct(m.shadows)}를 참고해 조정할 수 있습니다."
            "보정 제안"->"보정은 측정값을 바꾸는 작업입니다. 하이라이트와 암부 수치를 확인한 뒤 최소한으로 조정하고, 바꾼 뒤의 휘도와 대비를 다시 확인하십시오."
            else->tail
        }
    }
}
