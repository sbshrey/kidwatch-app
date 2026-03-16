package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.VideoEventEntity

@Dao
interface VideoEventsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VideoEventEntity): Long

    @Query("SELECT * FROM VideoEvents ORDER BY timestamp DESC")
    suspend fun getAll(): List<VideoEventEntity>

    @Query("SELECT * FROM VideoEvents WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    suspend fun getInWindow(startMs: Long, endMs: Long): List<VideoEventEntity>
}
