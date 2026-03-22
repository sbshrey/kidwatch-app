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

    @Query("SELECT * FROM AppUsage WHERE packageName = :packageName ORDER BY endTime DESC LIMIT 1")
    suspend fun getLatestForPackage(packageName: String): AppUsageEntity?

    @Query("UPDATE AppUsage SET startTime = :startTime, endTime = :endTime, duration = :duration WHERE id = :id")
    suspend fun updateSession(id: Long, startTime: Long, endTime: Long, duration: Long)

    @Query("DELETE FROM AppUsage WHERE endTime < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM AppUsage")
    suspend fun deleteAll()
}
