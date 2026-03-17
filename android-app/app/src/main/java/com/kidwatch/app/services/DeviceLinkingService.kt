package com.kidwatch.app.services

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

class DeviceLinkingService(
    private val firestore: FirebaseFirestore
) {

    suspend fun upsertDeviceProfile(
        deviceInfo: DeviceInfoProvider.DeviceInfo,
        authUserId: String,
        preferredName: String
    ) {
        firestore.collection(DEVICE_PROFILES_COLLECTION)
            .document(deviceInfo.deviceId)
            .set(
                mapOf(
                    "deviceId" to deviceInfo.deviceId,
                    "deviceName" to deviceInfo.deviceName,
                    "model" to deviceInfo.model,
                    "authUserId" to authUserId,
                    "preferredName" to preferredName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .await()
    }

    suspend fun fetchLinkedDevices(localDeviceId: String): List<LinkedDeviceSummary> {
        val snapshot = firestore.collection(DEVICE_PROFILES_COLLECTION)
            .document(localDeviceId)
            .collection(DEVICE_LINKS_COLLECTION)
            .get()
            .await()

        return snapshot.documents.map { doc ->
            LinkedDeviceSummary(
                remoteDeviceId = doc.id,
                remoteDeviceName = doc.getString("remoteDeviceName").orEmpty().ifBlank { "Family device" },
                remoteModel = doc.getString("remoteModel").orEmpty(),
                remotePreferredName = doc.getString("remotePreferredName").orEmpty(),
                customName = doc.getString("customName").orEmpty()
            )
        }.sortedBy { it.remoteDeviceName.lowercase() }
    }

    suspend fun linkDevicesBidirectional(
        localDeviceInfo: DeviceInfoProvider.DeviceInfo,
        localAuthUserId: String,
        localPreferredName: String,
        remoteDeviceId: String,
        remoteDeviceName: String,
        remotePreferredName: String,
        remoteModel: String,
        customName: String
    ) {
        val localProfileRef = firestore.collection(DEVICE_PROFILES_COLLECTION).document(localDeviceInfo.deviceId)
        val remoteProfileRef = firestore.collection(DEVICE_PROFILES_COLLECTION).document(remoteDeviceId)
        val localLinkRef = localProfileRef.collection(DEVICE_LINKS_COLLECTION).document(remoteDeviceId)
        val remoteLinkRef = remoteProfileRef.collection(DEVICE_LINKS_COLLECTION).document(localDeviceInfo.deviceId)
        val localEdgeRef = firestore.collection(DEVICE_LINK_EDGES_COLLECTION)
            .document("${localDeviceInfo.deviceId}__${remoteDeviceId}")
        val remoteEdgeRef = firestore.collection(DEVICE_LINK_EDGES_COLLECTION)
            .document("${remoteDeviceId}__${localDeviceInfo.deviceId}")

        firestore.runTransaction { transaction ->
            transaction.set(
                localProfileRef,
                mapOf(
                    "deviceId" to localDeviceInfo.deviceId,
                    "deviceName" to localDeviceInfo.deviceName,
                    "model" to localDeviceInfo.model,
                    "authUserId" to localAuthUserId,
                    "preferredName" to localPreferredName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            transaction.set(
                remoteProfileRef,
                mapOf(
                    "deviceId" to remoteDeviceId,
                    "deviceName" to remoteDeviceName,
                    "model" to remoteModel,
                    "preferredName" to remotePreferredName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )

            transaction.set(
                localLinkRef,
                mapOf(
                    "remoteDeviceId" to remoteDeviceId,
                    "remoteDeviceName" to remoteDeviceName,
                    "remoteModel" to remoteModel,
                    "remotePreferredName" to remotePreferredName,
                    "customName" to customName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )

            // Reverse link is created immediately with neutral defaults.
            transaction.set(
                remoteLinkRef,
                mapOf(
                    "remoteDeviceId" to localDeviceInfo.deviceId,
                    "remoteDeviceName" to localDeviceInfo.deviceName,
                    "remoteModel" to localDeviceInfo.model,
                    "remotePreferredName" to localPreferredName,
                    "customName" to "",
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )

            transaction.set(
                localEdgeRef,
                mapOf(
                    "localDeviceId" to localDeviceInfo.deviceId,
                    "remoteDeviceId" to remoteDeviceId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            transaction.set(
                remoteEdgeRef,
                mapOf(
                    "localDeviceId" to remoteDeviceId,
                    "remoteDeviceId" to localDeviceInfo.deviceId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }.await()
    }

    suspend fun updateLinkedDeviceName(
        localDeviceId: String,
        remoteDeviceId: String,
        customName: String
    ) {
        firestore.collection(DEVICE_PROFILES_COLLECTION)
            .document(localDeviceId)
            .collection(DEVICE_LINKS_COLLECTION)
            .document(remoteDeviceId)
            .set(
                mapOf(
                    "customName" to customName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .await()
    }

    suspend fun unlinkDevicesBidirectional(
        localDeviceId: String,
        remoteDeviceId: String
    ) {
        val localRef = firestore.collection(DEVICE_PROFILES_COLLECTION)
            .document(localDeviceId)
            .collection(DEVICE_LINKS_COLLECTION)
            .document(remoteDeviceId)
        val remoteRef = firestore.collection(DEVICE_PROFILES_COLLECTION)
            .document(remoteDeviceId)
            .collection(DEVICE_LINKS_COLLECTION)
            .document(localDeviceId)
        val localEdgeRef = firestore.collection(DEVICE_LINK_EDGES_COLLECTION)
            .document("${localDeviceId}__${remoteDeviceId}")
        val remoteEdgeRef = firestore.collection(DEVICE_LINK_EDGES_COLLECTION)
            .document("${remoteDeviceId}__${localDeviceId}")
        firestore.runBatch { batch ->
            batch.delete(localRef)
            batch.delete(remoteRef)
            batch.delete(localEdgeRef)
            batch.delete(remoteEdgeRef)
        }.await()
    }

    suspend fun resolveFamilyIdForUser(userId: String): String {
        val legacyMembership = firestore.collection(FAMILY_MEMBERS_COLLECTION)
            .document(userId)
            .get()
            .await()
        val familyId = legacyMembership.getString("familyId").orEmpty()
        return familyId.ifBlank { userId }
    }

    suspend fun fetchProfileSnapshot(
        familyId: String,
        currentUserId: String
    ): ProfileSnapshot {
        ensureOwnerMembershipIfMissing(familyId, currentUserId)

        val membersSnapshot = familiesCollection()
            .document(familyId)
            .collection(FAMILY_MEMBERS_COLLECTION)
            .get()
            .await()
        val devicesSnapshot = familiesCollection()
            .document(familyId)
            .collection(DEVICES_COLLECTION)
            .get()
            .await()
        val requestsSnapshot = familiesCollection()
            .document(familyId)
            .collection(LINK_REQUESTS_COLLECTION)
            .whereEqualTo(FIELD_STATUS, STATUS_PENDING)
            .get()
            .await()

        val members = membersSnapshot.documents.map { doc ->
            FamilyMemberSummary(
                userId = doc.getString("userId").orEmpty().ifBlank { doc.id },
                role = doc.getString("role").orEmpty().ifBlank { ROLE_VIEWER }
            )
        }.sortedBy { it.userId }

        val devices = devicesSnapshot.documents.map { doc ->
            DeviceSummary(
                deviceId = doc.id,
                deviceName = doc.getString("deviceName").orEmpty().ifBlank { "Unknown device" },
                model = doc.getString("model").orEmpty(),
                ownerUserId = doc.getString("ownerUserId").orEmpty(),
                relationTag = doc.getString("relationTag").orEmpty().ifBlank { ROLE_KID }
            )
        }.sortedBy { it.deviceName.lowercase() }

        val requests = requestsSnapshot.documents.map { doc ->
            LinkRequestSummary(
                requestId = doc.id,
                requesterUserId = doc.getString("requesterUserId").orEmpty(),
                requesterDeviceId = doc.getString("requesterDeviceId").orEmpty(),
                requesterDeviceName = doc.getString("requesterDeviceName").orEmpty(),
                requestedRole = doc.getString("requestedRole").orEmpty().ifBlank { ROLE_KID }
            )
        }.sortedBy { it.requesterUserId }

        val currentMemberDoc = familiesCollection()
            .document(familyId)
            .collection(FAMILY_MEMBERS_COLLECTION)
            .document(currentUserId)
            .get()
            .await()
        val currentRole = currentMemberDoc.getString("role").orEmpty().ifBlank {
            if (familyId == currentUserId) ROLE_OWNER else ROLE_VIEWER
        }
        return ProfileSnapshot(
            familyId = familyId,
            currentUserRole = currentRole,
            members = members,
            devices = devices,
            pendingRequests = requests
        )
    }

    suspend fun generateLinkCode(
        familyId: String,
        createdByUserId: String,
        allowedRole: String = ROLE_KID,
        ttlMinutes: Int = DEFAULT_CODE_TTL_MINUTES
    ): GeneratedLinkCode {
        val code = buildCode()
        val now = System.currentTimeMillis()
        val expiresAtMillis = now + ttlMinutes * 60_000L

        familiesCollection()
            .document(familyId)
            .collection(LINK_CODES_COLLECTION)
            .document(code)
            .set(
                mapOf(
                    FIELD_CODE to code,
                    FIELD_STATUS to STATUS_PENDING,
                    FIELD_ALLOWED_ROLE to allowedRole,
                    FIELD_CREATED_BY_USER_ID to createdByUserId,
                    FIELD_CREATED_AT to FieldValue.serverTimestamp(),
                    FIELD_EXPIRES_AT_MILLIS to expiresAtMillis
                )
            )
            .await()

        firestore.collection(LINK_CODES_GLOBAL_COLLECTION)
            .document(code)
            .set(
                mapOf(
                    FIELD_CODE to code,
                    "familyId" to familyId,
                    FIELD_STATUS to STATUS_PENDING,
                    FIELD_ALLOWED_ROLE to allowedRole,
                    FIELD_CREATED_BY_USER_ID to createdByUserId,
                    FIELD_CREATED_AT to FieldValue.serverTimestamp(),
                    FIELD_EXPIRES_AT_MILLIS to expiresAtMillis
                )
            )
            .await()

        return GeneratedLinkCode(
            code = code,
            expiresAtMillis = expiresAtMillis
        )
    }

    suspend fun submitLinkRequestByCode(
        code: String,
        requesterUserId: String,
        deviceInfo: DeviceInfoProvider.DeviceInfo
    ) {
        val normalizedCode = code.trim().uppercase()
        if (normalizedCode.isBlank()) throw IllegalArgumentException("Invalid or expired code.")

        val globalCodeDoc = firestore.collection(LINK_CODES_GLOBAL_COLLECTION)
            .document(normalizedCode)
            .get()
            .await()
        if (!globalCodeDoc.exists()) throw IllegalStateException("Invalid or expired code.")

        val familyId = globalCodeDoc.getString("familyId").orEmpty()
        if (familyId.isBlank()) throw IllegalStateException("Invalid code state.")
        val familyRef = familiesCollection().document(familyId)
        val codeRef = familyRef.collection(LINK_CODES_COLLECTION).document(normalizedCode)
        val globalCodeRef = firestore.collection(LINK_CODES_GLOBAL_COLLECTION).document(normalizedCode)
        val requestsCollection = familyRef.collection(LINK_REQUESTS_COLLECTION)

        firestore.runTransaction { transaction ->
            val freshGlobalCode = transaction.get(globalCodeRef)
            val freshCode = transaction.get(codeRef)

            if (!freshGlobalCode.exists()) throw IllegalStateException("Invalid or expired code.")

            val globalStatus = freshGlobalCode.getString(FIELD_STATUS).orEmpty()
            val status = freshCode.getString(FIELD_STATUS).orEmpty()
            val expiresAtMillis = freshCode.getLong(FIELD_EXPIRES_AT_MILLIS) ?: 0L
            if (globalStatus != STATUS_PENDING || status != STATUS_PENDING || expiresAtMillis < System.currentTimeMillis()) {
                throw IllegalStateException("Invalid or expired code.")
            }

            val requestRef = requestsCollection.document()
            transaction.set(
                requestRef,
                mapOf(
                    "requesterUserId" to requesterUserId,
                    "requesterDeviceId" to deviceInfo.deviceId,
                    "requesterDeviceName" to deviceInfo.deviceName,
                    "requesterModel" to deviceInfo.model,
                    "requestedRole" to (freshCode.getString(FIELD_ALLOWED_ROLE) ?: ROLE_KID),
                    "relationTag" to (freshCode.getString(FIELD_ALLOWED_ROLE) ?: ROLE_KID),
                    FIELD_STATUS to STATUS_PENDING,
                    "codeId" to codeRef.id,
                    FIELD_CREATED_AT to FieldValue.serverTimestamp()
                )
            )
            transaction.update(
                codeRef,
                mapOf(
                    FIELD_STATUS to STATUS_CONSUMED,
                    "consumedByUserId" to requesterUserId,
                    "consumedAt" to FieldValue.serverTimestamp()
                )
            )
            transaction.update(
                globalCodeRef,
                mapOf(
                    FIELD_STATUS to STATUS_CONSUMED,
                    "consumedByUserId" to requesterUserId,
                    "consumedAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    suspend fun approveLinkRequest(
        familyId: String,
        requestId: String,
        approverUserId: String
    ) {
        val familyRef = familiesCollection().document(familyId)
        val requestRef = familyRef.collection(LINK_REQUESTS_COLLECTION).document(requestId)

        firestore.runTransaction { transaction ->
            val requestDoc = transaction.get(requestRef)
            if (!requestDoc.exists()) {
                throw IllegalStateException("Request not found.")
            }
            if (requestDoc.getString(FIELD_STATUS) != STATUS_PENDING) {
                throw IllegalStateException("Request already handled.")
            }

            val requesterUserId = requestDoc.getString("requesterUserId")
                ?: throw IllegalStateException("Invalid request user.")
            val requesterDeviceId = requestDoc.getString("requesterDeviceId")
                ?: throw IllegalStateException("Invalid request device.")
            val requesterDeviceName = requestDoc.getString("requesterDeviceName").orEmpty()
            val requesterModel = requestDoc.getString("requesterModel").orEmpty()
            val requestedRole = requestDoc.getString("requestedRole").orEmpty().ifBlank { ROLE_KID }
            val relationTag = requestDoc.getString("relationTag").orEmpty().ifBlank { requestedRole }

            val memberRef = familyRef.collection(FAMILY_MEMBERS_COLLECTION).document(requesterUserId)
            val familyDeviceRef = familyRef.collection(DEVICES_COLLECTION).document(requesterDeviceId)
            val globalDeviceRef = firestore.collection(DEVICES_COLLECTION).document(requesterDeviceId)

            transaction.set(
                memberRef,
                mapOf(
                    "userId" to requesterUserId,
                    "role" to requestedRole,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            val devicePayload = mapOf(
                "ownerUserId" to requesterUserId,
                "familyId" to familyId,
                "deviceName" to requesterDeviceName,
                "model" to requesterModel,
                "relationTag" to relationTag,
                "linkedByUserId" to approverUserId,
                "linkedAt" to FieldValue.serverTimestamp(),
                FIELD_CREATED_AT to FieldValue.serverTimestamp()
            )
            transaction.set(familyDeviceRef, devicePayload)
            transaction.set(globalDeviceRef, devicePayload)
            transaction.update(
                requestRef,
                mapOf(
                    FIELD_STATUS to STATUS_APPROVED,
                    "approvedByUserId" to approverUserId,
                    "approvedAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    suspend fun denyLinkRequest(
        familyId: String,
        requestId: String,
        approverUserId: String
    ) {
        familiesCollection()
            .document(familyId)
            .collection(LINK_REQUESTS_COLLECTION)
            .document(requestId)
            .update(
                mapOf(
                    FIELD_STATUS to STATUS_DENIED,
                    "deniedByUserId" to approverUserId,
                    "deniedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }

    suspend fun unlinkDevice(
        familyId: String,
        deviceId: String
    ) {
        val familyDeviceRef = familiesCollection()
            .document(familyId)
            .collection(DEVICES_COLLECTION)
            .document(deviceId)

        familyDeviceRef.delete().await()
        firestore.collection(DEVICES_COLLECTION).document(deviceId).delete().await()
    }

    suspend fun linkDeviceDirect(
        familyId: String,
        approverUserId: String,
        targetUserId: String,
        targetDeviceId: String,
        targetDeviceName: String,
        targetModel: String,
        relationTag: String
    ) {
        val familyRef = familiesCollection().document(familyId)
        val memberRef = familyRef.collection(FAMILY_MEMBERS_COLLECTION).document(targetUserId)
        val familyDeviceRef = familyRef.collection(DEVICES_COLLECTION).document(targetDeviceId)
        val globalDeviceRef = firestore.collection(DEVICES_COLLECTION).document(targetDeviceId)

        firestore.runTransaction { transaction ->
            transaction.set(
                memberRef,
                mapOf(
                    "userId" to targetUserId,
                    "role" to relationTag,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            val payload = mapOf(
                "ownerUserId" to targetUserId,
                "familyId" to familyId,
                "deviceName" to targetDeviceName,
                "model" to targetModel,
                "relationTag" to relationTag,
                "linkedByUserId" to approverUserId,
                "linkedAt" to FieldValue.serverTimestamp(),
                FIELD_CREATED_AT to FieldValue.serverTimestamp()
            )
            transaction.set(familyDeviceRef, payload)
            transaction.set(globalDeviceRef, payload)
        }.await()
    }

    private fun buildCode(length: Int = LINK_CODE_LENGTH): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val chars = CharArray(length) {
            alphabet[random.nextInt(alphabet.length)]
        }
        return String(chars)
    }

    private suspend fun ensureOwnerMembershipIfMissing(
        familyId: String,
        currentUserId: String
    ) {
        if (familyId != currentUserId) return

        val familyRef = familiesCollection().document(familyId)
        val memberRef = familyRef.collection(FAMILY_MEMBERS_COLLECTION).document(currentUserId)
        val legacyMemberRef = firestore.collection(FAMILY_MEMBERS_COLLECTION).document(currentUserId)

        firestore.runTransaction { transaction ->
            val familyDoc = transaction.get(familyRef)
            val memberDoc = transaction.get(memberRef)
            val legacyMemberDoc = transaction.get(legacyMemberRef)

            if (!familyDoc.exists()) {
                transaction.set(
                    familyRef,
                    mapOf(
                        "ownerUserId" to currentUserId,
                        "displayName" to currentUserId,
                        FIELD_CREATED_AT to FieldValue.serverTimestamp()
                    )
                )
            }

            if (!memberDoc.exists()) {
                transaction.set(
                    memberRef,
                    mapOf(
                        "userId" to currentUserId,
                        "role" to ROLE_OWNER,
                        FIELD_CREATED_AT to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            if (!legacyMemberDoc.exists()) {
                transaction.set(
                    legacyMemberRef,
                    mapOf(
                        "userId" to currentUserId,
                        "familyId" to familyId,
                        "role" to ROLE_OWNER,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }.await()
    }

    private fun familiesCollection() = firestore.collection(FAMILIES_COLLECTION)

    data class ProfileSnapshot(
        val familyId: String,
        val currentUserRole: String,
        val members: List<FamilyMemberSummary>,
        val devices: List<DeviceSummary>,
        val pendingRequests: List<LinkRequestSummary>
    )

    data class FamilyMemberSummary(
        val userId: String,
        val role: String
    )

    data class DeviceSummary(
        val deviceId: String,
        val deviceName: String,
        val model: String,
        val ownerUserId: String,
        val relationTag: String
    )

    data class LinkRequestSummary(
        val requestId: String,
        val requesterUserId: String,
        val requesterDeviceId: String,
        val requesterDeviceName: String,
        val requestedRole: String
    )

    data class GeneratedLinkCode(
        val code: String,
        val expiresAtMillis: Long
    )

    data class LinkedDeviceSummary(
        val remoteDeviceId: String,
        val remoteDeviceName: String,
        val remoteModel: String,
        val remotePreferredName: String,
        val customName: String
    )

    private companion object {
        private val random = SecureRandom()

        private const val FAMILIES_COLLECTION = "families"
        private const val DEVICE_PROFILES_COLLECTION = "deviceProfiles"
        private const val DEVICE_LINKS_COLLECTION = "links"
        private const val DEVICE_LINK_EDGES_COLLECTION = "deviceLinkEdges"
        private const val FAMILY_MEMBERS_COLLECTION = "familyMembers"
        private const val DEVICES_COLLECTION = "devices"
        private const val LINK_CODES_COLLECTION = "linkCodes"
        private const val LINK_CODES_GLOBAL_COLLECTION = "linkCodesGlobal"
        private const val LINK_REQUESTS_COLLECTION = "linkRequests"

        private const val FIELD_CODE = "code"
        private const val FIELD_STATUS = "status"
        private const val FIELD_ALLOWED_ROLE = "allowedRole"
        private const val FIELD_CREATED_BY_USER_ID = "createdByUserId"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_EXPIRES_AT_MILLIS = "expiresAtMillis"

        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_CONSUMED = "CONSUMED"
        private const val STATUS_APPROVED = "APPROVED"
        private const val STATUS_DENIED = "DENIED"

        private const val ROLE_VIEWER = "VIEWER"
        private const val ROLE_OWNER = "OWNER"
        private const val ROLE_KID = "KID"
        private const val DEFAULT_CODE_TTL_MINUTES = 5
        private const val LINK_CODE_LENGTH = 8
    }
}
