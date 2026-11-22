package com.dailymoney.core.domain.auth

enum class PasswordRule { MIN_LENGTH, MAX_LENGTH, LETTER, DIGIT, NOT_EMAIL }

enum class PasswordStrength { WEAK, MEDIUM, STRONG }

enum class FieldError { REQUIRED, INVALID_EMAIL, PASSWORD_RULES, PASSWORDS_DIFFERENT, NAME_TOO_LONG, TERMS_REQUIRED }

/** Client-side copy of the Firebase password policy (doc 04 §3). Firebase enforces it again on the server. */
object CredentialRules {

    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 64
    const val NAME_MAX = 60
    const val EMAIL_MAX = 254

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    fun normalizeEmail(email: String): String = email.trim().lowercase()

    fun emailError(email: String): FieldError? {
        val value = normalizeEmail(email)
        return when {
            value.isEmpty() -> FieldError.REQUIRED
            value.length > EMAIL_MAX || !EMAIL.matches(value) -> FieldError.INVALID_EMAIL
            else -> null
        }
    }

    fun brokenPasswordRules(password: String, email: String): Set<PasswordRule> = buildSet {
        if (password.length < PASSWORD_MIN) add(PasswordRule.MIN_LENGTH)
        if (password.length > PASSWORD_MAX) add(PasswordRule.MAX_LENGTH)
        if (password.none { it.isLetter() }) add(PasswordRule.LETTER)
        if (password.none { it.isDigit() }) add(PasswordRule.DIGIT)
        if (email.isNotBlank() && password.equals(normalizeEmail(email), ignoreCase = true)) add(PasswordRule.NOT_EMAIL)
    }

    fun strength(password: String): PasswordStrength {
        var score = 0
        if (password.length >= PASSWORD_MIN) score++
        if (password.length >= 12) score++
        if (password.any { it.isLetter() } && password.any { it.isDigit() }) score++
        if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++
        return when {
            score >= 4 -> PasswordStrength.STRONG
            score >= 2 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.WEAK
        }
    }

    fun nameError(name: String): FieldError? = when {
        name.isBlank() -> FieldError.REQUIRED
        name.trim().length > NAME_MAX -> FieldError.NAME_TOO_LONG
        else -> null
    }
}
