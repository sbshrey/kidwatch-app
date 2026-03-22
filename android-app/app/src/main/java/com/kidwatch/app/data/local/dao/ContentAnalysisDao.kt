package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.ContentAnalysisEntity

@Dao
interface ContentAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ContentAnalysisEntity>)

    @Query("SELECT * FROM ContentAnalysis WHERE dateKey = :dateKey ORDER BY createdAt DESC")
    suspend fun getForDate(dateKey: String): List<ContentAnalysisEntity>

    @Query("SELECT * FROM ContentAnalysis WHERE dateKey = :dateKey AND deviceId = :deviceId ORDER BY createdAt DESC")
    suspend fun getForDateAndDevice(dateKey: String, deviceId: String): List<ContentAnalysisEntity>

    @Query("DELETE FROM ContentAnalysis WHERE dateKey = :dateKey AND deviceId = :deviceId")
    suspend fun deleteForDateAndDevice(dateKey: String, deviceId: String)

    @Query("DELETE FROM ContentAnalysis")
    suspend fun deleteAll()
}
