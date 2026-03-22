package com.kidwatch.app.services

import android.content.Context
import android.content.pm.PackageManager
import com.kidwatch.app.BuildConfig

class AppBuildInfoProvider(
    private val context: Context
) {

    data class AppBuildInfo(
        val versionName: String,
        val versionCode: Long,
        val buildType: String
    )

    fun get(): AppBuildInfo {
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        val versionCode = when {
            packageInfo == null -> 0L
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P -> packageInfo.longVersionCode
            else -> @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        }

        return AppBuildInfo(
            versionName = packageInfo?.versionName.orEmpty().ifBlank { "unknown" },
            versionCode = versionCode,
            buildType = BuildConfig.BUILD_TYPE
        )
    }
}
