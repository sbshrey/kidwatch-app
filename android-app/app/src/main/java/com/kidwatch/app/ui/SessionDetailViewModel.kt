package com.kidwatch.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kidwatch.app.data.local.entity.PersonProfileEntity
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.launch

data class SessionDetailUiState(
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val detail: LocalMonitoringRepository.ActivitySessionDetail? = null,
    val availableProfiles: List<PersonProfileEntity> = emptyList(),
    val errorMessage: String? = null
)

class SessionDetailViewModel(
    private val repository: LocalMonitoringRepository
) : ViewModel() {

    private val _uiState = MutableLiveData(SessionDetailUiState(isLoading = true))
    val uiState: LiveData<SessionDetailUiState> = _uiState

    fun load(sessionId: Long) {
        _uiState.value = SessionDetailUiState(isLoading = true)
        viewModelScope.launch {
            runCatching {
                repository.getSessionDetail(sessionId) to repository.getPersonProfiles()
            }.onSuccess { (detail, profiles) ->
                if (detail == null) {
                    _uiState.value = SessionDetailUiState(
                        isLoading = false,
                        errorMessage = "Session no longer exists."
                    )
                } else {
                    _uiState.value = SessionDetailUiState(
                        isLoading = false,
                        detail = detail,
                        availableProfiles = profiles
                    )
                    refreshAiSuitability(sessionId)
                }
            }.onFailure { error ->
                _uiState.value = SessionDetailUiState(
                    isLoading = false,
                    errorMessage = error.message ?: "Session detail unavailable."
                )
            }
        }
    }

    fun assignSessionToPerson(profileId: Long?) {
        val currentDetail = _uiState.value?.detail ?: return
        viewModelScope.launch {
            runCatching {
                repository.assignSessionToPerson(currentDetail.session.id, profileId)
                repository.refreshAgeSuitabilityForSession(currentDetail.session.id)
                repository.getSessionDetail(currentDetail.session.id) to repository.getPersonProfiles()
            }.onSuccess { (detail, profiles) ->
                _uiState.value = if (detail == null) {
                    SessionDetailUiState(
                        isLoading = false,
                        errorMessage = "Session no longer exists."
                    )
                } else {
                    SessionDetailUiState(
                        isLoading = false,
                        detail = detail,
                        availableProfiles = profiles
                    )
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value?.copy(
                    errorMessage = error.message ?: "Could not update the linked person."
                )
            }
        }
    }

    fun deleteSession() {
        val currentDetail = _uiState.value?.detail ?: return
        _uiState.value = _uiState.value?.copy(isDeleting = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                repository.deleteActivitySession(currentDetail.session.id)
            }.onSuccess { deleted ->
                if (deleted) {
                    _uiState.value = SessionDetailUiState(
                        isLoading = false,
                        isDeleting = false,
                        isDeleted = true
                    )
                } else {
                    _uiState.value = SessionDetailUiState(
                        isLoading = false,
                        detail = null,
                        errorMessage = "Session no longer exists."
                    )
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value?.copy(
                    isDeleting = false,
                    errorMessage = error.message ?: "Could not delete this session."
                )
            }
        }
    }

    private fun refreshAiSuitability(sessionId: Long) {
        viewModelScope.launch {
            val updated = runCatching {
                repository.refreshAgeSuitabilityForSession(sessionId)
            }.getOrDefault(false)
            if (!updated) return@launch
            val current = _uiState.value ?: return@launch
            val detail = repository.getSessionDetail(sessionId) ?: return@launch
            _uiState.value = current.copy(detail = detail)
        }
    }
}

class SessionDetailViewModelFactory(
    private val repository: LocalMonitoringRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SessionDetailViewModel(repository) as T
    }
}
