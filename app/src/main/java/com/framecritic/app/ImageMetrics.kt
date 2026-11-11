package com.framecritic.app
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

data class ImageMetrics(val brightness:Double,val darkClip:Double,val brightClip:Double,val saturation:Double,val edgeContrast:Double)
object ImageAnalyzer {
 fun analyze(src:Bitmap):ImageMetrics {
  val factor=min(1.0,512.0/max(src.width,src.height))
  val w=max(1,(src.width*factor).toInt());val h=max(1,(src.height*factor).toInt())
  val b=Bitmap.createScaledBitmap(src,w,h,true)
  val px=IntArray(w*h);b.getPixels(px,0,w,0,0,w,h)
  val lum=DoubleArray(px.size)
  var sum=0.0;var dark=0;var bright=0;var sat=0.0
  for(i in px.indices){
   val c=px[i];val r=Color.red(c).toDouble();val g=Color.green(c).toDouble();val bl=Color.blue(c).toDouble()
   val y=(.2126*r+.7152*g+.0722*bl)/255.0
   lum[i]=y;sum+=y
   if(y<.025)dark++
   if(y>.975)bright++
   val hi=max(r,max(g,bl));val lo=min(r,min(g,bl))
   sat+=if(hi==0.0)0.0 else (hi-lo)/hi
  }
  var edge=0.0;var n=0
  for(y in 1 until h-1)for(x in 1 until w-1){
   val i=y*w+x;val dx=lum[i+1]-lum[i-1];val dy=lum[i+w]-lum[i-w]
   edge+=sqrt(dx*dx+dy*dy);n++
  }
  return ImageMetrics(sum/px.size,dark.toDouble()/px.size,bright.toDouble()/px.size,sat/px.size,if(n==0)0.0 else edge/n)
 }
}
object CritiqueRules {
 fun report(m:ImageMetrics,persona:String,level:String,tone:String):String {
  val items=mutableListOf<String>()
  if(m.brightClip>.05)items+="하이라이트 클리핑이 감지됩니다: "+(m.brightClip*100).toInt()+"%."
  if(m.darkClip>.12)items+="어두운 영역 비율이 높습니다: "+(m.darkClip*100).toInt()+"%. 의도적 실루엣일 수 있습니다."
  if(m.brightness<.28)items+="전체적으로 낮은 휘도입니다. 저조도 표현 의도를 고려하세요."
  if(m.brightness>.76)items+="전체적으로 높은 휘도입니다. 하이키 표현 의도를 고려하세요."
  if(m.edgeContrast<.025)items+="국소 경계 대비가 낮습니다. 부드러운 표현 또는 흐림 가능성이 있습니다."
  if(items.isEmpty())items+="극단적인 픽셀 기반 품질 이상은 감지되지 않았습니다."
  val focus=when(persona){
   "Bresson"->"브레송 관점: 기하학과 순간의 관계를 살펴보세요. 현재 엔진은 결정적 순간을 판정하지 못합니다."
   "Adams"->"애덤스 관점: 하이라이트부터 암부까지 계조 표현을 확인하세요."
   "Leiter"->"라이터 관점: 색채의 관계와 프레임의 중첩을 살펴보세요. 반사 검출은 지원하지 않습니다."
   else->"판호 관점: 빛과 그림자의 기하학, 실루엣의 역할을 살펴보세요."
  }
  val intro=when(tone){"Mentor"->"사진을 발전시키기 위한 기술적 관찰입니다."; "Analyst"->"픽셀 기반 측정 결과입니다.";else->"기술적 문제를 점검합니다."}
  val strict=if(level=="Strict")"엄격 모드: 표현 의도와 기술적 결함을 분리해 재검토하세요." else ""
  return (listOf(intro)+items+listOf(focus,strict)).filter{it.isNotBlank()}.joinToString("\n\n")
 }
}
