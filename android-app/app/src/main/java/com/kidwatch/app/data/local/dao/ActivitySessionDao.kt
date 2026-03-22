package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kidwatch.app.data.local.entity.ActivitySessionEntity

@Dao
interface ActivitySessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ActivitySessionEntity): Long

    @Update
    suspend fun update(entry: ActivitySessionEntity)

    @Query("SELECT * FROM ActivitySession WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ActivitySessionEntity?

    @Query("SELECT * FROM ActivitySession WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ActivitySessionEntity>

    @Query("SELECT * FROM ActivitySession ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatest(): ActivitySessionEntity?

    @Query("SELECT * FROM ActivitySession WHERE packageName = :packageName ORDER BY endTime DESC LIMIT 1")
    suspend fun getLatestForPackage(packageName: String): ActivitySessionEntity?

    @Query(
        "SELECT * FROM ActivitySession " +
            "WHERE packageName = :packageName AND startTime <= :timestamp AND endTime >= :timestamp " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun findMatchingSession(packageName: String, timestamp: Long): ActivitySessionEntity?

    @Query("SELECT * FROM ActivitySession WHERE startTime >= :startMs ORDER BY startTime DESC")
    suspend fun getSince(startMs: Long): List<ActivitySessionEntity>

    @Query(
        "SELECT * FROM ActivitySession " +
            "WHERE startTime >= :startMs " +
            "AND (:beforeStartTime IS NULL OR startTime < :beforeStartTime) " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    suspend fun getPageSince(
        startMs: Long,
        beforeStartTime: Long?,
        limit: Int
    ): List<ActivitySessionEntity>

    @Query(
        "SELECT * FROM ActivitySession " +
            "WHERE startTime >= :startMs " +
            "AND attentionLevel != 'normal' " +
            "AND (:beforeStartTime IS NULL OR startTime < :beforeStartTime) " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    suspend fun getNeedsReviewPageSince(
        startMs: Long,
        beforeStartTime: Long?,
        limit: Int
    ): List<ActivitySessionEntity>

    @Query(
        "SELECT * FROM ActivitySession " +
            "WHERE startTime >= :startMs " +
            "AND faceObservationCount > 0 " +
            "AND (primaryIdentityLabel IS NULL OR primaryIdentityLabel = '' OR primaryIdentityLabel = 'Unknown viewer') " +
            "AND (:beforeStartTime IS NULL OR startTime < :beforeStartTime) " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    suspend fun getUnknownViewerPageSince(
        startMs: Long,
        beforeStartTime: Long?,
        limit: Int
    ): List<ActivitySessionEntity>

    @Query(
        "SELECT * FROM ActivitySession " +
            "WHERE startTime >= :startMs " +
            "AND (videoEventCount > 0 OR packageName IN (:supportedPackages)) " +
            "AND (:beforeStartTime IS NULL OR startTime < :beforeStartTime) " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    suspend fun getContentAppsPageSince(
        startMs: Long,
        beforeStartTime: Long?,
        limit: Int,
        supportedPackages: List<String>
    ): List<ActivitySessionEntity>

    @Query("SELECT COUNT(*) FROM ActivitySession WHERE startTime >= :startMs")
    suspend fun countSince(startMs: Long): Int

    @Query("SELECT COUNT(*) FROM ActivitySession WHERE startTime >= :startMs AND attentionLevel != 'normal'")
    suspend fun countNeedsReviewSince(startMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM ActivitySession " +
            "WHERE startTime >= :startMs " +
            "AND faceObservationCount > 0 " +
            "AND (primaryIdentityLabel IS NULL OR primaryIdentityLabel = '' OR primaryIdentityLabel = 'Unknown viewer')"
    )
    suspend fun countUnknownViewerSince(startMs: Long): Int

    @Query("SELECT * FROM ActivitySession WHERE updatedAt >= :cutoffMs ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestUpdatedSince(cutoffMs: Long): ActivitySessionEntity?

    @Query("DELETE FROM ActivitySession WHERE endTime < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM ActivitySession WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM ActivitySession")
    suspend fun deleteAll()
}
