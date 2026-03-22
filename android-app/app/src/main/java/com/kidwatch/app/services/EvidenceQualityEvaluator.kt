package com.kidwatch.app.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.math.sqrt

class EvidenceQualityEvaluator {

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    fun isValidScreenshot(bitmap: Bitmap): Boolean {
        if (bitmap.width < MIN_SCREENSHOT_WIDTH || bitmap.height < MIN_SCREENSHOT_HEIGHT) {
            return false
        }
        val stats = analyze(bitmap)
        if (stats.sampleCount == 0) return false
        if (stats.darkRatio > MAX_NEAR_BLACK_RATIO) return false
        if (stats.brightRatio > MAX_NEAR_WHITE_RATIO) return false
        return stats.contrast >= MIN_SCREENSHOT_CONTRAST
    }

    suspend fun isValidFaceCapture(bitmap: Bitmap): Boolean {
        if (bitmap.width < MIN_FACE_WIDTH || bitmap.height < MIN_FACE_HEIGHT) {
            return false
        }
        val stats = analyze(bitmap)
        if (stats.sampleCount == 0) return false
        if (stats.darkRatio > MAX_FACE_BLANK_RATIO) return false
        if (stats.brightRatio > MAX_FACE_BLANK_RATIO) return false
        if (stats.contrast < MIN_FACE_CONTRAST) return false
        if (stats.directionalEdgeRatio > MAX_FACE_DIRECTIONAL_EDGE_RATIO) return false

        val detectedFaces = runCatching {
            faceDetector.process(InputImage.fromBitmap(bitmap, 0)).await()
        }.getOrDefault(emptyList())

        return detectedFaces.any { face ->
            val widthRatio = face.boundingBox.width().toFloat() / bitmap.width.toFloat()
            val heightRatio = face.boundingBox.height().toFloat() / bitmap.height.toFloat()
            widthRatio in MIN_FACE_RATIO..MAX_FACE_RATIO &&
                heightRatio in MIN_FACE_RATIO..MAX_FACE_RATIO
        }
    }

    fun isValidScreenshotFile(path: String?): Boolean {
        return decodeSampledBitmap(path, 1080, 1920)?.let(::isValidScreenshot) ?: false
    }

    suspend fun isValidFaceFile(path: String?): Boolean {
        return decodeSampledBitmap(path, 720, 960)?.let { bitmap ->
            isValidFaceCapture(bitmap)
        } ?: false
    }

    private fun decodeSampledBitmap(path: String?, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, requestedWidth, requestedHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var sampleSize = 1

        if (height > requestedHeight || width > requestedWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / sampleSize >= requestedHeight && halfWidth / sampleSize >= requestedWidth) {
                sampleSize *= 2
                halfHeight = height / 2
                halfWidth = width / 2
            }
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun analyze(bitmap: Bitmap): ImageStats {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return ImageStats()

        val stepX = maxOf(1, width / SAMPLE_GRID_SIZE)
        val stepY = maxOf(1, height / SAMPLE_GRID_SIZE)

        var count = 0
        var sum = 0.0
        var sumSquares = 0.0
        var darkPixels = 0
        var brightPixels = 0
        var horizontalEdgeSum = 0.0
        var horizontalEdgeCount = 0
        var verticalEdgeSum = 0.0
        var verticalEdgeCount = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val luma = (0.299 * red + 0.587 * green + 0.114 * blue)
                sum += luma
                sumSquares += luma * luma
                if (luma <= NEAR_BLACK_LUMA) darkPixels++
                if (luma >= NEAR_WHITE_LUMA) brightPixels++
                if (x + stepX < width) {
                    val rightPixel = bitmap.getPixel(x + stepX, y)
                    val rightLuma = lumaOf(rightPixel)
                    horizontalEdgeSum += kotlin.math.abs(rightLuma - luma)
                    horizontalEdgeCount++
                }
                if (y + stepY < height) {
                    val bottomPixel = bitmap.getPixel(x, y + stepY)
                    val bottomLuma = lumaOf(bottomPixel)
                    verticalEdgeSum += kotlin.math.abs(bottomLuma - luma)
                    verticalEdgeCount++
                }
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0) return ImageStats()
        val mean = sum / count
        val variance = ((sumSquares / count) - (mean * mean)).coerceAtLeast(0.0)
        return ImageStats(
            sampleCount = count,
            darkRatio = darkPixels.toFloat() / count.toFloat(),
            brightRatio = brightPixels.toFloat() / count.toFloat(),
            contrast = sqrt(variance).toFloat(),
            directionalEdgeRatio = (
                maxOf(
                    horizontalEdgeSum / horizontalEdgeCount.coerceAtLeast(1),
                    verticalEdgeSum / verticalEdgeCount.coerceAtLeast(1)
                ) / maxOf(
                    1.0,
                    minOf(
                        horizontalEdgeSum / horizontalEdgeCount.coerceAtLeast(1),
                        verticalEdgeSum / verticalEdgeCount.coerceAtLeast(1)
                    )
                )
            ).toFloat()
        )
    }

    private fun lumaOf(pixel: Int): Double {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return 0.299 * red + 0.587 * green + 0.114 * blue
    }

    private data class ImageStats(
        val sampleCount: Int = 0,
        val darkRatio: Float = 0f,
        val brightRatio: Float = 0f,
        val contrast: Float = 0f,
        val directionalEdgeRatio: Float = 1f
    )

    private companion object {
        private const val SAMPLE_GRID_SIZE = 32
        private const val NEAR_BLACK_LUMA = 10.0
        private const val NEAR_WHITE_LUMA = 245.0
        private const val MAX_NEAR_BLACK_RATIO = 0.97f
        private const val MAX_NEAR_WHITE_RATIO = 0.97f
        private const val MIN_SCREENSHOT_CONTRAST = 8f
        private const val MIN_SCREENSHOT_WIDTH = 240
        private const val MIN_SCREENSHOT_HEIGHT = 320
        private const val MAX_FACE_BLANK_RATIO = 0.90f
        private const val MIN_FACE_CONTRAST = 12f
        private const val MIN_FACE_WIDTH = 120
        private const val MIN_FACE_HEIGHT = 140
        private const val MIN_FACE_RATIO = 0.10f
        private const val MAX_FACE_RATIO = 0.95f
        private const val MAX_FACE_DIRECTIONAL_EDGE_RATIO = 2.4f
    }
}
