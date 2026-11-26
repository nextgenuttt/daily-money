package com.dailymoney.feature.money.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dailymoney.feature.money.accounts.AccountsScreen
import com.dailymoney.feature.money.budgets.BudgetsScreen
import com.dailymoney.feature.money.categories.CategoriesScreen
import com.dailymoney.feature.money.categories.CategoryRulesScreen
import com.dailymoney.feature.money.importing.FoundPaymentsScreen
import com.dailymoney.feature.money.importing.ImportStatementScreen
import com.dailymoney.feature.money.edit.AddEditTransactionScreen
import com.dailymoney.feature.money.planning.DebtDetailScreen
import com.dailymoney.feature.money.planning.DebtsScreen
import com.dailymoney.feature.money.planning.ExportScreen
import com.dailymoney.feature.money.planning.GoalDetailScreen
import com.dailymoney.feature.money.planning.GoalsScreen
import com.dailymoney.feature.money.planning.RecurringScreen
import com.dailymoney.feature.money.planning.SubscriptionsScreen
import com.dailymoney.feature.money.transactions.MoneyMenuItem
import com.dailymoney.feature.money.transactions.TransactionsScreen
import kotlinx.serialization.Serializable

@Serializable data object TransactionsRoute
/**
 * [type] pre-selects EXPENSE / INCOME / TRANSFER for a new transaction (quick add); [sharedText] is a
 * bank or UPI message shared into the app, read to fill the form.
 */
@Serializable data class AddEditTransactionRoute(
    val transactionId: String? = null,
    val type: String? = null,
    val sharedText: String? = null,
    /** A payment found in a bank SMS, to fill the form from; marked added once saved. */
    val suggestionId: String? = null,
)
@Serializable data object AccountsRoute
@Serializable data object CategoriesRoute
@Serializable data object BudgetsRoute
@Serializable data object RecurringRoute
@Serializable data object SubscriptionsRoute
@Serializable data object GoalsRoute
@Serializable data class GoalDetailRoute(val goalId: String)
@Serializable data object DebtsRoute
@Serializable data class DebtDetailRoute(val debtId: String)
@Serializable data object ExportRoute
@Serializable data object ImportStatementRoute
@Serializable data object CategoryRulesRoute
@Serializable data object FoundPaymentsRoute

/** [onReports] opens the Reports feature (wired by the app). */
fun NavGraphBuilder.moneyGraph(navController: NavController, onReports: () -> Unit) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<TransactionsRoute> {
        TransactionsScreen(
            onAdd = { navController.navigate(AddEditTransactionRoute()) },
            onOpen = { id -> navController.navigate(AddEditTransactionRoute(id)) },
            onAccounts = { navController.navigate(AccountsRoute) },
            onMenu = { item ->
                if (item == MoneyMenuItem.REPORTS) return@TransactionsScreen onReports()
                navController.navigate(
                    when (item) {
                        MoneyMenuItem.BUDGETS -> BudgetsRoute
                        MoneyMenuItem.REPORTS -> error("handled above")
                        MoneyMenuItem.CATEGORIES -> CategoriesRoute
                        MoneyMenuItem.RECURRING -> RecurringRoute
                        MoneyMenuItem.SUBSCRIPTIONS -> SubscriptionsRoute
                        MoneyMenuItem.GOALS -> GoalsRoute
                        MoneyMenuItem.DEBTS -> DebtsRoute
                        MoneyMenuItem.EXPORT -> ExportRoute
                        MoneyMenuItem.IMPORT -> ImportStatementRoute
                        MoneyMenuItem.RULES -> CategoryRulesRoute
                    },
                )
            },
        )
    }
    composable<AddEditTransactionRoute> { AddEditTransactionScreen(onClose = back) }
    composable<AccountsRoute> { AccountsScreen(onBack = back) }
    composable<CategoriesRoute> { CategoriesScreen(onBack = back) }
    composable<BudgetsRoute> { BudgetsScreen(onBack = back) }
    composable<RecurringRoute> { RecurringScreen(onBack = back, onAdd = { navController.navigate(AddEditTransactionRoute()) }) }
    composable<SubscriptionsRoute> { SubscriptionsScreen(onBack = back) }
    composable<GoalsRoute> { GoalsScreen(onBack = back, onOpen = { navController.navigate(GoalDetailRoute(it)) }) }
    composable<GoalDetailRoute> { GoalDetailScreen(onBack = back) }
    composable<DebtsRoute> { DebtsScreen(onBack = back, onOpen = { navController.navigate(DebtDetailRoute(it)) }) }
    composable<DebtDetailRoute> { DebtDetailScreen(onBack = back) }
    composable<ExportRoute> { ExportScreen(onBack = back) }
    composable<ImportStatementRoute> { ImportStatementScreen(onBack = back) }
    composable<CategoryRulesRoute> { CategoryRulesScreen(onBack = back) }
    composable<FoundPaymentsRoute> { FoundPaymentsScreen(onBack = back, onAdd = { navController.navigate(AddEditTransactionRoute(suggestionId = it)) }) }
}
