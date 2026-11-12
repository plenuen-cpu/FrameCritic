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
import kotlin.math.*

data class Subject(val label:String,val confidence:Float,val box:RectF)
data class Line(val x1:Float,val y1:Float,val x2:Float,val y2:Float,val angle:Double)
data class Metrics(val histogram:List<Int>,val highlights:Double,val shadows:Double,val luminance:Double,
    val contrast:Double,val localContrast:Double,val sharpness:Double,val rgb:List<Double>,val hues:List<Int>,val lines:List<Line>,val clipMap:Bitmap,
    val saturation:Double=0.0,val edges:Double=0.0) {
    fun json()=JSONObject().put("luminance_mean_0_255",luminance).put("highlight_fraction_Y_ge_250",highlights)
        .put("shadow_fraction_Y_le_5",shadows).put("global_luma_std",contrast).put("local_luma_std_mean",localContrast)
        .put("laplacian_variance_at_analysis_resolution",sharpness).put("mean_RGB_not_illuminant",JSONArray(rgb))
        .put("hue_histogram_12",JSONArray(hues)).put("luma_histogram_256",JSONArray(histogram))
        .put("mean_saturation_0_1",saturation).put("canny_edge_fraction",edges)
        .put("near_horizontal_candidates",JSONArray(lines.map{JSONObject().put("angle",it.angle)}))
}
object Vision {
    fun metrics(bitmap:Bitmap):Metrics {
        check(OpenCVLoader.initLocal()) { "OpenCV 로딩 실패" }
        val mats=mutableListOf<Mat>();fun mat()=Mat().also{mats+=it}
        val rgba=mat();val rgb=mat();val gray=mat();val hsv=mat();val lap=mat();val edge=mat();val lines=mat()
        try {
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV)
            Imgproc.Laplacian(gray,lap,CvType.CV_64F)
            fun stats(m:Mat):Pair<Double,Double> {val mean=MatOfDouble();val std=MatOfDouble();try{Core.meanStdDev(m,mean,std);return mean.toArray()[0] to std.toArray()[0]}finally{mean.release();std.release()}}
            val (mean,std)=stats(gray);val sharp=stats(lap).second.pow(2)
            val luma=ByteArray(bitmap.width*bitmap.height);gray.get(0,0,luma)
            val hist=IntArray(256);luma.forEach{hist[it.toInt()and 255]++}
            val colors=ByteArray(luma.size*3);hsv.get(0,0,colors);val hues=IntArray(12)
            var saturationSum=0.0
            for(i in luma.indices){val s=colors[i*3+1].toInt()and 255;saturationSum+=s;if(s>32)hues[((colors[i*3].toInt()and 255)/15).coerceIn(0,11)]++}
            val meanSaturation=if(luma.isEmpty())0.0 else saturationSum/luma.size/255.0
            var local=0.0;var count=0
            for(y in 0 until gray.rows() step 32)for(x in 0 until gray.cols() step 32){val patch=gray.submat(y,min(y+32,gray.rows()),x,min(x+32,gray.cols()));try{local+=stats(patch).second;count++}finally{patch.release()}}
            Imgproc.Canny(gray,edge,60.0,150.0)
            val edgeFraction=if(luma.isEmpty())0.0 else Core.countNonZero(edge).toDouble()/luma.size
            Imgproc.HoughLinesP(edge,lines,1.0,Math.PI/180,60,bitmap.width*.22,20.0)
            val candidates=mutableListOf<Line>()
            for(i in 0 until lines.rows()){val v=lines.get(i,0);val a=Math.toDegrees(atan2(v[3]-v[1],v[2]-v[0]));if(abs(a)<15)candidates+=Line((v[0]/bitmap.width).toFloat(),(v[1]/bitmap.height).toFloat(),(v[2]/bitmap.width).toFloat(),(v[3]/bitmap.height).toFloat(),a)}
            val map=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
            map.setPixels(IntArray(luma.size){when(luma[it].toInt()and 255){in 0..5->0x990078FF.toInt();in 250..255->0x99FF4A50.toInt();else->Color.TRANSPARENT}},0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val channel=Core.mean(rgb).`val`.take(3)
            return Metrics(hist.toList(),hist.takeLast(6).sum().toDouble()/luma.size,hist.take(6).sum().toDouble()/luma.size,mean,std,if(count==0)0.0 else local/count,sharp,channel,hues.toList(),candidates.sortedByDescending{hypot((it.x2-it.x1).toDouble(),(it.y2-it.y1).toDouble())}.take(5),map,meanSaturation,edgeFraction)
        } finally {mats.forEach{it.release()}}
    }
    fun subjects(context:Context,folder:File,bitmap:Bitmap):List<Subject> {
        val options=ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(File(folder,"objects.tflite").absolutePath).build())
            .setScoreThreshold(.35f).setMaxResults(8).build()
        return ObjectDetector.createFromOptions(context,options).use {detector->
            val image=BitmapImageBuilder(bitmap).build()
            try {detector.detect(image).detections().map {d->val c=d.categories().maxBy{it.score()};val b=d.boundingBox();Subject(c.categoryName(),c.score(),RectF(b.left/bitmap.width,b.top/bitmap.height,b.right/bitmap.width,b.bottom/bitmap.height))}}
            finally {image.close()}
        }
    }
}
