package com.kidwatch.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "IdentityCluster",
    indices = [
        Index("updatedAt")
    ]
)
data class IdentityClusterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String? = null,
    val role: String = "unknown",
    val personProfileId: Long? = null,
    val representativeCropPath: String? = null,
    val centroid: String,
    val sampleCount: Int = 1,
    val createdAt: Long,
    val updatedAt: Long
)
