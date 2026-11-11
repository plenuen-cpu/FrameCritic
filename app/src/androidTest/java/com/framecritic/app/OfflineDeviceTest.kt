package com.framecritic.app
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class OfflineDeviceTest {
    @Test fun bundledModelsNimaOpenCvAndMediaPipe()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=ModelStore.prepare(context){}
        val b=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.GRAY)}
        try{val n=Nima.analyze(folder,b);assertTrue(n.aesthetic in 1.0..10.0);val m=Vision.metrics(b);assertEquals(0.0,m.sharpness,1e-5);Vision.subjects(context,folder,b)}finally{b.recycle()}
    }
    @Test fun realVlmAndKoreanGeneration()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=ModelStore.prepare(context){}
        val b=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888)
        val c=android.graphics.Canvas(b);c.drawColor(android.graphics.Color.WHITE);c.drawCircle(192f,192f,110f,android.graphics.Paint().apply{color=android.graphics.Color.RED})
        try{val a=Pipeline.analyze(context,folder,b){};assertNotNull(a.scene);val r=Pipeline.report(context,folder,a,Persona.LEITER,Level.BALANCED,Tone.ANALYST,Depth.QUICK){};assertNull(r.failure);assertTrue(r.sections.isNotEmpty())}finally{b.recycle()}
    }
}
