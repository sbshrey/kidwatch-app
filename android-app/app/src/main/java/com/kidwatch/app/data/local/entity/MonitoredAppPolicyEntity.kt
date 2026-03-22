package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "MonitoredAppPolicy",
    indices = [
        Index("category"),
        Index("isRecommended"),
        Index("trackSessions")
    ]
)
data class MonitoredAppPolicyEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val category: String,
    val isRecommended: Boolean,
    val trackSessions: Boolean,
    val allowScreenshots: Boolean,
    val allowFaceCapture: Boolean,
    val updatedAt: Long
)
