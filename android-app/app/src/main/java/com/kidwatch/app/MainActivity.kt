package com.kidwatch.app

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.insights.AppCatalogMapper
import com.kidwatch.app.monitoring.MonitoringScheduler
import com.kidwatch.app.repository.DashboardRepository
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.DeviceLinkingService
import com.kidwatch.app.services.FaceCaptureService
import com.kidwatch.app.services.FaceCaptureState
import com.kidwatch.app.services.UsageAccessHelper
import com.kidwatch.app.ui.DashboardUiState
import com.kidwatch.app.ui.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var dashboardRepository: DashboardRepository

    private lateinit var localMonitoringRepository: LocalMonitoringRepository
    private lateinit var titleText: TextView
    private lateinit var dashboardStatusText: TextView
    private lateinit var dashboardTodayText: TextView
    private lateinit var dashboardTopAppsText: TextView
    private lateinit var dashboardLastUpdatedText: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var dashboardTabContent: View
    private lateinit var activityTabContent: View
    private lateinit var dashboardShowQrButton: ImageButton
    private lateinit var dashboardScanQrButton: ImageButton
    private lateinit var chipMy: Chip
    private lateinit var chipFamily: Chip
    private lateinit var dashboardMyContent: View
    private lateinit var dashboardFamilyContent: View
    private lateinit var familyDashboardStatusText: TextView
    private lateinit var familyDevicesUsageContainer: LinearLayout
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
    private lateinit var faceDetectionStatusText: TextView
    private lateinit var btnFaceDetectionPermissions: MaterialButton
    private lateinit var videosWatchedText: TextView
    private lateinit var btnOpenAccessibilitySettings: MaterialButton
    private val topAppRows = mutableListOf<TopAppRow>()
    private val previousTopAppMinutes = mutableMapOf<String, Int>()
    private val packageByAppLabel = mutableMapOf<String, String>()
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var deviceLinkingService: DeviceLinkingService
    private var preferredName: String = ""
    private var isNamePromptVisible: Boolean = false
    private var hasInitializedSignedInFlow: Boolean = false
    private var hasShownSignInSuccess: Boolean = false

    private val profilePrefs by lazy { getSharedPreferences("profile_prefs", MODE_PRIVATE) }

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

    private val usageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUsageAccessState()
        if (hasFaceCapturePermissions()) FaceCaptureService.start(applicationContext)
        refreshFaceCaptureStatus()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) FaceCaptureService.start(applicationContext)
        refreshFaceCaptureStatus()
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) return@registerForActivityResult
        handleScannedDeviceQr(raw)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupBottomNavigation()
        initViewModels()
        observeState()
        updateUsageAccessState()
        preferredName = profilePrefs.getString(PREF_PREFERRED_NAME, "").orEmpty()
        promptForPreferredNameIfNeeded()

        chipMy.setOnClickListener { showDashboardMy() }
        chipFamily.setOnClickListener { showDashboardFamily() }
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
        dashboardShowQrButton.setOnClickListener { showMyDeviceQr() }
        dashboardScanQrButton.setOnClickListener { scanFamilyDeviceQr() }
        btnFaceDetectionPermissions.setOnClickListener { requestFaceCapturePermissions() }
        btnOpenAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        onSignedIn()
    }

    override fun onResume() {
        super.onResume()
        refreshFaceCaptureStatus()
        refreshVideosWatchedCard()
    }

    private fun bindViews() {
        titleText = findViewById(R.id.tvTitle)
        chipMy = findViewById(R.id.chipMy)
        chipFamily = findViewById(R.id.chipFamily)
        dashboardMyContent = findViewById(R.id.dashboardMyContent)
        dashboardFamilyContent = findViewById(R.id.dashboardFamilyContent)
        dashboardStatusText = findViewById(R.id.tvDashboardStatus)
        dashboardTodayText = findViewById(R.id.tvTodayUsage)
        dashboardTopAppsText = findViewById(R.id.tvTopApps)
        dashboardLastUpdatedText = findViewById(R.id.tvLastUpdated)
        bottomNav = findViewById(R.id.bottomNav)
        dashboardTabContent = findViewById(R.id.dashboardTabContent)
        activityTabContent = findViewById(R.id.activityTabContent)
        dashboardShowQrButton = findViewById(R.id.btnDashboardShowQr)
        dashboardScanQrButton = findViewById(R.id.btnDashboardScanQr)
        familyDashboardStatusText = findViewById(R.id.tvFamilyDashboardStatus)
        familyDevicesUsageContainer = findViewById(R.id.familyDevicesUsageContainer)
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
        faceDetectionStatusText = findViewById(R.id.tvFaceDetectionStatus)
        btnFaceDetectionPermissions = findViewById(R.id.btnFaceDetectionPermissions)
        videosWatchedText = findViewById(R.id.tvVideosWatched)
        btnOpenAccessibilitySettings = findViewById(R.id.btnOpenAccessibilitySettings)
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
        deviceInfoProvider = DeviceInfoProvider(this)
        deviceLinkingService = DeviceLinkingService(FirebaseFirestore.getInstance())
        dashboardRepository = DashboardRepository(FirebaseFirestore.getInstance())
        localMonitoringRepository = LocalMonitoringRepository(applicationContext)

        dashboardViewModel = ViewModelProvider(
            this,
            DashboardViewModelFactory(
                dashboardRepository = dashboardRepository,
                localMonitoringRepository = localMonitoringRepository
            )
        )[DashboardViewModel::class.java]
    }

    private fun observeState() {
        dashboardViewModel.uiState.observe(this) { state ->
            renderDashboard(state)
        }
    }

    private fun onSignedIn() {
        updateUsageAccessState()
        if (!hasShownSignInSuccess) {
            Toast.makeText(this, "Device ready", Toast.LENGTH_SHORT).show()
            hasShownSignInSuccess = true
        }
        if (hasInitializedSignedInFlow) return
        hasInitializedSignedInFlow = true
        val deviceId = deviceInfoProvider.getDeviceInfo().deviceId
        MonitoringScheduler.schedule(applicationContext)
        if (hasFaceCapturePermissions()) {
            FaceCaptureService.start(applicationContext)
        }
        dashboardViewModel.loadSummary(deviceId)
        refreshFamilyDashboardData()
        refreshInsightsCard()
        updateDashboardGreeting()
    }

    private fun renderDashboard(state: DashboardUiState) {
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

    private fun hasFaceCapturePermissions(): Boolean {
        val hasCamera = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } else true
        return hasCamera && UsageAccessHelper.hasUsageAccess(this)
    }

    private fun hasCameraPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun refreshVideosWatchedCard() {
        if (!::videosWatchedText.isInitialized) return
        lifecycleScope.launch {
            val events = runCatching {
                localMonitoringRepository.getRecentVideoEvents(15)
            }.getOrElse { emptyList() }
            videosWatchedText.text = if (events.isEmpty()) {
                getString(R.string.videos_watched_empty)
            } else {
                events.joinToString("\n") { e ->
                    getString(R.string.videos_watched_item, e.title, e.channel)
                }
            }
        }
    }

    private fun refreshFaceCaptureStatus() {
        if (!::faceDetectionStatusText.isInitialized) return
        val missing = mutableListOf<String>()
        if (!hasCameraPermission()) missing.add(getString(R.string.face_detection_camera_needed))
        if (!UsageAccessHelper.hasUsageAccess(this)) missing.add(getString(R.string.face_detection_usage_needed))
        if (missing.isNotEmpty()) {
            faceDetectionStatusText.text = getString(R.string.face_detection_status_permissions_hint)
            btnFaceDetectionPermissions.visibility = View.VISIBLE
            return
        }
        btnFaceDetectionPermissions.visibility = View.GONE
        val lastAt = FaceCaptureState.lastFaceDetectedAt
        if (lastAt == 0L) {
            faceDetectionStatusText.text = getString(R.string.face_detection_status_no_viewer)
            return
        }
        val ago = formatTimeAgo(System.currentTimeMillis() - lastAt)
        faceDetectionStatusText.text = getString(R.string.face_detection_status_viewer_seen, ago)
    }

    private fun formatTimeAgo(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> getString(R.string.time_ago_seconds, sec)
            else -> getString(R.string.time_ago_minutes, sec / 60)
        }
    }

    private fun requestFaceCapturePermissions() {
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (!UsageAccessHelper.hasUsageAccess(this)) {
            usageAccessLauncher.launch(UsageAccessHelper.createUsageAccessIntent())
            return
        }
        FaceCaptureService.start(applicationContext)
        refreshFaceCaptureStatus()
    }

    private fun updateUsageAccessState() {
        val granted = UsageAccessHelper.hasUsageAccess(this)
        if (!granted) {
            dashboardStatusText.visibility = View.VISIBLE
            dashboardStatusText.text = getString(R.string.dashboard_unavailable)
        }
    }

    private fun scheduleDashboardRefresh() {
        val deviceId = deviceInfoProvider.getDeviceInfo().deviceId
        lifecycleScope.launch {
            delay(4000L)
            dashboardViewModel.loadSummary(deviceId)
            refreshFamilyDashboardData()
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
        activityTabContent.visibility = if (itemId == R.id.nav_activity) View.VISIBLE else View.GONE
        if (itemId == R.id.nav_activity) {
            refreshFaceCaptureStatus()
            refreshVideosWatchedCard()
        }
    }

    private fun showDashboardMy() {
        chipMy.isChecked = true
        chipFamily.isChecked = false
        dashboardMyContent.visibility = View.VISIBLE
        dashboardFamilyContent.visibility = View.GONE
    }

    private fun showDashboardFamily() {
        chipMy.isChecked = false
        chipFamily.isChecked = true
        dashboardMyContent.visibility = View.GONE
        dashboardFamilyContent.visibility = View.VISIBLE
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

    private fun createInfoRow(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(currentTextColor)
        }

    private suspend fun ensureLocalDeviceProfile() {
        val userId = resolveLocalIdentity()
        val localDevice = deviceInfoProvider.getDeviceInfo()
        deviceLinkingService.upsertDeviceProfile(
            localDevice,
            userId,
            preferredName.ifBlank { localDevice.deviceName }
        )
    }

    private fun clearFamilyDashboardUi() {
        familyDashboardStatusText.text = getString(R.string.family_dashboard_empty)
        familyDevicesUsageContainer.removeAllViews()
    }

    private fun refreshFamilyDashboardData() {
        val localDeviceId = deviceInfoProvider.getDeviceInfo().deviceId
        familyDashboardStatusText.text = getString(R.string.family_dashboard_loading)
        lifecycleScope.launch {
            runCatching {
                ensureLocalDeviceProfile()
                dashboardRepository.fetchFamilyDevicesSummary(localDeviceId)
            }.onSuccess { summaries ->
                familyDevicesUsageContainer.removeAllViews()
                if (summaries.isEmpty()) {
                    familyDashboardStatusText.text = getString(R.string.family_dashboard_empty)
                    return@onSuccess
                }
                val activeDevices = summaries.count { it.totalMinutes > 0 }
                val latestSyncedAt = summaries.mapNotNull { it.lastSyncedAtMillis }.maxOrNull()
                familyDashboardStatusText.text = if (latestSyncedAt != null) {
                    getString(
                        R.string.family_dashboard_synced_count_with_time,
                        activeDevices,
                        summaries.size,
                        formatTimestamp(latestSyncedAt)
                    )
                } else {
                    getString(R.string.family_dashboard_synced_count, activeDevices, summaries.size)
                }
                familyDevicesUsageContainer.addView(createFamilyOverviewChartCard(summaries))
                summaries.forEach { summary ->
                    familyDevicesUsageContainer.addView(
                        createFamilyDeviceUsageCard(
                            summary = summary,
                            maxFamilyMinutes = summaries.maxOfOrNull { it.totalMinutes } ?: 0
                        )
                    )
                }
            }.onFailure { throwable ->
                familyDashboardStatusText.text = throwable.message ?: getString(R.string.dashboard_unavailable)
            }
        }
    }

    private fun createFamilyOverviewChartCard(
        summaries: List<DashboardRepository.FamilyDeviceUsageSummary>
    ): MaterialCardView {
        val maxMinutes = (summaries.maxOfOrNull { it.totalMinutes } ?: 0).coerceAtLeast(1)
        val card = MaterialCardView(this).apply {
            radius = 18f
            cardElevation = 0f
            strokeWidth = 1
            setContentPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(
            createInfoRow(getString(R.string.family_overview_chart_title)).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            }
        )
        column.addView(
            createInfoRow(getString(R.string.family_overview_chart_subtitle)).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            }
        )

        summaries.forEachIndexed { index, summary ->
            if (index > 0) {
                column.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(8)
                    )
                })
            }
            column.addView(createFamilyUsageBarRow(summary.displayName, summary.totalMinutes, maxMinutes))
        }
        card.addView(column)
        return card
    }

    private fun createFamilyUsageBarRow(label: String, minutes: Int, maxMinutes: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val labelView = createInfoRow(label).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        val valueView = createInfoRow(getString(R.string.family_usage_minutes, minutes)).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        }
        header.addView(labelView)
        header.addView(valueView)

        val progress = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            max = 100
            progress = ((minutes * 100f) / maxMinutes).toInt().coerceIn(0, 100)
            trackCornerRadius = dp(6)
        }
        row.addView(header)
        row.addView(progress)
        return row
    }

    private fun createFamilyDeviceUsageCard(
        summary: DashboardRepository.FamilyDeviceUsageSummary,
        maxFamilyMinutes: Int
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = 18f
            cardElevation = 0f
            setContentPadding(24, 24, 24, 24)
            strokeWidth = 1
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(createInfoRow(summary.displayName).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        })
        column.addView(
            createInfoRow(
                summary.lastSyncedAtMillis?.let {
                    getString(R.string.family_device_last_synced, formatTimestamp(it))
                } ?: getString(R.string.family_device_not_synced)
            ).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            }
        )
        column.addView(
            createFamilyUsageBarRow(
                label = getString(R.string.family_today_label),
                minutes = summary.totalMinutes,
                maxMinutes = maxFamilyMinutes.coerceAtLeast(1)
            ).apply {
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = dp(10)
                layoutParams = params
            }
        )
        column.addView(
            createInfoRow("Top apps: ${summary.topAppsText}").apply {
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = dp(8)
                layoutParams = params
            }
        )
        if (!summary.isLocalDevice) {
            column.addView(
                MaterialButton(this).apply {
                    text = getString(R.string.profile_action_unlink)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(12)
                    }
                    setOnClickListener { unlinkFamilyDevice(summary.deviceId) }
                }
            )
        }
        card.addView(column)
        return card
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun unlinkFamilyDevice(remoteDeviceId: String) {
        val localDeviceId = deviceInfoProvider.getDeviceInfo().deviceId
        lifecycleScope.launch {
            runCatching {
                deviceLinkingService.unlinkDevicesBidirectional(localDeviceId, remoteDeviceId)
            }.onSuccess {
                Toast.makeText(this@MainActivity, getString(R.string.profile_status_link_removed), Toast.LENGTH_SHORT).show()
                refreshFamilyDashboardData()
            }.onFailure { throwable ->
                Toast.makeText(this@MainActivity, throwable.message ?: getString(R.string.dashboard_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMyDeviceQr() {
        if (preferredName.isBlank()) {
            promptForPreferredNameIfNeeded(force = true)
            return
        }
        dashboardStatusText.text = getString(R.string.profile_status_qr_generating)
        lifecycleScope.launch { ensureLocalDeviceProfile() }
        val info = deviceInfoProvider.getDeviceInfo()
        val resolvedIdentity = resolveLocalIdentity()
        val payload = JSONObject()
            .put("userId", resolvedIdentity)
            .put("deviceId", info.deviceId)
            .put("deviceName", info.deviceName)
            .put("model", info.model)
            .put("preferredName", preferredName)
            .put("isOfflineIdentity", false)
            .toString()

        val bitmap = runCatching {
            createQrBitmap(payload, 680)
        }.getOrElse {
            Log.e("KidWatchLinking", "showMyDeviceQr failed", it)
            dashboardStatusText.text = it.message ?: getString(R.string.profile_qr_invalid)
            Toast.makeText(this, getString(R.string.profile_qr_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        dashboardStatusText.text = getString(R.string.profile_status_qr_ready)
        showQrDialog(bitmap)
    }

    private fun showQrDialog(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_qr_dialog_title))
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun scanFamilyDeviceQr() {
        val options = ScanOptions().apply {
            setPrompt("Scan family device QR")
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setBeepEnabled(false)
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setOrientationLocked(true)
        }
        qrScanLauncher.launch(options)
    }

    private fun handleScannedDeviceQr(raw: String) {
        val parsed = runCatching {
            val json = JSONObject(raw)
            QrDevicePayload(
                userId = json.getString("userId"),
                deviceId = json.getString("deviceId"),
                deviceName = json.optString("deviceName", "Family device"),
                model = json.optString("model", "Unknown"),
                preferredName = json.optString("preferredName", "")
            )
        }.getOrNull()

        if (parsed == null) {
            Toast.makeText(this, getString(R.string.profile_qr_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        linkScannedDevice(parsed, resolveLocalIdentity())
    }

    private fun linkScannedDevice(payload: QrDevicePayload, approverId: String) {
        val localDevice = deviceInfoProvider.getDeviceInfo()
        lifecycleScope.launch {
            runCatching {
                deviceLinkingService.linkDevicesBidirectional(
                    localDeviceInfo = localDevice,
                    localAuthUserId = approverId,
                    localPreferredName = preferredName.ifBlank { localDevice.deviceName },
                    remoteDeviceId = payload.deviceId,
                    remoteDeviceName = payload.deviceName,
                    remotePreferredName = payload.preferredName.ifBlank { payload.deviceName },
                    remoteModel = payload.model,
                    customName = payload.preferredName.ifBlank { payload.deviceName }
                )
            }.onSuccess {
                Toast.makeText(this@MainActivity, getString(R.string.profile_qr_link_success), Toast.LENGTH_SHORT).show()
                dashboardStatusText.text = getString(R.string.profile_qr_link_success)
                refreshFamilyDashboardData()
            }.onFailure { throwable ->
                Toast.makeText(this@MainActivity, throwable.message ?: getString(R.string.profile_qr_link_failed), Toast.LENGTH_LONG).show()
                dashboardStatusText.text = throwable.message ?: getString(R.string.profile_qr_link_failed)
            }
        }
    }

    private fun resolveLocalIdentity(): String {
        val deviceId = deviceInfoProvider.getDeviceInfo().deviceId
        return "local-${deviceId.take(12)}"
    }

    private fun createQrBitmap(contents: String, size: Int): Bitmap {
        val bitMatrix = MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }

    private fun promptForPreferredNameIfNeeded(force: Boolean = false) {
        if (isNamePromptVisible) return
        if (!force && preferredName.isNotBlank()) {
            updateDashboardGreeting()
            return
        }
        isNamePromptVisible = true
        val nameInput = TextInputEditText(this).apply {
            hint = getString(R.string.profile_onboarding_name_hint)
            setText(preferredName)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_onboarding_name_title))
            .setCancelable(false)
            .setView(nameInput)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = nameInput.text?.toString().orEmpty().trim()
                if (entered.isBlank()) {
                    dashboardStatusText.text = getString(R.string.profile_onboarding_name_required)
                    isNamePromptVisible = false
                    promptForPreferredNameIfNeeded(force = true)
                    return@setPositiveButton
                }
                preferredName = entered
                profilePrefs.edit().putString(PREF_PREFERRED_NAME, preferredName).apply()
                dashboardStatusText.text = getString(R.string.profile_name_saved, preferredName)
                updateDashboardGreeting()
                refreshFamilyDashboardData()
                isNamePromptVisible = false
            }
            .show()
    }

    private fun updateDashboardGreeting() {
        val name = preferredName.ifBlank { "there" }
        titleText.text = getString(R.string.dashboard_greeting_hi, name)
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

    private data class QrDevicePayload(
        val userId: String,
        val deviceId: String,
        val deviceName: String,
        val model: String,
        val preferredName: String
    )

    private companion object {
        private const val PREF_PREFERRED_NAME = "preferred_name"
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
