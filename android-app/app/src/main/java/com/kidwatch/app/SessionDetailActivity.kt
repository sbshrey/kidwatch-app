package com.kidwatch.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kidwatch.app.data.local.entity.PersonProfileEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.AccessibilityServiceState
import com.kidwatch.app.services.EvidencePreferences
import com.kidwatch.app.services.MediaProjectionPermissionStore
import com.kidwatch.app.services.UsageAccessHelper
import com.kidwatch.app.ui.ActivityEvidenceUi
import com.kidwatch.app.ui.SessionDetailViewModel
import com.kidwatch.app.ui.SessionDetailViewModelFactory
import com.kidwatch.app.ui.SessionScreenshotAdapter
import android.widget.Toast

class SessionDetailActivity : AppCompatActivity() {

    private val viewModel: SessionDetailViewModel by viewModels {
        SessionDetailViewModelFactory(LocalMonitoringRepository(applicationContext))
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var heroImage: ImageView
    private lateinit var scoreCard: MaterialCardView
    private lateinit var scoreLabelText: TextView
    private lateinit var scoreValueText: TextView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var summaryText: TextView
    private lateinit var recommendationText: TextView
    private lateinit var countsText: TextView
    private lateinit var subsystemStatusText: TextView
    private lateinit var identityChip: Chip
    private lateinit var attentionChip: Chip
    private lateinit var personContextText: TextView
    private lateinit var assignPersonButton: MaterialButton
    private lateinit var screenshotsCard: MaterialCardView
    private lateinit var screenshotsRecycler: RecyclerView
    private lateinit var screenshotsEmptyText: TextView
    private lateinit var viewerSummaryText: TextView
    private lateinit var viewersContainer: LinearLayout
    private lateinit var videosContainer: LinearLayout
    private lateinit var videosEmptyText: TextView
    private lateinit var explanationText: TextView
    private lateinit var modelText: TextView
    private lateinit var statusText: TextView
    private lateinit var deleteButton: MaterialButton
    private val screenshotAdapter = SessionScreenshotAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)
        bindViews()

        toolbar.setNavigationOnClickListener { finish() }
        screenshotsRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        screenshotsRecycler.adapter = screenshotAdapter
        screenshotAdapter.onScreenshotClick = { screenshot ->
            openEvidenceImage(
                imagePath = screenshot.filePath,
                title = getString(R.string.evidence_image_title_screenshot),
                subtitle = getString(
                    R.string.session_screenshot_caption,
                    ActivityEvidenceUi.formatClock(screenshot.capturedAt),
                    screenshot.triggerType.replace('_', ' ')
                )
            )
        }

