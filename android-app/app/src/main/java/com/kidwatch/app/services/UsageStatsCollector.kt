package com.kidwatch.app.services

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsageStatsCollector(
    private val context: Context,
    private val repository: LocalMonitoringRepository
) {

    suspend fun collectLastInterval(intervalMs: Long): Int = withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis()
        val start = end - intervalMs
        val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageManager.queryEvents(start, end)
        val event = UsageEvents.Event()
        val activeSessions = mutableMapOf<String, Long>()
        var stored = 0

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName.orEmpty()
            if (packageName.isBlank() || packageName == context.packageName) continue
            val timestamp = event.timeStamp
            when {
                isForegroundEvent(event.eventType) -> {
                    activeSessions[packageName] = timestamp
                }
                isBackgroundEvent(event.eventType) -> {
                    val startedAt = activeSessions.remove(packageName) ?: continue
                    val startTime = maxOf(start, startedAt)
                    val endTime = minOf(end, timestamp)
                    if (endTime <= startTime) continue
                    repository.saveAppUsage(
                        appPackage = packageName,
                        startTime = startTime,
                        endTime = endTime,
                        duration = endTime - startTime
                    )
                    stored += 1
                }
            }
        }

        // Close sessions that are still active at interval end.
        activeSessions.forEach { (packageName, startedAt) ->
            val startTime = maxOf(start, startedAt)
            if (end <= startTime) return@forEach
            repository.saveAppUsage(
                appPackage = packageName,
                startTime = startTime,
                endTime = end,
                duration = end - startTime
            )
            stored += 1
        }
        stored
    }

    private fun isForegroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            eventType == UsageEvents.Event.ACTIVITY_RESUMED
    }

    private fun isBackgroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
            eventType == UsageEvents.Event.ACTIVITY_STOPPED
    }

    companion object {
        // WorkManager periodic work minimum interval is 15 minutes.
        const val DEFAULT_INTERVAL_MS: Long = 15 * 60 * 1000L
    }
}
