package com.kidwatch.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.auth.AuthUiState
import com.kidwatch.app.auth.AuthViewModel
import com.kidwatch.app.auth.GoogleAuthClient
import com.kidwatch.app.insights.AppCatalogMapper
import com.kidwatch.app.monitoring.MonitoringScheduler
import com.kidwatch.app.repository.AuthRepository
import com.kidwatch.app.repository.DashboardRepository
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.FirestoreDeviceService
import com.kidwatch.app.services.FirestoreFamilyService
import com.kidwatch.app.services.FirestoreUserService
import com.kidwatch.app.services.UsageAccessHelper
import com.kidwatch.app.ui.DashboardUiState
import com.kidwatch.app.ui.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var authViewModel: AuthViewModel
    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var googleAuthClient: GoogleAuthClient

    private lateinit var signInButton: AppCompatButton
    private lateinit var signOutButton: AppCompatButton
    private lateinit var usageAccessButton: AppCompatButton
    private lateinit var runMonitoringNowButton: AppCompatButton
    private lateinit var runSyncNowButton: AppCompatButton
    private lateinit var localMonitoringRepository: LocalMonitoringRepository
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var dashboardStatusText: TextView
    private lateinit var dashboardTodayText: TextView
    private lateinit var dashboardTopAppsText: TextView
    private lateinit var dashboardDeviceUsageText: TextView
    private lateinit var dashboardLastUpdatedText: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var dashboardTabContent: View
    private lateinit var alertsTabContent: View
    private lateinit var rulesTabContent: View
    private lateinit var settingsTabContent: View
    private lateinit var topAppsCard: View
    private lateinit var chipScreenTime: Chip
    private lateinit var chipSessions: Chip
    private lateinit var chipRiskFlags: Chip
    private lateinit var topAppsSkeleton: View
    private lateinit var topAppsEmptyState: View
    private lateinit var topAppsList: View
    private lateinit var insightsStatusText: TextView
    private lateinit var insightsEmptyState: View
    private lateinit var insightsList: View
    private lateinit var insightRow1: TextView
    private lateinit var insightRow2: TextView
    private lateinit var insightRow3: TextView
    private val topAppRows = mutableListOf<TopAppRow>()
    private val previousTopAppMinutes = mutableMapOf<String, Int>()
    private val packageByAppLabel = mutableMapOf<String, String>()
    private var hasInitializedSignedInFlow: Boolean = false
    private var hasShownSignInSuccess: Boolean = false
    private var skipNextResumeAuthRefresh: Boolean = false
    private var latestAuthState: AuthUiState = AuthUiState()

    private data class TopAppRow(
        val container: View,
        val icon: ImageView,
        val name: TextView,
        val minutes: TextView,
        val trend: TextView
    )

    private data class TopAppEntry(
        val name: String,
        val minutes: Int
    )

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val idTokenResult = googleAuthClient.extractIdToken(result.data)
        val idToken = idTokenResult.idToken

        if (idToken.isNullOrBlank()) {
            val fallback = if (result.resultCode == Activity.RESULT_CANCELED) {
                "Google sign-in was canceled."
            } else {
                getString(R.string.auth_google_token_error)
            }
            val errorMessage = idTokenResult.errorMessage ?: fallback
            authViewModel.setAuthError(errorMessage)
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        authViewModel.signInWithGoogle(idToken)
    }

    private val usageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUsageAccessState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupBottomNavigation()
        initViewModels()
        observeState()
        updateUsageAccessState()

        signInButton.setOnClickListener {
            skipNextResumeAuthRefresh = true
            signInLauncher.launch(googleAuthClient.signInIntent())
        }
        signOutButton.setOnClickListener {
            authViewModel.signOut()
        }
        usageAccessButton.setOnClickListener {
            usageAccessLauncher.launch(UsageAccessHelper.createUsageAccessIntent())
        }
        runMonitoringNowButton.setOnClickListener {
            authViewModel.refreshAuthState()
            if (!authViewModel.hasActiveSession()) {
                Toast.makeText(this, getString(R.string.sign_in_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MonitoringScheduler.runMonitoringNow(applicationContext)
            Toast.makeText(this, getString(R.string.run_now_enqueued), Toast.LENGTH_SHORT).show()
            scheduleDashboardRefresh()
        }
        runSyncNowButton.setOnClickListener {
            authViewModel.refreshAuthState()
            if (!authViewModel.hasActiveSession()) {
                Toast.makeText(this, getString(R.string.sign_in_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MonitoringScheduler.runSyncNow(applicationContext)
            Toast.makeText(this, getString(R.string.run_now_enqueued), Toast.LENGTH_SHORT).show()
            scheduleDashboardRefresh()
        }
        topAppsCard.setOnClickListener {
            openTopAppsScreen()
        }
        topAppsList.setOnClickListener {
            openTopAppsScreen()
        }
        topAppsEmptyState.setOnClickListener {
            openTopAppsScreen()
        }
        insightRow1.setOnClickListener {
            openTopAppsScreen(packageFilter = "com.google.android.youtube", query = "YouTube")
        }
        insightRow2.setOnClickListener {
            openTopAppsScreen(packageFilter = "com.google.android.youtube", query = "YouTube")
        }
        insightRow3.setOnClickListener {
            openTopAppsScreen(packageFilter = "com.google.android.youtube", query = "YouTube")
        }
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeAuthRefresh) {
            skipNextResumeAuthRefresh = false
            return
        }
        authViewModel.refreshAuthState()
    }

    private fun bindViews() {
        signInButton = findViewById(R.id.btnGoogleSignIn)
        signOutButton = findViewById(R.id.btnSignOut)
        usageAccessButton = findViewById(R.id.btnGrantUsageAccess)
        runMonitoringNowButton = findViewById(R.id.btnRunMonitoringNow)
        runSyncNowButton = findViewById(R.id.btnRunSyncNow)
        statusText = findViewById(R.id.tvAuthStatus)
        progressBar = findViewById(R.id.pbAuth)
        dashboardStatusText = findViewById(R.id.tvDashboardStatus)
        dashboardTodayText = findViewById(R.id.tvTodayUsage)
        dashboardTopAppsText = findViewById(R.id.tvTopApps)
        dashboardDeviceUsageText = findViewById(R.id.tvDeviceUsage)
        dashboardLastUpdatedText = findViewById(R.id.tvLastUpdated)
        bottomNav = findViewById(R.id.bottomNav)
        dashboardTabContent = findViewById(R.id.dashboardTabContent)
        alertsTabContent = findViewById(R.id.alertsTabContent)
        rulesTabContent = findViewById(R.id.rulesTabContent)
        settingsTabContent = findViewById(R.id.settingsTabContent)
        topAppsCard = findViewById(R.id.topAppsCard)
        chipScreenTime = findViewById(R.id.chipScreenTime)
        chipSessions = findViewById(R.id.chipSessions)
        chipRiskFlags = findViewById(R.id.chipRiskFlags)
        topAppsSkeleton = findViewById(R.id.topAppsSkeleton)
        topAppsEmptyState = findViewById(R.id.topAppsEmptyState)
        topAppsList = findViewById(R.id.topAppsList)
        insightsStatusText = findViewById(R.id.tvInsightsStatus)
        insightsEmptyState = findViewById(R.id.insightsEmptyState)
        insightsList = findViewById(R.id.insightsList)
        insightRow1 = findViewById(R.id.tvInsightRow1)
        insightRow2 = findViewById(R.id.tvInsightRow2)
        insightRow3 = findViewById(R.id.tvInsightRow3)
        topAppRows.clear()
        topAppRows += TopAppRow(
            container = findViewById(R.id.topAppRow1),
            icon = findViewById(R.id.topAppIcon1),
            name = findViewById(R.id.topAppName1),
            minutes = findViewById(R.id.topAppMinutes1),
            trend = findViewById(R.id.topAppTrend1)
        )
        topAppRows += TopAppRow(
            container = findViewById(R.id.topAppRow2),
            icon = findViewById(R.id.topAppIcon2),
            name = findViewById(R.id.topAppName2),
            minutes = findViewById(R.id.topAppMinutes2),
            trend = findViewById(R.id.topAppTrend2)
        )
        topAppRows += TopAppRow(
            container = findViewById(R.id.topAppRow3),
            icon = findViewById(R.id.topAppIcon3),
            name = findViewById(R.id.topAppName3),
            minutes = findViewById(R.id.topAppMinutes3),
            trend = findViewById(R.id.topAppTrend3)
        )
        buildInstalledLabelIndex()
    }

    private fun initViewModels() {
        googleAuthClient = GoogleAuthClient(this)

        val authRepository = AuthRepository(FirebaseAuth.getInstance())
        val userService = FirestoreUserService(FirebaseFirestore.getInstance())
        val familyService = FirestoreFamilyService(FirebaseFirestore.getInstance())
        val deviceService = FirestoreDeviceService(FirebaseFirestore.getInstance())
        val deviceInfoProvider = DeviceInfoProvider(this)
        localMonitoringRepository = LocalMonitoringRepository(applicationContext)

        authViewModel = ViewModelProvider(
            this,
            AuthViewModelFactory(
                authRepository = authRepository,
                firestoreUserService = userService,
                firestoreFamilyService = familyService,
                firestoreDeviceService = deviceService,
                deviceInfoProvider = deviceInfoProvider,
                localMonitoringRepository = localMonitoringRepository
            )
        )[AuthViewModel::class.java]

        dashboardViewModel = ViewModelProvider(
            this,
            DashboardViewModelFactory(
                dashboardRepository = DashboardRepository(FirebaseFirestore.getInstance()),
                localMonitoringRepository = localMonitoringRepository
            )
        )[DashboardViewModel::class.java]
    }

    private fun observeState() {
        authViewModel.uiState.observe(this) { state ->
            renderState(state)
        }
        dashboardViewModel.uiState.observe(this) { state ->
            renderDashboard(state)
        }
    }

    private fun renderState(state: AuthUiState) {
        val signedIn = state.isSignedIn && authViewModel.hasActiveSession() && state.userId.isNotBlank()
        latestAuthState = state.copy(isSignedIn = signedIn)
        progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        signInButton.isEnabled = !state.isLoading && !signedIn
        signInButton.visibility = if (signedIn) View.GONE else View.VISIBLE
        signOutButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        runMonitoringNowButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        runSyncNowButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (signedIn) {
            updateUsageAccessState()
        } else {
            usageAccessButton.visibility = View.GONE
        }
        runMonitoringNowButton.isEnabled = !state.isLoading
        runSyncNowButton.isEnabled = !state.isLoading

        if (!signedIn) {
            hasInitializedSignedInFlow = false
            hasShownSignInSuccess = false
            dashboardStatusText.visibility = View.GONE
            dashboardTodayText.visibility = View.GONE
            dashboardTopAppsText.visibility = View.GONE
            dashboardDeviceUsageText.visibility = View.GONE
            dashboardLastUpdatedText.visibility = View.GONE
            topAppsSkeleton.visibility = View.GONE
            topAppsList.visibility = View.GONE
            topAppsEmptyState.visibility = View.GONE
            insightsStatusText.visibility = View.GONE
            insightsList.visibility = View.GONE
            insightsEmptyState.visibility = View.GONE
        } else {
            dashboardTodayText.visibility = View.VISIBLE
            dashboardTopAppsText.visibility = View.VISIBLE
            dashboardDeviceUsageText.visibility = View.VISIBLE
            dashboardLastUpdatedText.visibility = View.VISIBLE
            insightsStatusText.visibility = View.VISIBLE
        }

        statusText.text = when {
            signedIn -> {
                onSignedIn(state.userId)
                getString(R.string.auth_signed_in, state.userDisplayName.ifBlank { state.userId })
            }
            !state.errorMessage.isNullOrBlank() -> state.errorMessage
            else -> getString(R.string.auth_signed_out)
        }
    }

    private fun onSignedIn(userId: String) {
        if (!hasShownSignInSuccess) {
            Toast.makeText(this, getString(R.string.auth_sign_in_success), Toast.LENGTH_SHORT).show()
            hasShownSignInSuccess = true
        }
        if (hasInitializedSignedInFlow) return
        hasInitializedSignedInFlow = true
        val deviceId = DeviceInfoProvider(this).getDeviceInfo().deviceId
        MonitoringScheduler.schedule(applicationContext)
        dashboardViewModel.loadSummary(userId, deviceId)
        refreshInsightsCard()
    }

    private fun renderDashboard(state: DashboardUiState) {
        if (!latestAuthState.isSignedIn) {
            dashboardStatusText.visibility = View.GONE
            return
        }

        if (state.isLoading) {
            dashboardStatusText.visibility = View.VISIBLE
            dashboardStatusText.text = getString(R.string.dashboard_loading)
            topAppsSkeleton.visibility = View.VISIBLE
            topAppsList.visibility = View.GONE
            topAppsEmptyState.visibility = View.GONE
            insightsStatusText.visibility = View.VISIBLE
            insightsStatusText.text = getString(R.string.insights_loading)
            insightsList.visibility = View.GONE
            insightsEmptyState.visibility = View.GONE
            return
        }

        if (!state.errorMessage.isNullOrBlank()) {
            dashboardStatusText.visibility = View.VISIBLE
            dashboardStatusText.text = state.errorMessage
        } else {
            dashboardStatusText.visibility = View.GONE
        }

        dashboardTodayText.text = getString(R.string.dashboard_today_usage, state.totalUsageMinutes)
        dashboardTopAppsText.text = getString(R.string.dashboard_top_apps, state.topAppsText)
        dashboardDeviceUsageText.text = getString(R.string.dashboard_device_usage, state.deviceUsageText)
        renderKpiChips(state)
        renderTopAppsRows(state.topAppsText)
        refreshInsightsCard()
        val lastUpdated = state.lastUpdatedAtMillis
        if (lastUpdated != null) {
            dashboardLastUpdatedText.visibility = View.VISIBLE
            dashboardLastUpdatedText.text = getString(R.string.dashboard_last_updated, formatTimestamp(lastUpdated))
        } else {
            dashboardLastUpdatedText.visibility = View.GONE
        }
    }

    private fun updateUsageAccessState() {
        val granted = UsageAccessHelper.hasUsageAccess(this)
        val shouldShowUsageButton = latestAuthState.isSignedIn && !granted
        usageAccessButton.visibility = if (shouldShowUsageButton) View.VISIBLE else View.GONE
        if (!granted) {
            dashboardStatusText.visibility = View.VISIBLE
            dashboardStatusText.text = getString(R.string.dashboard_unavailable)
        }
    }

    private fun scheduleDashboardRefresh() {
        if (!latestAuthState.isSignedIn) return
        val userId = latestAuthState.userId
        if (userId.isBlank()) return
        val deviceId = DeviceInfoProvider(this).getDeviceInfo().deviceId
        lifecycleScope.launch {
            delay(4000L)
            dashboardViewModel.loadSummary(userId, deviceId)
            refreshInsightsCard()
        }
    }

    private fun refreshInsightsCard() {
        lifecycleScope.launch {
            val dateKey = LocalDate.now().toString()
            val analyses = runCatching {
                localMonitoringRepository.getContentAnalysisForDate(dateKey)
            }.getOrElse {
                insightsStatusText.visibility = View.VISIBLE
                insightsStatusText.text = it.message ?: getString(R.string.dashboard_unavailable)
                insightsList.visibility = View.GONE
                insightsEmptyState.visibility = View.GONE
                return@launch
            }

            if (analyses.isEmpty()) {
                insightsStatusText.visibility = View.GONE
                insightsList.visibility = View.GONE
                insightsEmptyState.visibility = View.VISIBLE
                return@launch
            }

            val rows = analyses.distinctBy { it.channel.lowercase() }
                .take(3)
                .map { "${it.channel} - ${it.label.uppercase()}" }
            val rowViews = listOf(insightRow1, insightRow2, insightRow3)
            rowViews.forEachIndexed { index, rowView ->
                val text = rows.getOrNull(index)
                if (text == null) {
                    rowView.visibility = View.GONE
                } else {
                    rowView.visibility = View.VISIBLE
                    rowView.text = text
                }
            }

            insightsStatusText.visibility = View.VISIBLE
            insightsStatusText.text = getString(R.string.dashboard_last_updated, formatTimestamp(System.currentTimeMillis()))
            insightsList.visibility = View.VISIBLE
            insightsEmptyState.visibility = View.GONE
        }
    }

    private fun setupBottomNavigation() {
        bottomNav.selectedItemId = R.id.nav_dashboard
        showTab(R.id.nav_dashboard)
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
    }

    private fun showTab(itemId: Int) {
        dashboardTabContent.visibility = if (itemId == R.id.nav_dashboard) View.VISIBLE else View.GONE
        alertsTabContent.visibility = if (itemId == R.id.nav_alerts) View.VISIBLE else View.GONE
        rulesTabContent.visibility = if (itemId == R.id.nav_rules) View.VISIBLE else View.GONE
        settingsTabContent.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
    }

    private fun renderKpiChips(state: DashboardUiState) {
        val entries = parseTopApps(state.topAppsText)
        val sessions = entries.size
        val riskyApps = setOf("YouTube", "TikTok", "Instagram", "Snapchat", "Discord", "Facebook", "X", "Reddit")
        val riskFlags = entries.count { it.name in riskyApps && it.minutes >= 30 }

        chipScreenTime.text = getString(R.string.kpi_screen_time, state.totalUsageMinutes)
        chipSessions.text = getString(R.string.kpi_sessions, sessions)
        chipRiskFlags.text = getString(R.string.kpi_risk_flags, riskFlags)
    }

    private fun renderTopAppsRows(rawTopApps: String) {
        val entries = parseTopApps(rawTopApps)
        topAppsSkeleton.visibility = View.GONE

        if (entries.isEmpty()) {
            topAppsList.visibility = View.GONE
            topAppsEmptyState.visibility = View.VISIBLE
            return
        }

        topAppsList.visibility = View.VISIBLE
        topAppsEmptyState.visibility = View.GONE

        topAppRows.forEachIndexed { index, row ->
            val entry = entries.getOrNull(index)
            if (entry == null) {
                row.container.visibility = View.GONE
                return@forEachIndexed
            }
            row.container.visibility = View.VISIBLE
            row.name.text = entry.name
            row.minutes.text = "${entry.minutes}m"
            row.trend.text = resolveTrendLabel(entry)
            applyTopAppIcon(row.icon, entry.name)
        }
        previousTopAppMinutes.clear()
        entries.forEach { previousTopAppMinutes[it.name] = it.minutes }
    }

    private fun parseTopApps(rawTopApps: String): List<TopAppEntry> {
        if (rawTopApps.isBlank() || rawTopApps == "N/A") return emptyList()
        return rawTopApps.split(", ")
            .mapNotNull { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator <= 0 || separator >= entry.length - 1) return@mapNotNull null
                val name = entry.substring(0, separator).trim()
                val minutes = entry.substring(separator + 1).removeSuffix("m").trim().toIntOrNull() ?: return@mapNotNull null
                TopAppEntry(name = name, minutes = minutes)
            }
    }

    private fun resolveTrendLabel(entry: TopAppEntry): String {
        val previous = previousTopAppMinutes[entry.name] ?: return "NEW"
        return when {
            entry.minutes > previous -> "UP"
            entry.minutes < previous -> "DOWN"
            else -> "STEADY"
        }
    }

    private fun applyTopAppIcon(iconView: ImageView, appName: String) {
        if (appName == "Misc apps") {
            iconView.setImageResource(android.R.drawable.ic_menu_sort_by_size)
            return
        }
        val normalizedName = appName.trim().lowercase(Locale.getDefault())
        val packageName = AppCatalogMapper.resolvePackageForDisplayName(appName)
            ?: packageByAppLabel[appName.lowercase(Locale.getDefault())]
            ?: packageByAppLabel.entries.firstOrNull { (label, _) ->
                label.contains(normalizedName) || normalizedName.contains(label)
            }?.value
        if (!packageName.isNullOrBlank()) {
            runCatching {
                packageManager.getApplicationIcon(packageName)
            }.onSuccess { drawable ->
                iconView.setImageDrawable(drawable)
                return
            }
        }
        iconView.setImageResource(android.R.drawable.sym_def_app_icon)
    }

    private fun buildInstalledLabelIndex() {
        packageByAppLabel.clear()
        val installed = packageManager.getInstalledApplications(0)
        installed.forEach { appInfo ->
            val label = packageManager.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank()) {
                packageByAppLabel[label.lowercase(Locale.getDefault())] = appInfo.packageName
            }
        }
        // Ensure key social/video apps resolve even if OEM labels differ.
        packageByAppLabel.putIfAbsent("youtube", "com.google.android.youtube")
        packageByAppLabel.putIfAbsent("whatsapp", "com.whatsapp")
        packageByAppLabel.putIfAbsent("instagram", "com.instagram.android")
    }

    private fun openTopAppsScreen(packageFilter: String? = null, query: String? = null) {
        val intent = Intent(this, TopAppsActivity::class.java)
        if (!packageFilter.isNullOrBlank()) {
            intent.putExtra(TopAppsActivity.EXTRA_PACKAGE_FILTER, packageFilter)
        }
        if (!query.isNullOrBlank()) {
            intent.putExtra(TopAppsActivity.EXTRA_QUERY, query)
        }
        startActivity(intent)
    }

    private fun formatTimestamp(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return formatter.format(Date(timestampMillis))
    }
}

private class AuthViewModelFactory(
    private val authRepository: AuthRepository,
    private val firestoreUserService: FirestoreUserService,
    private val firestoreFamilyService: FirestoreFamilyService,
    private val firestoreDeviceService: FirestoreDeviceService,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val localMonitoringRepository: LocalMonitoringRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuthViewModel(
            authRepository = authRepository,
            firestoreUserService = firestoreUserService,
            firestoreFamilyService = firestoreFamilyService,
            firestoreDeviceService = firestoreDeviceService,
            deviceInfoProvider = deviceInfoProvider,
            localMonitoringRepository = localMonitoringRepository
        ) as T
    }
}

private class DashboardViewModelFactory(
    private val dashboardRepository: DashboardRepository,
    private val localMonitoringRepository: LocalMonitoringRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(
            dashboardRepository = dashboardRepository,
            localMonitoringRepository = localMonitoringRepository
        ) as T
    }
}
