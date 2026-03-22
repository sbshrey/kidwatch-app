package com.kidwatch.app.monitoring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kidwatch.app.insights.OpenAiContentAnalyzer
import com.kidwatch.app.insights.OnDeviceRiskScorer
import com.kidwatch.app.repository.LocalMonitoringRepository
import com.kidwatch.app.services.DeviceInfoProvider
import com.kidwatch.app.services.UsageAccessHelper
import com.kidwatch.app.services.UsageStatsCollector
import java.time.LocalDate

class MonitoringWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!UsageAccessHelper.hasUsageAccess(applicationContext)) {
            return Result.success()
        }

        val repository = LocalMonitoringRepository(applicationContext)
        val collector = UsageStatsCollector(applicationContext, repository)
        val deviceInfo = DeviceInfoProvider(applicationContext).getDeviceInfo()
        repository.pruneOldTelemetry()

        collector.collectLastInterval(UsageStatsCollector.DEFAULT_INTERVAL_MS)

        val dateKey = LocalDate.now().toString()
        val dayStart = LocalDate.now().atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        val appMinutes = repository.aggregateDailyUsage(dayStart, dayEnd)
        repository.enqueueDailyUsageSummary(dateKey, deviceInfo.deviceId, appMinutes)

        val (topChannels, topVideos) = repository.aggregateContentSummary(dayStart, dayEnd)
        repository.enqueueContentSummary(dateKey, deviceInfo.deviceId, topChannels, topVideos)

        val rankedChannels = topChannels.entries
            .sortedByDescending { it.value }
            .take(MAX_ANALYSIS_CHANNELS)
            .map { it.key }
        val existingAnalyses = repository.getContentAnalysisForDate(dateKey, deviceInfo.deviceId)
        val preferredModel = if (OpenAiContentAnalyzer.isConfigured()) {
            OpenAiContentAnalyzer.ANALYSIS_MODEL
        } else {
            OnDeviceRiskScorer.MODEL_NAME
        }

        val shouldRefreshAnalysis =
            rankedChannels.isNotEmpty() && !canReuseExistingAnalysis(existingAnalyses, rankedChannels, preferredModel)

        if (shouldRefreshAnalysis) {
            val analyses = analyzeChannels(topChannels)
            repository.saveContentAnalysis(dateKey, deviceInfo.deviceId, analyses)
            repository.enqueueContentAnalysis(dateKey, deviceInfo.deviceId, analyses)
        }

        return Result.success()
    }

    private suspend fun analyzeChannels(
        channelCounts: Map<String, Int>
    ): List<LocalMonitoringRepository.ChannelAssessment> {
        val rankedChannels = channelCounts.entries
            .sortedByDescending { it.value }
            .take(MAX_ANALYSIS_CHANNELS)
            .map { it.key }
        if (rankedChannels.isEmpty()) return emptyList()

        if (OpenAiContentAnalyzer.isConfigured()) {
            val openAiAssessments = runCatching {
                OpenAiContentAnalyzer().assessChannelsForYoungKids(rankedChannels)
            }.getOrDefault(emptyList())
                .map {
                    LocalMonitoringRepository.ChannelAssessment(
                        channel = it.channel,
                        label = normalizeOpenAiLabel(it.label),
                        reason = it.reason.ifBlank { "No rationale returned" },
                        model = OpenAiContentAnalyzer.ANALYSIS_MODEL
                    )
                }

            val isCompleteResult = openAiAssessments.size == rankedChannels.size &&
                openAiAssessments.map { it.channel }.toSet() == rankedChannels.toSet()
            if (isCompleteResult && openAiAssessments.any { it.label != "unknown" }) {
                return openAiAssessments
            }
        }

        return OnDeviceRiskScorer.assessChannels(channelCounts, maxChannels = MAX_ANALYSIS_CHANNELS)
    }

    private fun canReuseExistingAnalysis(
        existingAnalyses: List<com.kidwatch.app.data.local.entity.ContentAnalysisEntity>,
        rankedChannels: List<String>,
        preferredModel: String
    ): Boolean {
        if (existingAnalyses.isEmpty()) return false
        val existingChannels = existingAnalyses.map { it.channel }.distinct().sorted()
        return existingChannels == rankedChannels.sorted() &&
            existingAnalyses.all { it.model == preferredModel }
    }

    private fun normalizeOpenAiLabel(label: String): String {
        return when (label.trim().lowercase()) {
            "safe" -> "safe"
            "overstimulating" -> "overstimulating"
            "addictive" -> "addictive"
            else -> "unknown"
        }
    }

    private companion object {
        private const val MAX_ANALYSIS_CHANNELS = 8
    }
}
