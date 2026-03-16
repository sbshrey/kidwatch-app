package com.kidwatch.app.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.insights.AppCatalogMapper
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

class DashboardRepository(
    private val firestore: FirebaseFirestore
) {

    suspend fun fetchTodaySummary(familyId: String): DashboardSummary {
        val dateKey = LocalDate.now().toString()
        val devices = firestore.collection("families")
            .document(familyId)
            .collection("devices")
            .get()
            .await()

        if (devices.isEmpty) return DashboardSummary.empty("No devices")

        val combined = mutableMapOf<String, Long>()
        var devicesWithData = 0
        for (deviceDoc in devices.documents) {
            val usageDoc = firestore.collection("families")
                .document(familyId)
                .collection("devices")
                .document(deviceDoc.id)
                .collection("dailyUsage")
                .document(dateKey)
                .get()
                .await()
            if (!usageDoc.exists()) continue
            devicesWithData += 1
            val appMinutes = usageDoc.data.orEmpty()
                .filterKeys { it != "date" && it != "deviceId" }
                .mapValues { (_, value) -> (value as? Number)?.toLong() ?: 0L }
            appMinutes.forEach { (app, minutes) ->
                combined[app] = (combined[app] ?: 0L) + minutes
            }
        }

        if (combined.isEmpty()) return DashboardSummary.empty("${devices.size()} devices")

        val topAppsText = AppCatalogMapper.toDisplaySummary(combined, topLimit = 3)

        return DashboardSummary(
            totalMinutes = combined.values.sum().toInt(),
            topAppsText = topAppsText,
            deviceUsageText = "$devicesWithData/${devices.size()} devices synced"
        )
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
}
