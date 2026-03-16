package com.kidwatch.app.services

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class FirestoreUserService(
    private val firestore: FirebaseFirestore
) {

    suspend fun upsertUser(user: FirebaseUser) {
        val userDoc = firestore.collection(USERS_COLLECTION).document(user.uid)
        val snapshot = userDoc.get().await()

        val payload = mutableMapOf<String, Any>(
            FIELD_NAME to (user.displayName ?: ""),
            FIELD_EMAIL to (user.email ?: ""),
            FIELD_ROLE to DEFAULT_ROLE
        )

        if (!snapshot.exists()) {
            payload[FIELD_CREATED_AT] = FieldValue.serverTimestamp()
        }

        userDoc.set(payload, SetOptions.merge()).await()
    }

    private companion object {
        private const val USERS_COLLECTION = "users"
        private const val FIELD_NAME = "name"
        private const val FIELD_EMAIL = "email"
        private const val FIELD_ROLE = "role"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val DEFAULT_ROLE = "PARENT"
    }
}