        viewModel.uiState.observe(this) { state ->
            statusText.visibility = if (state.errorMessage.isNullOrBlank() && !state.isLoading && !state.isDeleting) View.GONE else View.VISIBLE
            statusText.text = when {
                state.isLoading -> getString(R.string.session_detail_loading)
                state.isDeleting -> getString(R.string.session_detail_deleting)
                !state.errorMessage.isNullOrBlank() -> state.errorMessage
                else -> ""
            }
        deleteButton.isEnabled = state.detail != null && !state.isLoading && !state.isDeleting
            assignPersonButton.isEnabled = state.detail != null && !state.isLoading && !state.isDeleting
            if (state.isDeleted) {
                Toast.makeText(this, getString(R.string.session_detail_delete_done), Toast.LENGTH_SHORT).show()
                finish()
                return@observe
            }
            state.detail?.let(::renderDetail)
        }

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId <= 0L) {
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.session_detail_missing_id)
            return
        }
        viewModel.load(sessionId)
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbarSessionDetail)
        heroImage = findViewById(R.id.ivSessionDetailHero)
        scoreCard = findViewById(R.id.cardSessionDetailKidScore)
        scoreLabelText = findViewById(R.id.tvSessionDetailKidScoreLabel)
        scoreValueText = findViewById(R.id.tvSessionDetailKidScoreValue)
        titleText = findViewById(R.id.tvSessionDetailTitle)
        metaText = findViewById(R.id.tvSessionDetailMeta)
        summaryText = findViewById(R.id.tvSessionDetailSummary)
        recommendationText = findViewById(R.id.tvSessionDetailRecommendation)
        countsText = findViewById(R.id.tvSessionDetailCounts)
        subsystemStatusText = findViewById(R.id.tvSessionDetailSubsystemStatus)
        identityChip = findViewById(R.id.chipSessionDetailIdentity)
        attentionChip = findViewById(R.id.chipSessionDetailAttention)
        personContextText = findViewById(R.id.tvSessionPersonContext)
        assignPersonButton = findViewById(R.id.btnAssignSessionPerson)
        screenshotsCard = findViewById(R.id.cardSessionScreenshots)
        screenshotsRecycler = findViewById(R.id.rvSessionScreenshots)
        screenshotsEmptyText = findViewById(R.id.tvSessionScreenshotsEmpty)
        viewerSummaryText = findViewById(R.id.tvSessionViewerSummary)
        viewersContainer = findViewById(R.id.sessionIdentityContainer)
        videosContainer = findViewById(R.id.sessionVideosContainer)
        videosEmptyText = findViewById(R.id.tvSessionVideosEmpty)
        explanationText = findViewById(R.id.tvSessionInsightExplanation)
        modelText = findViewById(R.id.tvSessionInsightModel)
        statusText = findViewById(R.id.tvSessionDetailStatus)
        deleteButton = findViewById(R.id.btnDeleteSession)
        findViewById<View>(R.id.btnOpenEvidenceSettings).setOnClickListener {
            startActivity(MainActivity.createIntentForTab(this, R.id.nav_manage))
        }
        assignPersonButton.setOnClickListener {
            val current = viewModel.uiState.value ?: return@setOnClickListener
            if (current.availableProfiles.isEmpty()) {
                startActivity(
                    MainActivity.createIntentForTab(
                        context = this,
                        tabId = R.id.nav_manage,
                        openSection = "this_device"
                    )
                )
                return@setOnClickListener
            }
            current.detail?.let { detail ->
                showAssignPersonDialog(
                    currentAssignedProfileId = detail.session.assignedPersonProfileId,
                    profiles = current.availableProfiles
                )
            }
        }
        deleteButton.setOnClickListener { confirmDeleteSession() }
    }

    private fun renderDetail(detail: LocalMonitoringRepository.ActivitySessionDetail) {
        val session = detail.session
        titleText.text = session.appName
        metaText.text = getString(
            R.string.session_detail_meta,
            ActivityEvidenceUi.formatTimeRange(session.startTime, session.endTime),
            ActivityEvidenceUi.formatDuration(session.duration)
        )
        summaryText.text = session.summary.ifBlank { getString(R.string.activity_feed_summary_fallback) }
        recommendationText.text = session.recommendation.ifBlank {
            getString(R.string.activity_feed_recommendation_fallback)
        }
        val score = ActivityEvidenceUi.computeKidFriendlyScore(session)
        scoreValueText.text = getString(R.string.session_score_value, score)
        ActivityEvidenceUi.styleKidFriendlyScoreBadge(
            card = scoreCard,
            labelView = scoreLabelText,
            valueView = scoreValueText,
            context = this,
            score = score
        )
        scoreCard.contentDescription = getString(R.string.session_score_accessibility, score)
        countsText.text = getString(
            R.string.session_detail_counts,
            session.screenshotCount,
            session.faceObservationCount,
            session.videoEventCount
        )
        subsystemStatusText.text = buildSubsystemStatus()
        identityChip.text = ActivityEvidenceUi.resolveIdentityLabel(
            session.primaryIdentityLabel,
            session.faceObservationCount
        )
        ActivityEvidenceUi.styleNeutralChip(identityChip, this)
        attentionChip.text = ActivityEvidenceUi.resolveAttentionLabel(session.attentionLevel)
        ActivityEvidenceUi.styleAttentionChip(attentionChip, this, session.attentionLevel)
        personContextText.text = buildPersonContext(session)
        assignPersonButton.text = if (session.assignedPersonProfileId != null) {
            getString(R.string.session_assign_person_edit_button)
        } else {
            getString(R.string.session_assign_person_button)
        }

        val heroBitmap = ActivityEvidenceUi.loadBitmap(session.representativeScreenshotPath, 1080, 720)
        if (heroBitmap != null) {
            heroImage.setImageBitmap(heroBitmap)
            heroImage.scaleType = ImageView.ScaleType.CENTER_CROP
            heroImage.setPadding(0, 0, 0, 0)
            heroImage.setOnClickListener {
                session.representativeScreenshotPath?.let { imagePath ->
                    openEvidenceImage(
                        imagePath = imagePath,
                        title = getString(R.string.evidence_image_title_screenshot),
                        subtitle = getString(
                            R.string.session_detail_meta,
                            ActivityEvidenceUi.formatTimeRange(session.startTime, session.endTime),
                            ActivityEvidenceUi.formatDuration(session.duration)
                        )
                    )
                }
            }
        } else {
            heroImage.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(session.packageName) }
                    .getOrElse { ContextCompat.getDrawable(this, R.drawable.ic_placeholder_apps) }
            )
            heroImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            heroImage.setPadding(dp(32), dp(32), dp(32), dp(32))
            heroImage.setOnClickListener(null)
        }

        screenshotAdapter.submitList(detail.screenshots)
        screenshotsEmptyText.visibility = if (detail.screenshots.isEmpty()) View.VISIBLE else View.GONE
        screenshotsRecycler.visibility = if (detail.screenshots.isEmpty()) View.GONE else View.VISIBLE
        screenshotsCard.visibility = View.VISIBLE

        viewerSummaryText.text = when {
            detail.faceObservations.isEmpty() -> getString(R.string.session_viewer_summary_none)
            session.primaryIdentityLabel.isNullOrBlank() -> getString(
                R.string.session_viewer_summary_unknown,
                detail.faceObservations.size
            )
            else -> getString(
                R.string.session_viewer_summary_named,
                session.primaryIdentityLabel,
                detail.faceObservations.size
            )
        }
        viewersContainer.removeAllViews()
        detail.faceObservations.forEach { observation ->
            viewersContainer.addView(
                createDetailRow(
                    title = ActivityEvidenceUi.resolveIdentityLabel(
                        observation.cluster?.label,
                        faceObservationCount = 1
                    ),
                    body = getString(
                        R.string.session_viewer_row,
                        ActivityEvidenceUi.formatClock(observation.observation.observedAt),
                        (observation.observation.confidence * 100).toInt()
                    ),
                    imagePath = observation.observation.cropPath
                )
            )
        }
        if (detail.faceObservations.isEmpty()) {
            viewersContainer.addView(createEmptyRow(getString(R.string.session_no_viewer_rows)))
        }

        videosContainer.removeAllViews()
        detail.videos.forEach { video ->
            val linkAction = resolveVideoLinkAction(video)
            videosContainer.addView(
                createDetailRow(
                    title = video.title,
                    body = getString(
                        R.string.session_video_row,
                        video.channel,
                        ActivityEvidenceUi.formatClock(video.timestamp)
                    ),
                    actionLabel = getString(linkAction.labelRes),
                    actionEnabled = linkAction.url != null,
                    onAction = { linkAction.url?.let(::openVideoLink) },
                    onActionLongClick = { linkAction.url?.let(::copyVideoLink) },
                    onCardClick = { linkAction.url?.let(::openVideoLink) }
                )
            )
        }
        videosEmptyText.visibility = if (detail.videos.isEmpty()) View.VISIBLE else View.GONE

        explanationText.text = detail.insight?.explanation ?: getString(R.string.session_insight_empty)
        modelText.text = detail.insight?.model ?: getString(R.string.session_insight_model_fallback)
    }

    private fun createDetailRow(
        title: String,
        body: String,
        imagePath: String? = null,
        actionLabel: String? = null,
        actionEnabled: Boolean = false,
        onAction: (() -> Unit)? = null,
        onActionLongClick: (() -> Unit)? = null,
        onCardClick: (() -> Unit)? = null
    ): View {
        val card = layoutInflater.inflate(R.layout.item_evidence_cluster, viewersContainer, false)
        val preview = card.findViewById<ImageView>(R.id.ivClusterPreview)
        val titleView = card.findViewById<TextView>(R.id.tvClusterTitle)
        val bodyView = card.findViewById<TextView>(R.id.tvClusterBody)
        val actionButton = card.findViewById<MaterialButton>(R.id.btnClusterLabel)
        titleView.text = title
        bodyView.text = body
        if (actionLabel.isNullOrBlank()) {
            actionButton.visibility = View.GONE
        } else {
            actionButton.visibility = View.VISIBLE
            actionButton.text = actionLabel
            actionButton.icon = null
            actionButton.isEnabled = actionEnabled
            actionButton.alpha = if (actionEnabled) 1f else 0.6f
            actionButton.setOnClickListener { onAction?.invoke() }
            actionButton.setOnLongClickListener {
                onActionLongClick?.invoke()
                onActionLongClick != null
            }
        }
        val bitmap = ActivityEvidenceUi.loadBitmap(imagePath, 180, 180)
        if (bitmap != null) {
            preview.setImageBitmap(bitmap)
            preview.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.setPadding(0, 0, 0, 0)
            card.setOnClickListener {
                imagePath?.let {
                    openEvidenceImage(
                        imagePath = it,
                        title = title,
                        subtitle = body
                    )
                }
            }
        } else {
            preview.setImageResource(R.drawable.ic_placeholder_apps)
            preview.scaleType = ImageView.ScaleType.CENTER_INSIDE
            preview.setPadding(dp(16), dp(16), dp(16), dp(16))
            card.setOnClickListener { onCardClick?.invoke() }
        }
        return card
    }

    private fun openEvidenceImage(imagePath: String, title: String, subtitle: String? = null) {
        startActivity(
            EvidenceImageViewerActivity.createIntent(
                context = this,
                imagePath = imagePath,
                title = title,
                subtitle = subtitle
            )
        )
    }

    private fun openVideoLink(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.session_link_open_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyVideoLink(url: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("KidWatch video link", url))
        Toast.makeText(this, getString(R.string.session_link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun resolveVideoLinkAction(video: VideoEventEntity): VideoLinkAction {
        return when {
            !video.canonicalUrl.isNullOrBlank() -> VideoLinkAction(
                url = video.canonicalUrl,
                labelRes = R.string.session_link_open_exact
            )
            else -> VideoLinkAction(
                url = null,
                labelRes = R.string.session_link_unavailable
            )
        }
    }

    private fun createEmptyRow(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.kw_on_surface_variant))
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun buildSubsystemStatus(): String {
        val parts = buildList {
            add(
                if (UsageAccessHelper.hasUsageAccess(this@SessionDetailActivity)) {
                    getString(R.string.session_detail_status_tracking_active)
                } else {
                    getString(R.string.session_detail_status_tracking_paused)
                }
            )
            add(
                if (EvidencePreferences.isAutomaticEvidenceEnabled(this@SessionDetailActivity) && hasCameraPermission()) {
                    getString(R.string.session_detail_status_viewers_active)
                } else {
                    getString(R.string.session_detail_status_viewers_paused)
                }
            )
            add(
                if (EvidencePreferences.isScreenCaptureCurrentlyAvailable(this@SessionDetailActivity) &&
                    MediaProjectionPermissionStore.hasGrant()
                ) {
                    getString(R.string.session_detail_status_screenshots_active)
                } else {
                    getString(R.string.session_detail_status_screenshots_paused)
                }
            )
            if (!AccessibilityServiceState.isContentCaptureEnabled(this@SessionDetailActivity)) {
                add(getString(R.string.session_detail_status_content_paused))
            }
        }
        return parts.joinToString(" • ")
    }

    private fun hasCameraPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun confirmDeleteSession() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.session_detail_delete_title))
            .setMessage(getString(R.string.session_detail_delete_body))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.session_detail_delete_confirm) { _, _ ->
                viewModel.deleteSession()
            }
            .show()
    }

    private fun showAssignPersonDialog(
        currentAssignedProfileId: Long?,
        profiles: List<PersonProfileEntity>
    ) {
        val options = profiles.map { profile ->
            profile.id to buildAssignableProfileLabel(profile)
        } + (null to getString(R.string.session_assign_person_clear))
        var selectedIndex = options.indexOfFirst { it.first == currentAssignedProfileId }
            .takeIf { it >= 0 } ?: options.lastIndex

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.session_assign_person_title))
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.session_assign_person_save) { _, _ ->
                viewModel.assignSessionToPerson(options[selectedIndex].first)
            }
            .show()
    }

    private fun buildAssignableProfileLabel(profile: PersonProfileEntity): String {
        return when {
            profile.isDeviceOwner -> getString(R.string.manage_owner_display, profile.name)
            profile.role == "child" && profile.ageYears != null -> getString(
                R.string.manage_child_display,
                profile.name,
                profile.ageYears
            )
            else -> profile.name
        }
    }

    private fun buildPersonContext(session: com.kidwatch.app.data.local.entity.ActivitySessionEntity): String {
        return when {
            !session.assignedPersonName.isNullOrBlank() &&
                session.assignedPersonRole == "child" &&
                session.assignedPersonAgeYears != null -> getString(
                R.string.session_person_context_child_assigned,
                session.assignedPersonName,
                session.assignedPersonAgeYears
            )

            !session.assignedPersonName.isNullOrBlank() -> getString(
                R.string.session_person_context_assigned,
                session.assignedPersonName
            )

            !session.primaryIdentityLabel.isNullOrBlank() &&
                session.primaryIdentityLabel != "Unknown viewer" -> getString(
                R.string.session_person_context_detected,
                session.primaryIdentityLabel
            )

            else -> getString(R.string.session_person_context_unassigned)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    private data class VideoLinkAction(
        val url: String?,
        val labelRes: Int
    )
}
