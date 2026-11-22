package com.dailymoney.core.domain.auth

import kotlinx.coroutines.flow.Flow

/** Firebase Authentication, behind an interface so the rest of the app stays testable. */
interface AuthRepository {
    val session: Flow<SessionState>

    suspend fun signInWithEmail(email: String, password: String): AuthResult<AuthUser>
    suspend fun signUpWithEmail(name: String, email: String, password: String): AuthResult<AuthUser>
    suspend fun signInWithGoogle(idToken: String): AuthResult<AuthUser>
    suspend fun sendPasswordReset(email: String): AuthResult<Unit>
    suspend fun resendVerification(): AuthResult<Unit>

    /** Re-reads the user from Firebase (e.g. after the email was verified in a browser). */
    suspend fun reloadUser(): AuthResult<Unit>
    suspend fun applyEmailVerification(oobCode: String): AuthResult<Unit>

    /** Returns the email the reset link belongs to. */
    suspend fun verifyPasswordResetCode(oobCode: String): AuthResult<String>
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): AuthResult<Unit>
    suspend fun signOut()
}

/** Our Spring Boot API session. Every call is a no-op when the backend is not configured (R1). */
interface BackendSession {
    suspend fun bootstrap(): Boolean
    suspend fun unregisterDevice()
    suspend fun signOutEverywhere(): Boolean
}

/** Removes everything the signed-in user left on this phone. */
interface LocalDataWiper {
    suspend fun wipe()
}

/** Local changes not yet sent to the server (the Room outbox). */
interface PendingChangesRepository {
    suspend fun pendingCount(): Int
}
