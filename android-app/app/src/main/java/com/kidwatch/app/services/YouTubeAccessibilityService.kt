package com.kidwatch.app.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

        var textParts = event.text.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        if (textParts.isEmpty()) {
            textParts = extractTextFromWindow()
        }
        if (textParts.isEmpty()) return

        val title = textParts.firstOrNull().orEmpty()
        val channel = textParts.drop(1).firstOrNull().orEmpty()
        if (title.length < 3) return

        val now = System.currentTimeMillis()
        val signature = "${title.lowercase()}|${channel.lowercase()}"
        if (signature == lastSignature && now - lastEventAtMs < EVENT_THROTTLE_MS) return
        if (now - lastEventAtMs < EVENT_THROTTLE_MS / 2) return
        lastSignature = signature
        lastEventAtMs = now

        startFaceCaptureServiceIfNeeded()

        val repository = LocalMonitoringRepository(applicationContext)
        val faceDetected = FaceCaptureState.wasFaceSeenRecently()
        serviceScope.launch {
            repository.saveVideoEvent(
                title = title,
                channel = channel,
                timestamp = now,
                faceDetected = faceDetected
            )
        }
    }

    override fun onInterrupt() = Unit

    private fun startFaceCaptureServiceIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, FaceCaptureService::class.java))
            } else {
                startService(Intent(this, FaceCaptureService::class.java))
            }
        } catch (_: Exception) {}
    }

    private fun extractTextFromWindow(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableSetOf<String>()
        collectTextRecursive(root, texts)
        return texts.filter { it.length >= 3 }.distinct().take(5)
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return
        try {
            node.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { out.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { out.add(it) }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                collectTextRecursive(child, out)
                child?.recycle()
            }
        } catch (_: Exception) {}
    }

    private companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val EVENT_THROTTLE_MS = 15_000L
        private val TRACKED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )
    }
}
