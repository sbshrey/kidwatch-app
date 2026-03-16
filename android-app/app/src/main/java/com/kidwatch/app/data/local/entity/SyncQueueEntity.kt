package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "SyncQueue")
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val payloadJson: String,
    val createdAt: Long,
    val isSynced: Boolean = false
)
