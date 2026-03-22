package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "PersonProfile",
    indices = [
        Index("isDeviceOwner"),
        Index("role"),
        Index("updatedAt")
    ]
)
data class PersonProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: String,
    val ageYears: Int? = null,
    val isDeviceOwner: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)
