package com.kidwatch.app.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YouTubeAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastEventAtMs: Long = 0L
    private var lastSignature: String = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != YOUTUBE_PACKAGE) return
        if (!TRACKED_EVENT_TYPES.contains(event.eventType)) return

        val textParts = event.text.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        if (textParts.isEmpty()) return

        val title = textParts.firstOrNull().orEmpty()
        val channel = textParts.drop(1).firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val signature = "${title.lowercase()}|${channel.lowercase()}"
        if (signature == lastSignature && now - lastEventAtMs < EVENT_THROTTLE_MS) return
        if (now - lastEventAtMs < EVENT_THROTTLE_MS / 2) return
        lastSignature = signature
        lastEventAtMs = now

        val repository = LocalMonitoringRepository(applicationContext)
        serviceScope.launch {
            repository.saveVideoEvent(
                title = title,
                channel = channel,
                timestamp = now
            )
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val EVENT_THROTTLE_MS = 20_000L
        private val TRACKED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )
    }
}
