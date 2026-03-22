package com.kidwatch.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts baseline monitoring at device boot so session tracking can resume
 * without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(context, AutoMonitoringService::class.java))
        } else {
            context.startService(Intent(context, AutoMonitoringService::class.java))
        }
    }
}
