package com.framecritic.app
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class OfflineDeviceTest {
    @Test fun bundledModelsNimaOpenCvAndMediaPipe()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=ModelStore.prepare(context){}
        val b=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GRAY)}
        try{val n=Nima.analyze(folder,b);assertNotNull("NIMA가 중립값도 돌려주지 못했습니다.",n);assertTrue(n!!.aesthetic in 1.0..10.0);val m=Vision.metrics(b);assertEquals(0.0,m.sharpness,1e-5);Vision.subjects(context,folder,b);assertFalse("피사체 탐지가 원본 비트맵을 recycle했습니다.",b.isRecycled)}finally{b.recycle()}
    }
    @Test fun prepareSkipsLargeLanguageWeights()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=ModelStore.prepare(context){}
        assertTrue(File(folder,"nima_aesthetic.tflite").isFile)
        assertTrue(File(folder,"nima_technical.tflite").isFile)
        assertTrue(File(folder,"objects.tflite").isFile)
        assertFalse(File(folder,"vision.gguf").exists())
        assertFalse(File(folder,"korean.gguf").exists())
    }
    @Test fun offlineMeasurementCriticProducesKoreanReport()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=ModelStore.prepare(context){}
        val b=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        val c=Canvas(b);c.drawColor(Color.rgb(28,36,52));c.drawCircle(220f,250f,90f,Paint().apply{color=Color.rgb(214,176,120)})
        try{
            val a=Pipeline.analyze(context,folder,b){}
            assertFalse("분석이 화면이 그리는 원본 비트맵을 recycle했습니다.",b.isRecycled)
            assertEquals(Axis.entries.size,a.assessment.evidence.size)
            val r=Pipeline.report(a,Persona.LEITER,Level.BALANCED,Tone.ANALYST,Depth.QUICK)
            assertNull(r.failure)
            assertEquals(11,r.sections.size)
            assertNotNull(r.score)
            assertTrue(r.sections.values.all{it.count{ch->ch in '가'..'힣'}>=3})
        }finally{b.recycle()}
    }
}
