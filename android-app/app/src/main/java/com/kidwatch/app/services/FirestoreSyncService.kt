package com.kidwatch.app.services

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class FirestoreSyncService(
    private val firestore: FirebaseFirestore
) {

    suspend fun uploadDailyUsage(
        familyId: String,
        deviceId: String,
        dateKey: String,
        appMinutes: Map<String, Long>
    ) {
        val doc = firestore.collection("families")
            .document(familyId)
            .collection("devices")
            .document(deviceId)
            .collection("dailyUsage")
            .document(dateKey)

        val payload = mutableMapOf<String, Any>(
            "date" to dateKey,
            "deviceId" to deviceId
        )
        appMinutes.forEach { (pkg, mins) ->
            val key = sanitizeKey(pkg)
            payload[key] = mins
        }
        doc.set(payload, SetOptions.merge()).await()
    }

    suspend fun uploadContentSummary(
        familyId: String,
        deviceId: String,
        dateKey: String,
        topChannels: Map<String, Int>,
        topVideos: Map<String, Int>
    ) {
        val doc = firestore.collection("families")
            .document(familyId)
            .collection("devices")
            .document(deviceId)
            .collection("contentSummary")
            .document(dateKey)

        val payload = mapOf(
            "date" to dateKey,
            "deviceId" to deviceId,
            "topChannels" to topChannels,
            "topVideos" to topVideos
        )
        doc.set(payload, SetOptions.merge()).await()
    }

    suspend fun uploadContentAnalysis(
        familyId: String,
        deviceId: String,
        dateKey: String,
        assessments: List<ContentAssessment>
    ) {
        val doc = firestore.collection("families")
            .document(familyId)
            .collection("devices")
            .document(deviceId)
            .collection("contentAnalysis")
            .document(dateKey)

        val channelsMap = assessments.associate { assessment ->
            assessment.channel to mapOf(
                "label" to assessment.label,
                "reason" to assessment.reason
            )
        }

        val payload = mapOf(
            "date" to dateKey,
            "deviceId" to deviceId,
            "channels" to channelsMap
        )
        doc.set(payload, SetOptions.merge()).await()
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
                reason = item.optString("reason", "")
            )
        }
        return ContentAnalysisPayload(date, deviceId, assessments)
    }

    private fun sanitizeKey(raw: String): String = raw.replace(".", "_")

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

    data class ContentAssessment(
        val channel: String,
        val label: String,
        val reason: String
    )
}
