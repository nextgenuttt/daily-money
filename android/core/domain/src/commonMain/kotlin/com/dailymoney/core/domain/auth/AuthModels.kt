package com.dailymoney.core.domain.auth

data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val emailVerified: Boolean,
    val usesPassword: Boolean,
)

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class NeedsVerification(val email: String) : SessionState
    data class SignedIn(val user: AuthUser) : SessionState
}

sealed interface AuthError {
    data object InvalidCredentials : AuthError
    data object InvalidEmail : AuthError
    data object EmailInUse : AuthError
    data object TooManyAttempts : AuthError
    data object Disabled : AuthError
    data object LinkExpired : AuthError
    data object RequiresRecentLogin : AuthError
    data object Network : AuthError
    data object NoGoogleAccount : AuthError
    data object GoogleNotConfigured : AuthError
    data object Cancelled : AuthError
    data class WeakPassword(val rules: Set<PasswordRule>) : AuthError
    data class Unknown(val message: String?) : AuthError
}

sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}
