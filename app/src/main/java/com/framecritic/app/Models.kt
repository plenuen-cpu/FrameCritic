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
import kotlin.coroutines.coroutineContext

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
    fun analyze(folder:File,bitmap:Bitmap):NimaResult {
        val scaled=Bitmap.createScaledBitmap(bitmap,224,224,true)
        val pixels=IntArray(224*224);scaled.getPixels(pixels,0,224,0,0,224,224)
        val buffer=ByteBuffer.allocateDirect(224*224*3*4).order(ByteOrder.nativeOrder())
        for(p in pixels){buffer.putFloat(((p shr 16)and 255)/127.5f-1);buffer.putFloat(((p shr 8)and 255)/127.5f-1);buffer.putFloat((p and 255)/127.5f-1)}
        if(scaled!==bitmap)scaled.recycle()
        fun infer(name:String):FloatArray=Interpreter(File(folder,name),Interpreter.Options().setNumThreads(4)).use { interpreter->
            check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1,224,224,3)))
            check(interpreter.getOutputTensor(0).numElements()==10)
            buffer.rewind();val output=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder())
            interpreter.run(buffer,output);output.rewind();FloatArray(10){output.float}
        }
        val a=normalised(infer("nima_aesthetic.tflite"));val t=normalised(infer("nima_technical.tflite"))
        val (mean,sd)=Scoring.distribution(a)
        return NimaResult(mean,Scoring.distribution(t).first,sd,a.toList())
    }
}
