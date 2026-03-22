package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.SessionInsightEntity

@Dao
interface SessionInsightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SessionInsightEntity): Long

    @Query("SELECT * FROM SessionInsight WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getForSession(sessionId: Long): SessionInsightEntity?

    @Query("DELETE FROM SessionInsight WHERE createdAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM SessionInsight WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long): Int

    @Query("DELETE FROM SessionInsight")
    suspend fun deleteAll()
}
