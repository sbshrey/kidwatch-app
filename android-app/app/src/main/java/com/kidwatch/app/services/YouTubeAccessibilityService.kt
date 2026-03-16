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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != YOUTUBE_PACKAGE) return

        val textParts = event.text?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (textParts.isEmpty()) return

        val title = textParts.firstOrNull().orEmpty()
        val channel = textParts.drop(1).firstOrNull().orEmpty()

        val repository = LocalMonitoringRepository(applicationContext)
        serviceScope.launch {
            repository.saveVideoEvent(
                title = title,
                channel = channel,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
