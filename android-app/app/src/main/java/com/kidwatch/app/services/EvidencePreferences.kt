package com.kidwatch.app.services

import android.content.Context

object EvidencePreferences {
    private const val PREFS_NAME = "evidence_prefs"
    private const val PREF_ALLOWED_PACKAGES = "allowed_packages"
    private const val PREF_LEGACY_ALLOWLIST_MIGRATED = "legacy_allowlist_migrated"
    private const val PREF_AUTOMATIC_EVIDENCE_ENABLED = "automatic_evidence_enabled"
    private const val PREF_AUTOMATIC_EVIDENCE_ENABLED_AT = "automatic_evidence_enabled_at"
    private const val PREF_CAMERA_CAPTURE_ENABLED = "camera_capture_enabled"
    private const val PREF_SCREEN_CAPTURE_CONFIGURED = "screen_capture_configured"
    private const val PREF_SCREEN_CAPTURE_CURRENTLY_AVAILABLE = "screen_capture_currently_available"
    const val RETENTION_DAYS = 3

    private val defaultAllowedPackages = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids"
    )

    fun getLegacyAllowedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(PREF_ALLOWED_PACKAGES, defaultAllowedPackages)
            ?.toSet()
            ?: defaultAllowedPackages
    }

    fun isLegacyPackageAllowed(context: Context, packageName: String): Boolean {
        return getLegacyAllowedPackages(context).contains(packageName)
    }

    fun setLegacyPackageAllowed(context: Context, packageName: String, allowed: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = getLegacyAllowedPackages(context).toMutableSet()
        if (allowed) {
            updated.add(packageName)
        } else {
            updated.remove(packageName)
        }
        prefs.edit().putStringSet(PREF_ALLOWED_PACKAGES, updated).apply()
    }

    fun hasMigratedLegacyAllowlist(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_LEGACY_ALLOWLIST_MIGRATED, false)
    }

    fun markLegacyAllowlistMigrated(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_LEGACY_ALLOWLIST_MIGRATED, true)
            .apply()
    }

    data class CapabilityState(
        val automaticEvidenceEnabled: Boolean = false,
        val automaticEvidenceEnabledAt: Long? = null,
        val cameraCaptureEnabled: Boolean = false,
        val screenCaptureConfigured: Boolean = false,
        val screenCaptureCurrentlyAvailable: Boolean = false
    )

    fun getCapabilityState(context: Context): CapabilityState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabledAt = prefs.getLong(PREF_AUTOMATIC_EVIDENCE_ENABLED_AT, 0L)
        return CapabilityState(
            automaticEvidenceEnabled = prefs.getBoolean(PREF_AUTOMATIC_EVIDENCE_ENABLED, false),
            automaticEvidenceEnabledAt = enabledAt.takeIf { it > 0L },
            cameraCaptureEnabled = prefs.getBoolean(PREF_CAMERA_CAPTURE_ENABLED, false),
            screenCaptureConfigured = prefs.getBoolean(PREF_SCREEN_CAPTURE_CONFIGURED, false),
            screenCaptureCurrentlyAvailable = prefs.getBoolean(
                PREF_SCREEN_CAPTURE_CURRENTLY_AVAILABLE,
                false
            )
        )
    }

    fun isAutomaticEvidenceEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTOMATIC_EVIDENCE_ENABLED, false)
    }

    fun getAutomaticEvidenceEnabledAt(context: Context): Long? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_AUTOMATIC_EVIDENCE_ENABLED_AT, 0L)
        return value.takeIf { it > 0L }
    }

    fun setAutomaticEvidenceEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(PREF_AUTOMATIC_EVIDENCE_ENABLED, enabled)
            if (enabled) {
                putLong(PREF_AUTOMATIC_EVIDENCE_ENABLED_AT, System.currentTimeMillis())
            } else {
                remove(PREF_AUTOMATIC_EVIDENCE_ENABLED_AT)
            }
        }.apply()
    }

    fun isCameraCaptureEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_CAMERA_CAPTURE_ENABLED, false)
    }

    fun setCameraCaptureEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_CAMERA_CAPTURE_ENABLED, enabled)
            .apply()
    }

    fun isScreenCaptureConfigured(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_SCREEN_CAPTURE_CONFIGURED, false)
    }

    fun setScreenCaptureConfigured(context: Context, configured: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SCREEN_CAPTURE_CONFIGURED, configured)
            .apply()
    }

    fun isScreenCaptureCurrentlyAvailable(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_SCREEN_CAPTURE_CURRENTLY_AVAILABLE, false)
    }

    fun setScreenCaptureCurrentlyAvailable(context: Context, available: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SCREEN_CAPTURE_CURRENTLY_AVAILABLE, available)
            .apply()
    }

    fun markAutomaticEvidenceConfigured(
        context: Context,
        cameraCaptureEnabled: Boolean,
        screenCaptureConfigured: Boolean,
        screenCaptureCurrentlyAvailable: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(PREF_AUTOMATIC_EVIDENCE_ENABLED, true)
            putLong(PREF_AUTOMATIC_EVIDENCE_ENABLED_AT, System.currentTimeMillis())
            putBoolean(PREF_CAMERA_CAPTURE_ENABLED, cameraCaptureEnabled)
            putBoolean(PREF_SCREEN_CAPTURE_CONFIGURED, screenCaptureConfigured)
            putBoolean(PREF_SCREEN_CAPTURE_CURRENTLY_AVAILABLE, screenCaptureCurrentlyAvailable)
        }.apply()
    }

    fun syncScreenCaptureAvailability(context: Context, hasLiveProjectionGrant: Boolean) {
        setScreenCaptureCurrentlyAvailable(context, hasLiveProjectionGrant)
    }

    fun clearAutomaticEvidence(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_AUTOMATIC_EVIDENCE_ENABLED)
            .remove(PREF_AUTOMATIC_EVIDENCE_ENABLED_AT)
            .remove(PREF_CAMERA_CAPTURE_ENABLED)
            .remove(PREF_SCREEN_CAPTURE_CONFIGURED)
            .remove(PREF_SCREEN_CAPTURE_CURRENTLY_AVAILABLE)
            .apply()
    }

    @Deprecated("Use isAutomaticEvidenceEnabled")
    fun isEvidenceSessionArmed(context: Context): Boolean = isAutomaticEvidenceEnabled(context)

    @Deprecated("Use getAutomaticEvidenceEnabledAt")
    fun getEvidenceSessionStartedAt(context: Context): Long? = getAutomaticEvidenceEnabledAt(context)

    @Deprecated("Use setAutomaticEvidenceEnabled or markAutomaticEvidenceConfigured")
    fun setEvidenceSessionArmed(context: Context, armed: Boolean) {
        setAutomaticEvidenceEnabled(context, armed)
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
