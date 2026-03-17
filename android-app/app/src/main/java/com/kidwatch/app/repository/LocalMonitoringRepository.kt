package com.kidwatch.app.repository

import android.content.Context
import com.kidwatch.app.data.local.KidWatchDatabase
import com.kidwatch.app.data.local.entity.AppUsageEntity
import com.kidwatch.app.data.local.entity.ContentAnalysisEntity
import com.kidwatch.app.data.local.entity.SyncQueueEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity
import org.json.JSONArray
import org.json.JSONObject

class LocalMonitoringRepository(
    context: Context
) {
    private val database: KidWatchDatabase = KidWatchDatabase.getInstance(context)

    fun initializeDatabase() {
        database.openHelper.writableDatabase
    }

    suspend fun enqueueSummary(entityType: String, payloadJson: String) {
        database.syncQueueDao().insert(
            SyncQueueEntity(
                entityType = entityType,
                payloadJson = payloadJson,
                createdAt = System.currentTimeMillis(),
                isSynced = false
            )
        )
    }

    suspend fun saveAppUsage(appPackage: String, startTime: Long, endTime: Long, duration: Long) {
        if (duration <= 0L) return
        val latest = database.appUsageDao().getLatestForPackage(appPackage)
        if (latest != null && startTime <= latest.endTime + SESSION_MERGE_WINDOW_MS) {
            val mergedStart = minOf(latest.startTime, startTime)
            val mergedEnd = maxOf(latest.endTime, endTime)
            database.appUsageDao().updateSession(
                id = latest.id,
                startTime = mergedStart,
                endTime = mergedEnd,
                duration = mergedEnd - mergedStart
            )
            return
        }
        database.appUsageDao().insert(
            AppUsageEntity(
                packageName = appPackage,
                startTime = startTime,
                endTime = endTime,
                duration = duration
            )
        )
    }

    suspend fun saveVideoEvent(title: String, channel: String, timestamp: Long, faceDetected: Boolean = false) {
        if (title.isBlank() && channel.isBlank()) return
        val latest = database.videoEventsDao().getLatest()
        if (
            latest != null &&
            latest.title.equals(title, ignoreCase = true) &&
            latest.channel.equals(channel, ignoreCase = true) &&
            timestamp - latest.timestamp < VIDEO_EVENT_MIN_INTERVAL_MS
        ) {
            return
        }
        database.videoEventsDao().insert(
            VideoEventEntity(
                title = title.ifBlank { "Unknown" },
                channel = channel.ifBlank { "Unknown" },
                timestamp = timestamp,
                faceDetected = faceDetected
            )
        )
    }

    suspend fun aggregateDailyUsage(startMs: Long, endMs: Long): Map<String, Long> {
        return database.appUsageDao().getInWindow(startMs, endMs)
            .groupBy { it.packageName }
            .mapValues { (_, entries) -> entries.sumOf { it.duration } / 60000L }
    }

    suspend fun aggregateContentSummary(startMs: Long, endMs: Long): Pair<Map<String, Int>, Map<String, Int>> {
        val entries = database.videoEventsDao().getInWindow(startMs, endMs)
        val channels = entries.groupingBy { it.channel }.eachCount()
        val videos = entries.groupingBy { it.title }.eachCount()
        return channels to videos
    }

    suspend fun enqueueDailyUsageSummary(dateKey: String, deviceId: String, appMinutes: Map<String, Long>) {
        val payload = JSONObject().apply {
            put("date", dateKey)
            put("deviceId", deviceId)
            put("appMinutes", JSONObject(appMinutes))
        }.toString()
        enqueueSummary("daily_usage", payload)
    }

    suspend fun enqueueContentSummary(dateKey: String, deviceId: String, topChannels: Map<String, Int>, topVideos: Map<String, Int>) {
        val payload = JSONObject().apply {
            put("date", dateKey)
            put("deviceId", deviceId)
            put("topChannels", JSONObject(topChannels))
            put("topVideos", JSONObject(topVideos))
        }.toString()
        enqueueSummary("content_summary", payload)
    }

    suspend fun saveContentAnalysis(dateKey: String, deviceId: String, analyses: List<ChannelAssessment>) {
        if (analyses.isEmpty()) return
        val createdAt = System.currentTimeMillis()
        database.contentAnalysisDao().insertAll(
            analyses.map {
                ContentAnalysisEntity(
                    dateKey = dateKey,
                    deviceId = deviceId,
                    channel = it.channel,
                    label = it.label,
                    reason = it.reason,
                    model = "on-device-heuristic-v1",
                    createdAt = createdAt
                )
            }
        )
    }

    suspend fun enqueueContentAnalysis(
        dateKey: String,
        deviceId: String,
        analyses: List<ChannelAssessment>
    ) {
        if (analyses.isEmpty()) return
        val assessments = JSONArray()
        analyses.forEach { assessment ->
            assessments.put(
                JSONObject().apply {
                    put("channel", assessment.channel)
                    put("label", assessment.label)
                    put("reason", assessment.reason)
                }
            )
        }

        val payload = JSONObject().apply {
            put("date", dateKey)
            put("deviceId", deviceId)
            put("assessments", assessments)
        }.toString()
        enqueueSummary("content_analysis", payload)
    }

    suspend fun getContentAnalysisForDate(dateKey: String): List<ContentAnalysisEntity> {
        return database.contentAnalysisDao().getForDate(dateKey)
    }

    suspend fun getPendingSync() = database.syncQueueDao().getPending()

    suspend fun markSyncDone(id: Long) {
        database.syncQueueDao().markSynced(id)
    }

    suspend fun pruneOldTelemetry(nowMillis: Long = System.currentTimeMillis()) {
        val cutoff = nowMillis - TELEMETRY_RETENTION_MS
        database.appUsageDao().deleteOlderThan(cutoff)
        database.videoEventsDao().deleteOlderThan(cutoff)
    }

    data class ChannelAssessment(
        val channel: String,
        val label: String,
        val reason: String
    )

    private companion object {
        private const val SESSION_MERGE_WINDOW_MS = 2 * 60 * 1000L
        private const val VIDEO_EVENT_MIN_INTERVAL_MS = 20_000L
        private const val TELEMETRY_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
