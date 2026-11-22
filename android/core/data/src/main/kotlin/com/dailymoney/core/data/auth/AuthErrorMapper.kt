package com.dailymoney.core.data.auth

import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.CredentialRules
import com.dailymoney.core.domain.auth.PasswordRule
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

/** Firebase exceptions → plain [AuthError]s (doc 04 §3). Wrong email and wrong password look the same. */
object AuthErrorMapper {

    fun map(error: Throwable, password: String = ""): AuthError = when (error) {
        is FirebaseNetworkException -> AuthError.Network
        is FirebaseTooManyRequestsException -> AuthError.TooManyAttempts
        is FirebaseAuthWeakPasswordException ->
            AuthError.WeakPassword(CredentialRules.brokenPasswordRules(password, "").ifEmpty { setOf(PasswordRule.MIN_LENGTH) })
        is FirebaseAuthException -> mapCode(error.errorCode, error.message)
        else -> AuthError.Unknown(error.message)
    }

    fun mapCode(code: String?, message: String? = null): AuthError = when (code) {
        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND",
        "ERROR_INVALID_LOGIN_CREDENTIALS" -> AuthError.InvalidCredentials
        "ERROR_INVALID_EMAIL" -> AuthError.InvalidEmail
        "ERROR_EMAIL_ALREADY_IN_USE" -> AuthError.EmailInUse
        "ERROR_USER_DISABLED" -> AuthError.Disabled
        "ERROR_TOO_MANY_REQUESTS" -> AuthError.TooManyAttempts
        "ERROR_EXPIRED_ACTION_CODE", "ERROR_INVALID_ACTION_CODE" -> AuthError.LinkExpired
        "ERROR_REQUIRES_RECENT_LOGIN" -> AuthError.RequiresRecentLogin
        "ERROR_WEAK_PASSWORD", "ERROR_PASSWORD_DOES_NOT_MEET_REQUIREMENTS" ->
            AuthError.WeakPassword(setOf(PasswordRule.MIN_LENGTH))
        "ERROR_NETWORK_REQUEST_FAILED" -> AuthError.Network
        else -> AuthError.Unknown(message ?: code)
    }
}
