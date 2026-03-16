package com.kidwatch.app.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.kidwatch.app.repository.AuthRepository
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.FirestoreDeviceService
import com.kidwatch.app.services.FirestoreFamilyService
import com.kidwatch.app.services.FirestoreUserService
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val firestoreUserService: FirestoreUserService,
    private val firestoreFamilyService: FirestoreFamilyService,
    private val firestoreDeviceService: FirestoreDeviceService,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val localMonitoringRepository: LocalMonitoringRepository
) : ViewModel() {

    private val _uiState = MutableLiveData(AuthUiState())
    val uiState: LiveData<AuthUiState> = _uiState

    init {
        refreshAuthState()
    }

    fun signInWithGoogle(idToken: String) {
        _uiState.value = _uiState.value?.copy(
            isLoading = true,
            errorMessage = null
        )

        viewModelScope.launch {
            runCatching {
                val firebaseUser = authRepository.signInWithGoogleIdToken(idToken)
                firestoreUserService.upsertUser(firebaseUser)
                firestoreFamilyService.ensureFamilyAndMembership(firebaseUser)

                val deviceInfo = deviceInfoProvider.getDeviceInfo()
                firestoreDeviceService.ensureDeviceRegistered(
                    deviceInfo = deviceInfo,
                    ownerUserId = firebaseUser.uid
                )

                localMonitoringRepository.initializeDatabase()

                firebaseUser
            }.onSuccess { user ->
                _uiState.value = AuthUiState(
                    isLoading = false,
                    isSignedIn = true,
                    userId = user.uid,
                    userDisplayName = user.displayName ?: user.email.orEmpty(),
                    errorMessage = null
                )
            }.onFailure { throwable ->
                _uiState.value = AuthUiState(
                    isLoading = false,
                    isSignedIn = false,
                    userDisplayName = "",
                    errorMessage = mapAuthErrorMessage(throwable)
                )
            }
        }
    }

    fun setAuthError(message: String) {
        _uiState.value = AuthUiState(
            isLoading = false,
            isSignedIn = false,
            userId = "",
            userDisplayName = "",
            errorMessage = message
        )
    }

    fun signOut() {
        authRepository.signOut()
        refreshAuthState()
    }

    fun hasActiveSession(): Boolean = authRepository.getCurrentUser() != null

    fun refreshAuthState() {
        val existing = authRepository.getCurrentUser()
        _uiState.value = if (existing != null) {
            AuthUiState(
                isLoading = false,
                isSignedIn = true,
                userId = existing.uid,
                userDisplayName = existing.displayName ?: existing.email.orEmpty(),
                errorMessage = null
            )
        } else {
            AuthUiState(
                isLoading = false,
                isSignedIn = false,
                userId = "",
                userDisplayName = "",
                errorMessage = null
            )
        }
    }

    private fun mapAuthErrorMessage(error: Throwable): String {
        val rawMessage = error.message.orEmpty()
        val isPermissionDeniedError =
            (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
                rawMessage.contains("permission denied", ignoreCase = true)

        return if (isPermissionDeniedError) {
            "Access denied. Please sign in with an account that has Firestore permission."
        } else {
            rawMessage.ifBlank { "Authentication failed." }
        }
    }
}
