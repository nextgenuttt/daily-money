package com.dailymoney.feature.auth.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dailymoney.feature.auth.forgot.ForgotPasswordScreen
import com.dailymoney.feature.auth.signin.SignInScreen
import com.dailymoney.feature.auth.signup.SignUpScreen
import com.dailymoney.feature.auth.welcome.WelcomeScreen
import kotlinx.serialization.Serializable

@Serializable internal data object WelcomeRoute
@Serializable internal data object SignInRoute
@Serializable internal data object SignUpRoute
@Serializable internal data object ForgotPasswordRoute

/** Signed-out flow. Leaving it is driven by the session, not by navigation. */
@Composable
fun AuthFlow(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = WelcomeRoute) {
        composable<WelcomeRoute> {
            WelcomeScreen(
                onSignInWithEmail = { navController.navigate(SignInRoute) },
                onCreateAccount = { navController.navigate(SignUpRoute) },
            )
        }
        composable<SignInRoute> {
            SignInScreen(
                onBack = navController::popBackStack,
                onForgotPassword = { navController.navigate(ForgotPasswordRoute) },
                onCreateAccount = {
                    navController.navigate(SignUpRoute) { popUpTo(WelcomeRoute) }
                },
            )
        }
        composable<SignUpRoute> {
            SignUpScreen(
                onBack = navController::popBackStack,
                onHaveAccount = {
                    navController.navigate(SignInRoute) { popUpTo(WelcomeRoute) }
                },
            )
        }
        composable<ForgotPasswordRoute> {
            ForgotPasswordScreen(onBack = navController::popBackStack)
        }
    }
}
