package com.dailymoney.feature.auth

import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.core.domain.auth.FieldError
import com.dailymoney.core.domain.auth.SessionState
import com.dailymoney.core.domain.auth.SignInWithEmailUseCase
import com.dailymoney.core.domain.auth.SignInWithGoogleUseCase
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.feature.auth.google.GoogleSignInHandler
import com.dailymoney.feature.auth.signin.SignInViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var signInCalls = 0
    private var gate: CompletableDeferred<AuthResult<AuthUser>> = CompletableDeferred()

    private val repo = object : AuthRepository {
        override val session: Flow<SessionState> = emptyFlow()
        override suspend fun signInWithEmail(email: String, password: String): AuthResult<AuthUser> {
            signInCalls++
            return gate.await()
        }
        override suspend fun signUpWithEmail(name: String, email: String, password: String) = error("unused")
        override suspend fun signInWithGoogle(idToken: String) = error("unused")
        override suspend fun sendPasswordReset(email: String) = error("unused")
        override suspend fun resendVerification() = error("unused")
        override suspend fun reloadUser() = error("unused")
        override suspend fun applyEmailVerification(oobCode: String) = error("unused")
        override suspend fun verifyPasswordResetCode(oobCode: String) = error("unused")
        override suspend fun confirmPasswordReset(oobCode: String, newPassword: String) = error("unused")
        override suspend fun signOut() = Unit
    }

    private val config = AppConfig("app", "0.1", "", "", "")

    private fun viewModel() = SignInViewModel(
        SignInWithEmailUseCase(repo),
        GoogleSignInHandler(config, SignInWithGoogleUseCase(repo)),
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `empty form shows field errors and does not call Firebase`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.submit()
        advanceUntilIdle()
        assertEquals(FieldError.REQUIRED, vm.state.value.emailError)
        assertEquals(FieldError.REQUIRED, vm.state.value.passwordError)
        assertEquals(0, signInCalls)
    }

    @Test
    fun `submitting twice sends one request`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEmailChange("ravi@x.com")
        vm.onPasswordChange("secret123")
        vm.submit()
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.submitting)
        assertEquals(1, signInCalls)

        gate.complete(AuthResult.Success(AuthUser("u", "ravi@x.com", null, true, true)))
        advanceUntilIdle()
        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.formError)
    }

    @Test
    fun `wrong password shows form error and typing clears it`() = runTest(dispatcher) {
        gate.complete(AuthResult.Failure(AuthError.InvalidCredentials))
        val vm = viewModel()
        vm.onEmailChange("ravi@x.com")
        vm.onPasswordChange("bad")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AuthError.InvalidCredentials, vm.state.value.formError)

        vm.onPasswordChange("better1")
        assertNull(vm.state.value.formError)
    }
}
