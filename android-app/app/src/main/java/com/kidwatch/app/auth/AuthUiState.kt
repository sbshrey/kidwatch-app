package com.kidwatch.app.auth

data class AuthUiState(
    val isLoading: Boolean = false,
    val isSignedIn: Boolean = false,
    val userId: String = "",
    val userDisplayName: String = "",
    val errorMessage: String? = null
)
