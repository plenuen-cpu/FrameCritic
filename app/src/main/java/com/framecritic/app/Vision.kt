package com.framecritic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

data class Subject(val label:String,val confidence:Float,val box:RectF)
data class Line(val x1:Float,val y1:Float,val x2:Float,val y2:Float,val angle:Double)
data class Metrics(val histogram:List<Int>,val highlights:Double,val shadows:Double,val luminance:Double,
    val contrast:Double,val localContrast:Double,val sharpness:Double,val rgb:List<Double>,val hues:List<Int>,val lines:List<Line>,val clipMap:Bitmap?,
    val saturation:Double=0.0,val edges:Double=0.0) {
    fun json()=JSONObject().put("luminance_mean_0_255",luminance).put("highlight_fraction_Y_ge_250",highlights)
        .put("shadow_fraction_Y_le_5",shadows).put("global_luma_std",contrast).put("local_luma_std_mean",localContrast)
        .put("laplacian_variance_at_analysis_resolution",sharpness).put("mean_RGB_not_illuminant",JSONArray(rgb))
        .put("hue_histogram_12",JSONArray(hues)).put("luma_histogram_256",JSONArray(histogram))
        .put("mean_saturation_0_1",saturation).put("canny_edge_fraction",edges)
        .put("near_horizontal_candidates",JSONArray(lines.map{JSONObject().put("angle",it.angle)}))
}
object Vision {
    /** OpenCV 연산은 행·열·타입이 조금만 어긋나도 예외를 던진다. 단계마다 확인하고 기본값으로 넘긴다. */
    fun metrics(bitmap:Bitmap):Metrics {
        require(bitmap.width>0&&bitmap.height>0) { "사진 크기를 읽지 못했습니다." }
        check(OpenCVLoader.initLocal()) { "OpenCV 로딩 실패" }
        val width=bitmap.width;val height=bitmap.height
        val pixelCount=width.toLong()*height.toLong()
        require(pixelCount in 1..Int.MAX_VALUE.toLong()) { "사진 크기가 처리 범위를 넘습니다." }
        val source=if(bitmap.config==Bitmap.Config.HARDWARE) (bitmap.copy(Bitmap.Config.ARGB_8888,false)?:bitmap) else bitmap
        val mats=mutableListOf<Mat>();fun mat()=Mat().also{mats+=it}
        val rgba=mat();val rgb=mat();val gray=mat();val hsv=mat();val lap=mat();val edge=mat();val lines=mat()
        try {
            val target=rgba
            Utils.bitmapToMat(source,target)
            require(!target.empty()) { "사진을 행렬로 바꾸지 못했습니다." }
            Imgproc.cvtColor(target,rgb,Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)
            Imgproc.Laplacian(gray,lap,CvType.CV_64F)
            fun stats(m:Mat):Pair<Double,Double> {
                val mean=MatOfDouble();val std=MatOfDouble()
                try {
                    Core.meanStdDev(m,mean,std)
                    val meanValue=mean.toArray().firstOrNull()?:0.0
                    val stdValue=std.toArray().firstOrNull()?:0.0
                    return (if(meanValue.isFinite())meanValue else 0.0) to (if(stdValue.isFinite())stdValue else 0.0)
                } finally {mean.release();std.release()}
            }
            val (mean,std)=stats(gray);val sharp=stats(lap).second.pow(2)
            val luma=ByteArray(pixelCount.toInt())
            gray.get(0,0,luma)
            val hist=IntArray(256);luma.forEach{hist[it.toInt()and 255]++}
            val colors=ByteArray(luma.size*3);hsv.get(0,0,colors)
            val hues=IntArray(12)
            var saturationSum=0.0
            for(i in luma.indices){
                val s=colors[i*3+1].toInt()and 255;saturationSum+=s
                if(s>32) hues[((colors[i*3].toInt()and 255)/15).coerceIn(0,11)]++
            }
            val meanSaturation=if(luma.isEmpty()) 0.0 else saturationSum/luma.size/255.0
            var local=0.0;var count=0
            for(y in 0 until gray.rows() step 32) for(x in 0 until gray.cols() step 32) {
                val patch=runCatching{gray.submat(y,min(y+32,gray.rows()),x,min(x+32,gray.cols()))}.getOrNull()?:continue
                try { local+=stats(patch).second;count++ } catch(e:Throwable) { /* 패치 하나를 못 읽어도 전체 측정은 계속한다. */ }
                finally { runCatching{patch.release()} }
            }
            Imgproc.Canny(gray,edge,60.0,150.0)
            val edgeFraction=if(luma.isEmpty()) 0.0 else Core.countNonZero(edge).toDouble()/luma.size
            Imgproc.HoughLinesP(edge,lines,1.0,Math.PI/180,60,width*.22,20.0)
            val candidates=mutableListOf<Line>()
            val rowLimit=runCatching{lines.rows()}.getOrDefault(0)
            for(i in 0 until rowLimit) {
                val v=runCatching{lines.get(i,0)}.getOrNull()
                if(v==null||v.size<4) continue
                if(!(v[0].isFinite()&&v[1].isFinite()&&v[2].isFinite()&&v[3].isFinite())) continue
                val angle=Math.toDegrees(atan2(v[3]-v[1],v[2]-v[0]))
                if(angle.isFinite()&&abs(angle)<15) candidates+=Line((v[0]/width).toFloat(),(v[1]/height).toFloat(),(v[2]/width).toFloat(),(v[3]/height).toFloat(),angle)
            }
            val clipMap=runCatching{
                Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{map->
                    map.setPixels(IntArray(luma.size){when(luma[it].toInt()and 255){in 0..5->0x990078FF.toInt();in 250..255->0x99FF4A50.toInt();else->Color.TRANSPARENT}},0,width,0,0,width,height)
                }
            }.getOrNull()
            val channel=Core.mean(rgb).`val`.take(3)
            return Metrics(hist.toList(),hist.takeLast(6).sum().toDouble()/luma.size,hist.take(6).sum().toDouble()/luma.size,mean,std,
                if(count==0) 0.0 else local/count,sharp,channel,hues.toList(),
                candidates.sortedByDescending{hypot((it.x2-it.x1).toDouble(),(it.y2-it.y1).toDouble())}.take(5),clipMap,meanSaturation,edgeFraction)
        } finally {
            // 비트맵은 여기서 해제하지 않는다. source는 화면이 같은 원본을 그리는 동안
            // 남아 있을 수 있어, 수동 recycle()은 다음 프레임의 Canvas 예외로 이어진다.
            mats.forEach{runCatching{it.release()}}
        }
    }
    /**
     * 비트맵을 위·가운데·아래 띠로 나누어 장면 근거를 모은다.
     * 웹의 analyzer.ts가 픽셀 배열에서 하는 계산과 같은 값이다.
     */
    fun cues(bitmap:Bitmap):SceneCues? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val stepX = max(1, width / 160)
        val stepY = max(1, height / 160)
        data class Acc(var r: Double = 0.0, var g: Double = 0.0, var b: Double = 0.0, var y: Double = 0.0,
                       var y2: Double = 0.0, var n: Int = 0, var green: Int = 0, var blue: Int = 0, var bright: Int = 0)
        val accs = listOf(Acc(), Acc(), Acc())
        for (y in 0 until height step stepY) {
            val acc = accs[if (y < height / 3) 0 else if (y < height * 2 / 3) 1 else 2]
            for (x in 0 until width step stepX) {
                val pixel = runCatching { bitmap.getPixel(x, y) }.getOrNull() ?: continue
                val r = (pixel shr 16 and 0xFF).toDouble()
                val g = (pixel shr 8 and 0xFF).toDouble()
                val b = (pixel and 0xFF).toDouble()
                val luma = .299 * r + .587 * g + .114 * b
                acc.r += r; acc.g += g; acc.b += b; acc.y += luma; acc.y2 += luma * luma; acc.n++
                if (g > r + 8 && g > b + 8) acc.green++
                if (b > r + 8) acc.blue++
                if (luma >= 200) acc.bright++
            }
        }
        fun stats(acc: Acc): BandStats {
            if (acc.n == 0) return BandStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val mean = acc.y / acc.n
            return BandStats(
                acc.r / acc.n, acc.g / acc.n, acc.b / acc.n, mean,
                sqrt(max(0.0, acc.y2 / acc.n - mean * mean)),
                acc.green.toDouble() / acc.n, acc.blue.toDouble() / acc.n, acc.bright.toDouble() / acc.n
            )
        }
        return SceneCues(stats(accs[0]), stats(accs[1]), stats(accs[2]), false)
    }


    /** 주인공 상자 안과 바깥의 밝기 차이. 0에 가까우면 같은 톤, 1에 가까우면 밝기로 나뉜다. */
    fun separation(bitmap:Bitmap, box:SubjectBox):Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0.0
        val left = box.cx - box.w / 2
        val right = box.cx + box.w / 2
        val top = box.cy - box.h / 2
        val bottom = box.cy + box.h / 2
        var inside = 0.0; var insideCount = 0; var outside = 0.0; var outsideCount = 0
        for (y in 0 until height step 4) {
            val ny = y.toDouble() / height
            for (x in 0 until width step 4) {
                val nx = x.toDouble() / width
                val pixel = runCatching { bitmap.getPixel(x, y) }.getOrNull() ?: continue
                val luma = .299 * (pixel shr 16 and 0xFF) + .587 * (pixel shr 8 and 0xFF) + .114 * (pixel and 0xFF)
                if (nx in left..right && ny in top..bottom) { inside += luma; insideCount++ }
                else { outside += luma; outsideCount++ }
            }
        }
        if (insideCount == 0 || outsideCount == 0) return 0.0
        return (abs(inside / insideCount - outside / outsideCount) / 70.0).coerceIn(0.0, 1.0)
    }

    /** 모델 파일을 자산 경로가 아니라 버퍼로 넘겨, 경로 해석 실패로 탐지가 통째로 비지 않게 한다. */
    private fun baseOptions(model:File):BaseOptions {
        val bytes=model.readBytes()
        val buffer=ByteBuffer.allocateDirect(bytes.size).apply{put(bytes);rewind()}
        return runCatching{BaseOptions.builder().setModelAssetBuffer(buffer).build()}
            .getOrElse{BaseOptions.builder().setModelAssetPath(model.absolutePath).build()}
    }
    fun subjects(context:Context,folder:File,bitmap:Bitmap):List<Subject> {
        val model=File(folder,"objects.tflite")
        check(model.isFile&&model.length()>0L) { "피사체 탐지 모델 파일이 없습니다." }
        if(bitmap.width<=0||bitmap.height<=0||bitmap.isRecycled) return emptyList()
        val options=ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions(model))
            .setScoreThreshold(.30f).setMaxResults(10).build()
        // MediaPipe에는 원본이 아니라 복사본을 넘긴다. MPImage를 닫을 때 MediaPipe가 넘겨받은
        // 안드로이드 Bitmap을 recycle()하는 경로가 있어, 원본을 그대로 주면 화면이 그 사진을
        // 그리던 중 "Canvas: trying to use a recycled bitmap"으로 앱이 종료된다.
        val input=runCatching{bitmap.copy(Bitmap.Config.ARGB_8888,false)}.getOrNull()
            ?:return emptyList()
        try {
            return ObjectDetector.createFromOptions(context,options).use {detector->
                val image=BitmapImageBuilder(input).build()
                try {
                    detector.detect(image).detections().mapNotNull {d->
                        // categories()가 비어 있으면 maxBy가 예외를 던진다. maxByOrNull로 건너뛴다.
                        val category=d.categories().maxByOrNull{it.score()}?:return@mapNotNull null
                        val box=d.boundingBox()
                        val width=bitmap.width.toFloat();val height=bitmap.height.toFloat()
                        if(!(box.left.isFinite()&&box.top.isFinite()&&box.right.isFinite()&&box.bottom.isFinite())) return@mapNotNull null
                        val left=box.left/width;val top=box.top/height;val right=box.right/width;val bottom=box.bottom/height
                        if(!(left.isFinite()&&top.isFinite()&&right.isFinite()&&bottom.isFinite())) return@mapNotNull null
                        if(right<=left||bottom<=top) return@mapNotNull null
                        Subject(category.categoryName(),category.score(),RectF(left,top,right,bottom))
                    }
                } finally {runCatching{image.close()}}
            }
        } finally {
            // 해제하는 것은 복사본뿐이다. MediaPipe가 이미 닫아 두었다면 두 번 부르지 않는다.
            runCatching{if(!input.isRecycled) input.recycle()}
        }
    }
}
