package com.kidwatch.app.services

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class FirestoreFamilyService(
    private val firestore: FirebaseFirestore
) {

    suspend fun ensureFamilyAndMembership(user: FirebaseUser) {
        val familyId = user.uid

        firestore.collection(FAMILIES_COLLECTION)
            .document(familyId)
            .set(
                mapOf(
                    "ownerUserId" to user.uid,
                    "displayName" to (user.displayName ?: user.email.orEmpty()),
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .await()

        firestore.collection(FAMILIES_COLLECTION)
            .document(familyId)
            .collection(FAMILY_MEMBERS_COLLECTION)
            .document(user.uid)
            .set(
                mapOf(
                    "userId" to user.uid,
                    "role" to ROLE_OWNER,
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .await()

        firestore.collection(FAMILY_MEMBERS_COLLECTION)
            .document(user.uid)
            .set(
                mapOf(
                    "userId" to user.uid,
                    "familyId" to familyId,
                    "role" to ROLE_OWNER,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .await()

        ensureRoleDoc(ROLE_OWNER)
        ensureRoleDoc(ROLE_PARENT)
        ensureRoleDoc(ROLE_GUARDIAN)
        ensureRoleDoc(ROLE_VIEWER)
    }

    private suspend fun ensureRoleDoc(role: String) {
        firestore.collection(ROLES_COLLECTION)
            .document(role)
            .set(
                mapOf(
                    "name" to role,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .await()
    }

    private companion object {
        private const val FAMILIES_COLLECTION = "families"
        private const val FAMILY_MEMBERS_COLLECTION = "familyMembers"
        private const val ROLES_COLLECTION = "roles"

        private const val ROLE_OWNER = "OWNER"
        private const val ROLE_PARENT = "PARENT"
        private const val ROLE_GUARDIAN = "GUARDIAN"
        private const val ROLE_VIEWER = "VIEWER"
    }
}
