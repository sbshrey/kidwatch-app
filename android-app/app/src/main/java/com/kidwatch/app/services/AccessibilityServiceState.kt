package com.kidwatch.app.services

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityServiceState {
    fun isContentCaptureEnabled(context: Context): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!accessibilityEnabled) return false

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val targetService = ComponentName(context, YouTubeAccessibilityService::class.java).flattenToString()
        return enabledServices.split(':').any { it.equals(targetService, ignoreCase = true) }
    }

    fun isYouTubeMonitorEnabled(context: Context): Boolean = isContentCaptureEnabled(context)
}
