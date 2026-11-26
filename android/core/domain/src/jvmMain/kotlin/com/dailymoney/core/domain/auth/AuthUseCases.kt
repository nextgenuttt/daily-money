package com.dailymoney.core.domain.auth

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class ObserveSessionUseCase @Inject constructor(private val auth: AuthRepository) {
    operator fun invoke(): Flow<SessionState> = auth.session
}

class SignInWithEmailUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): AuthResult<AuthUser> =
        auth.signInWithEmail(CredentialRules.normalizeEmail(email), password)
}

class SignUpWithEmailUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(name: String, email: String, password: String): AuthResult<AuthUser> {
        val broken = CredentialRules.brokenPasswordRules(password, email)
        if (broken.isNotEmpty()) return AuthResult.Failure(AuthError.WeakPassword(broken))
        return auth.signUpWithEmail(name.trim(), CredentialRules.normalizeEmail(email), password)
    }
}

class SignInWithGoogleUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(idToken: String): AuthResult<AuthUser> = auth.signInWithGoogle(idToken)
}

class SendPasswordResetUseCase @Inject constructor(private val auth: AuthRepository) {
    /** Always reports success for unknown emails too, so nobody can probe which emails have accounts. */
    suspend operator fun invoke(email: String): AuthResult<Unit> =
        when (val result = auth.sendPasswordReset(CredentialRules.normalizeEmail(email))) {
            is AuthResult.Failure -> if (result.error == AuthError.InvalidCredentials) AuthResult.Success(Unit) else result
            is AuthResult.Success -> result
        }
}

class ConfirmPasswordResetUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(oobCode: String, email: String, newPassword: String): AuthResult<Unit> {
        val broken = CredentialRules.brokenPasswordRules(newPassword, email)
        if (broken.isNotEmpty()) return AuthResult.Failure(AuthError.WeakPassword(broken))
        return auth.confirmPasswordReset(oobCode, newPassword)
    }
}

class SignOutUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val backend: BackendSession,
    private val wiper: LocalDataWiper,
) {
    /** Order matters: the push token is removed while the user can still authenticate (doc 04 §4.6). */
    suspend operator fun invoke() {
        runCatching { backend.unregisterDevice() }
        auth.signOut()
        wiper.wipe()
    }
}

class SignOutEverywhereUseCase @Inject constructor(
    private val backend: BackendSession,
    private val signOut: SignOutUseCase,
) {
    suspend operator fun invoke(): Boolean {
        val revoked = backend.signOutEverywhere()
        if (revoked) signOut()
        return revoked
    }
}

sealed interface DeleteAccountResult {
    data object Deleted : DeleteAccountResult
    /** Owner of groups that still have other members; [groups] is the server's list of names. */
    data class TransferOwnershipFirst(val groups: String) : DeleteAccountResult
    /** Firebase wants a fresh sign-in before deleting (only without a backend). */
    data object SignInAgain : DeleteAccountResult
    data object Failed : DeleteAccountResult
}

/** Deletes the account with the server (or Firebase directly when there is no backend). */
interface AccountDeletion {
    suspend fun delete(): DeleteAccountResult
}

/** Play policy: in-app account deletion. Afterwards the phone is cleared exactly like a sign-out. */
class DeleteAccountUseCase @Inject constructor(
    private val deletion: AccountDeletion,
    private val auth: AuthRepository,
    private val backend: BackendSession,
    private val wiper: LocalDataWiper,
) {
    suspend operator fun invoke(): DeleteAccountResult {
        val result = deletion.delete()
        if (result == DeleteAccountResult.Deleted) {
            runCatching { backend.unregisterDevice() }
            auth.signOut()
            wiper.wipe()
        }
        return result
    }
}
