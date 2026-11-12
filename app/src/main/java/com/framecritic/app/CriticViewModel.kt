package com.framecritic.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class ScreenState(val bitmap:Bitmap?=null,val analysis:Analysis?=null,val report:Report?=null,
    val busy:Boolean=false,val progress:String="",val error:String?=null,
    val persona:Persona=Persona.BRESSON,val level:Level=Level.BALANCED,val tone:Tone=Tone.MENTOR,val depth:Depth=Depth.STANDARD)
class CriticViewModel(app:Application):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(ScreenState());val state=mutable.asStateFlow()
    private var job:Job?=null;private var folder:File?=null
    private fun progress(s:String){mutable.update{it.copy(progress=s)}}
    fun setOptions(p:Persona?=null,l:Level?=null,t:Tone?=null,d:Depth?=null){if(mutable.value.busy)return;mutable.update{it.copy(persona=p?:it.persona,level=l?:it.level,tone=t?:it.tone,depth=d?:it.depth,report=null)}}
    fun cancel(){job?.cancel()}
    fun load(uri:Uri) {
        if(mutable.value.busy)return
        job=viewModelScope.launch(Dispatchers.IO) {
            mutable.update{it.copy(busy=true,error=null,progress="사진 불러오기")}
            try {
                val source=ImageDecoder.createSource(getApplication<Application>().contentResolver,uri)
                val image=ImageDecoder.decodeBitmap(source){decoder,info,_->
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio=minOf(1.0,1280.0/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
                    decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                }.let{if(it.config==Bitmap.Config.ARGB_8888)it else it.copy(Bitmap.Config.ARGB_8888,false)}
                mutable.update{it.copy(bitmap=image,analysis=null,report=null)}
            }catch(e:CancellationException){throw e}catch(e:OutOfMemoryError){mutable.update{it.copy(error="사진을 열 메모리가 부족합니다.")}}catch(e:Exception){mutable.update{it.copy(error=e.message)}}finally{mutable.update{it.copy(busy=false,progress="")}}
        }
    }
    fun analyze() {
        val selected=mutable.value;if(selected.busy||selected.bitmap==null)return
        job=viewModelScope.launch(Dispatchers.IO) {
            mutable.update{it.copy(busy=true,error=null,report=null)}
            try {
                val context=getApplication<Application>()
                val a=selected.analysis?:run{
                    progress("내장 모델 확인")
                    val models=folder?:ModelStore.prepare(context,::progress).also{folder=it}
                    Pipeline.analyze(context,models,selected.bitmap,::progress).also{result->mutable.update{it.copy(analysis=result)}}
                }
                progress("측정 근거로 비평 작성")
                val report=Pipeline.report(a,selected.persona,selected.level,selected.tone,selected.depth)
                mutable.update{it.copy(report=report)}
            }catch(e:CancellationException){mutable.update{it.copy(error="분석을 취소했습니다.")};throw e}
            catch(e:OutOfMemoryError){mutable.update{it.copy(error="메모리가 부족합니다. 다른 앱을 닫고 다시 시도하세요.")}}
            catch(e:Exception){mutable.update{it.copy(error=e.message?:"분석 실패")}}
            finally{mutable.update{it.copy(busy=false,progress="")}}
        }
    }
}
