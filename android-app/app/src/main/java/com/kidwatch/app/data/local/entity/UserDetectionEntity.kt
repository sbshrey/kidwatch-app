package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "UserDetection")
data class UserDetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val detectedUser: String,
    val confidence: Float,
    val timestamp: Long
)
