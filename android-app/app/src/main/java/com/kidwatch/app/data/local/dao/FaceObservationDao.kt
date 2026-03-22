package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.FaceObservationEntity

@Dao
interface FaceObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FaceObservationEntity): Long

    @Query("SELECT * FROM FaceObservation WHERE sessionId = :sessionId ORDER BY observedAt ASC")
    suspend fun getForSession(sessionId: Long): List<FaceObservationEntity>

    @Query("SELECT * FROM FaceObservation ORDER BY observedAt DESC LIMIT 1")
    suspend fun getLatest(): FaceObservationEntity?

    @Query("SELECT * FROM FaceObservation WHERE observedAt < :cutoffMs")
    suspend fun getOlderThan(cutoffMs: Long): List<FaceObservationEntity>

    @Query("DELETE FROM FaceObservation WHERE observedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM FaceObservation WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM FaceObservation")
    suspend fun deleteAll()
}
