package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidwatch.app.data.local.entity.MonitoredAppPolicyEntity

@Dao
interface MonitoredAppPolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MonitoredAppPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<MonitoredAppPolicyEntity>)

    @Query("SELECT * FROM MonitoredAppPolicy ORDER BY isRecommended DESC, displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<MonitoredAppPolicyEntity>

    @Query("SELECT * FROM MonitoredAppPolicy WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackage(packageName: String): MonitoredAppPolicyEntity?

    @Query("SELECT COUNT(*) FROM MonitoredAppPolicy")
    suspend fun countAll(): Int

    @Query("DELETE FROM MonitoredAppPolicy")
    suspend fun deleteAll()
}
