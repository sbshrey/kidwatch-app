package com.kidwatch.app.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.kidwatch.app.R
import com.kidwatch.app.data.local.entity.ActivitySessionEntity

class ActivitySessionFeedAdapter(
    private val context: Context,
    private val onClick: (ActivitySessionEntity) -> Unit
) : ListAdapter<ActivitySessionEntity, ActivitySessionFeedAdapter.SessionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.sessionCard)
        private val thumbnail: ImageView = itemView.findViewById(R.id.ivSessionThumbnail)
        private val scoreCard: MaterialCardView = itemView.findViewById(R.id.cardSessionKidScore)
        private val scoreLabel: TextView = itemView.findViewById(R.id.tvSessionKidScoreLabel)
        private val scoreValue: TextView = itemView.findViewById(R.id.tvSessionKidScoreValue)
        private val appIcon: ImageView = itemView.findViewById(R.id.ivSessionAppIcon)
        private val appName: TextView = itemView.findViewById(R.id.tvSessionAppName)
        private val meta: TextView = itemView.findViewById(R.id.tvSessionMeta)
        private val summary: TextView = itemView.findViewById(R.id.tvSessionSummary)
        private val recommendation: TextView = itemView.findViewById(R.id.tvSessionRecommendation)
        private val attentionChip: Chip = itemView.findViewById(R.id.chipSessionAttention)
        private val identityChip: Chip = itemView.findViewById(R.id.chipSessionIdentity)
        private val screenshotsChip: Chip = itemView.findViewById(R.id.chipSessionScreenshots)
        private val videosChip: Chip = itemView.findViewById(R.id.chipSessionVideos)
        private val facesChip: Chip = itemView.findViewById(R.id.chipSessionFaces)

        fun bind(session: ActivitySessionEntity) {
            appName.text = session.appName
            meta.text = context.getString(
                R.string.activity_feed_meta,
                ActivityEvidenceUi.formatTimeRange(session.startTime, session.endTime),
                ActivityEvidenceUi.formatDuration(session.duration)
            )
            summary.text = session.summary.ifBlank {
                context.getString(R.string.activity_feed_summary_fallback)
            }
            recommendation.text = session.recommendation.ifBlank {
                context.getString(R.string.activity_feed_recommendation_fallback)
            }

            val score = ActivityEvidenceUi.computeKidFriendlyScore(session)
            scoreValue.text = context.getString(R.string.session_score_value, score)
            ActivityEvidenceUi.styleKidFriendlyScoreBadge(
                card = scoreCard,
                labelView = scoreLabel,
                valueView = scoreValue,
                context = context,
                score = score
            )
            scoreCard.contentDescription = context.getString(R.string.session_score_accessibility, score)

            attentionChip.text = ActivityEvidenceUi.resolveAttentionLabel(session.attentionLevel)
            ActivityEvidenceUi.styleAttentionChip(attentionChip, context, session.attentionLevel)

            identityChip.text = ActivityEvidenceUi.resolveIdentityLabel(
                session.primaryIdentityLabel,
                session.faceObservationCount
            )
            ActivityEvidenceUi.styleNeutralChip(identityChip, context)

            screenshotsChip.text = context.getString(R.string.session_chip_screenshots, session.screenshotCount)
            videosChip.text = context.getString(R.string.session_chip_videos, session.videoEventCount)
            facesChip.text = context.getString(R.string.session_chip_faces, session.faceObservationCount)
            ActivityEvidenceUi.styleNeutralChip(screenshotsChip, context)
            ActivityEvidenceUi.styleNeutralChip(videosChip, context)
            ActivityEvidenceUi.styleNeutralChip(facesChip, context)

            val screenshotBitmap = ActivityEvidenceUi.loadBitmap(session.representativeScreenshotPath, 320, 200)
            if (screenshotBitmap != null) {
                thumbnail.setImageBitmap(screenshotBitmap)
                thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                thumbnail.background = null
                thumbnail.setPadding(0, 0, 0, 0)
            } else {
                thumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
                thumbnail.setImageDrawable(resolveAppIcon(session.packageName) ?: defaultAppDrawable())
                thumbnail.background = ContextCompat.getDrawable(context, R.drawable.bg_surface_card_alt)
                thumbnail.setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            appIcon.setImageDrawable(resolveAppIcon(session.packageName) ?: defaultAppDrawable())
            card.setOnClickListener { onClick(session) }
        }

        private fun resolveAppIcon(packageName: String): Drawable? {
            return runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
        }

        private fun defaultAppDrawable(): Drawable? {
            return ContextCompat.getDrawable(context, R.drawable.ic_placeholder_apps)
        }

        private fun dp(value: Int): Int {
            return (value * itemView.resources.displayMetrics.density).toInt()
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ActivitySessionEntity>() {
        override fun areItemsTheSame(oldItem: ActivitySessionEntity, newItem: ActivitySessionEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ActivitySessionEntity, newItem: ActivitySessionEntity): Boolean {
            return oldItem == newItem
        }
    }
}
