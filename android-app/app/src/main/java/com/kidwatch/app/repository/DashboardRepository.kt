package com.kidwatch.app.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.insights.AppCatalogMapper
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

class DashboardRepository(
    private val firestore: FirebaseFirestore
) {

    suspend fun fetchOwnTodaySummary(deviceId: String): DashboardSummary {
        val dateKey = LocalDate.now().toString()
        val usageDoc = firestore.collection("deviceProfiles")
            .document(deviceId)
            .collection("dailyUsage")
            .document(dateKey)
            .get()
            .await()

        if (!usageDoc.exists()) return DashboardSummary.empty("My device")
        val combined = usageDoc.data.orEmpty()
            .filterKeys { it != "date" && it != "deviceId" }
            .mapValues { (key, value) ->
                val pkgName = key.replace("_", ".")
                pkgName to ((value as? Number)?.toLong() ?: 0L)
            }
            .values
            .associate { it.first to it.second }

        val topAppsText = AppCatalogMapper.toDisplaySummary(combined, topLimit = 3)

        return DashboardSummary(
            totalMinutes = combined.values.sum().toInt(),
            topAppsText = topAppsText,
            deviceUsageText = "My phone"
        )
    }

    suspend fun fetchFamilyDevicesSummary(localDeviceId: String): List<FamilyDeviceUsageSummary> {
        val dateKey = LocalDate.now().toString()
        val linksSnapshot = firestore.collection("deviceProfiles")
            .document(localDeviceId)
            .collection("links")
            .get()
            .await()
        val edgeSnapshot = firestore.collection("deviceLinkEdges")
            .whereEqualTo("remoteDeviceId", localDeviceId)
            .get()
            .await()

        val linkedIds = mutableSetOf(localDeviceId)
        val linkedMeta = mutableMapOf<String, Map<String, Any>>()
        for (doc in linksSnapshot.documents) {
            linkedIds += doc.id
            linkedMeta[doc.id] = doc.data.orEmpty()
        }
        for (doc in edgeSnapshot.documents) {
            val inverseLocalId = doc.getString("localDeviceId").orEmpty()
            if (inverseLocalId.isNotBlank() && inverseLocalId != localDeviceId) {
                linkedIds += inverseLocalId
            }
        }

        return linkedIds.map { deviceId ->
            val profile = firestore.collection("deviceProfiles").document(deviceId).get().await()
            val usageDoc = firestore.collection("deviceProfiles")
                .document(deviceId)
                .collection("dailyUsage")
                .document(dateKey)
                .get()
                .await()

            val appMinutes = usageDoc.data.orEmpty()
                .filterKeys { it != "date" && it != "deviceId" }
                .mapValues { (key, value) ->
                    val pkgName = key.replace("_", ".")
                    pkgName to ((value as? Number)?.toLong() ?: 0L)
                }
                .values
                .associate { it.first to it.second }
            val usageSyncedAtMillis = usageDoc.getTimestamp("syncedAt")?.toDate()?.time
            val profileUpdatedAtMillis = profile.getTimestamp("updatedAt")?.toDate()?.time

            val linkedDisplayName = linkedMeta[deviceId]?.get("customName") as? String
            val profilePreferredName = profile.getString("preferredName")
            val profileDeviceName = profile.getString("deviceName")
            val defaultName = if (deviceId == localDeviceId) "My phone" else "Family device"

            FamilyDeviceUsageSummary(
                deviceId = deviceId,
                displayName = linkedDisplayName?.ifBlank { null }
                    ?: profilePreferredName?.ifBlank { null }
                    ?: profileDeviceName?.ifBlank { null }
                    ?: defaultName,
                isLocalDevice = deviceId == localDeviceId,
                totalMinutes = appMinutes.values.sum().toInt(),
                topAppsText = AppCatalogMapper.toDisplaySummary(appMinutes, topLimit = 3),
                lastSyncedAtMillis = usageSyncedAtMillis ?: profileUpdatedAtMillis
            )
        }.sortedByDescending { it.totalMinutes }
    }

    data class DashboardSummary(
        val totalMinutes: Int,
        val topAppsText: String,
        val deviceUsageText: String
    ) {
        companion object {
            fun empty(deviceId: String): DashboardSummary =
                DashboardSummary(0, "N/A", deviceId)
        }
    }

    data class FamilyDeviceUsageSummary(
        val deviceId: String,
        val displayName: String,
        val isLocalDevice: Boolean,
        val totalMinutes: Int,
        val topAppsText: String,
        val lastSyncedAtMillis: Long?
    )
}
