package com.dailymoney.feature.home.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.feature.home.home.HomeScreen
import com.dailymoney.feature.home.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable internal data object HomeRoute
@Serializable internal data object SettingsRoute

/** Signed-in area. The bottom navigation (Money, Reminders, Groups) is added with those features. */
@Composable
fun MainFlow(user: AuthUser) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(user = user, onOpenSettings = { navController.navigate(SettingsRoute) })
        }
        composable<SettingsRoute> {
            SettingsScreen(user = user, onBack = navController::popBackStack)
        }
    }
}
