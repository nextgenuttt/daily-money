package com.dailymoney.feature.home.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.feature.home.home.HomeScreen
import com.dailymoney.feature.home.home.QuickAddActions
import com.dailymoney.feature.home.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object SettingsRoute

/** Links from Home into other features; the app module wires them so features stay independent. */
data class HomeLinks(
    val addTransaction: () -> Unit,
    val addIncome: () -> Unit,
    val addReminder: () -> Unit,
    val addTask: () -> Unit,
    val todo: () -> Unit,
    val openTodo: (String) -> Unit,
    val openTransaction: (String) -> Unit,
    val allTransactions: () -> Unit,
    val budgets: () -> Unit,
    val reports: () -> Unit,
    val subscriptions: () -> Unit,
    val goals: () -> Unit,
    val debts: () -> Unit,
    val reminders: () -> Unit,
    val openReminder: (String, Long) -> Unit,
)

fun NavGraphBuilder.homeGraph(navController: NavController, user: AuthUser, links: HomeLinks) {
    composable<HomeRoute> {
        HomeScreen(
            user = user,
            onOpenSettings = { navController.navigate(SettingsRoute) },
            onAddTransaction = links.addTransaction,
            quickAdd = QuickAddActions(links.addTransaction, links.addIncome, links.addReminder, links.addTask),
            onOpenTodo = links.todo,
            onOpenTask = links.openTodo,
            onOpenTransaction = links.openTransaction,
            onSeeAllTransactions = links.allTransactions,
            onOpenBudgets = links.budgets,
            onOpenReports = links.reports,
            onOpenSubscriptions = links.subscriptions,
            onOpenGoals = links.goals,
            onOpenDebts = links.debts,
            onOpenReminders = links.reminders,
            onOpenReminder = links.openReminder,
        )
    }
    composable<SettingsRoute> {
        SettingsScreen(user = user, onBack = { navController.popBackStack() })
    }
}
