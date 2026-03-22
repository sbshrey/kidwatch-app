package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.SessionScreenshotEntity

@Dao
interface SessionScreenshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SessionScreenshotEntity): Long

    @Query("SELECT * FROM SessionScreenshot WHERE sessionId = :sessionId ORDER BY capturedAt ASC")
    suspend fun getForSession(sessionId: Long): List<SessionScreenshotEntity>

    @Query("SELECT COUNT(*) FROM SessionScreenshot WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("DELETE FROM SessionScreenshot WHERE capturedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT * FROM SessionScreenshot WHERE capturedAt < :cutoffMs")
    suspend fun getOlderThan(cutoffMs: Long): List<SessionScreenshotEntity>

    @Query("DELETE FROM SessionScreenshot WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM SessionScreenshot")
    suspend fun deleteAll()
}
