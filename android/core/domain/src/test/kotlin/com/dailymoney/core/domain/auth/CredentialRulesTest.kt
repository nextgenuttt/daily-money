package com.dailymoney.core.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRulesTest {

    @Test
    fun `email is trimmed and lowercased`() {
        assertEquals("ravi@example.com", CredentialRules.normalizeEmail("  Ravi@Example.COM "))
    }

    @Test
    fun `email validation`() {
        assertEquals(FieldError.REQUIRED, CredentialRules.emailError("  "))
        assertEquals(FieldError.INVALID_EMAIL, CredentialRules.emailError("ravi@"))
        assertEquals(FieldError.INVALID_EMAIL, CredentialRules.emailError("ravi example.com"))
        assertNull(CredentialRules.emailError("ravi@example.com"))
    }

    @Test
    fun `password rules`() {
        assertEquals(
            setOf(PasswordRule.MIN_LENGTH, PasswordRule.DIGIT),
            CredentialRules.brokenPasswordRules("abc", "x@y.com"),
        )
        assertTrue(CredentialRules.brokenPasswordRules("secret123", "x@y.com").isEmpty())
        assertTrue(PasswordRule.LETTER in CredentialRules.brokenPasswordRules("12345678", ""))
        assertTrue(PasswordRule.MAX_LENGTH in CredentialRules.brokenPasswordRules("a1".repeat(33), ""))
    }

    @Test
    fun `password equal to email is rejected`() {
        assertTrue(PasswordRule.NOT_EMAIL in CredentialRules.brokenPasswordRules("ravi1@x.com", "Ravi1@X.com"))
    }

    @Test
    fun `strength grows with variety`() {
        assertEquals(PasswordStrength.WEAK, CredentialRules.strength("abc"))
        assertEquals(PasswordStrength.MEDIUM, CredentialRules.strength("secret123"))
        assertEquals(PasswordStrength.STRONG, CredentialRules.strength("Secret-123-long"))
    }

    @Test
    fun `name validation`() {
        assertEquals(FieldError.REQUIRED, CredentialRules.nameError(" "))
        assertEquals(FieldError.NAME_TOO_LONG, CredentialRules.nameError("a".repeat(61)))
        assertNull(CredentialRules.nameError("Ravi"))
    }
}
