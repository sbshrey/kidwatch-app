package com.kidwatch.app.monitoring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.UsageAccessHelper
import com.kidwatch.app.services.UsageStatsCollector
import java.time.LocalDate

class MonitoringWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!UsageAccessHelper.hasUsageAccess(applicationContext)) {
            return Result.success()
        }

        val repository = LocalMonitoringRepository(applicationContext)
        val collector = UsageStatsCollector(applicationContext, repository)
        val deviceInfo = DeviceInfoProvider(applicationContext).getDeviceInfo()

        collector.collectLastInterval(UsageStatsCollector.DEFAULT_INTERVAL_MS)

        val dateKey = LocalDate.now().toString()
        val dayStart = LocalDate.now().atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        val appMinutes = repository.aggregateDailyUsage(dayStart, dayEnd)
        repository.enqueueDailyUsageSummary(dateKey, deviceInfo.deviceId, appMinutes)

        val (topChannels, topVideos) = repository.aggregateContentSummary(dayStart, dayEnd)
        repository.enqueueContentSummary(dateKey, deviceInfo.deviceId, topChannels, topVideos)

        return Result.success()
    }
}
