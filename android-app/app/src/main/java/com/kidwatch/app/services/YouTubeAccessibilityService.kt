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
    private val lastCaptureByPackage = mutableMapOf<String, RecentCapture>()
    private val repository by lazy { LocalMonitoringRepository(applicationContext) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (!AccessibilityCaptureCatalog.isSupportedPackage(packageName)) return
        if (!TRACKED_EVENT_TYPES.contains(event.eventType)) return

        val eventTextParts = event.text.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        val windowTextParts = extractTextFromWindow()
        val capturedContent = AccessibilityCaptureCatalog.parse(
            packageName = packageName,
            eventTextParts = eventTextParts,
            windowTextParts = windowTextParts
        ) ?: return

        val now = System.currentTimeMillis()
        val signatureBase = buildString {
            append(packageName)
            append('|')
            append(capturedContent.title.lowercase())
            append('|')
            append(capturedContent.channel.lowercase())
        }
        val linkScore = capturedContent.linkScore()
        val previousCapture = lastCaptureByPackage[packageName]
        if (
            previousCapture != null &&
            previousCapture.signatureBase == signatureBase &&
            now - previousCapture.timestamp < EVENT_THROTTLE_MS &&
            linkScore <= previousCapture.linkScore
        ) {
            return
        }
        if (
            previousCapture != null &&
            now - previousCapture.timestamp < EVENT_THROTTLE_MS / 2 &&
            linkScore <= previousCapture.linkScore
        ) {
            return
        }
        lastCaptureByPackage[packageName] = RecentCapture(
            signatureBase = signatureBase,
            linkScore = linkScore,
            timestamp = now
        )

        serviceScope.launch {
            val capturePolicy = repository.getCapturePolicy(packageName)
            if (!capturePolicy.trackSessions) return@launch

            val faceDetected = FaceCaptureState.wasFaceSeenRecently()
            val resolvedSessionId = repository.saveVideoEvent(
                packageName = packageName,
                title = capturedContent.title,
                channel = capturedContent.channel,
                timestamp = now,
                canonicalUrl = capturedContent.canonicalUrl,
                faceDetected = faceDetected,
                sessionId = EvidenceRuntimeState.currentSessionId
            ) ?: EvidenceRuntimeState.currentSessionId
            resolvedSessionId?.let(EvidenceRuntimeState::requestScreenshot)
        }
    }

    override fun onInterrupt() = Unit

    private fun extractTextFromWindow(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableSetOf<String>()
        collectTextRecursive(root, texts)
        return texts.filter { it.length >= 3 }.distinct().take(12)
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
        private const val EVENT_THROTTLE_MS = 15_000L
        private val TRACKED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        )
    }

    private data class RecentCapture(
        val signatureBase: String,
        val linkScore: Int,
        val timestamp: Long
    )

    private fun AccessibilityCaptureCatalog.CapturedContent.linkScore(): Int {
        return when (linkKind) {
            "exact" -> 1
            else -> 0
        }
    }
}
