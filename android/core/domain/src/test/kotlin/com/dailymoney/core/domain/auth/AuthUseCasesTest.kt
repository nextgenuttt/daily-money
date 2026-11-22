package com.dailymoney.core.domain.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUseCasesTest {

    private val calls = mutableListOf<String>()

    private val auth = object : AuthRepository {
        var resetResult: AuthResult<Unit> = AuthResult.Success(Unit)
        override val session: Flow<SessionState> = MutableStateFlow(SessionState.SignedOut)
        override suspend fun signInWithEmail(email: String, password: String): AuthResult<AuthUser> {
            calls += "signIn:$email"
            return AuthResult.Success(AuthUser("u", email, null, true, true))
        }
        override suspend fun signUpWithEmail(name: String, email: String, password: String): AuthResult<AuthUser> {
            calls += "signUp:$name:$email"
            return AuthResult.Success(AuthUser("u", email, name, false, true))
        }
        override suspend fun signInWithGoogle(idToken: String) = AuthResult.Failure(AuthError.Cancelled)
        override suspend fun sendPasswordReset(email: String) = resetResult
        override suspend fun resendVerification() = AuthResult.Success(Unit)
        override suspend fun reloadUser() = AuthResult.Success(Unit)
        override suspend fun applyEmailVerification(oobCode: String) = AuthResult.Success(Unit)
        override suspend fun verifyPasswordResetCode(oobCode: String) = AuthResult.Success("x@y.com")
        override suspend fun confirmPasswordReset(oobCode: String, newPassword: String): AuthResult<Unit> {
            calls += "confirm"
            return AuthResult.Success(Unit)
        }
        override suspend fun signOut() {
            calls += "auth.signOut"
        }
    }

    private val backend = object : BackendSession {
        var everywhere = true
        override suspend fun bootstrap() = true
        override suspend fun unregisterDevice() {
            calls += "backend.unregister"
            error("offline")
        }
        override suspend fun signOutEverywhere(): Boolean {
            calls += "backend.everywhere"
            return everywhere
        }
    }

    private val wiper = object : LocalDataWiper {
        override suspend fun wipe() {
            calls += "wipe"
        }
    }

    @Test
    fun `sign in normalizes email`() = runTest {
        SignInWithEmailUseCase(auth)(" Ravi@X.com ", "pw")
        assertEquals(listOf("signIn:ravi@x.com"), calls)
    }

    @Test
    fun `sign up refuses weak password without calling Firebase`() = runTest {
        val result = SignUpWithEmailUseCase(auth)("Ravi", "ravi@x.com", "short")
        assertTrue(result is AuthResult.Failure && result.error is AuthError.WeakPassword)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `sign up trims name and email`() = runTest {
        SignUpWithEmailUseCase(auth)("  Ravi ", " RAVI@x.com", "secret123")
        assertEquals(listOf("signUp:Ravi:ravi@x.com"), calls)
    }

    @Test
    fun `password reset hides unknown email`() = runTest {
        auth.resetResult = AuthResult.Failure(AuthError.InvalidCredentials)
        assertEquals(AuthResult.Success(Unit), SendPasswordResetUseCase(auth)("nobody@x.com"))
    }

    @Test
    fun `password reset reports network errors`() = runTest {
        auth.resetResult = AuthResult.Failure(AuthError.Network)
        assertEquals(AuthResult.Failure(AuthError.Network), SendPasswordResetUseCase(auth)("a@x.com"))
    }

    @Test
    fun `confirm reset validates new password`() = runTest {
        val result = ConfirmPasswordResetUseCase(auth)("code", "x@y.com", "abc")
        assertTrue(result is AuthResult.Failure)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `sign out continues when backend is unreachable and wipes last`() = runTest {
        SignOutUseCase(auth, backend, wiper)()
        assertEquals(listOf("backend.unregister", "auth.signOut", "wipe"), calls)
    }

    @Test
    fun `sign out everywhere keeps session when server refuses`() = runTest {
        backend.everywhere = false
        val ok = SignOutEverywhereUseCase(backend, SignOutUseCase(auth, backend, wiper))()
        assertEquals(false, ok)
        assertEquals(listOf("backend.everywhere"), calls)
    }
}
