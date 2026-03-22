package com.kidwatch.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kidwatch.app.MainActivity
import com.kidwatch.app.R
import com.kidwatch.app.repository.LocalMonitoringRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutoMonitoringService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var repository: LocalMonitoringRepository
    private var loopStarted = false
    private var ignoredHomePackage: String? = null

    private var activeSessionId: Long? = null
    private var activePackageName: String? = null
    private var activeSessionStartedAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        repository = LocalMonitoringRepository(applicationContext)
        ignoredHomePackage = resolveHomePackage()
        createNotificationChannel()
        serviceScope.launch {
            repository.syncMonitoredAppPolicies()
            repository.pruneOldTelemetry()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (!loopStarted) {
            loopStarted = true
            startForegroundCheckLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        EvidenceRuntimeState.clearActiveSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCheckLoop() {
        handler.post(object : Runnable {
            override fun run() {
                serviceScope.launch {
                    runCatching { tick() }
                }
                handler.postDelayed(this, FOREGROUND_CHECK_INTERVAL_MS)
            }
        })
    }

    private suspend fun tick() {
        if (!UsageAccessHelper.hasUsageAccess(this)) {
            clearActiveSession()
            refreshNotification()
            return
        }

        val foregroundPackage = getForegroundPackage()
        val now = System.currentTimeMillis()
        if (foregroundPackage.isNullOrBlank() || shouldIgnorePackage(foregroundPackage)) {
            activeSessionId?.let { repository.extendActivitySession(it, now) }
            if (activeSessionId != null) {
                Log.d(TAG, "Clearing active session for package=$activePackageName")
            }
            clearActiveSession()
            refreshNotification()
            return
        }

        if (foregroundPackage != activePackageName || activeSessionId == null) {
            activeSessionId?.let { repository.extendActivitySession(it, now) }
            val capturePolicy = repository.getCapturePolicy(foregroundPackage)
            if (!capturePolicy.trackSessions) {
                clearActiveSession()
                refreshNotification()
                return
            }
            activePackageName = foregroundPackage
            activeSessionStartedAt = now
            activeSessionId = repository.upsertActivitySession(
                packageName = foregroundPackage,
                startTime = now,
                endTime = now
            ).takeIf { it > 0L }
            Log.d(TAG, "Started/resumed session id=$activeSessionId package=$activePackageName")
        } else {
            activeSessionId?.let { repository.extendActivitySession(it, now) }
        }

        EvidenceRuntimeState.setActiveSession(
            sessionId = activeSessionId,
            packageName = activePackageName,
            startedAt = activeSessionStartedAt.takeIf { it > 0L }
        )
        refreshNotification()
    }

    private fun clearActiveSession() {
        activeSessionId = null
        activePackageName = null
        activeSessionStartedAt = 0L
        EvidenceRuntimeState.clearActiveSession()
    }

    private fun getForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val end = System.currentTimeMillis()
        val start = end - FOREGROUND_QUERY_WINDOW_MS
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        return stats
            ?.filter { it.lastTimeUsed > start }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    private fun shouldIgnorePackage(packageName: String): Boolean {
        if (packageName == applicationContext.packageName) return true
        if (packageName == "com.android.systemui") return true
        if (packageName == ignoredHomePackage) return true
        return false
    }

    private fun resolveHomePackage(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.auto_monitoring_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.auto_monitoring_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val contentText = when {
            !UsageAccessHelper.hasUsageAccess(this) -> getString(R.string.auto_monitoring_notification_usage_needed)
            !activePackageName.isNullOrBlank() -> getString(
                R.string.auto_monitoring_notification_active,
                resolveAppLabel(activePackageName.orEmpty())
            )
            else -> getString(R.string.auto_monitoring_notification_ready)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.auto_monitoring_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_kidwatch)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun resolveAppLabel(packageName: String): String {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    companion object {
        private const val TAG = "AutoMonitoringService"
        private const val NOTIFICATION_ID = 4000
        private const val CHANNEL_ID = "auto_monitoring"
        private const val FOREGROUND_CHECK_INTERVAL_MS = 3_000L
        private const val FOREGROUND_QUERY_WINDOW_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, AutoMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoMonitoringService::class.java))
        }
    }
}
