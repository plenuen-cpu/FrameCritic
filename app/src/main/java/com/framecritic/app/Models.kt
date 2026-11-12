package com.framecritic.app

import android.content.Context
import android.graphics.Bitmap
import android.os.StatFs
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** 단계별 방어에서 쓰는 중립값. 어떤 경로에서도 이 값으로 결과를 완성한다. */
object Neutral {
    const val AESTHETIC=5.5
    const val TECHNICAL=5.0
    const val DEVIATION=1.0
    fun nima():NimaResult=NimaResult(AESTHETIC,TECHNICAL,DEVIATION,List(10){.1f})
    fun metrics(width:Int,height:Int,clip:Bitmap?):Metrics=Metrics(
        histogram=List(256){0},highlights=0.0,shadows=0.0,luminance=128.0,
        contrast=0.0,localContrast=0.0,sharpness=0.0,rgb=listOf(128.0,128.0,128.0),
        hues=List(12){0},lines=emptyList(),clipMap=clip,saturation=0.0,edges=0.0
    )
}

/**
 * 분석 중 진행 단계를 파일에 남긴다. 앱이 예외 없이 강제 종료되면(네이티브 오류·시스템 메모리 정리)
 * 남은 기록으로 다음 실행에서 어느 단계였는지 확인할 수 있다. 정상 종료 시 파일을 지운다.
 */
object StageLog {
    private const val NAME="stage.txt"
    private fun file(context:Context)=File(context.filesDir,NAME)
    fun write(context:Context,stage:String){
        runCatching{
            val stamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date())
            file(context).writeText("$stamp\n진행 단계: $stage")
        }
    }
    fun read(context:Context):String?=runCatching{
        val f=file(context)
        if(!f.exists()||f.length()==0L)null else f.readText().take(2_000).trim().ifBlank{null}
    }.getOrNull()
    fun clear(context:Context){runCatching{file(context).delete()}}
}

object ModelStore {
    /** Only the small TFLite models are needed now: the .gguf language/vision weights are never loaded. */
    suspend fun prepare(context:Context,onProgress:(String)->Unit):File {
        val folder=File(context.noBackupFilesDir,"models-v2").apply{mkdirs()}
        val manifest=JSONArray(context.assets.open("models/manifest.json").bufferedReader().use{it.readText()})
        val entries=(0 until manifest.length()).map{manifest.getJSONObject(it)}.filterNot{it.getString("name").endsWith(".gguf")}
        var index=0
        for (entry in entries) {
            coroutineContext.ensureActive()
            index++
            val name=entry.getString("name");val size=entry.getLong("size")
            val file=File(folder,name);val sha=entry.getString("sha256")
            onProgress("내장 모델 확인 $index/${entries.size} · $name")
            if(file.exists() && file.length()==size) continue
            check(StatFs(folder.path).availableBytes>size+32*1024*1024) { "내장 모델을 준비할 저장 공간이 부족합니다. 약 20MB의 여유 공간을 확보하세요." }
            val tmp=File(folder,"$name.part")
            try {
                val md=MessageDigest.getInstance("SHA-256")
                val stream=DigestInputStream(context.assets.open("models/$name"),md)
                stream.use{input->tmp.outputStream().use{out->val b=ByteArray(64*1024);while(true){coroutineContext.ensureActive();val n=input.read(b);if(n<0)break;out.write(b,0,n)}}}
                val hash=md.digest().joinToString(""){"%02x".format(it)}
                check(tmp.length()==size && hash==sha) { "모델 무결성 검사 실패: $name" }
                check(tmp.renameTo(file)) { "모델 파일 저장 실패" }
            } finally {tmp.delete()}
        }
        return folder
    }
}
object Nima {
    /** TFLite 출력이 NaN이거나 합이 1에서 벗어나도 10구간 확률분포로 정리한다. */
    private fun normalised(values:FloatArray):FloatArray {
        val finite=FloatArray(10){index->if(values.size==10&&values[index].isFinite()&&values[index]>0f)values[index] else 0f}
        val sum=finite.sum()
        return if(sum>0f) FloatArray(10){finite[it]/sum} else FloatArray(10){.1f}
    }
    private fun infer(folder:File,name:String,buffer:ByteBuffer):FloatArray?=try {
        Interpreter(File(folder,name),Interpreter.Options().setNumThreads(4)).use { interpreter->
            if(interpreter.getOutputTensor(0).numElements()!=10) null
            else {
                buffer.rewind();val output=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder())
                interpreter.run(buffer,output);output.rewind();FloatArray(10){output.float}
            }
        }
    } catch(e:Throwable) {
        null
    }
    /**
     * 두 모델 중 하나만 실행되어도 결과를 만든다. 둘 다 실패하면 null을 돌려주고
     * 호출부(Pipeline)가 중립 점수로 대체한다.
     */
    fun analyze(folder:File,bitmap:Bitmap):NimaResult? {
        // scaled는 recycle()하지 않는다. createScaledBitmap은 크기가 같으면 원본을 그대로 돌려주므로
        // 그 경우 화면이 그리는 비트맵을 해제하게 된다. 해제는 GC에 맡긴다.
        val scaled=runCatching{Bitmap.createScaledBitmap(bitmap,224,224,true)}.getOrNull()?:return null
        try {
            val pixels=IntArray(224*224)
            if(!runCatching{scaled.getPixels(pixels,0,224,0,0,224,224)}.isSuccess) return null
            val buffer=ByteBuffer.allocateDirect(224*224*3*4).order(ByteOrder.nativeOrder())
            for(p in pixels){buffer.putFloat(((p shr 16)and 255)/127.5f-1);buffer.putFloat(((p shr 8)and 255)/127.5f-1);buffer.putFloat((p and 255)/127.5f-1)}
            val aesthetic=infer(folder,"nima_aesthetic.tflite",buffer)?.let{normalised(it)}
            val technical=infer(folder,"nima_technical.tflite",buffer)?.let{normalised(it)}
            val a=aesthetic?:technical?:return null
            val t=technical?:a
            val mean=runCatching{Scoring.distribution(a).first}.getOrNull()?:Neutral.AESTHETIC
            val technicalMean=runCatching{Scoring.distribution(t).first}.getOrNull()?:Neutral.TECHNICAL
            val deviation=runCatching{Scoring.distribution(a).second}.getOrNull()?:Neutral.DEVIATION
            return NimaResult(
                mean.takeIf{it.isFinite()}?.coerceIn(1.0,10.0)?:Neutral.AESTHETIC,
                technicalMean.takeIf{it.isFinite()}?.coerceIn(1.0,10.0)?:Neutral.TECHNICAL,
                deviation.takeIf{it.isFinite()}?.coerceIn(0.0,4.5)?:Neutral.DEVIATION,
                a.toList()
            )
        } catch(e:Throwable) {
            return null
        }
    }
}
