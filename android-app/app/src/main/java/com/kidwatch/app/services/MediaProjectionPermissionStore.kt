package com.kidwatch.app.services

import android.content.Intent

object MediaProjectionPermissionStore {
    data class PermissionGrant(
        val resultCode: Int,
        val data: Intent
    )

    @Volatile
    private var permissionGrant: PermissionGrant? = null

    fun store(resultCode: Int, data: Intent) {
        permissionGrant = PermissionGrant(resultCode, Intent(data))
    }

    fun get(): PermissionGrant? = permissionGrant?.let { PermissionGrant(it.resultCode, Intent(it.data)) }

    fun hasGrant(): Boolean = permissionGrant != null

    fun clear() {
        permissionGrant = null
    }
}
