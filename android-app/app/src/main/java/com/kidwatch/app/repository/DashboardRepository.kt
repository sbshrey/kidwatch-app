package com.kidwatch.app.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

class DashboardRepository(
    private val firestore: FirebaseFirestore
) {

    suspend fun fetchTodaySummary(familyId: String, deviceId: String): DashboardSummary {
        val dateKey = LocalDate.now().toString()
        val doc = firestore.collection("families")
            .document(familyId)
            .collection("devices")
            .document(deviceId)
            .collection("dailyUsage")
            .document(dateKey)
            .get()
            .await()

        if (!doc.exists()) return DashboardSummary.empty(deviceId)

        val appMinutes = doc.data.orEmpty()
            .filterKeys { it != "date" && it != "deviceId" }
            .mapValues { (_, value) -> (value as? Number)?.toLong() ?: 0L }

        val topApps = appMinutes.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { "${it.key}:${it.value}m" }

        return DashboardSummary(
            totalMinutes = appMinutes.values.sum().toInt(),
            topAppsText = topApps.joinToString(", ").ifBlank { "N/A" },
            deviceUsageText = deviceId
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
