package com.kidwatch.app.services

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class FirestoreDeviceService(
    private val firestore: FirebaseFirestore
) {

    suspend fun ensureDeviceRegistered(
        deviceInfo: DeviceInfoProvider.DeviceInfo,
        ownerUserId: String
    ) {
        val deviceDoc = firestore.collection(DEVICES_COLLECTION).document(deviceInfo.deviceId)
        val snapshot = deviceDoc.get().await()
        if (snapshot.exists()) return

        val payload = mapOf(
            FIELD_OWNER_USER_ID to ownerUserId,
            FIELD_DEVICE_NAME to deviceInfo.deviceName,
            FIELD_MODEL to deviceInfo.model,
            FIELD_CREATED_AT to FieldValue.serverTimestamp()
        )

        deviceDoc.set(payload).await()
    }

    private companion object {
        private const val DEVICES_COLLECTION = "devices"
        private const val FIELD_OWNER_USER_ID = "ownerUserId"
        private const val FIELD_DEVICE_NAME = "deviceName"
        private const val FIELD_MODEL = "model"
        private const val FIELD_CREATED_AT = "createdAt"
    }
}
