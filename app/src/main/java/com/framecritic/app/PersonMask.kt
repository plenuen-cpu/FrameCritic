package com.framecritic.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Actual ML Kit selfie segmentation; only suitable for people, not general objects. */
object PersonMask {
    suspend fun overlay(bitmap: Bitmap): Result<Bitmap> =
        suspendCancellableCoroutine { continuation ->
            val segmenter = Segmentation.getClient(
                SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build()
            )
            segmenter.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { mask ->
                    runCatching {
                        val buffer = mask.buffer
                        buffer.rewind()
                        val w = mask.width
                        val h = mask.height
                        val colors = IntArray(w * h)
                        for (i in colors.indices) {
                            val confidence = buffer.float.coerceIn(0f, 1f)
                            colors[i] = Color.argb((confidence * 145).toInt(), 0, 210, 255)
                        }
                        val layer = Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
                        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val canvas = android.graphics.Canvas(output)
                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        canvas.drawBitmap(layer, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), paint)
                        output
                    }.let { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resume(Result.failure(error))
                }
                .addOnCompleteListener { segmenter.close() }
        }
}
