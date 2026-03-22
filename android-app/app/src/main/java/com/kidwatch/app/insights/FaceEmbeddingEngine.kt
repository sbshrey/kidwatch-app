package com.kidwatch.app.insights

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

class FaceEmbeddingEngine {

    fun createEmbedding(faceBitmap: Bitmap): FloatArray {
        val reduced = Bitmap.createScaledBitmap(faceBitmap, EMBEDDING_WIDTH, EMBEDDING_HEIGHT, true)
        val values = FloatArray(EMBEDDING_WIDTH * EMBEDDING_HEIGHT)
        var index = 0
        var norm = 0f
        for (y in 0 until EMBEDDING_HEIGHT) {
            for (x in 0 until EMBEDDING_WIDTH) {
                val pixel = reduced.getPixel(x, y)
                val luminance = (
                    Color.red(pixel) * 0.299f +
                        Color.green(pixel) * 0.587f +
                        Color.blue(pixel) * 0.114f
                    ) / 255f
                values[index] = luminance
                norm += luminance * luminance
                index++
            }
        }
        val scale = if (norm > 0f) 1f / sqrt(norm) else 1f
        for (i in values.indices) {
            values[i] *= scale
        }
        return values
    }

    companion object {
        private const val EMBEDDING_WIDTH = 8
        private const val EMBEDDING_HEIGHT = 8
    }
}
