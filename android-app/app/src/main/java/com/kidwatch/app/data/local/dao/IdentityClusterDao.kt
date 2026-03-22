package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kidwatch.app.data.local.entity.IdentityClusterEntity

@Dao
interface IdentityClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: IdentityClusterEntity): Long

    @Update
    suspend fun update(entry: IdentityClusterEntity)

    @Query("SELECT * FROM IdentityCluster WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): IdentityClusterEntity?

    @Query("SELECT * FROM IdentityCluster ORDER BY updatedAt DESC")
    suspend fun getAll(): List<IdentityClusterEntity>

    @Query("SELECT * FROM IdentityCluster WHERE personProfileId = :personProfileId ORDER BY updatedAt DESC")
    suspend fun getForPersonProfile(personProfileId: Long): List<IdentityClusterEntity>

    @Query("SELECT * FROM IdentityCluster WHERE label IS NULL OR TRIM(label) = '' ORDER BY updatedAt DESC")
    suspend fun getUnknownClusters(): List<IdentityClusterEntity>

    @Query("DELETE FROM IdentityCluster WHERE updatedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM IdentityCluster WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM IdentityCluster")
    suspend fun deleteAll()
}
