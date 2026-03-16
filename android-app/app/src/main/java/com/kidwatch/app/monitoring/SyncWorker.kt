package com.kidwatch.app.monitoring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.FirestoreSyncService

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val repository = LocalMonitoringRepository(applicationContext)
        val syncService = FirestoreSyncService(FirebaseFirestore.getInstance())
        val pending = repository.getPendingSync()

        pending.forEach { item ->
            runCatching {
                when (item.entityType) {
                    "daily_usage" -> {
                        val payload = syncService.parseDailyUsagePayload(item.payloadJson)
                        syncService.uploadDailyUsage(
                            familyId = userId,
                            deviceId = payload.deviceId,
                            dateKey = payload.dateKey,
                            appMinutes = payload.appMinutes
                        )
                    }

                    "content_summary" -> {
                        val payload = syncService.parseContentSummaryPayload(item.payloadJson)
                        syncService.uploadContentSummary(
                            familyId = userId,
                            deviceId = payload.deviceId,
                            dateKey = payload.dateKey,
                            topChannels = payload.topChannels,
                            topVideos = payload.topVideos
                        )
                    }
                }
                repository.markSyncDone(item.id)
            }.onFailure {
                return Result.retry()
            }
        }
        return Result.success()
    }
}
