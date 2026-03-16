package com.kidwatch.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.kidwatch.app.insights.AppCatalogMapper
import com.kidwatch.app.repository.DashboardRepository
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class DashboardViewModel(
    private val dashboardRepository: DashboardRepository,
    private val localMonitoringRepository: LocalMonitoringRepository
) : ViewModel() {

    private val _uiState = MutableLiveData(DashboardUiState())
    val uiState: LiveData<DashboardUiState> = _uiState

    fun loadSummary(familyId: String, deviceId: String) {
        _uiState.value = DashboardUiState(isLoading = true)
        viewModelScope.launch {
            val localSummary = runCatching {
                loadLocalSummary(deviceId)
            }.getOrNull()

            runCatching {
                dashboardRepository.fetchTodaySummary(familyId)
            }.onSuccess { remoteSummary ->
                val summary = if (remoteSummary.totalMinutes <= 0 && remoteSummary.topAppsText == "N/A" && localSummary != null) {
                    localSummary
                } else {
                    remoteSummary
                }
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    totalUsageMinutes = summary.totalMinutes,
                    topAppsText = summary.topAppsText,
                    deviceUsageText = summary.deviceUsageText,
                    lastUpdatedAtMillis = System.currentTimeMillis()
                )
            }.onFailure { error ->
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    totalUsageMinutes = localSummary?.totalMinutes ?: 0,
                    topAppsText = localSummary?.topAppsText ?: "",
                    deviceUsageText = localSummary?.deviceUsageText ?: "",
                    lastUpdatedAtMillis = if (localSummary != null) System.currentTimeMillis() else null,
                    errorMessage = if (localSummary != null) {
                        "Showing local usage data. Cloud sync pending."
                    } else {
                        mapDashboardErrorMessage(error)
                    }
                )
            }
        }
    }

    private suspend fun loadLocalSummary(deviceId: String): DashboardRepository.DashboardSummary {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now()
            .atStartOfDay()
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val appMinutes = localMonitoringRepository.aggregateDailyUsage(dayStart, now)
        val topAppsText = AppCatalogMapper.toDisplaySummary(appMinutes, topLimit = 3)

        return DashboardRepository.DashboardSummary(
            totalMinutes = appMinutes.values.sum().toInt(),
            topAppsText = topAppsText,
            deviceUsageText = "$deviceId (local)"
        )
    }

    private fun mapDashboardErrorMessage(error: Throwable): String {
        val rawMessage = error.message.orEmpty()
        val isPermissionDeniedError =
            (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
                rawMessage.contains("permission denied", ignoreCase = true)
        val isOfflineFirestoreError =
            (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.UNAVAILABLE) ||
                rawMessage.contains("client is offline", ignoreCase = true)

        return when {
            isPermissionDeniedError ->
                "Access denied. Please sign in with an account that has Firestore permission."
            isOfflineFirestoreError ->
                "No internet. Showing cached data if available."
            else -> rawMessage.ifBlank { "Dashboard unavailable." }
        }
    }
}
