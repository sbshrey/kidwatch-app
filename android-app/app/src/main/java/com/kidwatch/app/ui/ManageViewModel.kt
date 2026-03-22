package com.kidwatch.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kidwatch.app.data.local.entity.IdentityClusterEntity
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.AccessibilityServiceState
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.DeviceLinkingService
import com.kidwatch.app.services.EvidencePreferences
import com.kidwatch.app.services.MediaProjectionPermissionStore
import com.kidwatch.app.services.TesterProfileStore
import com.kidwatch.app.services.UsageAccessHelper
import kotlinx.coroutines.launch

data class ManagePermissionState(
    val usageAccess: Boolean = false,
    val cameraAccess: Boolean = false,
    val accessibilityAccess: Boolean = false,
    val screenshotConsent: Boolean = false
)

data class ManageEvidenceState(
    val automaticEvidenceEnabled: Boolean = false,
    val automaticEvidenceEnabledAt: Long? = null,
    val cameraCaptureEnabled: Boolean = false,
    val screenCaptureConfigured: Boolean = false,
    val screenCaptureCurrentlyAvailable: Boolean = false
)

data class ManageLinkedDeviceUi(
    val deviceId: String,
    val displayName: String
)

data class ManagePersonProfileUi(
    val id: Long,
    val name: String,
    val role: String,
    val ageYears: Int? = null,
    val isDeviceOwner: Boolean = false
)

data class ManageTesterProfileUi(
    val testerName: String,
    val maskedPhone: String,
    val testCohort: String,
    val installInstanceId: String,
    val isComplete: Boolean
)

data class ManageUiState(
    val isLoading: Boolean = false,
    val deviceName: String = "",
    val deviceOwnerProfile: ManagePersonProfileUi? = null,
    val testerProfile: ManageTesterProfileUi? = null,
    val childProfiles: List<ManagePersonProfileUi> = emptyList(),
    val linkedDevices: List<ManageLinkedDeviceUi> = emptyList(),
    val monitoringSummary: LocalMonitoringRepository.MonitoringSummary? = null,
    val unknownClusterCount: Int = 0,
    val unknownClusters: List<IdentityClusterEntity> = emptyList(),
    val permissions: ManagePermissionState = ManagePermissionState(),
    val evidence: ManageEvidenceState = ManageEvidenceState(),
    val retentionDays: Int = EvidencePreferences.RETENTION_DAYS,
    val errorMessage: String? = null
)

class ManageViewModel(
    private val appContext: Context,
    private val repository: LocalMonitoringRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val deviceLinkingService: DeviceLinkingService
) : ViewModel() {

    private val _uiState = MutableLiveData(ManageUiState(isLoading = true))
    val uiState: LiveData<ManageUiState> = _uiState

    fun load(preferredName: String) {
        _uiState.value = _uiState.value?.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                val deviceInfo = deviceInfoProvider.getDeviceInfo()
                val ownerProfile = repository.ensureDeviceOwnerProfile(
                    preferredName.ifBlank { deviceInfo.deviceName }
                )
                val testerState = TesterProfileStore(appContext).ensureInstallState()
                val childProfiles = repository.getChildProfiles()
                val monitoringSummary = repository.getMonitoringSummary()
                val unknownClusters = repository.getUnknownClusters()
                val linkedDevices = runCatching {
                    deviceLinkingService.fetchLinkedDevices(deviceInfo.deviceId)
                }.getOrElse { emptyList() }
                val evidenceState = EvidencePreferences.getCapabilityState(appContext)
                ManageUiState(
                    isLoading = false,
                    deviceName = preferredName.ifBlank { deviceInfo.deviceName },
                    deviceOwnerProfile = ownerProfile.toUi(),
                    testerProfile = ManageTesterProfileUi(
                        testerName = testerState.testerName.ifBlank { "Tester details incomplete" },
                        maskedPhone = testerState.maskedPhone,
                        testCohort = testerState.testCohort,
                        installInstanceId = testerState.installInstanceId,
                        isComplete = testerState.isTesterProfileComplete
                    ),
                    childProfiles = childProfiles.map { it.toUi() },
                    linkedDevices = linkedDevices.map {
                        ManageLinkedDeviceUi(
                            deviceId = it.remoteDeviceId,
                            displayName = it.customName.ifBlank {
                                it.remotePreferredName.ifBlank { it.remoteDeviceName }
                            }
                        )
                    },
                    monitoringSummary = monitoringSummary,
                    unknownClusterCount = unknownClusters.size,
                    unknownClusters = unknownClusters,
                    permissions = snapshotPermissions(),
                    evidence = ManageEvidenceState(
                        automaticEvidenceEnabled = evidenceState.automaticEvidenceEnabled,
                        automaticEvidenceEnabledAt = evidenceState.automaticEvidenceEnabledAt,
                        cameraCaptureEnabled = evidenceState.cameraCaptureEnabled,
                        screenCaptureConfigured = evidenceState.screenCaptureConfigured,
                        screenCaptureCurrentlyAvailable = evidenceState.screenCaptureCurrentlyAvailable
                    ),
                    retentionDays = EvidencePreferences.RETENTION_DAYS
                )
            }.onSuccess { state ->
                _uiState.value = state
            }.onFailure { error ->
                _uiState.value = ManageUiState(
                    isLoading = false,
                    errorMessage = error.message ?: "Manage controls unavailable."
                )
            }
        }
    }

    private fun snapshotPermissions(): ManagePermissionState {
        val cameraGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return ManagePermissionState(
            usageAccess = UsageAccessHelper.hasUsageAccess(appContext),
            cameraAccess = cameraGranted,
            accessibilityAccess = AccessibilityServiceState.isContentCaptureEnabled(appContext),
            screenshotConsent = MediaProjectionPermissionStore.hasGrant()
        )
    }

    private fun com.kidwatch.app.data.local.entity.PersonProfileEntity.toUi(): ManagePersonProfileUi {
        return ManagePersonProfileUi(
            id = id,
            name = name,
            role = role,
            ageYears = ageYears,
            isDeviceOwner = isDeviceOwner
        )
    }
}

class ManageViewModelFactory(
    private val appContext: Context,
    private val repository: LocalMonitoringRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val deviceLinkingService: DeviceLinkingService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ManageViewModel(
            appContext = appContext,
            repository = repository,
            deviceInfoProvider = deviceInfoProvider,
            deviceLinkingService = deviceLinkingService
        ) as T
    }
}
