package com.kidwatch.app.insights

import com.kidwatch.app.data.local.entity.ActivitySessionEntity
import com.kidwatch.app.data.local.entity.ContentAnalysisEntity
import com.kidwatch.app.data.local.entity.PersonProfileEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity
import com.kidwatch.app.services.AccessibilityCaptureCatalog

object SessionInsightGenerator {
    const val MODEL_NAME = "kidwatch:ondevice-session-v1"

    data class SessionInsightResult(
        val kidFriendlyScore: Int,
        val attentionLevel: String,
        val headline: String,
        val explanation: String,
        val recommendedAction: String
    )

    fun generate(
        session: ActivitySessionEntity,
        videos: List<VideoEventEntity>,
        analyses: List<ContentAnalysisEntity>,
        identityLabel: String?,
        resolvedProfile: PersonProfileEntity?,
        childAges: List<Int>
    ): SessionInsightResult {
        val riskyLabels = analyses.map { it.label.lowercase() }
        val isLongSession = session.duration >= 20 * 60 * 1000L
        val hasUnknownIdentity = session.faceObservationCount > 0 &&
            (identityLabel.isNullOrBlank() || identityLabel == "Unknown viewer")
        val hasNoIdentityEvidence = session.faceObservationCount == 0
        val isContentSession = AccessibilityCaptureCatalog.isSupportedPackage(session.packageName) || videos.isNotEmpty()
        val hasRiskyContent = riskyLabels.any { it == "overstimulating" || it == "addictive" }
        var score = 10
        if (resolvedProfile?.role == "child" && resolvedProfile.ageYears != null) {
            when {
                resolvedProfile.ageYears <= 4 && hasRiskyContent -> score -= 3
                resolvedProfile.ageYears <= 8 && hasRiskyContent -> score -= 2
            }
        } else if (childAges.any { it <= 5 } && hasRiskyContent) {
            score -= 2
        }
        if (hasUnknownIdentity) score -= 1
        if (hasNoIdentityEvidence && isContentSession) score -= 1
        if (isLongSession) score -= 1
        if (videos.size >= 3) score -= 1
        if (session.screenshotCount == 0 && isLongSession) score -= 1
        score = score.coerceIn(1, 10)

        return when {
            hasRiskyContent -> SessionInsightResult(
                kidFriendlyScore = score.coerceAtMost(4),
                attentionLevel = "review_now",
                headline = "Risky viewing pattern detected",
                explanation = "This session includes content that earlier analysis marked as overstimulating or addictive.",
                recommendedAction = "Open this session and review the videos before the same pattern repeats."
            )

            isLongSession && hasUnknownIdentity -> SessionInsightResult(
                kidFriendlyScore = score.coerceAtMost(5),
                attentionLevel = "review_now",
                headline = "Long session with unknown viewer",
                explanation = "The phone stayed in use for a long stretch, but the viewer cluster is still unlabeled.",
                recommendedAction = "Open Manage and label the viewer cluster so future sessions are clearer."
            )

            isContentSession && hasNoIdentityEvidence -> SessionInsightResult(
                kidFriendlyScore = score.coerceAtMost(6),
                attentionLevel = "watch",
                headline = "Content session without viewer evidence",
                explanation = "Content was captured but the app has no usable face evidence for who was in front of the phone.",
                recommendedAction = "Keep the phone facing the user for a few seconds during the next session."
            )

            session.screenshotCount == 0 && isLongSession -> SessionInsightResult(
                kidFriendlyScore = score.coerceAtMost(6),
                attentionLevel = "watch",
                headline = "Long session with light evidence",
                explanation = "The session lasted long enough to watch, but screenshots were not captured for review.",
                recommendedAction = "Open Manage and turn on screenshots for this app if you want visual evidence next time."
            )

            else -> SessionInsightResult(
                kidFriendlyScore = score,
                attentionLevel = "normal",
                headline = "Routine session",
                explanation = "Nothing unusual stands out in this session yet.",
                recommendedAction = "Keep an eye on repeat sessions later in the day."
            )
        }
    }
}
