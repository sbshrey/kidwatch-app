package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.UserDetectionEntity

@Dao
interface UserDetectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: UserDetectionEntity): Long

    @Query("SELECT * FROM UserDetection ORDER BY timestamp DESC")
    suspend fun getAll(): List<UserDetectionEntity>
}
