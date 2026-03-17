package com.kidwatch.app.services

/**
 * Shared state for face detection.
 * FaceCaptureService updates lastFaceDetectedAt when a face is seen.
 * Video event capture (AccessibilityService path) reads this to enrich events.
 */
object FaceCaptureState {
    @Volatile
    var lastFaceDetectedAt: Long = 0L
        private set

    /** Call when a face is detected. */
    fun onFaceDetected() {
        lastFaceDetectedAt = System.currentTimeMillis()
    }

    /** Returns true if a face was detected within the last [windowMs] milliseconds. */
    fun wasFaceSeenRecently(windowMs: Long = 5_000L): Boolean {
        return System.currentTimeMillis() - lastFaceDetectedAt < windowMs
    }
}
