package com.kidwatch.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kidwatch.app.data.local.dao.AppUsageDao
import com.kidwatch.app.data.local.dao.ActivitySessionDao
import com.kidwatch.app.data.local.dao.ContentAnalysisDao
import com.kidwatch.app.data.local.dao.FaceObservationDao
import com.kidwatch.app.data.local.dao.IdentityClusterDao
import com.kidwatch.app.data.local.dao.MonitoredAppPolicyDao
import com.kidwatch.app.data.local.dao.PersonProfileDao
import com.kidwatch.app.data.local.dao.SessionInsightDao
import com.kidwatch.app.data.local.dao.SessionScreenshotDao
import com.kidwatch.app.data.local.dao.SyncQueueDao
import com.kidwatch.app.data.local.dao.UserDetectionDao
import com.kidwatch.app.data.local.dao.VideoEventsDao
import com.kidwatch.app.data.local.entity.ActivitySessionEntity
import com.kidwatch.app.data.local.entity.AppUsageEntity
import com.kidwatch.app.data.local.entity.ContentAnalysisEntity
import com.kidwatch.app.data.local.entity.FaceObservationEntity
import com.kidwatch.app.data.local.entity.IdentityClusterEntity
import com.kidwatch.app.data.local.entity.MonitoredAppPolicyEntity
import com.kidwatch.app.data.local.entity.PersonProfileEntity
import com.kidwatch.app.data.local.entity.SessionInsightEntity
import com.kidwatch.app.data.local.entity.SessionScreenshotEntity
import com.kidwatch.app.data.local.entity.SyncQueueEntity
import com.kidwatch.app.data.local.entity.UserDetectionEntity
import com.kidwatch.app.data.local.entity.VideoEventEntity

@Database(
    entities = [
        ActivitySessionEntity::class,
        AppUsageEntity::class,
        VideoEventEntity::class,
        SessionScreenshotEntity::class,
        FaceObservationEntity::class,
        IdentityClusterEntity::class,
        PersonProfileEntity::class,
        MonitoredAppPolicyEntity::class,
        SessionInsightEntity::class,
        UserDetectionEntity::class,
        SyncQueueEntity::class,
        ContentAnalysisEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class KidWatchDatabase : RoomDatabase() {

    abstract fun activitySessionDao(): ActivitySessionDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun videoEventsDao(): VideoEventsDao
    abstract fun sessionScreenshotDao(): SessionScreenshotDao
    abstract fun faceObservationDao(): FaceObservationDao
    abstract fun identityClusterDao(): IdentityClusterDao
    abstract fun personProfileDao(): PersonProfileDao
    abstract fun monitoredAppPolicyDao(): MonitoredAppPolicyDao
    abstract fun sessionInsightDao(): SessionInsightDao
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
