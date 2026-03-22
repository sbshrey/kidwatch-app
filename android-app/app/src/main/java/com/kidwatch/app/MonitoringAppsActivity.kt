package com.kidwatch.app

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.kidwatch.app.analytics.AnalyticsTracker
import com.kidwatch.app.data.local.entity.MonitoredAppPolicyEntity
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.AutoMonitoringService
import com.kidwatch.app.services.EvidenceCaptureService
import com.kidwatch.app.services.MonitoringPolicyCatalog
import kotlinx.coroutines.launch

class MonitoringAppsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var policySummaryText: TextView
    private lateinit var policyEmptyText: TextView
    private lateinit var monitoredAppsContainer: LinearLayout

    private val repository by lazy { LocalMonitoringRepository(applicationContext) }
    private val analyticsTracker by lazy { AnalyticsTracker(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitoring_apps)
        bindViews()
        toolbar.setNavigationOnClickListener { finish() }
        loadMonitoringPolicies()
    }

    override fun onResume() {
        super.onResume()
        loadMonitoringPolicies()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbarMonitoringApps)
        policySummaryText = findViewById(R.id.tvPolicySummary)
        policyEmptyText = findViewById(R.id.tvPolicyEmpty)
        monitoredAppsContainer = findViewById(R.id.monitoringAppsContainer)
    }

    private fun loadMonitoringPolicies() {
        lifecycleScope.launch {
            val summary = runCatching { repository.getMonitoringSummary() }.getOrElse {
                policySummaryText.text = it.message ?: getString(R.string.dashboard_unavailable)
                monitoredAppsContainer.removeAllViews()
                policyEmptyText.visibility = View.VISIBLE
                return@launch
            }
            policySummaryText.text = getString(
                R.string.monitoring_policy_summary,
                summary.trackedCount,
                summary.screenshotCount,
                summary.faceCaptureCount
            )
            val policies = runCatching { repository.getEligibleMonitoringPolicies() }.getOrDefault(emptyList())
            renderPolicyList(policies)
        }
    }

    private fun renderPolicyList(policies: List<MonitoredAppPolicyEntity>) {
        monitoredAppsContainer.removeAllViews()
        policyEmptyText.visibility = if (policies.isEmpty()) View.VISIBLE else View.GONE
        if (policies.isEmpty()) return

        val orderedCategories = listOf(
            MonitoringPolicyCatalog.VIDEO_SHORTS,
            MonitoringPolicyCatalog.SOCIAL_CHAT,
            MonitoringPolicyCatalog.GAMES
        )
        orderedCategories.forEach { category ->
            val categoryPolicies = policies
                .filter { it.category == category }
                .sortedBy { it.displayName.lowercase() }
            if (categoryPolicies.isEmpty()) return@forEach
            val heading = MonitoringPolicyCatalog.definitionFor(category)?.label
                ?: MonitoringPolicyCatalog.displayLabel(category)
            monitoredAppsContainer.addView(createSectionHeading(heading))
            categoryPolicies.forEach { policy ->
                monitoredAppsContainer.addView(createPolicyRow(policy))
            }
        }
    }

    private fun createPolicyRow(policy: MonitoredAppPolicyEntity): View {
        val row = layoutInflater.inflate(R.layout.item_monitored_app_policy, monitoredAppsContainer, false)
        val icon = row.findViewById<ImageView>(R.id.ivPolicyAppIcon)
        val appName = row.findViewById<TextView>(R.id.tvPolicyAppName)
        val supportingText = row.findViewById<TextView>(R.id.tvPolicyAppPackage)
        val state = row.findViewById<TextView>(R.id.tvPolicyAppState)
        val trackSwitch = row.findViewById<SwitchMaterial>(R.id.switchPolicyTrack)
        val screenshotSwitch = row.findViewById<SwitchMaterial>(R.id.switchPolicyScreenshots)
        val faceSwitch = row.findViewById<SwitchMaterial>(R.id.switchPolicyFace)

        appName.text = policy.displayName
        supportingText.text = resolveSupportingText(policy)
        state.text = resolvePolicyState(policy)
        trackSwitch.isChecked = policy.trackSessions
        screenshotSwitch.isChecked = policy.trackSessions && policy.allowScreenshots
        faceSwitch.isChecked = policy.trackSessions && policy.allowFaceCapture
        screenshotSwitch.isEnabled = policy.trackSessions
        faceSwitch.isEnabled = policy.trackSessions

        runCatching {
            packageManager.getApplicationIcon(policy.packageName)
        }.onSuccess { icon.setImageDrawable(it) }
            .onFailure { icon.setImageResource(R.drawable.ic_placeholder_apps) }

        trackSwitch.setOnCheckedChangeListener { _, isChecked ->
            val screenshotsEnabled = if (isChecked) screenshotSwitch.isChecked else false
            val faceEnabled = if (isChecked) faceSwitch.isChecked else false
            updatePolicy(
                packageName = policy.packageName,
                trackSessions = isChecked,
                allowScreenshots = screenshotsEnabled,
                allowFaceCapture = faceEnabled
            )
        }
        screenshotSwitch.setOnCheckedChangeListener { _, isChecked ->
            updatePolicy(
                packageName = policy.packageName,
                trackSessions = trackSwitch.isChecked,
                allowScreenshots = isChecked,
                allowFaceCapture = faceSwitch.isChecked
            )
        }
        faceSwitch.setOnCheckedChangeListener { _, isChecked ->
            updatePolicy(
                packageName = policy.packageName,
                trackSessions = trackSwitch.isChecked,
                allowScreenshots = screenshotSwitch.isChecked,
                allowFaceCapture = isChecked
            )
        }

        return row
    }

    private fun createSectionHeading(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(getColor(R.color.kw_on_surface))
            setPadding(0, dp(16), 0, dp(4))
        }
    }

    private fun updatePolicy(
        packageName: String,
        trackSessions: Boolean,
        allowScreenshots: Boolean,
        allowFaceCapture: Boolean
    ) {
        lifecycleScope.launch {
            repository.updateMonitoredAppPolicy(
                packageName = packageName,
                trackSessions = trackSessions,
                allowScreenshots = allowScreenshots,
                allowFaceCapture = allowFaceCapture
            )
            analyticsTracker.logMonitoringPolicyChanged(
                packageName = packageName,
                trackSessions = trackSessions,
                allowScreenshots = allowScreenshots,
                allowFaceCapture = allowFaceCapture
            )
            AutoMonitoringService.start(applicationContext)
            EvidenceCaptureService.start(applicationContext)
            loadMonitoringPolicies()
        }
    }

    private fun resolvePolicyState(policy: MonitoredAppPolicyEntity): String {
        return when {
            !policy.trackSessions -> getString(R.string.monitoring_policy_state_off)
            policy.allowScreenshots && policy.allowFaceCapture -> getString(R.string.monitoring_policy_state_full)
            policy.allowScreenshots -> getString(R.string.monitoring_policy_state_screenshots)
            else -> getString(R.string.monitoring_policy_state_track_only)
        }
    }

    private fun resolveSupportingText(policy: MonitoredAppPolicyEntity): String {
        return when (policy.category) {
            MonitoringPolicyCatalog.VIDEO_SHORTS -> getString(R.string.monitoring_apps_video_label)
            MonitoringPolicyCatalog.SOCIAL_CHAT -> getString(R.string.monitoring_apps_social_label)
            MonitoringPolicyCatalog.GAMES -> getString(R.string.monitoring_apps_game_label)
            else -> MonitoringPolicyCatalog.displayLabel(policy.category)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
