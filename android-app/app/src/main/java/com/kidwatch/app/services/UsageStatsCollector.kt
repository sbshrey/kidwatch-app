package com.kidwatch.app.services

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
        val stats = usageManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)

        var inserted = 0
        stats.forEach { item ->
            val usedMs = item.totalTimeInForeground
            if (usedMs <= 0L) return@forEach
            val startTime = maxOf(start, end - usedMs)
            val endTime = minOf(end, startTime + usedMs)
            if (endTime <= startTime) return@forEach

            repository.saveAppUsage(
                appPackage = item.packageName,
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime
            )
            inserted += 1
        }
        inserted
    }

    companion object {
        // WorkManager periodic work minimum interval is 15 minutes.
        const val DEFAULT_INTERVAL_MS: Long = 15 * 60 * 1000L
    }
}
