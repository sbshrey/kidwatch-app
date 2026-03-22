package com.kidwatch.app.services

object EvidenceRuntimeState {
    @Volatile
    var currentSessionId: Long? = null
        private set

    @Volatile
    var currentPackageName: String? = null
        private set

    @Volatile
    var currentSessionStartedAt: Long? = null
        private set

    @Volatile
    private var pendingScreenshotForSessionId: Long? = null

    fun setActiveSession(sessionId: Long?, packageName: String?, startedAt: Long? = null) {
        currentSessionId = sessionId
        currentPackageName = packageName
        currentSessionStartedAt = startedAt
    }

    fun clearActiveSession() {
        currentSessionId = null
        currentPackageName = null
        currentSessionStartedAt = null
    }

    fun requestScreenshot(sessionId: Long) {
        pendingScreenshotForSessionId = sessionId
    }

    fun consumePendingScreenshot(sessionId: Long): Boolean {
        val matches = pendingScreenshotForSessionId == sessionId
        if (matches) pendingScreenshotForSessionId = null
        return matches
    }
}
