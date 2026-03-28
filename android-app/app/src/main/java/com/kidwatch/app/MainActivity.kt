package com.kidwatch.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.analytics.AnalyticsTracker
import com.kidwatch.app.data.local.entity.IdentityClusterEntity
import com.kidwatch.app.data.local.entity.PersonProfileEntity
import com.kidwatch.app.insights.AppCatalogMapper
import com.kidwatch.app.monitoring.MonitoringScheduler
import com.kidwatch.app.repository.DashboardRepository
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.AccessibilityServiceState
import com.kidwatch.app.services.AutoMonitoringService
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.DeviceLinkingService
import com.kidwatch.app.services.EvidenceCaptureService
import com.kidwatch.app.services.EvidencePreferences
import com.kidwatch.app.services.MediaProjectionPermissionStore
import com.kidwatch.app.services.FaceCaptureState
import com.kidwatch.app.services.MonitoringPolicyCatalog
import com.kidwatch.app.services.PermissionGuideAction
import com.kidwatch.app.services.PermissionGuideNavigator
import com.kidwatch.app.services.PermissionGuideState
import com.kidwatch.app.services.PermissionGuidance
import com.kidwatch.app.services.PermissionRequirement
import com.kidwatch.app.services.TesterProfileStore
import com.kidwatch.app.services.UsageAccessHelper
import com.kidwatch.app.ui.ActivityFeedFilter
import com.kidwatch.app.ui.ActivityFeedFooterAdapter
import com.kidwatch.app.ui.ActivityFeedHeaderAdapter
import com.kidwatch.app.ui.ActivityFeedUiState
import com.kidwatch.app.ui.ActivityFeedViewModel
import com.kidwatch.app.ui.ActivityFeedViewModelFactory
import com.kidwatch.app.ui.ActivityEvidenceUi
import com.kidwatch.app.ui.ActivitySessionFeedAdapter
import com.kidwatch.app.ui.DashboardUiState
import com.kidwatch.app.ui.DashboardViewModel
import com.kidwatch.app.ui.ManageEvidenceState
import com.kidwatch.app.ui.ManagePersonProfileUi
import com.kidwatch.app.ui.ManagePermissionState
import com.kidwatch.app.ui.ManageUiState
import com.kidwatch.app.ui.ManageViewModel
import com.kidwatch.app.ui.ManageViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var activityFeedViewModel: ActivityFeedViewModel
    private lateinit var manageViewModel: ManageViewModel
    private lateinit var dashboardRepository: DashboardRepository

    private lateinit var localMonitoringRepository: LocalMonitoringRepository
    private lateinit var titleText: TextView
    private lateinit var dashboardStatusText: TextView
    private lateinit var dashboardTodayText: TextView
    private lateinit var dashboardTopAppsText: TextView
    private lateinit var dashboardLastUpdatedText: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var dashboardTabContent: View
    private lateinit var activityTabContent: RecyclerView
    private lateinit var manageTabContent: NestedScrollView
    private lateinit var dashboardEvidenceCaptureButton: MaterialButton
    private lateinit var btnRunMonitoringNow: MaterialButton
    private lateinit var btnRunSyncNow: MaterialButton
    private lateinit var dashboardManageFamilyButton: MaterialButton
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
    private lateinit var manageStatusText: TextView
    private lateinit var manageDeviceNameText: TextView
    private lateinit var manageDeviceSummaryText: TextView
    private lateinit var manageLinkedNamesText: TextView
    private lateinit var manageOwnerNameText: TextView
    private lateinit var btnManageEditOwner: MaterialButton
    private lateinit var manageTesterSummaryText: TextView
    private lateinit var manageTesterMetaText: TextView
    private lateinit var btnManageEditTester: MaterialButton
    private lateinit var manageChildrenEmptyText: TextView
    private lateinit var manageChildrenContainer: LinearLayout
    private lateinit var btnManageAddChild: MaterialButton
    private lateinit var btnManageShowQr: MaterialButton
    private lateinit var btnManageScanQr: MaterialButton
    private lateinit var manageSectionThisDevice: View
    private lateinit var manageSectionMonitoringApps: View
    private lateinit var manageSectionUnknownViewers: View
    private lateinit var manageSectionPermissions: View
    private lateinit var manageMonitoringSummaryText: TextView
    private lateinit var manageCategoryChipGroup: ChipGroup
    private lateinit var manageUnknownClustersText: TextView
    private lateinit var manageClusterReviewContainer: LinearLayout
    private lateinit var manageClusterEmptyText: TextView
    private lateinit var btnManageOpenMonitoring: MaterialButton
    private lateinit var managePermissionsSummaryText: TextView
    private lateinit var managePermissionsManualHintText: TextView
    private lateinit var manageManualPermissionActions: LinearLayout
    private lateinit var manageUsageGuideCard: MaterialCardView
    private lateinit var manageUsageStatusText: TextView
    private lateinit var manageUsageBodyText: TextView
    private lateinit var manageAccessibilityGuideCard: MaterialCardView
    private lateinit var manageAccessibilityStatusText: TextView
    private lateinit var manageAccessibilityBodyText: TextView
    private lateinit var btnManageProjection: MaterialButton
    private lateinit var btnManageUsageAccess: MaterialButton
    private lateinit var btnManageCamera: MaterialButton
    private lateinit var btnManageAccessibility: MaterialButton
    private lateinit var managePrivacyBodyText: TextView
    private lateinit var btnManagePrivacy: MaterialButton
    private lateinit var btnManageTerms: MaterialButton
    private lateinit var btnManageEvidenceInfo: MaterialButton
    private lateinit var btnManageDeleteEvidence: MaterialButton
    private lateinit var btnManageClearHistory: MaterialButton
    private lateinit var btnManageUnlinkDevice: MaterialButton
    private val topAppRows = mutableListOf<TopAppRow>()
    private val previousTopAppMinutes = mutableMapOf<String, Int>()
    private val packageByAppLabel = mutableMapOf<String, String>()
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var deviceLinkingService: DeviceLinkingService
    private var preferredName: String = ""
    private var hasInitializedSignedInFlow: Boolean = false
    private var hasShownSignInSuccess: Boolean = false
    private var pendingManageSection: String? = null
    private var pendingEvidenceStart: Boolean = false
    private var awaitingProjectionConsent: Boolean = false
    private var activityPermissionBannerMessage: String? = null
    private var latestActivityFeedState: ActivityFeedUiState = ActivityFeedUiState(isInitialLoading = true)
    private var lastTrackedTabId: Int? = null

    private val profilePrefs by lazy {
        getSharedPreferences(ProfilePreferences.PREFS_NAME, MODE_PRIVATE)
    }
    private val testerProfileStore by lazy { TesterProfileStore(this) }
    private val analyticsTracker by lazy { AnalyticsTracker(this) }
    private val permissionGuidance by lazy { PermissionGuidance(this) }
    private var pendingGuideRequirement: PermissionRequirement? = null
    private var pendingGuideAction: PermissionGuideAction? = null

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

    private enum class EvidencePrimaryAction {
        ENABLE_AUTOMATIC,
        REFRESH_SCREENSHOTS,
        OPEN_MANAGE,
        NONE
    }

    private val usageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUsageAccessState()
        val usageGranted = UsageAccessHelper.hasUsageAccess(this)
        handlePendingPermissionGuideReturn(
            requirement = PermissionRequirement.USAGE_ACCESS,
            action = PermissionGuideAction.OPEN_USAGE_ACCESS_SETTINGS,
            grantedOverride = usageGranted
        )
        analyticsTracker.markUsageAccessGrantedIfNeeded(usageGranted)
        if (usageGranted) AutoMonitoringService.start(applicationContext)
        if (pendingEvidenceStart && UsageAccessHelper.hasUsageAccess(this)) {
            continueEvidenceStartFlow()
        } else if (pendingEvidenceStart && !UsageAccessHelper.hasUsageAccess(this)) {
            pendingEvidenceStart = false
        }
        activityFeedViewModel.loadFeed()
        refreshActivityFeedBanner()
        manageViewModel.load(preferredName)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        EvidencePreferences.setCameraCaptureEnabled(this, granted)
        analyticsTracker.markCameraGrantedIfNeeded(granted)
        if (UsageAccessHelper.hasUsageAccess(this)) AutoMonitoringService.start(applicationContext)
        if (granted && EvidencePreferences.isAutomaticEvidenceEnabled(this)) {
            EvidenceCaptureService.start(applicationContext)
        }
        if (pendingEvidenceStart && granted) {
            continueEvidenceStartFlow()
        } else if (pendingEvidenceStart && !granted) {
            pendingEvidenceStart = false
        }
        refreshActivityFeedBanner()
        manageViewModel.load(preferredName)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        awaitingProjectionConsent = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MediaProjectionPermissionStore.store(result.resultCode, data)
            EvidencePreferences.setScreenCaptureConfigured(this, true)
            EvidencePreferences.setScreenCaptureCurrentlyAvailable(this, true)
            if (pendingEvidenceStart) {
                enableAutomaticEvidence(showToast = true)
            } else {
                EvidenceCaptureService.start(applicationContext)
                Toast.makeText(this, getString(R.string.evidence_projection_refreshed), Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingEvidenceStart = false
        }
        refreshActivityFeedBanner()
        manageViewModel.load(preferredName)
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) return@registerForActivityResult
        handleScannedDeviceQr(raw)
    }

    private lateinit var activityFeedAdapter: ActivitySessionFeedAdapter
    private lateinit var activityFeedHeaderAdapter: ActivityFeedHeaderAdapter
    private lateinit var activityFeedFooterAdapter: ActivityFeedFooterAdapter
    private lateinit var activityFeedConcatAdapter: ConcatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analyticsTracker.noteAppOpen()
        testerProfileStore.ensureInstallState()
        preferredName = profilePrefs.getString(ProfilePreferences.PREF_PREFERRED_NAME, "").orEmpty()
        if (preferredName.isBlank() || !testerProfileStore.hasCompleteTesterProfile()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        pendingManageSection = intent.getStringExtra(EXTRA_OPEN_SECTION)

        bindViews()
        initViewModels()
        setupBottomNavigation()
        observeState()
        updateUsageAccessState()
        lifecycleScope.launch {
            val cleanupSummary = localMonitoringRepository.purgeInvalidEvidence()
            val scrubbedLinkCount = localMonitoringRepository.scrubInferredVideoLinks()
            if (cleanupSummary.totalRemoved > 0 || scrubbedLinkCount > 0) {
                activityFeedViewModel.loadFeed()
                manageViewModel.load(preferredName)
                scheduleDashboardRefresh()
            }
        }

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
            openActivityFeed(ActivityFeedFilter.CONTENT_APPS)
        }
        insightRow2.setOnClickListener {
            openActivityFeed(ActivityFeedFilter.CONTENT_APPS)
        }
        insightRow3.setOnClickListener {
            openActivityFeed(ActivityFeedFilter.CONTENT_APPS)
        }
        dashboardManageFamilyButton.setOnClickListener { openManageSection(MANAGE_SECTION_THIS_DEVICE) }
        dashboardEvidenceCaptureButton.setOnClickListener { handleDashboardEvidenceAction() }
        btnRunMonitoringNow.setOnClickListener {
            MonitoringScheduler.runMonitoringNow(applicationContext)
            Toast.makeText(this, getString(R.string.run_now_enqueued), Toast.LENGTH_SHORT).show()
            scheduleDashboardRefresh()
        }
        btnRunSyncNow.setOnClickListener {
            MonitoringScheduler.runSyncNow(applicationContext)
            Toast.makeText(this, getString(R.string.run_now_enqueued), Toast.LENGTH_SHORT).show()
            scheduleDashboardRefresh()
        }
        btnManageShowQr.setOnClickListener { showMyDeviceQr() }
        btnManageScanQr.setOnClickListener { scanFamilyDeviceQr() }
        btnManageEditOwner.setOnClickListener { showEditOwnerDialog() }
        btnManageEditTester.setOnClickListener { showEditTesterDialog() }
        btnManageAddChild.setOnClickListener { showChildProfileDialog() }
        btnManageOpenMonitoring.setOnClickListener {
            startActivity(Intent(this, MonitoringAppsActivity::class.java))
        }
        btnManageProjection.setOnClickListener { handleManageEvidenceAction() }
        btnManageUsageAccess.setOnClickListener {
            launchPermissionGuide(permissionGuidance.stateFor(PermissionRequirement.USAGE_ACCESS))
        }
        btnManageCamera.setOnClickListener {
            if (hasCameraPermission()) {
                manageViewModel.load(preferredName)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        btnManageAccessibility.setOnClickListener {
            launchPermissionGuide(permissionGuidance.stateFor(PermissionRequirement.ACCESSIBILITY))
        }
        btnManagePrivacy.setOnClickListener {
            startActivity(LegalDocumentActivity.createIntent(this, LegalDocumentActivity.TYPE_PRIVACY))
        }
        btnManageTerms.setOnClickListener {
            startActivity(LegalDocumentActivity.createIntent(this, LegalDocumentActivity.TYPE_TERMS))
        }
        btnManageEvidenceInfo.setOnClickListener {
            startActivity(LegalDocumentActivity.createIntent(this, LegalDocumentActivity.TYPE_EVIDENCE))
        }
        btnManageDeleteEvidence.setOnClickListener { confirmDeleteEvidence() }
        btnManageClearHistory.setOnClickListener { confirmClearMonitoringHistory() }
        btnManageUnlinkDevice.setOnClickListener { confirmUnlinkThisDevice() }
        onSignedIn()
    }

    override fun onResume() {
        super.onResume()
        if (pendingGuideAction != PermissionGuideAction.OPEN_USAGE_ACCESS_SETTINGS) {
            handlePendingPermissionGuideReturn()
        }
        EvidencePreferences.syncScreenCaptureAvailability(this, MediaProjectionPermissionStore.hasGrant())
        analyticsTracker.markAccessibilityEnabledIfNeeded(AccessibilityServiceState.isContentCaptureEnabled(this))
        analyticsTracker.markCameraGrantedIfNeeded(hasCameraPermission())
        analyticsTracker.markAutomaticEvidenceEnabledIfNeeded(EvidencePreferences.isAutomaticEvidenceEnabled(this))
        if (
            pendingEvidenceStart &&
            !awaitingProjectionConsent &&
            AccessibilityServiceState.isContentCaptureEnabled(this)
        ) {
            continueEvidenceStartFlow()
        }
        activityFeedViewModel.loadFeed()
        refreshActivityFeedBanner()
        manageViewModel.load(preferredName)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = intent.getIntExtra(EXTRA_OPEN_TAB, bottomNav.selectedItemId)
        pendingManageSection = intent.getStringExtra(EXTRA_OPEN_SECTION)
        if (bottomNav.selectedItemId == requestedTab) {
            showTab(requestedTab)
        } else {
            bottomNav.selectedItemId = requestedTab
        }
    }

    private fun bindViews() {
        titleText = findViewById(R.id.tvTitle)
        chipMy = findViewById(R.id.chipMy)
        chipFamily = findViewById(R.id.chipFamily)
        dashboardMyContent = findViewById(R.id.dashboardMyContent)
        dashboardFamilyContent = findViewById(R.id.dashboardFamilyContent)
        dashboardStatusText = findViewById(R.id.tvDashboardStatus)
        dashboardEvidenceCaptureButton = findViewById(R.id.btnDashboardEvidenceCapture)
        dashboardTodayText = findViewById(R.id.tvTodayUsage)
        dashboardTopAppsText = findViewById(R.id.tvTopApps)
        dashboardLastUpdatedText = findViewById(R.id.tvLastUpdated)
        bottomNav = findViewById(R.id.bottomNav)
        dashboardTabContent = findViewById(R.id.dashboardTabContent)
        activityTabContent = findViewById(R.id.activityTabContent)
        manageTabContent = findViewById(R.id.manageTabContent)
        btnRunMonitoringNow = findViewById(R.id.btnRunMonitoringNow)
        btnRunSyncNow = findViewById(R.id.btnRunSyncNow)
        dashboardManageFamilyButton = findViewById(R.id.btnDashboardManageFamily)
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
        manageStatusText = findViewById(R.id.tvManageStatus)
        manageDeviceNameText = findViewById(R.id.tvManageDeviceName)
        manageDeviceSummaryText = findViewById(R.id.tvManageDeviceSummary)
        manageLinkedNamesText = findViewById(R.id.tvManageLinkedNames)
        manageOwnerNameText = findViewById(R.id.tvManageOwnerName)
        btnManageEditOwner = findViewById(R.id.btnManageEditOwner)
        manageTesterSummaryText = findViewById(R.id.tvManageTesterSummary)
        manageTesterMetaText = findViewById(R.id.tvManageTesterMeta)
        btnManageEditTester = findViewById(R.id.btnManageEditTester)
        manageChildrenEmptyText = findViewById(R.id.tvManageChildrenEmpty)
        manageChildrenContainer = findViewById(R.id.layoutManageChildren)
        btnManageAddChild = findViewById(R.id.btnManageAddChild)
        btnManageShowQr = findViewById(R.id.btnManageShowQr)
        btnManageScanQr = findViewById(R.id.btnManageScanQr)
        manageSectionThisDevice = findViewById(R.id.manageSectionThisDevice)
        manageSectionMonitoringApps = findViewById(R.id.manageSectionMonitoringApps)
        manageSectionUnknownViewers = findViewById(R.id.manageSectionUnknownViewers)
        manageSectionPermissions = findViewById(R.id.manageSectionPermissions)
        manageMonitoringSummaryText = findViewById(R.id.tvManageMonitoringSummary)
        manageCategoryChipGroup = findViewById(R.id.manageCategoryChipGroup)
        manageUnknownClustersText = findViewById(R.id.tvManageUnknownClusters)
        manageClusterReviewContainer = findViewById(R.id.manageClusterReviewContainer)
        manageClusterEmptyText = findViewById(R.id.tvManageClusterEmpty)
        btnManageOpenMonitoring = findViewById(R.id.btnManageOpenMonitoring)
        managePermissionsSummaryText = findViewById(R.id.tvManagePermissionsSummary)
        managePermissionsManualHintText = findViewById(R.id.tvManagePermissionsManualHint)
        manageManualPermissionActions = findViewById(R.id.layoutManageManualPermissionActions)
        manageUsageGuideCard = findViewById(R.id.cardManageUsageGuide)
        manageUsageStatusText = findViewById(R.id.tvManageUsageStatus)
        manageUsageBodyText = findViewById(R.id.tvManageUsageBody)
        manageAccessibilityGuideCard = findViewById(R.id.cardManageAccessibilityGuide)
        manageAccessibilityStatusText = findViewById(R.id.tvManageAccessibilityStatus)
        manageAccessibilityBodyText = findViewById(R.id.tvManageAccessibilityBody)
        btnManageProjection = findViewById(R.id.btnManageProjection)
        btnManageUsageAccess = findViewById(R.id.btnManageUsageAccess)
        btnManageCamera = findViewById(R.id.btnManageCamera)
        btnManageAccessibility = findViewById(R.id.btnManageAccessibility)
        managePrivacyBodyText = findViewById(R.id.tvManagePrivacyBody)
        btnManagePrivacy = findViewById(R.id.btnManagePrivacy)
        btnManageTerms = findViewById(R.id.btnManageTerms)
        btnManageEvidenceInfo = findViewById(R.id.btnManageEvidenceInfo)
        btnManageDeleteEvidence = findViewById(R.id.btnManageDeleteEvidence)
        btnManageClearHistory = findViewById(R.id.btnManageClearHistory)
        btnManageUnlinkDevice = findViewById(R.id.btnManageUnlinkDevice)
        activityFeedHeaderAdapter = ActivityFeedHeaderAdapter(
            onOpenManage = { openManageSection() },
            onRefresh = {
                activityTabContent.scrollToPosition(0)
                activityFeedViewModel.refresh()
            },
            onFilterSelected = { filter ->
                activityTabContent.scrollToPosition(0)
                activityFeedViewModel.setFilter(filter)
            }
        )
        activityFeedFooterAdapter = ActivityFeedFooterAdapter(
            onRetry = { activityFeedViewModel.retryPageLoad() }
        )
        activityFeedAdapter = ActivitySessionFeedAdapter(this) { openSessionDetail(it.id) }
        activityFeedConcatAdapter = ConcatAdapter(
            activityFeedHeaderAdapter,
            activityFeedAdapter,
            activityFeedFooterAdapter
        )
        activityTabContent.layoutManager = LinearLayoutManager(this)
        activityTabContent.adapter = activityFeedConcatAdapter
        activityTabContent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val totalItems = activityFeedConcatAdapter.itemCount
                if (totalItems - lastVisible <= FEED_LOAD_MORE_THRESHOLD) {
                    activityFeedViewModel.loadNextPage()
                }
            }
        })
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
        activityFeedViewModel = ViewModelProvider(
            this,
            ActivityFeedViewModelFactory(localMonitoringRepository)
        )[ActivityFeedViewModel::class.java]
        manageViewModel = ViewModelProvider(
            this,
            ManageViewModelFactory(
                appContext = applicationContext,
                repository = localMonitoringRepository,
                deviceInfoProvider = deviceInfoProvider,
                deviceLinkingService = deviceLinkingService
            )
        )[ManageViewModel::class.java]
    }

    private fun observeState() {
        dashboardViewModel.uiState.observe(this) { state ->
            renderDashboard(state)
        }
        activityFeedViewModel.uiState.observe(this) { state ->
            renderActivityFeed(state)
        }
        manageViewModel.uiState.observe(this) { state ->
            renderManage(state)
        }
    }

    private fun onSignedIn() {
        updateUsageAccessState()
        analyticsTracker.markUsageAccessGrantedIfNeeded(UsageAccessHelper.hasUsageAccess(this))
        analyticsTracker.markAccessibilityEnabledIfNeeded(AccessibilityServiceState.isContentCaptureEnabled(this))
        analyticsTracker.markCameraGrantedIfNeeded(hasCameraPermission())
        analyticsTracker.markAutomaticEvidenceEnabledIfNeeded(EvidencePreferences.isAutomaticEvidenceEnabled(this))
        if (!hasShownSignInSuccess) {
            Toast.makeText(this, getString(R.string.device_ready_toast), Toast.LENGTH_SHORT).show()
            hasShownSignInSuccess = true
        }
        if (hasInitializedSignedInFlow) return
        hasInitializedSignedInFlow = true
        val deviceId = deviceInfoProvider.getDeviceInfo().deviceId
        MonitoringScheduler.schedule(applicationContext)
        if (UsageAccessHelper.hasUsageAccess(this)) {
            AutoMonitoringService.start(applicationContext)
        }
        if (EvidencePreferences.isAutomaticEvidenceEnabled(this)) {
            EvidencePreferences.setCameraCaptureEnabled(this, hasCameraPermission())
            EvidencePreferences.syncScreenCaptureAvailability(this, MediaProjectionPermissionStore.hasGrant())
            EvidenceCaptureService.start(applicationContext)
        }
        lifecycleScope.launch {
            localMonitoringRepository.syncMonitoredAppPolicies()
        }
        dashboardViewModel.loadSummary(deviceId)
        activityFeedViewModel.loadFeed()
        manageViewModel.load(preferredName)
        refreshFamilyDashboardData()
        refreshInsightsCard()
        updateDashboardGreeting()
        refreshActivityFeedBanner()
    }

    private fun renderDashboard(state: DashboardUiState) {
        if (state.isLoading) {
            showDashboardStatus(getString(R.string.dashboard_loading))
            topAppsSkeleton.visibility = View.VISIBLE
            topAppsList.visibility = View.GONE
            topAppsEmptyState.visibility = View.GONE
            insightsStatusText.visibility = View.VISIBLE
            insightsStatusText.text = getString(R.string.insights_loading)
            insightsList.visibility = View.GONE
            insightsEmptyState.visibility = View.GONE
            return
        }

        val topAppEntries = parseTopApps(state.topAppsText)
        val dashboardMessage = state.errorMessage?.takeIf { it.isNotBlank() }
            ?: resolveDashboardGuidance(state, topAppEntries)
        showDashboardStatus(dashboardMessage)

        dashboardTodayText.text = getString(R.string.dashboard_today_usage, state.totalUsageMinutes)
        dashboardTopAppsText.text = getString(R.string.dashboard_top_apps, state.topAppsText)
        renderKpiChips(state, topAppEntries)
        renderTopAppsRows(topAppEntries)
        refreshInsightsCard()
        val lastUpdated = state.lastUpdatedAtMillis
        if (lastUpdated != null) {
            dashboardLastUpdatedText.visibility = View.VISIBLE
            dashboardLastUpdatedText.text = getString(R.string.dashboard_last_updated, formatTimestamp(lastUpdated))
        } else {
            dashboardLastUpdatedText.visibility = View.GONE
        }
    }

    private fun hasCameraPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun renderActivityFeed(state: ActivityFeedUiState) {
        latestActivityFeedState = state
        activityFeedHeaderAdapter.submitState(state, activityPermissionBannerMessage)
        activityFeedAdapter.submitList(state.sessions)
        activityFeedFooterAdapter.submitState(state)
        refreshActivityFeedBanner()
    }

    private fun renderManage(state: ManageUiState) {
        if (state.isLoading) {
            manageStatusText.text = getString(R.string.dashboard_loading)
            return
        }

        val monitoringSummary = state.monitoringSummary
        manageDeviceNameText.text = state.deviceName.ifBlank {
            deviceInfoProvider.getDeviceInfo().deviceName
        }
        manageDeviceSummaryText.text = when (state.linkedDevices.size) {
            0 -> getString(R.string.manage_device_summary_none)
            1 -> getString(R.string.manage_device_summary_one, state.linkedDevices.size)
            else -> getString(R.string.manage_device_summary_many, state.linkedDevices.size)
        }
        manageLinkedNamesText.text = if (state.linkedDevices.isEmpty()) {
            ""
        } else {
            getString(
                R.string.manage_device_linked_names,
                state.linkedDevices.take(3).joinToString(", ") { it.displayName }
            )
        }
        manageLinkedNamesText.visibility = if (state.linkedDevices.isEmpty()) View.GONE else View.VISIBLE
        val testerProfile = state.testerProfile
        manageTesterSummaryText.text = testerProfile?.let {
            getString(R.string.manage_tester_display, it.testerName, it.maskedPhone)
        } ?: getString(R.string.manage_tester_missing)
        manageTesterMetaText.text = testerProfile?.let {
            getString(R.string.manage_tester_meta, it.testCohort)
        } ?: getString(R.string.manage_tester_meta, testerProfileStore.getState().testCohort)
        renderManageProfiles(state)

        if (monitoringSummary == null) {
            manageMonitoringSummaryText.text = state.errorMessage ?: getString(R.string.dashboard_unavailable)
            manageUnknownClustersText.text = state.errorMessage ?: getString(R.string.dashboard_unavailable)
            manageClusterReviewContainer.removeAllViews()
            manageClusterEmptyText.visibility = View.VISIBLE
            manageCategoryChipGroup.removeAllViews()
            managePermissionsSummaryText.text = state.errorMessage ?: getString(R.string.dashboard_unavailable)
            manageStatusText.text = state.errorMessage ?: getString(R.string.dashboard_unavailable)
            return
        }

        manageStatusText.text = resolveManageGuidance(state)
        manageMonitoringSummaryText.text = getString(
            R.string.manage_monitoring_summary,
            monitoringSummary.trackedCount,
            monitoringSummary.screenshotCount,
            monitoringSummary.faceCaptureCount
        )
        manageUnknownClustersText.text = getString(
            R.string.manage_monitoring_unknown_clusters,
            state.unknownClusterCount
        )
        managePrivacyBodyText.text = getString(
            R.string.manage_privacy_body,
            state.retentionDays
        )
        renderManageCategoryChips(monitoringSummary.recommendedCategoryCounts)
        managePermissionsSummaryText.text = buildManagePermissionSummary(state)
        renderManagePermissionGuides()
        renderEvidenceCaptureButtons(state)
        renderManageManualPermissionActions(state)
        renderManageUnknownClusters(state.unknownClusters)
        applyPendingManageSection()
    }

    private fun renderEvidenceCaptureButtons(state: ManageUiState) {
        dashboardEvidenceCaptureButton.text = when (resolveDashboardEvidenceAction(state)) {
            EvidencePrimaryAction.ENABLE_AUTOMATIC -> getString(R.string.evidence_projection_button_enable_auto)
            EvidencePrimaryAction.REFRESH_SCREENSHOTS -> getString(R.string.evidence_projection_button_refresh)
            EvidencePrimaryAction.OPEN_MANAGE -> getString(R.string.evidence_projection_button_open_manage)
            EvidencePrimaryAction.NONE -> getString(R.string.evidence_projection_button_open_manage)
        }
        when (resolveManageEvidenceAction(state)) {
            EvidencePrimaryAction.ENABLE_AUTOMATIC -> {
                btnManageProjection.visibility = View.VISIBLE
                btnManageProjection.text = getString(R.string.evidence_projection_button_enable_auto)
            }
            EvidencePrimaryAction.REFRESH_SCREENSHOTS -> {
                btnManageProjection.visibility = View.VISIBLE
                btnManageProjection.text = getString(R.string.evidence_projection_button_refresh)
            }
            else -> {
                btnManageProjection.visibility = View.GONE
            }
        }
    }

    private fun refreshActivityFeedBanner() {
        activityPermissionBannerMessage = when {
            !UsageAccessHelper.hasUsageAccess(this) -> getString(R.string.activity_feed_permissions_usage)
            !AccessibilityServiceState.isContentCaptureEnabled(this) -> getString(R.string.activity_feed_permissions_accessibility)
            !EvidencePreferences.isAutomaticEvidenceEnabled(this) -> getString(R.string.activity_feed_permissions_auto)
            !hasCameraPermission() -> getString(R.string.activity_feed_permissions_camera)
            EvidencePreferences.isScreenCaptureConfigured(this) && !EvidencePreferences.isScreenCaptureCurrentlyAvailable(this) ->
                getString(R.string.activity_feed_permissions_projection)
            else -> null
        }
        activityFeedHeaderAdapter.submitState(latestActivityFeedState, activityPermissionBannerMessage)
    }

    private fun renderManageCategoryChips(
        categories: List<LocalMonitoringRepository.MonitoringCategorySummary>
    ) {
        manageCategoryChipGroup.removeAllViews()
        categories
            .filter { it.installedCount > 0 }
            .forEach { category ->
                val chip = Chip(this).apply {
                    text = getString(
                        R.string.monitoring_policy_chip,
                        category.label,
                        category.trackedCount,
                        category.installedCount
                    )
                    isCheckable = false
                    isClickable = false
                    chipBackgroundColor = ContextCompat.getColorStateList(context, R.color.kw_card_surface_alt)
                }
                manageCategoryChipGroup.addView(chip)
            }
    }

    private fun renderManageProfiles(state: ManageUiState) {
        manageOwnerNameText.text = state.deviceOwnerProfile?.let { owner ->
            getString(R.string.manage_owner_display, owner.name)
        } ?: getString(R.string.manage_owner_missing)

        manageChildrenContainer.removeAllViews()
        manageChildrenEmptyText.visibility = if (state.childProfiles.isEmpty()) View.VISIBLE else View.GONE
        state.childProfiles.forEach { profile ->
            manageChildrenContainer.addView(createChildProfileCard(profile))
        }
    }

    private fun createChildProfileCard(profile: ManagePersonProfileUi): View {
        val card = MaterialCardView(this).apply {
            styleSurfaceCard(this, altBackground = true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            setContentPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(
            createInfoRow(
                if (profile.ageYears != null) {
                    getString(R.string.manage_child_display, profile.name, profile.ageYears)
                } else {
                    profile.name
                }
            ).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            }
        )
        column.addView(
            createInfoRow(getString(R.string.manage_child_row_body)).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            }
        )
        card.addView(column)
        card.setOnClickListener {
            showChildProfileDialog(
                existing = PersonProfileEntity(
                    id = profile.id,
                    name = profile.name,
                    role = profile.role,
                    ageYears = profile.ageYears,
                    isDeviceOwner = profile.isDeviceOwner,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
        }
        card.setOnLongClickListener {
            confirmDeleteChildProfile(profile)
            true
        }
        return card
    }

    private fun buildManagePermissionSummary(state: ManageUiState): String {
        val permissions = state.permissions
        val evidence = state.evidence
        val lines = buildList {
            add(
                if (evidence.automaticEvidenceEnabled) {
                    getString(R.string.evidence_status_auto_on)
                } else {
                    getString(R.string.evidence_status_auto_off)
                }
            )
            add(
                if (permissions.usageAccess) {
                    getString(R.string.evidence_status_usage_on)
                } else {
                    getString(R.string.evidence_status_usage_off)
                }
            )
            add(
                if (permissions.cameraAccess) {
                    getString(R.string.evidence_status_camera_on)
                } else {
                    getString(R.string.evidence_status_camera_off)
                }
            )
            add(
                if (permissions.accessibilityAccess) {
                    getString(R.string.evidence_status_accessibility_on)
                } else {
                    getString(R.string.evidence_status_accessibility_off)
                }
            )
            add(
                if (evidence.cameraCaptureEnabled && permissions.cameraAccess) {
                    getString(R.string.evidence_status_camera_capture_on)
                } else {
                    getString(R.string.evidence_status_camera_capture_off)
                }
            )
            add(
                if (evidence.screenCaptureCurrentlyAvailable && permissions.screenshotConsent) {
                    getString(R.string.evidence_status_projection_on)
                } else if (evidence.screenCaptureConfigured) {
                    getString(R.string.evidence_status_projection_paused)
                } else {
                    getString(R.string.evidence_status_projection_off)
                }
            )
        }
        return lines.joinToString("\n")
    }

    private fun renderManagePermissionGuides() {
        val usageState = permissionGuidance.stateFor(PermissionRequirement.USAGE_ACCESS)
        val accessibilityState = permissionGuidance.stateFor(PermissionRequirement.ACCESSIBILITY)
        applyPermissionGuide(
            state = usageState,
            card = manageUsageGuideCard,
            statusView = manageUsageStatusText,
            bodyView = manageUsageBodyText,
            button = btnManageUsageAccess
        )
        applyPermissionGuide(
            state = accessibilityState,
            card = manageAccessibilityGuideCard,
            statusView = manageAccessibilityStatusText,
            bodyView = manageAccessibilityBodyText,
            button = btnManageAccessibility
        )
    }

    private fun renderManageManualPermissionActions(state: ManageUiState) {
        val showUsage = !state.permissions.usageAccess
        val showCamera = !state.permissions.cameraAccess
        val showAccessibility = !state.permissions.accessibilityAccess
        val showProjection = resolveManageEvidenceAction(state) != EvidencePrimaryAction.NONE
        val showManualFallbacks = showUsage || showCamera || showAccessibility || showProjection

        managePermissionsManualHintText.visibility = if (showManualFallbacks) View.VISIBLE else View.GONE
        manageManualPermissionActions.visibility = if (showManualFallbacks) View.VISIBLE else View.GONE
        manageUsageGuideCard.visibility = if (showUsage) View.VISIBLE else View.GONE
        manageAccessibilityGuideCard.visibility = if (showAccessibility) View.VISIBLE else View.GONE
        btnManageCamera.visibility = if (showCamera) View.VISIBLE else View.GONE
        btnManageProjection.visibility = if (showProjection) View.VISIBLE else View.GONE
    }

    private fun applyPermissionGuide(
        state: PermissionGuideState,
        card: View,
        statusView: TextView,
        bodyView: TextView,
        button: MaterialButton
    ) {
        card.visibility = if (state.isReady) View.GONE else View.VISIBLE
        statusView.text = state.statusText
        statusView.setBackgroundResource(
            if (state.highlightStatus) R.drawable.bg_pill_orange else R.drawable.bg_pill_soft
        )
        statusView.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.highlightStatus) R.color.kw_on_surface else R.color.kw_on_primary_container
            )
        )
        bodyView.text = state.instructions
        button.text = state.ctaText.orEmpty()
    }

    private fun launchPermissionGuide(state: PermissionGuideState) {
        val intent = PermissionGuideNavigator.createIntent(this, state.ctaAction) ?: return
        permissionGuidance.markActionLaunched(state.ctaAction)
        pendingGuideRequirement = state.requirement
        pendingGuideAction = state.ctaAction
        analyticsTracker.logPermissionGuideLaunched(
            requirement = state.requirement.name.lowercase(),
            step = state.ctaAction.analyticsValue
        )
        if (state.ctaAction == PermissionGuideAction.OPEN_USAGE_ACCESS_SETTINGS) {
            usageAccessLauncher.launch(intent)
        } else {
            startActivity(intent)
        }
    }

    private fun handlePendingPermissionGuideReturn(
        requirement: PermissionRequirement? = pendingGuideRequirement,
        action: PermissionGuideAction? = pendingGuideAction,
        grantedOverride: Boolean? = null
    ) {
        val resolvedRequirement = requirement ?: return
        val resolvedAction = action ?: return
        val isGranted = grantedOverride ?: permissionGuidance.stateFor(resolvedRequirement).isReady
        analyticsTracker.logPermissionGuideReturned(
            requirement = resolvedRequirement.name.lowercase(),
            step = resolvedAction.analyticsValue,
            granted = isGranted
        )
        if (
            resolvedRequirement == pendingGuideRequirement &&
            resolvedAction == pendingGuideAction
        ) {
            pendingGuideRequirement = null
            pendingGuideAction = null
        }
    }

    private fun resolveManageGuidance(state: ManageUiState): String {
        return when {
            !state.permissions.usageAccess -> getString(R.string.manage_guidance_usage)
            !state.permissions.accessibilityAccess -> getString(R.string.manage_guidance_accessibility)
            !state.evidence.automaticEvidenceEnabled -> getString(R.string.manage_guidance_enable_automatic)
            !state.permissions.cameraAccess -> getString(R.string.manage_guidance_camera)
            state.evidence.screenCaptureConfigured && !state.evidence.screenCaptureCurrentlyAvailable ->
                getString(R.string.manage_guidance_projection)
            state.unknownClusterCount > 0 -> getString(
                R.string.manage_guidance_unknown_viewers,
                state.unknownClusterCount
            )
            state.childProfiles.isEmpty() -> getString(R.string.manage_guidance_add_children)
            state.linkedDevices.isEmpty() -> getString(R.string.manage_guidance_link_devices)
            state.monitoringSummary?.trackedCount == 0 -> getString(R.string.manage_guidance_pick_apps)
            else -> getString(R.string.manage_guidance_ready)
        }
    }

    private fun renderManageUnknownClusters(clusters: List<IdentityClusterEntity>) {
        manageClusterReviewContainer.removeAllViews()
        manageClusterEmptyText.visibility = if (clusters.isEmpty()) View.VISIBLE else View.GONE
        clusters.forEach { cluster ->
            manageClusterReviewContainer.addView(createClusterView(cluster))
        }
    }

    private fun requestProjectionConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        awaitingProjectionConsent = true
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun handleDashboardEvidenceAction() {
        when (resolveDashboardEvidenceAction()) {
            EvidencePrimaryAction.ENABLE_AUTOMATIC -> startAutomaticEvidenceFlow()
            EvidencePrimaryAction.REFRESH_SCREENSHOTS -> refreshScreenCaptureSession()
            EvidencePrimaryAction.OPEN_MANAGE -> openManageSection(MANAGE_SECTION_PERMISSIONS)
            EvidencePrimaryAction.NONE -> Unit
        }
    }

    private fun handleManageEvidenceAction() {
        when (resolveManageEvidenceAction()) {
            EvidencePrimaryAction.ENABLE_AUTOMATIC -> startAutomaticEvidenceFlow()
            EvidencePrimaryAction.REFRESH_SCREENSHOTS -> refreshScreenCaptureSession()
            EvidencePrimaryAction.OPEN_MANAGE -> openManageSection(MANAGE_SECTION_PERMISSIONS)
            EvidencePrimaryAction.NONE -> Unit
        }
    }

    private fun startAutomaticEvidenceFlow() {
        pendingEvidenceStart = true
        continueEvidenceStartFlow()
    }

    private fun refreshScreenCaptureSession() {
        if (!EvidencePreferences.isAutomaticEvidenceEnabled(this)) {
            startAutomaticEvidenceFlow()
            return
        }
        requestProjectionConsent()
    }

    private fun continueEvidenceStartFlow() {
        if (!UsageAccessHelper.hasUsageAccess(this)) {
            Toast.makeText(this, getString(R.string.evidence_usage_required_first), Toast.LENGTH_SHORT).show()
            launchPermissionGuide(permissionGuidance.stateFor(PermissionRequirement.USAGE_ACCESS))
            return
        }
        if (!AccessibilityServiceState.isContentCaptureEnabled(this)) {
            Toast.makeText(this, getString(R.string.evidence_accessibility_required_first), Toast.LENGTH_SHORT).show()
            launchPermissionGuide(permissionGuidance.stateFor(PermissionRequirement.ACCESSIBILITY))
            return
        }
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (MediaProjectionPermissionStore.hasGrant()) {
            enableAutomaticEvidence(showToast = true)
        } else {
            requestProjectionConsent()
        }
    }

    private fun enableAutomaticEvidence(showToast: Boolean) {
        pendingEvidenceStart = false
        val hasProjectionGrant = MediaProjectionPermissionStore.hasGrant()
        EvidencePreferences.markAutomaticEvidenceConfigured(
            context = this,
            cameraCaptureEnabled = hasCameraPermission(),
            screenCaptureConfigured = hasProjectionGrant || EvidencePreferences.isScreenCaptureConfigured(this),
            screenCaptureCurrentlyAvailable = hasProjectionGrant
        )
        analyticsTracker.markAutomaticEvidenceEnabledIfNeeded(true)
        AutoMonitoringService.start(applicationContext)
        EvidenceCaptureService.start(applicationContext)
        if (showToast) {
            Toast.makeText(this, getString(R.string.evidence_projection_enabled), Toast.LENGTH_SHORT).show()
        }
        refreshActivityFeedBanner()
        manageViewModel.load(preferredName)
    }

    private fun resolveDashboardEvidenceAction(state: ManageUiState? = manageViewModel.uiState.value): EvidencePrimaryAction {
        val permissions = state?.permissions ?: snapshotPermissionState()
        val evidence = state?.evidence ?: snapshotEvidenceState()
        return when {
            !permissions.usageAccess ||
                !permissions.accessibilityAccess ||
                !permissions.cameraAccess ||
                !evidence.automaticEvidenceEnabled -> EvidencePrimaryAction.ENABLE_AUTOMATIC
            evidence.screenCaptureConfigured && !evidence.screenCaptureCurrentlyAvailable ->
                EvidencePrimaryAction.REFRESH_SCREENSHOTS
            else -> EvidencePrimaryAction.OPEN_MANAGE
        }
    }

    private fun resolveManageEvidenceAction(state: ManageUiState? = manageViewModel.uiState.value): EvidencePrimaryAction {
        val permissions = state?.permissions ?: snapshotPermissionState()
        val evidence = state?.evidence ?: snapshotEvidenceState()
        return when {
            !permissions.usageAccess ||
                !permissions.accessibilityAccess ||
                !permissions.cameraAccess ||
                !evidence.automaticEvidenceEnabled -> EvidencePrimaryAction.ENABLE_AUTOMATIC
            evidence.screenCaptureConfigured && !evidence.screenCaptureCurrentlyAvailable ->
                EvidencePrimaryAction.REFRESH_SCREENSHOTS
            else -> EvidencePrimaryAction.NONE
        }
    }

    private fun snapshotPermissionState(): ManagePermissionState {
        return ManagePermissionState(
            usageAccess = UsageAccessHelper.hasUsageAccess(this),
            cameraAccess = hasCameraPermission(),
            accessibilityAccess = AccessibilityServiceState.isContentCaptureEnabled(this),
            screenshotConsent = MediaProjectionPermissionStore.hasGrant()
        )
    }

    private fun snapshotEvidenceState(): ManageEvidenceState {
        return ManageEvidenceState(
            automaticEvidenceEnabled = EvidencePreferences.isAutomaticEvidenceEnabled(this),
            automaticEvidenceEnabledAt = EvidencePreferences.getAutomaticEvidenceEnabledAt(this),
            cameraCaptureEnabled = EvidencePreferences.isCameraCaptureEnabled(this),
            screenCaptureConfigured = EvidencePreferences.isScreenCaptureConfigured(this),
            screenCaptureCurrentlyAvailable = EvidencePreferences.isScreenCaptureCurrentlyAvailable(this) &&
                MediaProjectionPermissionStore.hasGrant()
        )
    }

    private fun createClusterView(cluster: IdentityClusterEntity): View {
        val row = layoutInflater.inflate(R.layout.item_evidence_cluster, manageClusterReviewContainer, false)
        val preview = row.findViewById<ImageView>(R.id.ivClusterPreview)
        val title = row.findViewById<TextView>(R.id.tvClusterTitle)
        val body = row.findViewById<TextView>(R.id.tvClusterBody)
        title.text = getString(R.string.evidence_unknown_cluster_title)
        body.text = getString(R.string.evidence_unknown_cluster_body, cluster.sampleCount)

        val bitmap = ActivityEvidenceUi.loadBitmap(cluster.representativeCropPath, 180, 180)
        if (bitmap != null) {
            preview.setImageBitmap(bitmap)
            preview.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.setPadding(0, 0, 0, 0)
            preview.setOnClickListener {
                cluster.representativeCropPath?.let { imagePath ->
                    startActivity(
                        EvidenceImageViewerActivity.createIntent(
                            context = this,
                            imagePath = imagePath,
                            title = getString(R.string.evidence_unknown_cluster_title),
                            subtitle = getString(
                                R.string.evidence_unknown_cluster_body,
                                cluster.sampleCount
                            )
                        )
                    )
                }
            }
        } else {
            preview.setImageResource(R.drawable.ic_placeholder_apps)
            preview.scaleType = ImageView.ScaleType.CENTER_INSIDE
            preview.setPadding(dp(14), dp(14), dp(14), dp(14))
            preview.setOnClickListener(null)
        }

        row.findViewById<View>(R.id.btnClusterLabel).setOnClickListener {
            showLabelDialog(cluster)
        }
        return row
    }

    private fun showLabelDialog(cluster: IdentityClusterEntity) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.evidence_label_name_hint)
            setText(cluster.label.orEmpty())
        }
        val ageInput = TextInputEditText(this).apply {
            hint = getString(R.string.manage_child_age_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val ownerCheckbox = CheckBox(this).apply {
            text = getString(R.string.evidence_label_use_device_owner)
            visibility = View.GONE
        }
        val roleValues = listOf(
            "child" to getString(R.string.evidence_role_child),
            "parent" to getString(R.string.evidence_role_parent),
            "caregiver" to getString(R.string.evidence_role_caregiver),
            "other" to getString(R.string.evidence_role_other)
        )
        roleValues.forEachIndexed { index, (_, label) ->
            radioGroup.addView(
                RadioButton(this).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = roleValues[index].first == cluster.role ||
                        (cluster.role.isBlank() && index == 0)
                }
            )
        }
        container.addView(input)
        container.addView(ageInput)
        container.addView(ownerCheckbox)
        container.addView(radioGroup)

        fun updateRoleFields(role: String) {
            ageInput.visibility = if (role == "child") View.VISIBLE else View.GONE
            ownerCheckbox.visibility = if (role == "parent") View.VISIBLE else View.GONE
            if (role != "parent") ownerCheckbox.isChecked = false
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedRole = roleValues.getOrNull(
                radioGroup.indexOfChild(radioGroup.findViewById(checkedId))
            )?.first ?: "child"
            updateRoleFields(selectedRole)
        }
        updateRoleFields(cluster.role)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.evidence_label_dialog_title))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.evidence_label_save) { _, _ ->
                lifecycleScope.launch {
                    val selectedRole = roleValues.getOrNull(
                        radioGroup.indexOfChild(radioGroup.findViewById(radioGroup.checkedRadioButtonId))
                    )?.first ?: "child"
                    val label = input.text?.toString().orEmpty().trim().ifBlank {
                        when {
                            ownerCheckbox.isChecked -> manageViewModel.uiState.value?.deviceOwnerProfile?.name
                                ?: getString(R.string.evidence_role_parent)
                            selectedRole == "parent" -> getString(R.string.evidence_role_parent)
                            selectedRole == "caregiver" -> getString(R.string.evidence_role_caregiver)
                            selectedRole == "other" -> getString(R.string.evidence_role_other)
                            else -> getString(R.string.evidence_role_child)
                        }
                    }
                    localMonitoringRepository.labelIdentityCluster(
                        clusterId = cluster.id,
                        label = label,
                        role = selectedRole,
                        ageYears = ageInput.text?.toString()?.trim()?.toIntOrNull(),
                        useDeviceOwnerProfile = ownerCheckbox.isChecked
                    )
                    manageViewModel.load(preferredName)
                    activityFeedViewModel.loadFeed()
                }
            }
            .show()
    }

    private fun showEditOwnerDialog() {
        val currentName = manageViewModel.uiState.value?.deviceOwnerProfile?.name
            ?: preferredName
            .ifBlank { deviceInfoProvider.getDeviceInfo().deviceName }
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.profile_onboarding_name_hint)
            setText(currentName)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.manage_owner_edit))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.evidence_label_save) { _, _ ->
                val updatedName = input.text?.toString().orEmpty().trim()
                if (updatedName.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    localMonitoringRepository.updateDeviceOwnerProfile(updatedName)
                    preferredName = updatedName
                    profilePrefs.edit().putString(ProfilePreferences.PREF_PREFERRED_NAME, updatedName).apply()
                    updateDashboardGreeting()
                    ensureLocalDeviceProfile()
                    refreshFamilyDashboardData()
                    manageViewModel.load(preferredName)
                    activityFeedViewModel.loadFeed()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.profile_name_saved, updatedName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showEditTesterDialog() {
        val testerState = testerProfileStore.getState()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val helperText = createInfoRow(
            if (testerState.phoneLast4.isBlank()) {
                getString(R.string.manage_tester_phone_helper)
            } else {
                getString(R.string.manage_tester_phone_helper_existing, testerState.phoneLast4)
            }
        ).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        val testerNameInput = TextInputEditText(this).apply {
            hint = getString(R.string.onboarding_tester_name_label)
            setText(testerState.testerName)
        }
        val testerPhoneInput = TextInputEditText(this).apply {
            hint = getString(R.string.onboarding_tester_phone_label)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        container.addView(helperText)
        container.addView(testerNameInput)
        container.addView(testerPhoneInput)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.manage_tester_edit))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.evidence_label_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                testerNameInput.error = null
                testerPhoneInput.error = null
                val updatedName = testerNameInput.text?.toString().orEmpty().trim()
                val updatedPhone = testerPhoneInput.text?.toString().orEmpty().trim()
                if (updatedName.isBlank()) {
                    testerNameInput.error = getString(R.string.onboarding_tester_name_required)
                    return@setOnClickListener
                }
                val saveResult = runCatching {
                    testerProfileStore.saveTesterProfile(updatedName, updatedPhone)
                }.getOrElse { error ->
                    testerPhoneInput.error = error.message ?: getString(R.string.onboarding_tester_phone_required)
                    return@setOnClickListener
                }
                if (saveResult.wasFirstRegistration) {
                    analyticsTracker.logTesterProfileRegistered()
                }
                MonitoringScheduler.runSyncNow(applicationContext)
                manageViewModel.load(preferredName)
                Toast.makeText(
                    this,
                    getString(R.string.manage_tester_saved, updatedName),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showChildProfileDialog(existing: PersonProfileEntity? = null) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val nameInput = TextInputEditText(this).apply {
            hint = getString(R.string.manage_child_name_hint)
            setText(existing?.name.orEmpty())
        }
        val ageInput = TextInputEditText(this).apply {
            hint = getString(R.string.manage_child_age_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existing?.ageYears?.toString().orEmpty())
        }
        container.addView(nameInput)
        container.addView(ageInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing == null) {
                    getString(R.string.manage_children_add)
                } else {
                    getString(R.string.manage_child_edit_title)
                }
            )
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.evidence_label_save) { _, _ ->
                val childName = nameInput.text?.toString().orEmpty().trim()
                if (childName.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    localMonitoringRepository.saveChildProfile(
                        id = existing?.id,
                        name = childName,
                        ageYears = ageInput.text?.toString()?.trim()?.toIntOrNull()
                    )
                    manageViewModel.load(preferredName)
                    activityFeedViewModel.loadFeed()
                }
            }
            .show()
    }

    private fun confirmDeleteChildProfile(profile: ManagePersonProfileUi) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.manage_child_delete_title, profile.name))
            .setMessage(getString(R.string.manage_child_delete_body))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.manage_child_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    localMonitoringRepository.deletePersonProfile(profile.id)
                    manageViewModel.load(preferredName)
                    activityFeedViewModel.loadFeed()
                }
            }
            .show()
    }

    private fun confirmDeleteEvidence() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.evidence_delete_title))
            .setMessage(getString(R.string.evidence_delete_body))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.evidence_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    runCatching { localMonitoringRepository.clearAllEvidence() }
                        .onSuccess {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.evidence_delete_done),
                                Toast.LENGTH_SHORT
                            ).show()
                            activityFeedViewModel.loadFeed()
                            manageViewModel.load(preferredName)
                            scheduleDashboardRefresh()
                        }
                        .onFailure { throwable ->
                            Toast.makeText(
                                this@MainActivity,
                                throwable.message ?: getString(R.string.dashboard_unavailable),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .show()
    }

    private fun confirmClearMonitoringHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.manage_clear_history_title))
            .setMessage(getString(R.string.manage_clear_history_body))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.manage_clear_history_confirm) { _, _ ->
                lifecycleScope.launch {
                    runCatching { localMonitoringRepository.clearMonitoringHistory() }
                        .onSuccess {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.manage_clear_history_done),
                                Toast.LENGTH_SHORT
                            ).show()
                            activityFeedViewModel.loadFeed()
                            manageViewModel.load(preferredName)
                            scheduleDashboardRefresh()
                        }
                        .onFailure { throwable ->
                            Toast.makeText(
                                this@MainActivity,
                                throwable.message ?: getString(R.string.dashboard_unavailable),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .show()
    }

    private fun confirmUnlinkThisDevice() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.manage_unlink_title))
            .setMessage(getString(R.string.manage_unlink_body))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.manage_unlink_confirm) { _, _ ->
                lifecycleScope.launch {
                    unlinkAllFamilyDevices()
                }
            }
            .show()
    }

    private suspend fun unlinkAllFamilyDevices() {
        val localDeviceId = deviceInfoProvider.getDeviceInfo().deviceId
        val linkedDevices = runCatching {
            deviceLinkingService.fetchLinkedDevices(localDeviceId)
        }.getOrElse { throwable ->
            Toast.makeText(
                this@MainActivity,
                throwable.message ?: getString(R.string.dashboard_unavailable),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (linkedDevices.isEmpty()) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.manage_unlink_empty),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        runCatching {
            linkedDevices.forEach { linked ->
                deviceLinkingService.unlinkDevicesBidirectional(localDeviceId, linked.remoteDeviceId)
            }
        }.onSuccess {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.manage_unlink_done),
                Toast.LENGTH_SHORT
            ).show()
            refreshFamilyDashboardData()
            manageViewModel.load(preferredName)
        }.onFailure { throwable ->
            Toast.makeText(
                this@MainActivity,
                throwable.message ?: getString(R.string.dashboard_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateUsageAccessState() {
        val granted = UsageAccessHelper.hasUsageAccess(this)
        if (!granted) {
            showDashboardStatus(getString(R.string.dashboard_unavailable))
        }
    }

    private fun scheduleDashboardRefresh() {
        val deviceId = deviceInfoProvider.getDeviceInfo().deviceId
        lifecycleScope.launch {
            delay(4000L)
            dashboardViewModel.loadSummary(deviceId)
            activityFeedViewModel.loadFeed()
            manageViewModel.load(preferredName)
            refreshFamilyDashboardData()
            refreshInsightsCard()
            refreshActivityFeedBanner()
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

            val distinctAnalyses = analyses.distinctBy { it.channel.lowercase() }
            val rows = distinctAnalyses
                .take(3)
                .map { "${it.channel} - ${formatInsightLabel(it.label)}" }
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
            insightsStatusText.text = getString(R.string.dashboard_channels_reviewed, distinctAnalyses.size)
            insightsList.visibility = View.VISIBLE
            insightsEmptyState.visibility = View.GONE
        }
    }

    private fun setupBottomNavigation() {
        val initialTab = intent.getIntExtra(EXTRA_OPEN_TAB, R.id.nav_dashboard)
        bottomNav.selectedItemId = initialTab
        showTab(initialTab)
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
    }

    private fun showTab(itemId: Int) {
        dashboardTabContent.visibility = if (itemId == R.id.nav_dashboard) View.VISIBLE else View.GONE
        activityTabContent.visibility = if (itemId == R.id.nav_activity) View.VISIBLE else View.GONE
        manageTabContent.visibility = if (itemId == R.id.nav_manage) View.VISIBLE else View.GONE
        if (itemId != lastTrackedTabId) {
            analyticsTracker.logTabViewed(
                when (itemId) {
                    R.id.nav_activity -> "activity"
                    R.id.nav_manage -> "manage"
                    else -> "dashboard"
                }
            )
            lastTrackedTabId = itemId
        }
        if (itemId == R.id.nav_activity) {
            activityFeedViewModel.ensureLoaded()
            refreshActivityFeedBanner()
        }
        if (itemId == R.id.nav_manage) {
            manageViewModel.load(preferredName)
            applyPendingManageSection()
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

    private fun renderKpiChips(state: DashboardUiState, entries: List<TopAppEntry>) {
        val sessions = entries.size
        val riskFlags = countRiskFlags(entries)

        chipScreenTime.text = getString(R.string.kpi_screen_time, state.totalUsageMinutes)
        chipSessions.text = getString(R.string.kpi_sessions, sessions)
        chipRiskFlags.text = getString(R.string.kpi_risk_flags, riskFlags)
    }

    private fun renderTopAppsRows(entries: List<TopAppEntry>) {
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

    private fun countRiskFlags(entries: List<TopAppEntry>): Int {
        val riskyApps = setOf(
            "YouTube",
            "YouTube Kids",
            "TikTok",
            "Instagram",
            "WhatsApp",
            "Snapchat",
            "Facebook",
            "Messenger",
            "Threads",
            "Telegram",
            "Discord",
            "X",
            "Reddit",
            "Netflix",
            "Prime Video",
            "Disney+ Hotstar",
            "JioCinema",
            "MX Player"
        )
        return entries.count { it.name in riskyApps && it.minutes >= 30 }
    }

    private fun resolveDashboardGuidance(
        state: DashboardUiState,
        entries: List<TopAppEntry>
    ): String {
        if (!UsageAccessHelper.hasUsageAccess(this)) {
            return getString(R.string.dashboard_guidance_permissions)
        }
        if (entries.isEmpty() || state.totalUsageMinutes == 0) {
            return getString(R.string.dashboard_guidance_empty)
        }
        if (countRiskFlags(entries) > 0) {
            return getString(R.string.dashboard_guidance_risk)
        }

        val topEntry = entries.maxByOrNull { it.minutes }
        if (topEntry != null &&
            state.totalUsageMinutes > 0 &&
            topEntry.minutes * 100 >= state.totalUsageMinutes * 60
        ) {
            return getString(R.string.dashboard_guidance_top_app, topEntry.name)
        }
        if (state.totalUsageMinutes >= 120) {
            return getString(R.string.dashboard_guidance_high_usage)
        }
        if (hasCameraPermission() && FaceCaptureState.lastFaceDetectedAt == 0L) {
            return getString(R.string.dashboard_guidance_viewer)
        }
        return getString(R.string.dashboard_guidance_steady)
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

    private fun formatInsightLabel(label: String): String {
        return label.split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase(Locale.getDefault()).replaceFirstChar { first ->
                    if (first.isLowerCase()) first.titlecase(Locale.getDefault()) else first.toString()
                }
            }
    }

    private fun applyTopAppIcon(iconView: ImageView, appName: String) {
        if (appName == "Misc apps") {
            iconView.setImageResource(R.drawable.ic_placeholder_apps)
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
        iconView.setImageResource(R.drawable.ic_placeholder_apps)
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.kw_on_surface))
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
            styleSurfaceCard(this)
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
            trackThickness = dp(10)
            setIndicatorColor(ContextCompat.getColor(this@MainActivity, R.color.kw_primary))
            trackColor = ContextCompat.getColor(this@MainActivity, R.color.kw_surface_variant)
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
            styleSurfaceCard(this, altBackground = true)
            setContentPadding(dp(20), dp(20), dp(20), dp(20))
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
            createInfoRow(getString(R.string.dashboard_top_apps, summary.topAppsText)).apply {
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
                manageViewModel.load(preferredName)
            }.onFailure { throwable ->
                Toast.makeText(this@MainActivity, throwable.message ?: getString(R.string.dashboard_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMyDeviceQr() {
        if (preferredName.isBlank()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }
        showDashboardStatus(getString(R.string.profile_status_qr_generating))
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
            showDashboardStatus(it.message ?: getString(R.string.profile_qr_invalid))
            Toast.makeText(this, getString(R.string.profile_qr_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        showDashboardStatus(getString(R.string.profile_status_qr_ready))
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
                showDashboardStatus(getString(R.string.profile_qr_link_success))
                refreshFamilyDashboardData()
                manageViewModel.load(preferredName)
            }.onFailure { throwable ->
                Toast.makeText(this@MainActivity, throwable.message ?: getString(R.string.profile_qr_link_failed), Toast.LENGTH_LONG).show()
                showDashboardStatus(throwable.message ?: getString(R.string.profile_qr_link_failed))
            }
        }
    }

    private fun openManageSection(section: String? = null) {
        pendingManageSection = section
        if (bottomNav.selectedItemId == R.id.nav_manage) {
            showTab(R.id.nav_manage)
        } else {
            bottomNav.selectedItemId = R.id.nav_manage
        }
    }

    private fun applyPendingManageSection() {
        val section = pendingManageSection ?: return
        val target = when (section) {
            MANAGE_SECTION_THIS_DEVICE -> manageSectionThisDevice
            MANAGE_SECTION_MONITORING_APPS -> manageSectionMonitoringApps
            MANAGE_SECTION_UNKNOWN_VIEWERS -> manageSectionUnknownViewers
            MANAGE_SECTION_PERMISSIONS -> manageSectionPermissions
            else -> null
        } ?: return
        manageTabContent.post {
            manageTabContent.smoothScrollTo(0, (target.top - dp(12)).coerceAtLeast(0))
            pendingManageSection = null
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

    private fun openActivityFeed(filter: ActivityFeedFilter = ActivityFeedFilter.ALL) {
        bottomNav.selectedItemId = R.id.nav_activity
        activityTabContent.scrollToPosition(0)
        activityFeedViewModel.setFilter(filter)
        refreshActivityFeedBanner()
    }

    private fun openSessionDetail(sessionId: Long) {
        startActivity(
            Intent(this, SessionDetailActivity::class.java).apply {
                putExtra(SessionDetailActivity.EXTRA_SESSION_ID, sessionId)
            }
        )
    }

    private fun formatTimestamp(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return formatter.format(Date(timestampMillis))
    }

    private fun showDashboardStatus(message: String) {
        dashboardStatusText.visibility = View.VISIBLE
        dashboardStatusText.text = message
    }

    private fun styleSurfaceCard(card: MaterialCardView, altBackground: Boolean = false) {
        card.radius = dp(28).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = dp(1)
        card.strokeColor = ContextCompat.getColor(this, R.color.kw_outline_variant)
        card.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (altBackground) R.color.kw_card_surface_alt else R.color.kw_card_surface
            )
        )
    }

    private data class QrDevicePayload(
        val userId: String,
        val deviceId: String,
        val deviceName: String,
        val model: String,
        val preferredName: String
    )

    companion object {
        private const val EXTRA_OPEN_TAB = "open_tab"
        private const val EXTRA_OPEN_SECTION = "open_section"
        private const val MANAGE_SECTION_THIS_DEVICE = "this_device"
        private const val MANAGE_SECTION_MONITORING_APPS = "monitoring_apps"
        private const val MANAGE_SECTION_UNKNOWN_VIEWERS = "unknown_viewers"
        private const val MANAGE_SECTION_PERMISSIONS = "permissions"
        private const val FEED_LOAD_MORE_THRESHOLD = 5

        fun createIntentForTab(
            context: Context,
            tabId: Int,
            openSection: String? = null
        ): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_TAB, tabId)
                if (!openSection.isNullOrBlank()) {
                    putExtra(EXTRA_OPEN_SECTION, openSection)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }

        fun permissionSectionId(): String = MANAGE_SECTION_PERMISSIONS
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
