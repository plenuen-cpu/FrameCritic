package com.framecritic.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Genuine ML Kit image labeling. Labels are English and may be incorrect. Not a VLM. */
object SceneLabels {
    suspend fun detect(bitmap: Bitmap): Result<List<Pair<String, Float>>> =
        suspendCancellableCoroutine { continuation ->
            val client = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { labels ->
                    if (continuation.isActive) continuation.resume(Result.success(labels.filter { it.confidence >= .45f }.sortedByDescending { it.confidence }.take(6).map { it.text to it.confidence }))
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }
                .addOnCompleteListener { client.close() }
        }
}
