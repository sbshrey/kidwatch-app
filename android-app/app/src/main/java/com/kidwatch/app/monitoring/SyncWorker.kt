package com.kidwatch.app.monitoring

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.ProfilePreferences
import com.kidwatch.app.analytics.AnalyticsTracker
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.AccessibilityServiceState
import com.kidwatch.app.services.AppBuildInfoProvider
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.DeviceLinkingService
import com.kidwatch.app.services.EvidencePreferences
import com.kidwatch.app.services.FirestoreSyncService
import com.kidwatch.app.services.MediaProjectionPermissionStore
import com.kidwatch.app.services.TesterProfileStore
import com.kidwatch.app.services.UsageAccessHelper

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = LocalMonitoringRepository(applicationContext)
        val firestore = FirebaseFirestore.getInstance()
        val syncService = FirestoreSyncService(firestore)
        val testerProfileStore = TesterProfileStore(applicationContext)
        val analyticsTracker = AnalyticsTracker(applicationContext)
        if (!testerProfileStore.hasCompleteTesterProfile()) {
            return Result.success()
        }

        val testerState = testerProfileStore.getState()
        val deviceInfo = DeviceInfoProvider(applicationContext).getDeviceInfo()
        val buildInfo = AppBuildInfoProvider(applicationContext).get()
        val preferredName = applicationContext
            .getSharedPreferences(ProfilePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ProfilePreferences.PREF_PREFERRED_NAME, "")
            .orEmpty()
            .ifBlank { deviceInfo.deviceName }
        val linkedDeviceIds = runCatching {
            DeviceLinkingService(firestore)
                .fetchLinkedDevices(deviceInfo.deviceId)
                .map { it.remoteDeviceId }
        }.getOrDefault(emptyList())
        val capabilityState = EvidencePreferences.getCapabilityState(applicationContext)
        val metadata = FirestoreSyncService.TesterSyncMetadata(
            testCohort = testerState.testCohort,
            testerName = testerState.testerName,
            testerKey = testerState.testerKey,
            phoneLast4 = testerState.phoneLast4,
            installInstanceId = testerState.installInstanceId,
            versionName = buildInfo.versionName,
            versionCode = buildInfo.versionCode,
            buildType = buildInfo.buildType
        )
        runCatching {
            syncService.upsertTesterState(
                FirestoreSyncService.TesterRegistryPayload(
                    metadata = metadata,
                    deviceId = deviceInfo.deviceId,
                    deviceName = deviceInfo.deviceName,
                    model = deviceInfo.model,
                    preferredName = preferredName,
                    firstOpenedAt = testerState.firstOpenedAt,
                    lastOpenedAt = testerState.lastAppOpenAt,
                    testerProfileRegisteredAt = testerState.testerProfileRegisteredAt,
                    onboardingCompletedAt = testerState.onboardingCompletedAt,
                    usageAccessGrantedAt = testerState.usageAccessGrantedAt,
                    accessibilityEnabledAt = testerState.accessibilityEnabledAt,
                    cameraGrantedAt = testerState.cameraGrantedAt,
                    automaticEvidenceEnabledAt = testerState.automaticEvidenceEnabledAt,
                    firstSessionRecordedAt = testerState.firstSessionRecordedAt,
                    firstScreenshotRecordedAt = testerState.firstScreenshotRecordedAt,
                    firstFaceRecordedAt = testerState.firstFaceRecordedAt,
                    firstContentEventRecordedAt = testerState.firstContentEventRecordedAt,
                    linkedDeviceIds = linkedDeviceIds,
                    permissions = FirestoreSyncService.PermissionSnapshot(
                        usageAccess = UsageAccessHelper.hasUsageAccess(applicationContext),
                        cameraAccess = hasCameraPermission(),
                        accessibilityAccess = AccessibilityServiceState.isContentCaptureEnabled(applicationContext),
                        screenshotConsent = MediaProjectionPermissionStore.hasGrant()
                    ),
                    evidence = FirestoreSyncService.EvidenceSnapshot(
                        automaticEvidenceEnabled = capabilityState.automaticEvidenceEnabled,
                        cameraCaptureEnabled = capabilityState.cameraCaptureEnabled,
                        screenCaptureConfigured = capabilityState.screenCaptureConfigured,
                        screenCaptureCurrentlyAvailable = capabilityState.screenCaptureCurrentlyAvailable
                    )
                )
            )
        }.onFailure {
            Log.e(TAG, "Failed to sync tester state", it)
            analyticsTracker.logSyncFailed("tester_state")
            return Result.retry()
        }
        Log.i(TAG, "Synced tester state for ${testerState.testerKey} on ${deviceInfo.deviceId}")

        val pending = repository.getPendingSync()
        var processedCount = 0

        pending.forEach { item ->
            runCatching {
                when (item.entityType) {
                    "daily_usage" -> {
                        val payload = syncService.parseDailyUsagePayload(item.payloadJson)
                        syncService.uploadDailyUsage(
                            deviceId = payload.deviceId,
                            dateKey = payload.dateKey,
                            appMinutes = payload.appMinutes,
                            metadata = metadata
                        )
                    }

                    "content_summary" -> {
                        val payload = syncService.parseContentSummaryPayload(item.payloadJson)
                        syncService.uploadContentSummary(
                            deviceId = payload.deviceId,
                            dateKey = payload.dateKey,
                            topChannels = payload.topChannels,
                            topVideos = payload.topVideos,
                            metadata = metadata
                        )
                    }

                    "content_analysis" -> {
                        val payload = syncService.parseContentAnalysisPayload(item.payloadJson)
                        syncService.uploadContentAnalysis(
                            deviceId = payload.deviceId,
                            dateKey = payload.dateKey,
                            assessments = payload.assessments,
                            metadata = metadata
                        )
                    }

                    "content_event" -> {
                        val payload = syncService.parseContentEventPayload(item.payloadJson)
                        syncService.uploadContentEvent(payload, metadata)
                    }
                }
                repository.markSyncDone(item.id)
                processedCount += 1
                Log.d(TAG, "Synced queue item type=${item.entityType} id=${item.id}")
            }.onFailure {
                Log.e(TAG, "Failed to sync queue item type=${item.entityType} id=${item.id}", it)
                analyticsTracker.logSyncFailed(item.entityType)
                return Result.retry()
            }
        }
        analyticsTracker.logSyncCompleted(processedCount)
        Log.i(TAG, "Sync worker completed processedCount=$processedCount")
        return Result.success()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}
