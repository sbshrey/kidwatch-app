package com.kidwatch.app.repository

import android.content.Context
import com.kidwatch.app.data.local.KidWatchDatabase
import com.kidwatch.app.data.local.entity.AppUsageEntity
import com.kidwatch.app.data.local.entity.SyncQueueEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity
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
        database.appUsageDao().insert(
            AppUsageEntity(
                packageName = appPackage,
                startTime = startTime,
                endTime = endTime,
                duration = duration
            )
        )
    }

    suspend fun saveVideoEvent(title: String, channel: String, timestamp: Long) {
        if (title.isBlank() && channel.isBlank()) return
        database.videoEventsDao().insert(
            VideoEventEntity(
                title = title.ifBlank { "Unknown" },
                channel = channel.ifBlank { "Unknown" },
                timestamp = timestamp
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

    suspend fun getPendingSync() = database.syncQueueDao().getPending()

    suspend fun markSyncDone(id: Long) {
        database.syncQueueDao().markSynced(id)
    }
}
