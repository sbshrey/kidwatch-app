package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "VideoEvents")
data class VideoEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val channel: String,
    val timestamp: Long,
    val canonicalUrl: String? = null,
    val fallbackUrl: String? = null,
    val linkKind: String = "none",
    val linkSource: String = "none",
    val faceDetected: Boolean = false,
    val sessionId: Long? = null
)
