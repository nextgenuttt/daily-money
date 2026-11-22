package com.dailymoney.feature.auth.google

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.core.domain.auth.SignInWithGoogleUseCase
import com.dailymoney.core.domain.config.AppConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import javax.inject.Inject

/**
 * "Continue with Google": Credential Manager account sheet → Google ID token → Firebase.
 * Needs the Activity only for the duration of the call (the sheet is shown on top of it).
 */
class GoogleSignInHandler @Inject constructor(
    private val config: AppConfig,
    private val signInWithGoogle: SignInWithGoogleUseCase,
) {
    suspend fun signIn(activity: Activity): AuthResult<AuthUser> {
        if (!config.googleSignInConfigured) return AuthResult.Failure(AuthError.GoogleNotConfigured)
        val idToken = try {
            val option = GetSignInWithGoogleOption.Builder(config.googleWebClientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return AuthResult.Failure(AuthError.Unknown("Unexpected credential type"))
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GetCredentialCancellationException) {
            return AuthResult.Failure(AuthError.Cancelled)
        } catch (e: NoCredentialException) {
            return AuthResult.Failure(AuthError.NoGoogleAccount)
        } catch (e: GetCredentialException) {
            return AuthResult.Failure(AuthError.Unknown(e.message))
        } catch (e: GoogleIdTokenParsingException) {
            return AuthResult.Failure(AuthError.Unknown(e.message))
        }
        return signInWithGoogle(idToken)
    }
}
