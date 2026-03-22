package com.kidwatch.app.services

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

class EvidenceFileManager(
    context: Context
) {
    private val evidenceRoot = File(context.filesDir, "evidence").apply { mkdirs() }
    private val screenshotDir = File(evidenceRoot, "screenshots").apply { mkdirs() }
    private val faceDir = File(evidenceRoot, "faces").apply { mkdirs() }

    fun saveScreenshot(bitmap: Bitmap, sessionId: Long, timestamp: Long): String {
        return saveBitmap(File(screenshotDir, "session_${sessionId}_$timestamp.jpg"), bitmap)
    }

    fun saveFaceCrop(bitmap: Bitmap, sessionId: Long, timestamp: Long): String {
        return saveBitmap(File(faceDir, "face_${sessionId}_$timestamp.jpg"), bitmap)
    }

    fun deletePath(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    fun clearAll() {
        evidenceRoot.deleteRecursively()
        screenshotDir.mkdirs()
        faceDir.mkdirs()
    }

    private fun saveBitmap(file: File, bitmap: Bitmap): String {
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
        }
        return file.absolutePath
    }
}
