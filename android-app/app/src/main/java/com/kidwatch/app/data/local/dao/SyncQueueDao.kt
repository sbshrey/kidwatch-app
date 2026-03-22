package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kidwatch.app.data.local.entity.SyncQueueEntity

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncQueueEntity): Long

    @Query("SELECT * FROM SyncQueue WHERE isSynced = 0 ORDER BY createdAt ASC")
    suspend fun getPending(): List<SyncQueueEntity>

    @Update
    suspend fun update(entry: SyncQueueEntity)

    @Query("UPDATE SyncQueue SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("DELETE FROM SyncQueue WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM SyncQueue")
    suspend fun deleteAll()
}
