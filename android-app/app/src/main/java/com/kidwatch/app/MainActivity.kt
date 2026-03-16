package com.kidwatch.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kidwatch.app.auth.AuthUiState
import com.kidwatch.app.auth.AuthViewModel
import com.kidwatch.app.auth.GoogleAuthClient
import com.kidwatch.app.monitoring.MonitoringScheduler
import com.kidwatch.app.repository.AuthRepository
import com.kidwatch.app.repository.DashboardRepository
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.FirestoreDeviceService
import com.kidwatch.app.services.FirestoreUserService
import com.kidwatch.app.services.UsageAccessHelper
import com.kidwatch.app.ui.DashboardUiState
import com.kidwatch.app.ui.DashboardViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var authViewModel: AuthViewModel
    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var googleAuthClient: GoogleAuthClient

    private lateinit var signInButton: AppCompatButton
    private lateinit var signOutButton: AppCompatButton
    private lateinit var usageAccessButton: AppCompatButton
    private lateinit var runMonitoringNowButton: AppCompatButton
    private lateinit var runSyncNowButton: AppCompatButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var dashboardStatusText: TextView
    private lateinit var dashboardTodayText: TextView
    private lateinit var dashboardTopAppsText: TextView
    private lateinit var dashboardDeviceUsageText: TextView
    private var hasInitializedSignedInFlow: Boolean = false
    private var hasShownSignInSuccess: Boolean = false
    private var skipNextResumeAuthRefresh: Boolean = false
    private var latestAuthState: AuthUiState = AuthUiState()

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
        }
        runSyncNowButton.setOnClickListener {
            authViewModel.refreshAuthState()
            if (!authViewModel.hasActiveSession()) {
                Toast.makeText(this, getString(R.string.sign_in_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MonitoringScheduler.runSyncNow(applicationContext)
            Toast.makeText(this, getString(R.string.run_now_enqueued), Toast.LENGTH_SHORT).show()
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
    }

    private fun initViewModels() {
        googleAuthClient = GoogleAuthClient(this)

        val authRepository = AuthRepository(FirebaseAuth.getInstance())
        val userService = FirestoreUserService(FirebaseFirestore.getInstance())
        val deviceService = FirestoreDeviceService(FirebaseFirestore.getInstance())
        val deviceInfoProvider = DeviceInfoProvider(this)
        val localMonitoringRepository = LocalMonitoringRepository(applicationContext)

        authViewModel = ViewModelProvider(
            this,
            AuthViewModelFactory(
                authRepository = authRepository,
                firestoreUserService = userService,
                firestoreDeviceService = deviceService,
                deviceInfoProvider = deviceInfoProvider,
                localMonitoringRepository = localMonitoringRepository
            )
        )[AuthViewModel::class.java]

        dashboardViewModel = ViewModelProvider(
            this,
            DashboardViewModelFactory(
                dashboardRepository = DashboardRepository(FirebaseFirestore.getInstance())
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
        } else {
            dashboardTodayText.visibility = View.VISIBLE
            dashboardTopAppsText.visibility = View.VISIBLE
            dashboardDeviceUsageText.visibility = View.VISIBLE
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
    }

    private fun renderDashboard(state: DashboardUiState) {
        if (!latestAuthState.isSignedIn) {
            dashboardStatusText.visibility = View.GONE
            return
        }

        if (state.isLoading) {
            dashboardStatusText.visibility = View.VISIBLE
            dashboardStatusText.text = getString(R.string.dashboard_loading)
            return
        }

        if (!state.errorMessage.isNullOrBlank()) {
            dashboardStatusText.visibility = View.VISIBLE
            dashboardStatusText.text = state.errorMessage
            return
        }

        dashboardStatusText.visibility = View.GONE
        dashboardTodayText.text = getString(R.string.dashboard_today_usage, state.totalUsageMinutes)
        dashboardTopAppsText.text = getString(R.string.dashboard_top_apps, state.topAppsText)
        dashboardDeviceUsageText.text = getString(R.string.dashboard_device_usage, state.deviceUsageText)
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
}

private class AuthViewModelFactory(
    private val authRepository: AuthRepository,
    private val firestoreUserService: FirestoreUserService,
    private val firestoreDeviceService: FirestoreDeviceService,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val localMonitoringRepository: LocalMonitoringRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuthViewModel(
            authRepository = authRepository,
            firestoreUserService = firestoreUserService,
            firestoreDeviceService = firestoreDeviceService,
            deviceInfoProvider = deviceInfoProvider,
            localMonitoringRepository = localMonitoringRepository
        ) as T
    }
}

private class DashboardViewModelFactory(
    private val dashboardRepository: DashboardRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(
            dashboardRepository = dashboardRepository
        ) as T
    }
}
