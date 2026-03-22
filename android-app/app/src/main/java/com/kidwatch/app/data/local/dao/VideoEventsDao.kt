package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kidwatch.app.data.local.entity.VideoEventEntity

@Dao
interface VideoEventsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VideoEventEntity): Long

    @Update
    suspend fun update(entry: VideoEventEntity)

    @Query("SELECT * FROM VideoEvents ORDER BY timestamp DESC")
    suspend fun getAll(): List<VideoEventEntity>

    @Query("SELECT * FROM VideoEvents WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    suspend fun getInWindow(startMs: Long, endMs: Long): List<VideoEventEntity>

    @Query("SELECT * FROM VideoEvents ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): VideoEventEntity?

    @Query("SELECT * FROM VideoEvents WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getForSession(sessionId: Long): List<VideoEventEntity>

    @Query("SELECT * FROM VideoEvents WHERE sessionId IS NULL AND timestamp >= :startMs AND timestamp <= :endMs ORDER BY timestamp ASC")
    suspend fun getUnassignedInWindow(startMs: Long, endMs: Long): List<VideoEventEntity>

    @Query("UPDATE VideoEvents SET sessionId = :sessionId WHERE id = :id")
    suspend fun attachToSession(id: Long, sessionId: Long)

    @Query(
        "UPDATE VideoEvents " +
            "SET fallbackUrl = NULL, " +
            "linkKind = CASE WHEN canonicalUrl IS NOT NULL AND TRIM(canonicalUrl) != '' THEN 'exact' ELSE 'none' END, " +
            "linkSource = CASE WHEN canonicalUrl IS NOT NULL AND TRIM(canonicalUrl) != '' THEN 'accessibility_text' ELSE 'none' END " +
            "WHERE fallbackUrl IS NOT NULL OR linkKind != 'exact' OR linkSource LIKE 'inferred_%'"
    )
    suspend fun scrubNonExactLinks(): Int

    @Query("DELETE FROM VideoEvents WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM VideoEvents WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long): Int

    @Query("DELETE FROM VideoEvents")
    suspend fun deleteAll()
}
