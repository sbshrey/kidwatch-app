package com.kidwatch.app.services

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class FirestoreSyncService(
    private val firestore: FirebaseFirestore
) {

    suspend fun upsertTesterState(
        payload: TesterRegistryPayload
    ) {
        val testerDoc = firestore.collection(TEST_COHORTS_COLLECTION)
            .document(payload.metadata.testCohort)
            .collection(TESTERS_COLLECTION)
            .document(payload.metadata.testerKey)
        val installDoc = testerDoc.collection(INSTALLS_COLLECTION)
            .document(payload.metadata.installInstanceId)
        val deviceProfileDoc = firestore.collection("deviceProfiles")
            .document(payload.deviceId)

        val permissionsMap = mapOf(
            "usageAccess" to payload.permissions.usageAccess,
            "cameraAccess" to payload.permissions.cameraAccess,
            "accessibilityAccess" to payload.permissions.accessibilityAccess,
            "screenshotConsent" to payload.permissions.screenshotConsent
        )
        val evidenceMap = mapOf(
            "automaticEvidenceEnabled" to payload.evidence.automaticEvidenceEnabled,
            "cameraCaptureEnabled" to payload.evidence.cameraCaptureEnabled,
            "screenCaptureConfigured" to payload.evidence.screenCaptureConfigured,
            "screenCaptureCurrentlyAvailable" to payload.evidence.screenCaptureCurrentlyAvailable
        )
        val milestoneMap = mutableMapOf<String, Any?>(
            "testerProfileRegisteredAt" to payload.testerProfileRegisteredAt,
            "onboardingCompletedAt" to payload.onboardingCompletedAt,
            "usageAccessGrantedAt" to payload.usageAccessGrantedAt,
            "accessibilityEnabledAt" to payload.accessibilityEnabledAt,
            "cameraGrantedAt" to payload.cameraGrantedAt,
            "automaticEvidenceEnabledAt" to payload.automaticEvidenceEnabledAt,
            "firstSessionRecordedAt" to payload.firstSessionRecordedAt,
            "firstScreenshotRecordedAt" to payload.firstScreenshotRecordedAt,
            "firstFaceRecordedAt" to payload.firstFaceRecordedAt,
            "firstContentEventRecordedAt" to payload.firstContentEventRecordedAt
        ).filterValues { it != null }

        val testerSummaryBody = mutableMapOf<String, Any>(
            "testerName" to payload.metadata.testerName,
            "testerKey" to payload.metadata.testerKey,
            "phoneLast4" to payload.metadata.phoneLast4,
            "testCohort" to payload.metadata.testCohort,
            "firstSeenAt" to payload.firstOpenedAt,
            "lastSeenAt" to payload.lastOpenedAt,
            "latestDeviceId" to payload.deviceId,
            "deviceName" to payload.deviceName,
            "model" to payload.model,
            "preferredName" to payload.preferredName,
            "latestVersionName" to payload.metadata.versionName,
            "latestVersionCode" to payload.metadata.versionCode,
            "buildType" to payload.metadata.buildType,
            "installInstanceId" to payload.metadata.installInstanceId,
            "onboardingCompleted" to (payload.onboardingCompletedAt != null),
            "linkedDeviceIds" to payload.linkedDeviceIds,
            "permissions" to permissionsMap,
            "evidence" to evidenceMap,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        testerDoc.set(testerSummaryBody, SetOptions.merge()).await()

        val installBody = mutableMapOf<String, Any>(
            "testerName" to payload.metadata.testerName,
            "testerKey" to payload.metadata.testerKey,
            "phoneLast4" to payload.metadata.phoneLast4,
            "testCohort" to payload.metadata.testCohort,
            "installInstanceId" to payload.metadata.installInstanceId,
            "deviceId" to payload.deviceId,
            "deviceName" to payload.deviceName,
            "model" to payload.model,
            "preferredName" to payload.preferredName,
            "firstOpenAt" to payload.firstOpenedAt,
            "lastOpenAt" to payload.lastOpenedAt,
            "versionName" to payload.metadata.versionName,
            "versionCode" to payload.metadata.versionCode,
            "buildType" to payload.metadata.buildType,
            "permissions" to permissionsMap,
            "evidence" to evidenceMap,
            "linkedDeviceIds" to payload.linkedDeviceIds,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        milestoneMap.forEach { (key, value) ->
            if (value != null) {
                installBody[key] = value
            }
        }
        installDoc.set(installBody, SetOptions.merge()).await()

        val deviceProfileBody = mutableMapOf<String, Any>(
            "deviceId" to payload.deviceId,
            "deviceName" to payload.deviceName,
            "model" to payload.model,
            "preferredName" to payload.preferredName,
            "testCohort" to payload.metadata.testCohort,
            "testerName" to payload.metadata.testerName,
            "testerKey" to payload.metadata.testerKey,
            "phoneLast4" to payload.metadata.phoneLast4,
            "installInstanceId" to payload.metadata.installInstanceId,
            "versionName" to payload.metadata.versionName,
            "versionCode" to payload.metadata.versionCode,
            "buildType" to payload.metadata.buildType,
            "linkedDeviceIds" to payload.linkedDeviceIds,
            "permissions" to permissionsMap,
            "evidence" to evidenceMap,
            "testerSetupCompleted" to (payload.onboardingCompletedAt != null),
            "testerLastSeenAt" to payload.lastOpenedAt,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        deviceProfileDoc.set(deviceProfileBody, SetOptions.merge()).await()
    }

    suspend fun uploadDailyUsage(
        deviceId: String,
        dateKey: String,
        appMinutes: Map<String, Long>,
        metadata: TesterSyncMetadata? = null
    ) {
        val doc = firestore.collection("deviceProfiles")
            .document(deviceId)
            .collection("dailyUsage")
            .document(dateKey)

        val payload = mutableMapOf<String, Any>(
            "date" to dateKey,
            "deviceId" to deviceId,
            "syncedAt" to FieldValue.serverTimestamp()
        )
        appMinutes.forEach { (pkg, mins) ->
            val key = sanitizeKey(pkg)
            payload[key] = mins
        }
        appendTesterMetadata(payload, metadata)
        doc.set(payload, SetOptions.merge()).await()
    }

    suspend fun uploadContentSummary(
        deviceId: String,
        dateKey: String,
        topChannels: Map<String, Int>,
        topVideos: Map<String, Int>,
        metadata: TesterSyncMetadata? = null
    ) {
        val doc = firestore.collection("deviceProfiles")
            .document(deviceId)
            .collection("contentSummary")
            .document(dateKey)

        val payload = mutableMapOf<String, Any>(
            "date" to dateKey,
            "deviceId" to deviceId,
            "topChannels" to topChannels,
            "topVideos" to topVideos
        )
        appendTesterMetadata(payload, metadata)
        doc.set(payload, SetOptions.merge()).await()
    }

    suspend fun uploadContentAnalysis(
        deviceId: String,
        dateKey: String,
        assessments: List<ContentAssessment>,
        metadata: TesterSyncMetadata? = null
    ) {
        val doc = firestore.collection("deviceProfiles")
            .document(deviceId)
            .collection("contentAnalysis")
            .document(dateKey)

        val channelsMap = assessments.associate { assessment ->
            assessment.channel to mapOf(
                "label" to assessment.label,
                "reason" to assessment.reason,
                "model" to assessment.model
            )
        }

        val payload = mutableMapOf<String, Any>(
            "date" to dateKey,
            "deviceId" to deviceId,
            "channels" to channelsMap
        )
        appendTesterMetadata(payload, metadata)
        doc.set(payload, SetOptions.merge()).await()
    }

    suspend fun uploadContentEvent(
        payload: ContentEventPayload,
        metadata: TesterSyncMetadata? = null
    ) {
        val doc = firestore.collection("deviceProfiles")
            .document(payload.deviceId)
            .collection("contentEvents")
            .document(payload.localEventId.toString())

        val body = mutableMapOf<String, Any>(
            "deviceId" to payload.deviceId,
            "localEventId" to payload.localEventId,
            "packageName" to payload.packageName,
            "title" to payload.title,
            "channel" to payload.channel,
            "timestamp" to payload.timestamp,
            "linkKind" to payload.linkKind,
            "linkSource" to payload.linkSource,
            "syncedAt" to FieldValue.serverTimestamp()
        )
        payload.sessionId?.let { body["sessionId"] = it }
        payload.canonicalUrl?.let { body["canonicalUrl"] = it }
        appendTesterMetadata(body, metadata)
        doc.set(body, SetOptions.merge()).await()
    }

    fun parseDailyUsagePayload(json: String): DailyUsagePayload {
        val root = JSONObject(json)
        val date = root.getString("date")
        val deviceId = root.getString("deviceId")
        val appMinutesObj = root.getJSONObject("appMinutes")
        val appMinutes = mutableMapOf<String, Long>()
        appMinutesObj.keys().forEach { key ->
            appMinutes[key] = appMinutesObj.optLong(key, 0L)
        }
        return DailyUsagePayload(date, deviceId, appMinutes)
    }

    fun parseContentSummaryPayload(json: String): ContentSummaryPayload {
        val root = JSONObject(json)
        val date = root.getString("date")
        val deviceId = root.getString("deviceId")
        val channels = mutableMapOf<String, Int>()
        val videos = mutableMapOf<String, Int>()

        root.getJSONObject("topChannels").keys().forEach { key ->
            channels[key] = root.getJSONObject("topChannels").optInt(key, 0)
        }
        root.getJSONObject("topVideos").keys().forEach { key ->
            videos[key] = root.getJSONObject("topVideos").optInt(key, 0)
        }
        return ContentSummaryPayload(date, deviceId, channels, videos)
    }

    fun parseContentAnalysisPayload(json: String): ContentAnalysisPayload {
        val root = JSONObject(json)
        val date = root.getString("date")
        val deviceId = root.getString("deviceId")
        val assessmentsArray = root.optJSONArray("assessments") ?: org.json.JSONArray()
        val assessments = mutableListOf<ContentAssessment>()
        for (index in 0 until assessmentsArray.length()) {
            val item = assessmentsArray.optJSONObject(index) ?: continue
            assessments += ContentAssessment(
                channel = item.optString("channel", "Unknown"),
                label = item.optString("label", "unknown"),
                reason = item.optString("reason", ""),
                model = item.optString("model", "unknown")
            )
        }
        return ContentAnalysisPayload(date, deviceId, assessments)
    }

    fun parseContentEventPayload(json: String): ContentEventPayload {
        val root = JSONObject(json)
        return ContentEventPayload(
            deviceId = root.getString("deviceId"),
            localEventId = root.getLong("localEventId"),
            sessionId = root.optLong("sessionId").takeIf { !root.isNull("sessionId") },
            packageName = root.getString("packageName"),
            title = root.optString("title", "Unknown"),
            channel = root.optString("channel", "Unknown"),
            timestamp = root.getLong("timestamp"),
            canonicalUrl = root.optString("canonicalUrl").takeIf { it.isNotBlank() && it != "null" },
            fallbackUrl = root.optString("fallbackUrl").takeIf { it.isNotBlank() && it != "null" },
            linkKind = root.optString("linkKind", "none"),
            linkSource = root.optString("linkSource", "none")
        )
    }

    private fun sanitizeKey(raw: String): String = raw.replace(".", "_")

    private fun appendTesterMetadata(
        payload: MutableMap<String, Any>,
        metadata: TesterSyncMetadata?
    ) {
        if (metadata == null) return
        payload["testCohort"] = metadata.testCohort
        payload["testerKey"] = metadata.testerKey
        payload["testerName"] = metadata.testerName
        payload["phoneLast4"] = metadata.phoneLast4
        payload["installInstanceId"] = metadata.installInstanceId
        payload["versionName"] = metadata.versionName
        payload["versionCode"] = metadata.versionCode
        payload["buildType"] = metadata.buildType
    }

    data class DailyUsagePayload(
        val dateKey: String,
        val deviceId: String,
        val appMinutes: Map<String, Long>
    )

    data class ContentSummaryPayload(
        val dateKey: String,
        val deviceId: String,
        val topChannels: Map<String, Int>,
        val topVideos: Map<String, Int>
    )

    data class ContentAnalysisPayload(
        val dateKey: String,
        val deviceId: String,
        val assessments: List<ContentAssessment>
    )

    data class ContentEventPayload(
        val deviceId: String,
        val localEventId: Long,
        val sessionId: Long?,
        val packageName: String,
        val title: String,
        val channel: String,
        val timestamp: Long,
        val canonicalUrl: String?,
        val fallbackUrl: String?,
        val linkKind: String,
        val linkSource: String
    )

    data class TesterSyncMetadata(
        val testCohort: String,
        val testerName: String,
        val testerKey: String,
        val phoneLast4: String,
        val installInstanceId: String,
        val versionName: String,
        val versionCode: Long,
        val buildType: String
    )

    data class TesterRegistryPayload(
        val metadata: TesterSyncMetadata,
        val deviceId: String,
        val deviceName: String,
        val model: String,
        val preferredName: String,
        val firstOpenedAt: Long,
        val lastOpenedAt: Long,
        val testerProfileRegisteredAt: Long?,
        val onboardingCompletedAt: Long?,
        val usageAccessGrantedAt: Long?,
        val accessibilityEnabledAt: Long?,
        val cameraGrantedAt: Long?,
        val automaticEvidenceEnabledAt: Long?,
        val firstSessionRecordedAt: Long?,
        val firstScreenshotRecordedAt: Long?,
        val firstFaceRecordedAt: Long?,
        val firstContentEventRecordedAt: Long?,
        val linkedDeviceIds: List<String>,
        val permissions: PermissionSnapshot,
        val evidence: EvidenceSnapshot
    )

    data class PermissionSnapshot(
        val usageAccess: Boolean,
        val cameraAccess: Boolean,
        val accessibilityAccess: Boolean,
        val screenshotConsent: Boolean
    )

    data class EvidenceSnapshot(
        val automaticEvidenceEnabled: Boolean,
        val cameraCaptureEnabled: Boolean,
        val screenCaptureConfigured: Boolean,
        val screenCaptureCurrentlyAvailable: Boolean
    )

    data class ContentAssessment(
        val channel: String,
        val label: String,
        val reason: String,
        val model: String
    )

    companion object {
        private const val TEST_COHORTS_COLLECTION = "testCohorts"
        private const val TESTERS_COLLECTION = "testers"
        private const val INSTALLS_COLLECTION = "installs"
    }
}
