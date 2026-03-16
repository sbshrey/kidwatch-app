package com.kidwatch.app.services

import android.content.Context
import android.os.Build
import android.provider.Settings

class DeviceInfoProvider(
    private val context: Context
) {

    data class DeviceInfo(
        val deviceId: String,
        val deviceName: String,
        val model: String
    )

    fun getDeviceInfo(): DeviceInfo {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"

        val manufacturer = Build.MANUFACTURER ?: "Android"
        val model = Build.MODEL ?: "Unknown"
        val deviceName = "$manufacturer $model".trim()

        return DeviceInfo(
            deviceId = androidId,
            deviceName = deviceName,
            model = model
        )
    }
}
