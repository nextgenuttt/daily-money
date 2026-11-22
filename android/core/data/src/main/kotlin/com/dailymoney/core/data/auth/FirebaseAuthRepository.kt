package com.dailymoney.core.data.auth

import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.core.domain.auth.SessionState
import com.dailymoney.core.domain.config.AppConfig
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.tasks.await

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val config: AppConfig,
) : AuthRepository {

    // reload() changes emailVerified without firing the auth-state listener, so we re-emit by hand.
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val authChanges: Flow<Unit> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(Unit) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override val session: Flow<SessionState> = merge(authChanges, refreshes)
        .onStart { emit(Unit) }
        .map { auth.currentUser.toSession() }
        .distinctUntilChanged()

    override suspend fun signInWithEmail(email: String, password: String) = call(password) {
        auth.signInWithEmailAndPassword(email, password).await().user!!.toAuthUser()
    }

    override suspend fun signUpWithEmail(name: String, email: String, password: String) = call(password) {
        val user = auth.createUserWithEmailAndPassword(email, password).await().user!!
        user.updateProfile(userProfileChangeRequest { displayName = name }).await()
        user.sendVerification(actionCodeSettings())
        refreshes.tryEmit(Unit)
        user.toAuthUser()
    }

    override suspend fun signInWithGoogle(idToken: String) = call {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user!!.toAuthUser()
    }

    override suspend fun sendPasswordReset(email: String) = call {
        auth.sendReset(email, actionCodeSettings())
        Unit
    }

    override suspend fun resendVerification() = call {
        auth.currentUser?.sendVerification(actionCodeSettings())
        Unit
    }

    override suspend fun reloadUser() = call {
        auth.currentUser?.reload()?.await()
        refreshes.tryEmit(Unit)
        Unit
    }

    override suspend fun applyEmailVerification(oobCode: String) = call {
        auth.applyActionCode(oobCode).await()
        auth.currentUser?.reload()?.await()
        refreshes.tryEmit(Unit)
        Unit
    }

    override suspend fun verifyPasswordResetCode(oobCode: String) = call {
        auth.verifyPasswordResetCode(oobCode).await()
    }

    override suspend fun confirmPasswordReset(oobCode: String, newPassword: String) = call(newPassword) {
        auth.confirmPasswordReset(oobCode, newPassword).await()
        Unit
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    private fun actionCodeSettings(): ActionCodeSettings? {
        if (config.authContinueUrl.isBlank()) return null
        return ActionCodeSettings.newBuilder()
            .setUrl(config.authContinueUrl)
            .setAndroidPackageName(config.applicationId, true, null)
            .setHandleCodeInApp(false)
            .build()
    }

    private suspend fun <T> call(password: String = "", block: suspend () -> T): AuthResult<T> = try {
        AuthResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AuthResult.Failure(AuthErrorMapper.map(e, password))
    }

    private suspend fun FirebaseUser.sendVerification(settings: ActionCodeSettings?) {
        if (settings == null) sendEmailVerification().await() else sendEmailVerification(settings).await()
    }

    private suspend fun FirebaseAuth.sendReset(email: String, settings: ActionCodeSettings?) {
        if (settings == null) sendPasswordResetEmail(email).await() else sendPasswordResetEmail(email, settings).await()
    }
}

private fun FirebaseUser.usesPassword() = providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }

internal fun FirebaseUser.toAuthUser() = AuthUser(
    uid = uid,
    email = email,
    displayName = displayName,
    emailVerified = isEmailVerified,
    usesPassword = usesPassword(),
)

internal fun FirebaseUser?.toSession(): SessionState = when {
    this == null -> SessionState.SignedOut
    usesPassword() && !isEmailVerified -> SessionState.NeedsVerification(email.orEmpty())
    else -> SessionState.SignedIn(toAuthUser())
}
