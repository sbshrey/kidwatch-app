package com.kidwatch.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kidwatch.app.data.local.dao.AppUsageDao
import com.kidwatch.app.data.local.dao.ContentAnalysisDao
import com.kidwatch.app.data.local.dao.SyncQueueDao
import com.kidwatch.app.data.local.dao.UserDetectionDao
import com.kidwatch.app.data.local.dao.VideoEventsDao
import com.kidwatch.app.data.local.entity.AppUsageEntity
import com.kidwatch.app.data.local.entity.ContentAnalysisEntity
import com.kidwatch.app.data.local.entity.SyncQueueEntity
import com.kidwatch.app.data.local.entity.UserDetectionEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity

@Database(
    entities = [
        AppUsageEntity::class,
        VideoEventEntity::class,
        UserDetectionEntity::class,
        SyncQueueEntity::class,
        ContentAnalysisEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class KidWatchDatabase : RoomDatabase() {

    abstract fun appUsageDao(): AppUsageDao
    abstract fun videoEventsDao(): VideoEventsDao
    abstract fun userDetectionDao(): UserDetectionDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun contentAnalysisDao(): ContentAnalysisDao

    companion object {
        @Volatile
        private var INSTANCE: KidWatchDatabase? = null

        fun getInstance(context: Context): KidWatchDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KidWatchDatabase::class.java,
                    "kidwatch.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
