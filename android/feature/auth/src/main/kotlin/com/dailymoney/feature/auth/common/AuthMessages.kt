package com.dailymoney.feature.auth.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.FieldError
import com.dailymoney.core.domain.auth.PasswordRule
import com.dailymoney.feature.auth.R

@Composable
fun AuthError.message(): String? = when (this) {
    AuthError.Cancelled -> null
    AuthError.InvalidCredentials -> stringResource(R.string.auth_err_invalid_credentials)
    AuthError.InvalidEmail -> stringResource(R.string.auth_err_invalid_email)
    AuthError.EmailInUse -> stringResource(R.string.auth_err_email_in_use)
    AuthError.TooManyAttempts -> stringResource(R.string.auth_err_too_many)
    AuthError.Disabled -> stringResource(R.string.auth_err_disabled)
    AuthError.LinkExpired -> stringResource(R.string.auth_err_link_expired)
    AuthError.RequiresRecentLogin -> stringResource(R.string.auth_err_recent_login)
    AuthError.Network -> stringResource(R.string.auth_err_network)
    AuthError.NoGoogleAccount -> stringResource(R.string.auth_err_no_google)
    AuthError.GoogleNotConfigured -> stringResource(R.string.auth_err_google_not_configured)
    is AuthError.WeakPassword -> passwordRulesMessage(rules)
    is AuthError.Unknown -> stringResource(R.string.auth_err_unknown)
}

@Composable
fun FieldError.message(): String = when (this) {
    FieldError.REQUIRED -> stringResource(R.string.auth_err_required)
    FieldError.INVALID_EMAIL -> stringResource(R.string.auth_err_invalid_email)
    FieldError.PASSWORDS_DIFFERENT -> stringResource(R.string.auth_err_passwords_different)
    FieldError.NAME_TOO_LONG -> stringResource(R.string.auth_err_name_long)
    FieldError.TERMS_REQUIRED -> stringResource(R.string.auth_err_terms)
    FieldError.PASSWORD_RULES -> stringResource(R.string.auth_err_required)
}

@Composable
fun passwordRulesMessage(rules: Set<PasswordRule>): String {
    val parts = rules.sortedBy { it.ordinal }.map {
        when (it) {
            PasswordRule.MIN_LENGTH -> stringResource(R.string.auth_rule_min)
            PasswordRule.MAX_LENGTH -> stringResource(R.string.auth_rule_max)
            PasswordRule.LETTER -> stringResource(R.string.auth_rule_letter)
            PasswordRule.DIGIT -> stringResource(R.string.auth_rule_digit)
            PasswordRule.NOT_EMAIL -> stringResource(R.string.auth_rule_not_email)
        }
    }
    return stringResource(R.string.auth_err_password_needs, parts.joinToString(", "))
}
