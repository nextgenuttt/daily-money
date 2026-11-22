package com.dailymoney.core.data.auth

import com.dailymoney.core.domain.auth.AuthError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorMapperTest {

    @Test
    fun `wrong email and wrong password map to the same error`() {
        listOf("ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND").forEach {
            assertEquals(AuthError.InvalidCredentials, AuthErrorMapper.mapCode(it))
        }
    }

    @Test
    fun `known codes`() {
        assertEquals(AuthError.EmailInUse, AuthErrorMapper.mapCode("ERROR_EMAIL_ALREADY_IN_USE"))
        assertEquals(AuthError.Disabled, AuthErrorMapper.mapCode("ERROR_USER_DISABLED"))
        assertEquals(AuthError.LinkExpired, AuthErrorMapper.mapCode("ERROR_EXPIRED_ACTION_CODE"))
        assertEquals(AuthError.LinkExpired, AuthErrorMapper.mapCode("ERROR_INVALID_ACTION_CODE"))
        assertEquals(AuthError.RequiresRecentLogin, AuthErrorMapper.mapCode("ERROR_REQUIRES_RECENT_LOGIN"))
        assertEquals(AuthError.InvalidEmail, AuthErrorMapper.mapCode("ERROR_INVALID_EMAIL"))
        assertTrue(AuthErrorMapper.mapCode("ERROR_WEAK_PASSWORD") is AuthError.WeakPassword)
    }

    @Test
    fun `unknown code keeps the message for logs`() {
        assertEquals(AuthError.Unknown("boom"), AuthErrorMapper.mapCode("ERROR_SOMETHING_NEW", "boom"))
    }

    @Test
    fun `non Firebase exception is unknown`() {
        assertEquals(AuthError.Unknown("x"), AuthErrorMapper.map(IllegalStateException("x")))
    }
}
