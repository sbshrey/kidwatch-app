package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ActivitySession",
    indices = [
        Index("packageName"),
        Index("startTime"),
        Index("updatedAt")
    ]
)
data class ActivitySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val representativeScreenshotPath: String? = null,
    val screenshotCount: Int = 0,
    val faceObservationCount: Int = 0,
    val videoEventCount: Int = 0,
    val assignedPersonProfileId: Long? = null,
    val assignedPersonName: String? = null,
    val assignedPersonRole: String? = null,
    val assignedPersonAgeYears: Int? = null,
    val primaryClusterId: Long? = null,
    val primaryIdentityLabel: String? = null,
    val kidFriendlyScore: Int = 5,
    val kidFriendlyModel: String = "kidwatch:ondevice-session-v1",
    val kidFriendlyInputHash: String? = null,
    val kidFriendlyScoredAt: Long? = null,
    val attentionLevel: String = "normal",
    val summary: String = "",
    val recommendation: String = "",
    val createdAt: Long,
    val updatedAt: Long
)
