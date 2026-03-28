package com.kidwatch.app.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.kidwatch.app.services.AppBuildInfoProvider
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.TesterProfileStore

class AnalyticsTracker(
    context: Context
) {

    private val appContext = context.applicationContext
    private val analytics by lazy { FirebaseAnalytics.getInstance(appContext) }
    private val testerProfileStore = TesterProfileStore(appContext)
    private val deviceInfoProvider = DeviceInfoProvider(appContext)
    private val buildInfoProvider = AppBuildInfoProvider(appContext)

    fun noteAppOpen() {
        val result = testerProfileStore.noteAppOpen()
        if (result.isFirstOpen) {
            logEvent("app_first_open")
        }
        if (result.shouldLogOpen) {
            logEvent("app_open")
        }
    }

    fun logTesterProfileRegistered() {
        logEvent("tester_profile_registered")
    }

    fun markOnboardingCompletedIfNeeded() {
        if (testerProfileStore.markOnboardingCompleted() != null) {
            logEvent("onboarding_completed")
        }
    }

    fun markUsageAccessGrantedIfNeeded(granted: Boolean) {
        if (!granted) return
        if (testerProfileStore.markUsageAccessGranted() != null) {
            logEvent("usage_access_granted")
        }
    }

    fun markAccessibilityEnabledIfNeeded(enabled: Boolean) {
        if (!enabled) return
        if (testerProfileStore.markAccessibilityEnabled() != null) {
            logEvent("accessibility_enabled")
        }
    }

    fun markCameraGrantedIfNeeded(granted: Boolean) {
        if (!granted) return
        if (testerProfileStore.markCameraGranted() != null) {
            logEvent("camera_granted")
        }
    }

    fun markAutomaticEvidenceEnabledIfNeeded(enabled: Boolean) {
        if (!enabled) return
        if (testerProfileStore.markAutomaticEvidenceEnabled() != null) {
            logEvent("automatic_evidence_enabled")
        }
    }

    fun logTabViewed(tab: String) {
        logEvent("tab_viewed") {
            putString("tab", tab)
        }
    }

    fun logMonitoringPolicyChanged(
        packageName: String,
        trackSessions: Boolean,
        allowScreenshots: Boolean,
        allowFaceCapture: Boolean
    ) {
        logEvent("monitoring_policy_changed") {
            putString("package_name", packageName)
            putLong("track_sessions", if (trackSessions) 1L else 0L)
            putLong("allow_screens", if (allowScreenshots) 1L else 0L)
            putLong("allow_faces", if (allowFaceCapture) 1L else 0L)
        }
    }

    fun markFirstSessionRecordedIfNeeded(packageName: String) {
        if (testerProfileStore.markFirstSessionRecorded() != null) {
            logEvent("first_session_recorded") {
                putString("package_name", packageName)
            }
        }
    }

    fun markFirstScreenshotRecordedIfNeeded(packageName: String, triggerType: String) {
        if (testerProfileStore.markFirstScreenshotRecorded() != null) {
            logEvent("first_screenshot_recorded") {
                putString("package_name", packageName)
                putString("trigger_type", triggerType)
            }
        }
    }

    fun markFirstFaceObservationRecordedIfNeeded(packageName: String) {
        if (testerProfileStore.markFirstFaceRecorded() != null) {
            logEvent("first_face_observation_recorded") {
                putString("package_name", packageName)
            }
        }
    }

    fun logContentEventRecorded(packageName: String, hasCanonicalUrl: Boolean) {
        testerProfileStore.markFirstContentEventRecorded()
        logEvent("content_event_recorded") {
            putString("package_name", packageName)
            putLong("has_exact_link", if (hasCanonicalUrl) 1L else 0L)
        }
    }

    fun logSyncCompleted(processedCount: Int) {
        logEvent("sync_completed") {
            putLong("processed_count", processedCount.toLong())
        }
    }

    fun logSyncFailed(entityType: String?) {
        logEvent("sync_failed") {
            putString("entity_type", entityType ?: "unknown")
        }
    }

    fun logPermissionGuideLaunched(requirement: String, step: String) {
        logEvent("permission_guide_launched") {
            putString("requirement", requirement)
            putString("step", step)
        }
    }

    fun logPermissionGuideReturned(requirement: String, step: String, granted: Boolean) {
        logEvent("permission_guide_returned") {
            putString("requirement", requirement)
            putString("step", step)
            putLong("granted", if (granted) 1L else 0L)
        }
    }

    private fun logEvent(
        name: String,
        configure: Bundle.() -> Unit = {}
    ) {
        runCatching {
            val bundle = Bundle().apply {
                appendCommonParams(this)
                configure()
            }
            val testerState = testerProfileStore.getState()
            analytics.setUserId(testerState.testerKey.takeIf { it.isNotBlank() })
            analytics.setUserProperty("test_cohort", testerState.testCohort)
            analytics.logEvent(name, bundle)
            Log.d(TAG, "Logged analytics event=$name")
        }.onFailure {
            Log.w(TAG, "Failed to log analytics event=$name", it)
        }
    }

    private fun appendCommonParams(bundle: Bundle) {
        val testerState = testerProfileStore.getState()
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val buildInfo = buildInfoProvider.get()
        bundle.putString("test_cohort", testerState.testCohort)
        bundle.putString("install_instance_id", testerState.installInstanceId)
        bundle.putString("device_id", deviceInfo.deviceId)
        if (testerState.testerKey.isNotBlank()) {
            bundle.putString("tester_key", testerState.testerKey)
        }
        bundle.putString("version_name", buildInfo.versionName)
        bundle.putLong("version_code", buildInfo.versionCode)
        bundle.putString("build_type", buildInfo.buildType)
    }

    companion object {
        private const val TAG = "AnalyticsTracker"
    }
}
