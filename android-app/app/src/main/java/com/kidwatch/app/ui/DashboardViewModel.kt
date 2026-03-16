package com.kidwatch.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.kidwatch.app.repository.DashboardRepository
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val dashboardRepository: DashboardRepository
) : ViewModel() {

    private val _uiState = MutableLiveData(DashboardUiState())
    val uiState: LiveData<DashboardUiState> = _uiState

    fun loadSummary(familyId: String, deviceId: String) {
        _uiState.value = DashboardUiState(isLoading = true)
        viewModelScope.launch {
            runCatching {
                dashboardRepository.fetchTodaySummary(familyId, deviceId)
            }.onSuccess { summary ->
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    totalUsageMinutes = summary.totalMinutes,
                    topAppsText = summary.topAppsText,
                    deviceUsageText = summary.deviceUsageText
                )
            }.onFailure { error ->
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    errorMessage = mapDashboardErrorMessage(error)
                )
            }
        }
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
