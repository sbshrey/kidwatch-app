package com.kidwatch.app.services

import android.content.Context
import com.kidwatch.app.BuildConfig
import com.kidwatch.app.ProfilePreferences
import java.security.MessageDigest
import java.util.UUID

class TesterProfileStore(
    context: Context
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(ProfilePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    data class TesterProfileState(
        val testerName: String,
        val testerKey: String,
        val phoneLast4: String,
        val installInstanceId: String,
        val firstOpenedAt: Long,
        val lastAppOpenAt: Long,
        val testCohort: String,
        val testerProfileRegisteredAt: Long? = null,
        val onboardingCompletedAt: Long? = null,
        val usageAccessGrantedAt: Long? = null,
        val accessibilityEnabledAt: Long? = null,
        val cameraGrantedAt: Long? = null,
        val automaticEvidenceEnabledAt: Long? = null,
        val firstSessionRecordedAt: Long? = null,
        val firstScreenshotRecordedAt: Long? = null,
        val firstFaceRecordedAt: Long? = null,
        val firstContentEventRecordedAt: Long? = null
    ) {
        val isTesterProfileComplete: Boolean
            get() = testerName.isNotBlank() &&
                testerKey.isNotBlank() &&
                phoneLast4.isNotBlank() &&
                installInstanceId.isNotBlank()

        val maskedPhone: String
            get() = if (phoneLast4.isBlank()) "Phone not set" else "Ends with $phoneLast4"
    }

    data class AppOpenResult(
        val state: TesterProfileState,
        val isFirstOpen: Boolean,
        val shouldLogOpen: Boolean
    )

    data class SaveResult(
        val state: TesterProfileState,
        val wasFirstRegistration: Boolean
    )

    fun noteAppOpen(now: Long = System.currentTimeMillis()): AppOpenResult {
        val existingInstallId = prefs.getString(ProfilePreferences.PREF_INSTALL_INSTANCE_ID, "").orEmpty()
        val isFirstOpen = existingInstallId.isBlank()
        ensureInstallState(now)
        val lastEventAt = prefs.getLong(ProfilePreferences.PREF_LAST_APP_OPEN_EVENT_AT, 0L)
        val shouldLogOpen = lastEventAt == 0L || now - lastEventAt >= APP_OPEN_EVENT_DEDUP_MS
        prefs.edit()
            .putLong(ProfilePreferences.PREF_LAST_APP_OPEN_AT, now)
            .apply {
                if (shouldLogOpen) {
                    putLong(ProfilePreferences.PREF_LAST_APP_OPEN_EVENT_AT, now)
                }
            }
            .apply()
        return AppOpenResult(
            state = getState(),
            isFirstOpen = isFirstOpen,
            shouldLogOpen = shouldLogOpen
        )
    }

    fun ensureInstallState(now: Long = System.currentTimeMillis()): TesterProfileState {
        val installId = prefs.getString(ProfilePreferences.PREF_INSTALL_INSTANCE_ID, "").orEmpty()
        val currentCohort = prefs.getString(ProfilePreferences.PREF_TEST_COHORT, "").orEmpty()
        if (installId.isBlank() || currentCohort.isBlank()) {
            prefs.edit()
                .apply {
                    if (installId.isBlank()) {
                        putString(ProfilePreferences.PREF_INSTALL_INSTANCE_ID, UUID.randomUUID().toString())
                        putLong(ProfilePreferences.PREF_FIRST_OPENED_AT, now)
                        putLong(ProfilePreferences.PREF_LAST_APP_OPEN_AT, now)
                    }
                    if (currentCohort.isBlank()) {
                        putString(ProfilePreferences.PREF_TEST_COHORT, BuildConfig.TEST_COHORT.ifBlank { DEFAULT_COHORT })
                    }
                }
                .apply()
        }
        return getState()
    }

    fun getState(): TesterProfileState {
        return TesterProfileState(
            testerName = prefs.getString(ProfilePreferences.PREF_TESTER_NAME, "").orEmpty(),
            testerKey = prefs.getString(ProfilePreferences.PREF_TESTER_KEY, "").orEmpty(),
            phoneLast4 = prefs.getString(ProfilePreferences.PREF_TESTER_PHONE_LAST4, "").orEmpty(),
            installInstanceId = prefs.getString(ProfilePreferences.PREF_INSTALL_INSTANCE_ID, "").orEmpty(),
            firstOpenedAt = prefs.getLong(ProfilePreferences.PREF_FIRST_OPENED_AT, 0L),
            lastAppOpenAt = prefs.getLong(ProfilePreferences.PREF_LAST_APP_OPEN_AT, 0L),
            testCohort = prefs.getString(ProfilePreferences.PREF_TEST_COHORT, "").orEmpty().ifBlank {
                BuildConfig.TEST_COHORT.ifBlank { DEFAULT_COHORT }
            },
            testerProfileRegisteredAt = prefs.getLong(ProfilePreferences.PREF_TESTER_PROFILE_REGISTERED_AT, 0L).takeIf { it > 0L },
            onboardingCompletedAt = prefs.getLong(ProfilePreferences.PREF_ONBOARDING_COMPLETED_AT, 0L).takeIf { it > 0L },
            usageAccessGrantedAt = prefs.getLong(ProfilePreferences.PREF_USAGE_ACCESS_GRANTED_AT, 0L).takeIf { it > 0L },
            accessibilityEnabledAt = prefs.getLong(ProfilePreferences.PREF_ACCESSIBILITY_ENABLED_AT, 0L).takeIf { it > 0L },
            cameraGrantedAt = prefs.getLong(ProfilePreferences.PREF_CAMERA_GRANTED_AT, 0L).takeIf { it > 0L },
            automaticEvidenceEnabledAt = prefs.getLong(ProfilePreferences.PREF_AUTOMATIC_EVIDENCE_ENABLED_AT, 0L).takeIf { it > 0L },
            firstSessionRecordedAt = prefs.getLong(ProfilePreferences.PREF_FIRST_SESSION_RECORDED_AT, 0L).takeIf { it > 0L },
            firstScreenshotRecordedAt = prefs.getLong(ProfilePreferences.PREF_FIRST_SCREENSHOT_RECORDED_AT, 0L).takeIf { it > 0L },
            firstFaceRecordedAt = prefs.getLong(ProfilePreferences.PREF_FIRST_FACE_RECORDED_AT, 0L).takeIf { it > 0L },
            firstContentEventRecordedAt = prefs.getLong(ProfilePreferences.PREF_FIRST_CONTENT_EVENT_RECORDED_AT, 0L).takeIf { it > 0L }
        )
    }

    fun hasCompleteTesterProfile(): Boolean = getState().isTesterProfileComplete

    fun saveTesterProfile(
        testerName: String,
        rawPhone: String,
        now: Long = System.currentTimeMillis()
    ): SaveResult {
        ensureInstallState(now)
        val normalizedName = testerName.trim()
        val normalizedPhone = normalizePhone(rawPhone)
        require(normalizedName.isNotBlank()) { "Enter the tester name." }
        require(normalizedPhone.length >= MIN_PHONE_DIGITS) { "Enter a valid tester phone number." }

        val previousKey = prefs.getString(ProfilePreferences.PREF_TESTER_KEY, "").orEmpty()
        val testerKey = sha256(normalizedPhone)
        val phoneLast4 = normalizedPhone.takeLast(4)
        val firstRegistrationAt = prefs.getLong(ProfilePreferences.PREF_TESTER_PROFILE_REGISTERED_AT, 0L)
            .takeIf { it > 0L } ?: now

        prefs.edit()
            .putString(ProfilePreferences.PREF_TESTER_NAME, normalizedName)
            .putString(ProfilePreferences.PREF_TESTER_KEY, testerKey)
            .putString(ProfilePreferences.PREF_TESTER_PHONE_LAST4, phoneLast4)
            .putLong(ProfilePreferences.PREF_TESTER_PROFILE_REGISTERED_AT, firstRegistrationAt)
            .apply()

        return SaveResult(
            state = getState(),
            wasFirstRegistration = previousKey.isBlank()
        )
    }

    fun markOnboardingCompleted(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_ONBOARDING_COMPLETED_AT, now)

    fun markUsageAccessGranted(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_USAGE_ACCESS_GRANTED_AT, now)

    fun markAccessibilityEnabled(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_ACCESSIBILITY_ENABLED_AT, now)

    fun markCameraGranted(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_CAMERA_GRANTED_AT, now)

    fun markAutomaticEvidenceEnabled(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_AUTOMATIC_EVIDENCE_ENABLED_AT, now)

    fun markFirstSessionRecorded(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_FIRST_SESSION_RECORDED_AT, now)

    fun markFirstScreenshotRecorded(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_FIRST_SCREENSHOT_RECORDED_AT, now)

    fun markFirstFaceRecorded(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_FIRST_FACE_RECORDED_AT, now)

    fun markFirstContentEventRecorded(now: Long = System.currentTimeMillis()): Long? =
        markTimestamp(ProfilePreferences.PREF_FIRST_CONTENT_EVENT_RECORDED_AT, now)

    private fun markTimestamp(key: String, now: Long): Long? {
        ensureInstallState(now)
        if (prefs.getLong(key, 0L) > 0L) return null
        prefs.edit().putLong(key, now).apply()
        return now
    }

    private fun normalizePhone(rawPhone: String): String {
        return rawPhone.filter { it.isDigit() }
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DEFAULT_COHORT = "manual-apk"
        private const val MIN_PHONE_DIGITS = 8
        private const val APP_OPEN_EVENT_DEDUP_MS = 5_000L
    }
}
