package com.framecritic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Genuine LiteRT/TFLite model inference. No score is returned on model failure. */
object NimaEngine {
    data class Scores(val aesthetic: Double, val technical: Double)
    fun score(context: Context, image: Bitmap): Result<Scores> = runCatching {
        Scores(
            runModel(context, image, "nima_aesthetic_fp16.tflite"),
            runModel(context, image, "nima_technical_fp16.tflite")
        )
    }

    private fun runModel(context: Context, bitmap: Bitmap, name: String): Double {
        val bytes = context.assets.open(name).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        Interpreter(model, Interpreter.Options().setNumThreads(4)).use { interpreter ->
            val shape = interpreter.getInputTensor(0).shape()
            require(shape.size == 4 && shape[0] == 1 && shape[3] == 3) { "Unsupported input shape: ${shape.contentToString()}" }
            val h = shape[1]
            val w = shape[2]
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val input = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
            for (pixel in pixels) {
                input.putFloat(Color.red(pixel) / 127.5f - 1f)
                input.putFloat(Color.green(pixel) / 127.5f - 1f)
                input.putFloat(Color.blue(pixel) / 127.5f - 1f)
            }
            input.rewind()
            val outputShape = interpreter.getOutputTensor(0).shape()
            require(outputShape.fold(1) { a, b -> a * b } == 10) { "Expected ten NIMA bins" }
            val output = ByteBuffer.allocateDirect(10 * 4).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val values = FloatArray(10) { output.float }
            require(values.all { it.isFinite() && it >= 0f }) { "Invalid NIMA distribution" }
            val total = values.sum()
            require(total > 0f) { "Empty NIMA distribution" }
            return values.indices.sumOf { (it + 1) * values[it].toDouble() } / total
        }
    }
}
