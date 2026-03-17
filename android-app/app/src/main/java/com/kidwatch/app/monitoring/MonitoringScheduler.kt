package com.kidwatch.app.monitoring

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit

object MonitoringScheduler {

    private const val MONITORING_WORK_NAME = "kidwatch_monitoring_work"
    private const val SYNC_WORK_NAME = "kidwatch_sync_work"
    private const val MONITORING_NOW_WORK_NAME = "kidwatch_monitoring_now_work"
    private const val SYNC_NOW_WORK_NAME = "kidwatch_sync_now_work"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val monitoringRequest = PeriodicWorkRequestBuilder<MonitoringWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            MONITORING_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            monitoringRequest
        )

        workManager.enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }

    fun runMonitoringNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MONITORING_NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MonitoringWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
        )
    }

    fun runSyncNow(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val monitoringNow = OneTimeWorkRequestBuilder<MonitoringWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        val syncNow = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        workManager.beginUniqueWork(
            SYNC_NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            monitoringNow
        ).then(syncNow).enqueue()
    }
}
