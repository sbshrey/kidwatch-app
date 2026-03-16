package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.AppUsageEntity

@Dao
interface AppUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AppUsageEntity): Long

    @Query("SELECT * FROM AppUsage ORDER BY startTime DESC")
    suspend fun getAll(): List<AppUsageEntity>

    @Query("SELECT * FROM AppUsage WHERE startTime >= :startMs AND startTime < :endMs ORDER BY startTime DESC")
    suspend fun getInWindow(startMs: Long, endMs: Long): List<AppUsageEntity>
}
