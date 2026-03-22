package com.kidwatch.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kidwatch.app.data.local.entity.ActivitySessionEntity
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.EvidencePreferences
import kotlinx.coroutines.launch

data class ActivityFeedUiState(
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val filter: ActivityFeedFilter = ActivityFeedFilter.ALL,
    val sessions: List<ActivitySessionEntity> = emptyList(),
    val totalSessions: Int = 0,
    val reviewCount: Int = 0,
    val unknownCount: Int = 0,
    val hasMore: Boolean = true,
    val nextCursorStartTime: Long? = null,
    val errorMessage: String? = null,
    val loadMoreErrorMessage: String? = null
)

class ActivityFeedViewModel(
    private val repository: LocalMonitoringRepository
) : ViewModel() {

    private val _uiState = MutableLiveData(ActivityFeedUiState(isInitialLoading = true))
    val uiState: LiveData<ActivityFeedUiState> = _uiState

    private var currentFilter: ActivityFeedFilter = ActivityFeedFilter.ALL
    private var hasLoadedOnce: Boolean = false
    private var activeRetentionWindowStart: Long = retentionWindowStart()

    fun loadFeed() {
        refresh()
    }

    fun ensureLoaded() {
        if (!hasLoadedOnce) {
            loadInitial()
        }
    }

    fun loadInitial() {
        activeRetentionWindowStart = retentionWindowStart()
        _uiState.value = _uiState.value?.copy(
            isInitialLoading = true,
            isLoadingMore = false,
            sessions = emptyList(),
            hasMore = true,
            nextCursorStartTime = null,
            errorMessage = null,
            loadMoreErrorMessage = null,
            filter = currentFilter
        )
        viewModelScope.launch {
            runCatching {
                val counts = repository.getActivityFeedCounts(activeRetentionWindowStart)
                val page = repository.getActivitySessionPage(
                    startMs = activeRetentionWindowStart,
                    filter = currentFilter,
                    beforeStartTime = null,
                    limit = PAGE_SIZE
                )
                counts to page
            }.onSuccess { (counts, page) ->
                hasLoadedOnce = true
                _uiState.value = ActivityFeedUiState(
                    isInitialLoading = false,
                    isLoadingMore = false,
                    filter = currentFilter,
                    sessions = page.items,
                    totalSessions = counts.totalSessions,
                    reviewCount = counts.reviewCount,
                    unknownCount = counts.unknownCount,
                    hasMore = page.hasMore,
                    nextCursorStartTime = page.nextCursorStartTime
                )
                requestAiRefresh(page.items.map { it.id }, expectedFilter = currentFilter)
            }.onFailure { error ->
                _uiState.value = ActivityFeedUiState(
                    isInitialLoading = false,
                    filter = currentFilter,
                    sessions = emptyList(),
                    hasMore = false,
                    errorMessage = error.message ?: "Activity feed unavailable."
                )
            }
        }
    }

    fun refresh() {
        loadInitial()
    }

    fun setFilter(filter: ActivityFeedFilter) {
        if (currentFilter == filter && hasLoadedOnce) return
        currentFilter = filter
        loadInitial()
    }

    fun loadNextPage() {
        val current = _uiState.value ?: return
        if (!hasLoadedOnce || current.isInitialLoading || current.isLoadingMore || !current.hasMore) return
        val cursor = current.nextCursorStartTime ?: return
        _uiState.value = current.copy(isLoadingMore = true, loadMoreErrorMessage = null)

        viewModelScope.launch {
            runCatching {
                repository.getActivitySessionPage(
                    startMs = activeRetentionWindowStart,
                    filter = currentFilter,
                    beforeStartTime = cursor,
                    limit = PAGE_SIZE
                )
            }.onSuccess { page ->
                val merged = (current.sessions + page.items).distinctBy { it.id }
                _uiState.value = current.copy(
                    isLoadingMore = false,
                    sessions = merged,
                    hasMore = page.hasMore,
                    nextCursorStartTime = page.nextCursorStartTime,
                    loadMoreErrorMessage = null,
                    filter = currentFilter
                )
                requestAiRefresh(page.items.map { it.id }, expectedFilter = currentFilter)
            }.onFailure { error ->
                _uiState.value = current.copy(
                    isLoadingMore = false,
                    loadMoreErrorMessage = error.message ?: "Could not load more sessions."
                )
            }
        }
    }

    fun retryPageLoad() {
        val current = _uiState.value ?: return
        if (current.sessions.isEmpty()) {
            loadInitial()
        } else {
            loadNextPage()
        }
    }

    private fun retentionWindowStart(): Long {
        return System.currentTimeMillis() -
            EvidencePreferences.RETENTION_DAYS * 24L * 60L * 60L * 1000L
    }

    private fun requestAiRefresh(
        sessionIds: List<Long>,
        expectedFilter: ActivityFeedFilter
    ) {
        if (sessionIds.isEmpty()) return
        viewModelScope.launch {
            val changed = runCatching {
                repository.refreshAgeSuitabilityForSessions(sessionIds)
            }.getOrDefault(false)
            if (!changed) return@launch
            val current = _uiState.value ?: return@launch
            if (current.filter != expectedFilter) return@launch
            val refreshed = repository.getActivitySessionsByIds(current.sessions.map { it.id })
                .associateBy { it.id }
            _uiState.value = current.copy(
                sessions = current.sessions.map { session -> refreshed[session.id] ?: session }
            )
        }
    }

    private companion object {
        private const val PAGE_SIZE = 20
    }
}

class ActivityFeedViewModelFactory(
    private val repository: LocalMonitoringRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ActivityFeedViewModel(repository) as T
    }
}
