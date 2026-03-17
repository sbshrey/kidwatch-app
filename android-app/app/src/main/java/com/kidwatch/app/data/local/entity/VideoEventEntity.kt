package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "VideoEvents")
data class VideoEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val channel: String,
    val timestamp: Long,
    val faceDetected: Boolean = false
)
