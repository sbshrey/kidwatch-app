package com.kidwatch.app.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.kidwatch.app.R

class GoogleAuthClient(private val context: Context) {

    data class IdTokenResult(
        val idToken: String? = null,
        val errorMessage: String? = null
    )

    fun signInIntent(): Intent = getSignInClient().signInIntent

    fun extractIdToken(data: Intent?): IdTokenResult {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            val token = task.getResult(ApiException::class.java)?.idToken
            if (token.isNullOrBlank()) {
                IdTokenResult(
                    errorMessage = "Google account selected but ID token was empty."
                )
            } else {
                IdTokenResult(idToken = token)
            }
        } catch (error: ApiException) {
            val errorMessage = when (error.statusCode) {
                CommonStatusCodes.CANCELED -> "Google sign-in was canceled."
                CommonStatusCodes.NETWORK_ERROR -> "Network error during Google sign-in."
                CommonStatusCodes.DEVELOPER_ERROR ->
                    "Google sign-in is misconfigured (check Firebase OAuth client/SHA settings)."
                else -> "Google sign-in failed (code ${error.statusCode})."
            }
            Log.w("GoogleAuthClient", "Google sign-in failed", error)
            IdTokenResult(errorMessage = errorMessage)
        }
    }

    private fun getSignInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        return GoogleSignIn.getClient(context, options)
    }
}
