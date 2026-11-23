package com.dailymoney.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.feature.home.navigation.HomeLinks
import com.dailymoney.feature.home.navigation.HomeRoute
import com.dailymoney.feature.home.navigation.homeGraph
import com.dailymoney.feature.money.navigation.AddEditTransactionRoute
import com.dailymoney.feature.money.navigation.BudgetsRoute
import com.dailymoney.feature.money.navigation.DebtsRoute
import com.dailymoney.feature.money.navigation.GoalsRoute
import com.dailymoney.feature.money.navigation.SubscriptionsRoute
import com.dailymoney.feature.money.navigation.TransactionsRoute
import com.dailymoney.feature.money.navigation.moneyGraph
import com.dailymoney.feature.reminders.navigation.AddEditReminderRoute
import com.dailymoney.feature.reminders.navigation.ReminderDetailRoute
import com.dailymoney.feature.todo.navigation.AddEditTodoRoute
import com.dailymoney.feature.todo.navigation.TodoDetailRoute
import com.dailymoney.feature.todo.navigation.TodoRoute
import com.dailymoney.feature.todo.navigation.todoGraph
import com.dailymoney.feature.reminders.navigation.RemindersRoute
import com.dailymoney.feature.reminders.navigation.remindersGraph
import com.dailymoney.feature.reports.ReportsRoute
import com.dailymoney.feature.reports.reportsGraph
import kotlin.reflect.KClass

private enum class TopLevel(val route: Any, val routeClasses: List<KClass<*>>, val icon: ImageVector, @StringRes val label: Int) {
    HOME(HomeRoute, listOf(HomeRoute::class), Icons.Filled.Home, R.string.nav_home),
    MONEY(TransactionsRoute, listOf(TransactionsRoute::class), Icons.Filled.ReceiptLong, R.string.nav_money),
    PLANNER(RemindersRoute, listOf(RemindersRoute::class, TodoRoute::class), Icons.Filled.EventNote, R.string.nav_planner),
    BUDGETS(BudgetsRoute, listOf(BudgetsRoute::class), Icons.Filled.PieChart, R.string.nav_budgets),
    REPORTS(ReportsRoute, listOf(ReportsRoute::class), Icons.Filled.BarChart, R.string.nav_reports),
}

/**
 * Signed-in area. Features don't know about each other; cross-feature links are wired here.
 * To-Do and Groups join in later phases; Budgets and Reports then move under Money/Home (doc 05 §A.4).
 */
@Composable
internal fun MainShell(user: AuthUser, openRequest: OpenRequest?, onOpenHandled: () -> Unit) {
    val navController = rememberNavController()
    LaunchedEffect(openRequest) {
        when (val req = openRequest ?: return@LaunchedEffect) {
            is OpenRequest.Reminder -> navController.navigate(ReminderDetailRoute(req.id, req.epochDay, req.complete))
            OpenRequest.Subscriptions -> navController.navigate(SubscriptionsRoute)
            OpenRequest.Budgets -> navController.navigateToTab(BudgetsRoute)
            is OpenRequest.Todo -> navController.navigate(TodoDetailRoute(req.id))
        }
        onOpenHandled()
    }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val current = TopLevel.entries.firstOrNull { tab -> destination?.hierarchy?.any { d -> tab.routeClasses.any { d.hasRoute(it) } } == true }

    Scaffold(
        bottomBar = {
            if (current != null) {
                NavigationBar {
                    TopLevel.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == current,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            NavHost(navController = navController, startDestination = HomeRoute) {
                homeGraph(
                    navController = navController,
                    user = user,
                    links = HomeLinks(
                        addTransaction = { navController.navigate(AddEditTransactionRoute()) },
                        addIncome = { navController.navigate(AddEditTransactionRoute(type = "INCOME")) },
                        addReminder = { navController.navigate(AddEditReminderRoute()) },
                        addTask = { navController.navigate(AddEditTodoRoute()) },
                        todo = { navController.navigate(TodoRoute) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true } },
                        openTodo = { navController.navigate(TodoDetailRoute(it)) },
                        openTransaction = { id -> navController.navigate(AddEditTransactionRoute(id)) },
                        allTransactions = { navController.navigateToTab(TransactionsRoute) },
                        budgets = { navController.navigateToTab(BudgetsRoute) },
                        reports = { navController.navigateToTab(ReportsRoute) },
                        subscriptions = { navController.navigate(SubscriptionsRoute) },
                        goals = { navController.navigate(GoalsRoute) },
                        debts = { navController.navigate(DebtsRoute) },
                        reminders = { navController.navigateToTab(RemindersRoute) },
                        openReminder = { id, day -> navController.navigate(ReminderDetailRoute(id, day)) },
                    ),
                )
                moneyGraph(navController)
                remindersGraph(
                    navController,
                    onTodo = { navController.navigate(TodoRoute) { popUpTo(RemindersRoute); launchSingleTop = true } },
                    onOpenTask = { navController.navigate(TodoDetailRoute(it)) },
                )
                todoGraph(navController, onReminders = { if (!navController.popBackStack(RemindersRoute, inclusive = false)) navController.navigateToTab(RemindersRoute) })
                reportsGraph()
            }
        }
    }
}

/** Standard bottom-bar behaviour: one copy of each tab, state kept when switching. */
private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
