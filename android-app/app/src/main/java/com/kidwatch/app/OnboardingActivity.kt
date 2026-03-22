package com.kidwatch.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.kidwatch.app.analytics.AnalyticsTracker
import com.kidwatch.app.monitoring.MonitoringScheduler
import com.kidwatch.app.services.AccessibilityServiceState
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.TesterProfileStore
import com.kidwatch.app.services.UsageAccessHelper

class OnboardingActivity : AppCompatActivity() {

    private lateinit var onboardingScroll: NestedScrollView
    private lateinit var viewFlipper: ViewFlipper
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var dotOne: View
    private lateinit var dotTwo: View
    private lateinit var dotThree: View
    private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroBody: TextView
    private lateinit var heroMetricOne: TextView
    private lateinit var heroMetricTwo: TextView
    private lateinit var nameInput: TextInputEditText
    private lateinit var testerNameInput: TextInputEditText
    private lateinit var testerPhoneInput: TextInputEditText
    private lateinit var chipSuggestionMom: Chip
    private lateinit var chipSuggestionDad: Chip
    private lateinit var chipSuggestionKid: Chip
    private lateinit var usageStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var btnGrantUsageAccess: MaterialButton
    private lateinit var btnOpenAccessibility: MaterialButton

    private val profilePrefs by lazy {
        getSharedPreferences(ProfilePreferences.PREFS_NAME, MODE_PRIVATE)
    }
    private val testerProfileStore by lazy { TesterProfileStore(this) }
    private val analyticsTracker by lazy { AnalyticsTracker(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analyticsTracker.noteAppOpen()
        testerProfileStore.ensureInstallState()
        val storedName = profilePrefs.getString(ProfilePreferences.PREF_PREFERRED_NAME, "").orEmpty()
        val testerState = testerProfileStore.getState()
        if (storedName.isNotBlank() && testerState.isTesterProfileComplete) {
            openDashboard()
            return
        }

        setContentView(R.layout.activity_onboarding)
        bindViews()
        seedExistingValues(storedName, testerState)
        if (storedName.isNotBlank() && !testerState.isTesterProfileComplete) {
            viewFlipper.displayedChild = LAST_PAGE_INDEX
        }
        bindActions()
        updateStepUi()
        refreshPermissionState()
        trackPermissionMilestones()
    }

    override fun onResume() {
        super.onResume()
        if (::viewFlipper.isInitialized) {
            refreshPermissionState()
            trackPermissionMilestones()
        }
    }

    private fun bindViews() {
        onboardingScroll = findViewById(R.id.onboardingScroll)
        viewFlipper = findViewById(R.id.onboardingFlipper)
        btnSkip = findViewById(R.id.btnOnboardingSkip)
        btnBack = findViewById(R.id.btnOnboardingBack)
        btnNext = findViewById(R.id.btnOnboardingNext)
        dotOne = findViewById(R.id.onboardingDotOne)
        dotTwo = findViewById(R.id.onboardingDotTwo)
        dotThree = findViewById(R.id.onboardingDotThree)
        heroBadge = findViewById(R.id.tvOnboardingHeroBadge)
        heroTitle = findViewById(R.id.tvOnboardingHeroTitle)
        heroBody = findViewById(R.id.tvOnboardingHeroBody)
        heroMetricOne = findViewById(R.id.tvOnboardingHeroMetricOne)
        heroMetricTwo = findViewById(R.id.tvOnboardingHeroMetricTwo)
        nameInput = findViewById(R.id.etOnboardingName)
        testerNameInput = findViewById(R.id.etOnboardingTesterName)
        testerPhoneInput = findViewById(R.id.etOnboardingTesterPhone)
        chipSuggestionMom = findViewById(R.id.chipSuggestionMom)
        chipSuggestionDad = findViewById(R.id.chipSuggestionDad)
        chipSuggestionKid = findViewById(R.id.chipSuggestionKid)
        usageStatus = findViewById(R.id.tvOnboardingUsageStatus)
        accessibilityStatus = findViewById(R.id.tvOnboardingAccessibilityStatus)
        btnGrantUsageAccess = findViewById(R.id.btnOnboardingUsageAccess)
        btnOpenAccessibility = findViewById(R.id.btnOnboardingAccessibility)
    }

    private fun bindActions() {
        chipSuggestionMom.setOnClickListener { nameInput.setText(chipSuggestionMom.text) }
        chipSuggestionDad.setOnClickListener { nameInput.setText(chipSuggestionDad.text) }
        chipSuggestionKid.setOnClickListener { nameInput.setText(chipSuggestionKid.text) }

        btnSkip.setOnClickListener {
            if (viewFlipper.displayedChild < LAST_PAGE_INDEX) {
                viewFlipper.displayedChild = LAST_PAGE_INDEX
                updateStepUi()
            } else {
                completeOnboarding()
            }
        }
        btnBack.setOnClickListener {
            if (viewFlipper.displayedChild > 0) {
                viewFlipper.displayedChild -= 1
                updateStepUi()
            }
        }
        btnNext.setOnClickListener {
            if (viewFlipper.displayedChild < LAST_PAGE_INDEX) {
                viewFlipper.displayedChild += 1
                updateStepUi()
            } else {
                completeOnboarding()
            }
        }
        btnGrantUsageAccess.setOnClickListener {
            startActivity(UsageAccessHelper.createUsageAccessIntent())
        }
        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updateStepUi() {
        val displayedChild = viewFlipper.displayedChild
        btnBack.visibility = if (displayedChild == 0) View.INVISIBLE else View.VISIBLE
        btnNext.text = if (displayedChild == LAST_PAGE_INDEX) {
            getString(R.string.onboarding_finish)
        } else {
            getString(R.string.onboarding_next)
        }

        dotOne.setBackgroundResource(
            if (displayedChild == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
        )
        dotTwo.setBackgroundResource(
            if (displayedChild == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
        )
        dotThree.setBackgroundResource(
            if (displayedChild == 2) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
        )
        renderHero(displayedChild)
        onboardingScroll.post {
            onboardingScroll.scrollTo(0, 0)
        }
    }

    private fun renderHero(step: Int) {
        val heroContent = when (step) {
            1 -> OnboardingHeroContent(
                badgeRes = R.string.onboarding_hero_badge_step_two,
                titleRes = R.string.onboarding_hero_title_step_two,
                bodyRes = R.string.onboarding_hero_body_step_two,
                metricOneRes = R.string.onboarding_hero_metric_one_step_two,
                metricTwoRes = R.string.onboarding_hero_metric_two_step_two
            )
            2 -> OnboardingHeroContent(
                badgeRes = R.string.onboarding_hero_badge_step_three,
                titleRes = R.string.onboarding_hero_title_step_three,
                bodyRes = R.string.onboarding_hero_body_step_three,
                metricOneRes = R.string.onboarding_hero_metric_one_step_three,
                metricTwoRes = R.string.onboarding_hero_metric_two_step_three
            )
            else -> OnboardingHeroContent(
                badgeRes = R.string.onboarding_hero_badge_step_one,
                titleRes = R.string.onboarding_hero_title_step_one,
                bodyRes = R.string.onboarding_hero_body_step_one,
                metricOneRes = R.string.onboarding_hero_metric_one_step_one,
                metricTwoRes = R.string.onboarding_hero_metric_two_step_one
            )
        }

        heroBadge.setText(heroContent.badgeRes)
        heroTitle.setText(heroContent.titleRes)
        heroBody.setText(heroContent.bodyRes)
        heroMetricOne.setText(heroContent.metricOneRes)
        heroMetricTwo.setText(heroContent.metricTwoRes)
    }

    private fun refreshPermissionState() {
        applyPermissionStatus(usageStatus, UsageAccessHelper.hasUsageAccess(this))
        applyPermissionStatus(accessibilityStatus, isAccessibilityEnabled())
    }

    private fun trackPermissionMilestones() {
        analyticsTracker.markUsageAccessGrantedIfNeeded(UsageAccessHelper.hasUsageAccess(this))
        analyticsTracker.markAccessibilityEnabledIfNeeded(isAccessibilityEnabled())
    }

    private fun isAccessibilityEnabled(): Boolean {
        return AccessibilityServiceState.isContentCaptureEnabled(this)
    }

    private fun completeOnboarding() {
        val enteredName = nameInput.text?.toString().orEmpty().trim()
        val fallbackName = DeviceInfoProvider(this).getDeviceInfo().deviceName
            .ifBlank { getString(R.string.app_name) }
        val testerName = testerNameInput.text?.toString().orEmpty().trim()
        val testerPhone = testerPhoneInput.text?.toString().orEmpty().trim()
        nameInput.error = null
        testerNameInput.error = null
        testerPhoneInput.error = null
        if (testerName.isBlank()) {
            testerNameInput.error = getString(R.string.onboarding_tester_name_required)
            return
        }
        val saveResult = runCatching {
            testerProfileStore.saveTesterProfile(testerName, testerPhone)
        }.getOrElse { error ->
            testerPhoneInput.error = error.message ?: getString(R.string.onboarding_tester_phone_required)
            return
        }
        profilePrefs.edit()
            .putString(
                ProfilePreferences.PREF_PREFERRED_NAME,
                enteredName.ifBlank { fallbackName }
            )
            .apply()
        if (saveResult.wasFirstRegistration) {
            analyticsTracker.logTesterProfileRegistered()
        }
        analyticsTracker.markOnboardingCompletedIfNeeded()
        analyticsTracker.markUsageAccessGrantedIfNeeded(UsageAccessHelper.hasUsageAccess(this))
        analyticsTracker.markAccessibilityEnabledIfNeeded(isAccessibilityEnabled())
        MonitoringScheduler.runSyncNow(applicationContext)
        openDashboard()
    }

    private fun seedExistingValues(
        storedName: String,
        testerState: TesterProfileStore.TesterProfileState
    ) {
        if (storedName.isBlank()) {
            val fallbackName = DeviceInfoProvider(this).getDeviceInfo().deviceName
            if (fallbackName.isNotBlank()) {
                nameInput.setText(fallbackName)
            }
        } else {
            nameInput.setText(storedName)
        }
        if (testerState.testerName.isNotBlank()) {
            testerNameInput.setText(testerState.testerName)
        }
    }

    private fun openDashboard() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun applyPermissionStatus(statusView: TextView, isReady: Boolean) {
        statusView.text = if (isReady) {
            getString(R.string.onboarding_permission_ready)
        } else {
            getString(R.string.onboarding_permission_missing)
        }
        statusView.setBackgroundResource(
            if (isReady) R.drawable.bg_pill_soft else R.drawable.bg_pill_orange
        )
        statusView.setTextColor(
            ContextCompat.getColor(
                this,
                if (isReady) R.color.kw_on_primary_container else R.color.kw_on_surface
            )
        )
    }

    private companion object {
        private const val LAST_PAGE_INDEX = 2
    }

    private data class OnboardingHeroContent(
        val badgeRes: Int,
        val titleRes: Int,
        val bodyRes: Int,
        val metricOneRes: Int,
        val metricTwoRes: Int
    )
}
