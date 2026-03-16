package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ContentAnalysis")
data class ContentAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val deviceId: String,
    val channel: String,
    val label: String,
    val reason: String,
    val model: String,
    val createdAt: Long
)
