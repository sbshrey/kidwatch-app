package com.kidwatch.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.kidwatch.app.R
import com.kidwatch.app.data.local.entity.ActivitySessionEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ActivityFeedFilter {
    ALL,
    NEEDS_REVIEW,
    UNKNOWN_PERSON,
    CONTENT_APPS
}

object ActivityEvidenceUi {
    fun computeKidFriendlyScore(session: ActivitySessionEntity): Int {
        if (session.kidFriendlyScore in 1..10) {
            return session.kidFriendlyScore
        }
        return computeHeuristicKidFriendlyScore(session)
    }

    private fun computeHeuristicKidFriendlyScore(session: ActivitySessionEntity): Int {
        var score = 10
        when (session.attentionLevel.lowercase(Locale.getDefault())) {
            "review_now" -> score -= 4
            "watch" -> score -= 2
        }
        when {
            session.duration >= 40 * 60_000L -> score -= 2
            session.duration >= 20 * 60_000L -> score -= 1
        }
        when {
            session.videoEventCount >= 8 -> score -= 2
            session.videoEventCount >= 3 -> score -= 1
        }
        if (session.videoEventCount > 0 && session.faceObservationCount == 0) {
            score -= 1
        }
        if (
            session.faceObservationCount > 0 &&
            (session.primaryIdentityLabel.isNullOrBlank() || session.primaryIdentityLabel == "Unknown viewer")
        ) {
            score -= 1
        }
        if (session.screenshotCount == 0 && session.duration >= 20 * 60_000L) {
            score -= 1
        }
        return score.coerceIn(1, 10)
    }

    fun styleKidFriendlyScoreBadge(
        card: MaterialCardView,
        labelView: TextView,
        valueView: TextView,
        context: Context,
        score: Int
    ) {
        val (background, foreground) = when {
            score >= 8 -> R.color.kw_primary_container to R.color.kw_on_primary_container
            score >= 5 -> R.color.kw_accent_sky_soft to R.color.kw_on_surface
            else -> R.color.kw_accent_orange_soft to R.color.kw_secondary
        }
        card.setCardBackgroundColor(ContextCompat.getColor(context, background))
        labelView.setTextColor(ContextCompat.getColor(context, foreground))
        valueView.setTextColor(ContextCompat.getColor(context, foreground))
    }

    fun formatTimeRange(startTime: Long, endTime: Long): String {
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return "${formatter.format(Date(startTime))} - ${formatter.format(Date(endTime))}"
    }

    fun formatClock(timestamp: Long): String {
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    fun formatDuration(durationMs: Long): String {
        val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    fun resolveIdentityLabel(identityLabel: String?, faceObservationCount: Int): String {
        return when {
            !identityLabel.isNullOrBlank() -> identityLabel
            faceObservationCount > 0 -> "Unknown viewer"
            else -> "No identity evidence"
        }
    }

    fun resolveAttentionLabel(level: String): String {
        return when (level.lowercase(Locale.getDefault())) {
            "review_now" -> "Review now"
            "watch" -> "Watch"
            else -> "Normal"
        }
    }

    fun styleAttentionChip(chip: Chip, context: Context, level: String) {
        val (background, foreground) = when (level.lowercase(Locale.getDefault())) {
            "review_now" -> R.color.kw_accent_orange_soft to R.color.kw_secondary
            "watch" -> R.color.kw_card_surface_alt to R.color.kw_on_surface
            else -> R.color.kw_card_surface to R.color.kw_on_primary_container
        }
        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, background))
        chip.setTextColor(ContextCompat.getColor(context, foreground))
    }

    fun styleNeutralChip(chip: Chip, context: Context) {
        chip.chipBackgroundColor = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.kw_card_surface_alt)
        )
        chip.setTextColor(ContextCompat.getColor(context, R.color.kw_on_surface))
    }

    fun loadBitmap(path: String?, requestedWidth: Int = 360, requestedHeight: Int = 360): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = calculateInSampleSize(bounds, requestedWidth, requestedHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var sampleSize = 1

        if (height > requestedHeight || width > requestedWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / sampleSize >= requestedHeight && halfWidth / sampleSize >= requestedWidth) {
                sampleSize *= 2
                halfHeight = height / 2
                halfWidth = width / 2
            }
        }
        return sampleSize.coerceAtLeast(1)
    }
}
