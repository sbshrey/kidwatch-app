package com.kidwatch.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kidwatch.app.data.local.entity.PersonProfileEntity

@Dao
interface PersonProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PersonProfileEntity): Long

    @Update
    suspend fun update(entry: PersonProfileEntity)

    @Query("SELECT * FROM PersonProfile WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PersonProfileEntity?

    @Query("SELECT * FROM PersonProfile ORDER BY isDeviceOwner DESC, role ASC, name COLLATE NOCASE ASC")
    suspend fun getAll(): List<PersonProfileEntity>

    @Query("SELECT * FROM PersonProfile WHERE isDeviceOwner = 1 LIMIT 1")
    suspend fun getDeviceOwner(): PersonProfileEntity?

    @Query("SELECT * FROM PersonProfile WHERE role = 'child' ORDER BY ageYears ASC, name COLLATE NOCASE ASC")
    suspend fun getChildren(): List<PersonProfileEntity>

    @Query("DELETE FROM PersonProfile WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM PersonProfile")
    suspend fun deleteAll()
}
