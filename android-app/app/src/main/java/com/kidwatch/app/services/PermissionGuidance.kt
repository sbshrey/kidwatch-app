package com.kidwatch.app.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.kidwatch.app.ProfilePreferences
import com.kidwatch.app.R

enum class PermissionRequirement {
    USAGE_ACCESS,
    ACCESSIBILITY
}

enum class PermissionGuideAction(
    val analyticsValue: String
) {
    OPEN_USAGE_ACCESS_SETTINGS("usage_settings"),
    OPEN_APP_DETAILS("app_details"),
    OPEN_ACCESSIBILITY_SETTINGS("accessibility_settings"),
    NONE("none")
}

data class PermissionGuideState(
    val requirement: PermissionRequirement,
    val isReady: Boolean,
    val statusText: String,
    val highlightStatus: Boolean,
    val instructions: String,
    val ctaText: String? = null,
    val ctaAction: PermissionGuideAction = PermissionGuideAction.NONE
)

class PermissionGuidance(
    context: Context
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        ProfilePreferences.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun stateFor(requirement: PermissionRequirement): PermissionGuideState {
        return when (requirement) {
            PermissionRequirement.USAGE_ACCESS -> usageAccessState()
            PermissionRequirement.ACCESSIBILITY -> accessibilityState()
        }
    }

    fun hasUnresolvedCorePermissions(): Boolean {
        return !stateFor(PermissionRequirement.USAGE_ACCESS).isReady ||
            !stateFor(PermissionRequirement.ACCESSIBILITY).isReady
    }

    fun markActionLaunched(action: PermissionGuideAction, now: Long = System.currentTimeMillis()) {
        when (action) {
            PermissionGuideAction.OPEN_USAGE_ACCESS_SETTINGS -> {
                prefs.edit().putLong(ProfilePreferences.PREF_USAGE_SETTINGS_OPENED_AT, now).apply()
            }
            PermissionGuideAction.OPEN_APP_DETAILS -> {
                prefs.edit().putLong(ProfilePreferences.PREF_ACCESSIBILITY_APP_INFO_OPENED_AT, now).apply()
            }
            PermissionGuideAction.OPEN_ACCESSIBILITY_SETTINGS -> {
                prefs.edit().putLong(ProfilePreferences.PREF_ACCESSIBILITY_SETTINGS_OPENED_AT, now).apply()
            }
            PermissionGuideAction.NONE -> Unit
        }
    }

    private fun usageAccessState(): PermissionGuideState {
        val granted = UsageAccessHelper.hasUsageAccess(appContext)
        val attempted = prefs.getLong(ProfilePreferences.PREF_USAGE_SETTINGS_OPENED_AT, 0L) > 0L
        return if (granted) {
            PermissionGuideState(
                requirement = PermissionRequirement.USAGE_ACCESS,
                isReady = true,
                statusText = appContext.getString(R.string.onboarding_permission_ready),
                highlightStatus = false,
                instructions = appContext.getString(R.string.permission_usage_instructions_ready)
            )
        } else {
            PermissionGuideState(
                requirement = PermissionRequirement.USAGE_ACCESS,
                isReady = false,
                statusText = appContext.getString(
                    if (attempted) {
                        R.string.permission_status_still_missing
                    } else {
                        R.string.onboarding_permission_missing
                    }
                ),
                highlightStatus = true,
                instructions = appContext.getString(
                    if (attempted) {
                        R.string.permission_usage_instructions_retry
                    } else {
                        R.string.permission_usage_instructions_initial
                    }
                ),
                ctaText = appContext.getString(R.string.permission_usage_button),
                ctaAction = PermissionGuideAction.OPEN_USAGE_ACCESS_SETTINGS
            )
        }
    }

    private fun accessibilityState(): PermissionGuideState {
        val enabled = AccessibilityServiceState.isContentCaptureEnabled(appContext)
        val requiresRestrictedFlow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !enabled
        val appInfoOpened = prefs.getLong(ProfilePreferences.PREF_ACCESSIBILITY_APP_INFO_OPENED_AT, 0L) > 0L
        return when {
            enabled -> PermissionGuideState(
                requirement = PermissionRequirement.ACCESSIBILITY,
                isReady = true,
                statusText = appContext.getString(R.string.onboarding_permission_ready),
                highlightStatus = false,
                instructions = appContext.getString(R.string.permission_accessibility_instructions_ready)
            )
            requiresRestrictedFlow && !appInfoOpened -> PermissionGuideState(
                requirement = PermissionRequirement.ACCESSIBILITY,
                isReady = false,
                statusText = appContext.getString(R.string.permission_status_step_one_incomplete),
                highlightStatus = true,
                instructions = appContext.getString(R.string.permission_accessibility_instructions_step_one),
                ctaText = appContext.getString(R.string.permission_accessibility_button_app_info),
                ctaAction = PermissionGuideAction.OPEN_APP_DETAILS
            )
            requiresRestrictedFlow -> PermissionGuideState(
                requirement = PermissionRequirement.ACCESSIBILITY,
                isReady = false,
                statusText = appContext.getString(R.string.permission_status_step_two_incomplete),
                highlightStatus = true,
                instructions = appContext.getString(R.string.permission_accessibility_instructions_step_two),
                ctaText = appContext.getString(R.string.permission_accessibility_button_open_settings),
                ctaAction = PermissionGuideAction.OPEN_ACCESSIBILITY_SETTINGS
            )
            else -> PermissionGuideState(
                requirement = PermissionRequirement.ACCESSIBILITY,
                isReady = false,
                statusText = appContext.getString(R.string.onboarding_permission_missing),
                highlightStatus = true,
                instructions = appContext.getString(R.string.permission_accessibility_instructions_legacy),
                ctaText = appContext.getString(R.string.permission_accessibility_button_open_settings),
                ctaAction = PermissionGuideAction.OPEN_ACCESSIBILITY_SETTINGS
            )
        }
    }
}

object PermissionGuideNavigator {

    fun createIntent(context: Context, action: PermissionGuideAction): Intent? {
        return when (action) {
            PermissionGuideAction.OPEN_USAGE_ACCESS_SETTINGS -> UsageAccessHelper.createUsageAccessIntent()
            PermissionGuideAction.OPEN_APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            PermissionGuideAction.OPEN_ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionGuideAction.NONE -> null
        }
    }
}
