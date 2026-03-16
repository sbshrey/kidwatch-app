package com.kidwatch.app.ui

data class DashboardUiState(
    val isLoading: Boolean = true,
    val totalUsageMinutes: Int = 0,
    val topAppsText: String = "",
    val deviceUsageText: String = "",
    val errorMessage: String? = null
)
