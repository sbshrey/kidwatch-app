package com.kidwatch.app.insights

import com.kidwatch.app.repository.LocalMonitoringRepository

object OnDeviceRiskScorer {

    const val MODEL_NAME = "on-device-heuristic-v1"

    fun assessChannels(
        channelCounts: Map<String, Int>,
        maxChannels: Int = 10
    ): List<LocalMonitoringRepository.ChannelAssessment> {
        return channelCounts.entries
            .sortedByDescending { it.value }
            .take(maxChannels)
            .map { (channel, plays) ->
                val normalized = channel.lowercase()
                val keywordRisk = when {
                    HIGH_RISK_KEYWORDS.any { normalized.contains(it) } -> 2
                    MEDIUM_RISK_KEYWORDS.any { normalized.contains(it) } -> 1
                    else -> 0
                }
                val intensityRisk = when {
                    plays >= 30 -> 2
                    plays >= 15 -> 1
                    else -> 0
                }
                val safeSignal = SAFE_KEYWORDS.any { normalized.contains(it) }

                val score = (keywordRisk + intensityRisk - if (safeSignal) 1 else 0).coerceAtLeast(0)
                when {
                    score >= 3 -> LocalMonitoringRepository.ChannelAssessment(
                        channel = channel,
                        label = "high",
                        reason = "High-repeat or risky content keywords detected.",
                        model = MODEL_NAME
                    )
                    score == 2 -> LocalMonitoringRepository.ChannelAssessment(
                        channel = channel,
                        label = "moderate",
                        reason = "Moderate exposure pattern detected.",
                        model = MODEL_NAME
                    )
                    else -> LocalMonitoringRepository.ChannelAssessment(
                        channel = channel,
                        label = "safe",
                        reason = "No strong risk indicators in current viewing pattern.",
                        model = MODEL_NAME
                    )
                }
            }
    }

    private val HIGH_RISK_KEYWORDS = listOf(
        "prank", "fight", "violence", "horror", "weapon", "blood", "18+", "adult"
    )

    private val MEDIUM_RISK_KEYWORDS = listOf(
        "shorts", "challenge", "live", "gaming", "reaction", "stream"
    )

    private val SAFE_KEYWORDS = listOf(
        "kids", "learning", "education", "nursery", "rhymes", "science"
    )
}
