package com.dailymoney.feature.home.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.money.AmountStat
import com.dailymoney.core.designsystem.money.CategoryBadge
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.categoryColor
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.BudgetStatus
import com.dailymoney.core.domain.money.DashboardData
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.ObserveDashboardUseCase
import com.dailymoney.core.domain.money.ObservePlanningSummaryUseCase
import com.dailymoney.core.domain.money.PlanningSummary
import com.dailymoney.core.domain.reminders.ObserveRemindersOverviewUseCase
import com.dailymoney.core.domain.reminders.OccurrenceStatus
import com.dailymoney.core.domain.reminders.RemindersOverview
import com.dailymoney.core.domain.todo.CompleteTodoUseCase
import com.dailymoney.core.domain.todo.ObserveTodoOverviewUseCase
import com.dailymoney.core.domain.todo.TodoOverview
import com.dailymoney.core.domain.todo.TodoStatus
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.NotificationAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.AddCircleOutline
import com.dailymoney.core.designsystem.money.ReminderBadge
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val currency: String,
    val data: DashboardData? = null,
    val planning: PlanningSummary? = null,
    val reminders: RemindersOverview? = null,
    val todos: TodoOverview? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeDashboard: ObserveDashboardUseCase,
    observePlanning: ObservePlanningSummaryUseCase,
    observeReminders: ObserveRemindersOverviewUseCase,
    observeTodos: ObserveTodoOverviewUseCase,
    private val completeTodo: CompleteTodoUseCase,
    config: AppConfig,
) : ViewModel() {
    private val currency = config.defaultCurrency
    val state: StateFlow<HomeUiState> = combine(observeDashboard(), observePlanning(), observeReminders(), observeTodos()) { d, p, r, t ->
        HomeUiState(currency, d, p, r, t)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(currency))

    fun complete(todoId: String) {
        viewModelScope.launch { completeTodo(todoId) }
    }
}

/** Spec §3 global quick add. */
data class QuickAddActions(val expense: () -> Unit, val income: () -> Unit, val reminder: () -> Unit, val task: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    user: AuthUser,
    onOpenSettings: () -> Unit,
    onAddTransaction: () -> Unit,
    quickAdd: QuickAddActions,
    onOpenTodo: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenDebts: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenReminder: (String, Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstName = user.displayName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        firstName?.let { stringResource(R.string.home_greeting, it) } ?: stringResource(R.string.home_greeting_no_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { QuickAddButton(quickAdd) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val data = state.data
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (data == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            MonthCard(data, state.currency, onOpenBudgets)
            state.reminders?.let { RemindersCard(it, onOpenReminders, onOpenReminder) }
            state.todos?.let { TasksCard(it, onOpenTodo, onOpenTask, viewModel::complete) }
            state.planning?.let { PlanningCard(it, state.currency, onOpenSubscriptions, onOpenGoals, onOpenDebts) }
            if (!data.hasAnyTransaction) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.Filled.Insights,
                        title = stringResource(R.string.home_empty_title),
                        message = stringResource(R.string.home_empty_body),
                        action = { PrimaryButton(stringResource(R.string.home_add_first), onAddTransaction) },
                    )
                }
                return@Column
            }
            if (data.topCategories.isNotEmpty()) TopCategoriesCard(data, state.currency, onOpenReports)
            RecentCard(data, onOpenTransaction, onSeeAllTransactions)
        }
    }
}

@Composable
private fun MonthCard(data: DashboardData, currency: String, onOpenBudgets: () -> Unit) {
    val month = data.month.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.home_spent_in, month), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(data.totals.expenseMinor, currency, style = MaterialTheme.typography.displaySmall)
            val budget = data.overallBudget
            if (budget != null) {
                val status = LocalStatusColors.current
                val color = when (budget.status) {
                    BudgetStatus.ON_TRACK -> status.paid
                    BudgetStatus.NEAR_LIMIT -> status.upcoming
                    BudgetStatus.OVER -> status.overdue
                }
                LinearProgressIndicator(
                    progress = { budget.fraction.coerceIn(0f, 1f) },
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clickable(onClick = onOpenBudgets),
                )
                Text(
                    stringResource(
                        R.string.home_budget_line,
                        MoneyFormatter.format(budget.budget.limitMinor, currency),
                        (budget.fraction * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (budget.status == BudgetStatus.OVER) color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onOpenBudgets, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.home_set_budget))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AmountStat(stringResource(R.string.home_income), data.totals.incomeMinor, currency, color = LocalStatusColors.current.income)
                AmountStat(stringResource(R.string.home_net), data.totals.netMinor, currency)
                AmountStat(stringResource(R.string.home_balance), data.totalBalanceMinor, currency)
            }
        }
    }
}

@Composable
private fun TopCategoriesCard(data: DashboardData, currency: String, onOpenReports: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionHeader(stringResource(R.string.home_top_categories), stringResource(R.string.home_see_reports), onOpenReports)
            data.topCategories.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(slice.category?.iconKey, slice.category?.colorIndex ?: 5, size = 32.dp)
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
                        Text(slice.category?.name ?: stringResource(R.string.home_uncategorized), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { slice.share },
                            color = categoryColor(slice.category?.colorIndex ?: 5),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    MoneyText(slice.amountMinor, currency, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RecentCard(data: DashboardData, onOpen: (String) -> Unit, onSeeAll: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Spacing.sm)) {
            Row(Modifier.padding(horizontal = Spacing.md)) {
                SectionHeader(stringResource(R.string.home_recent), stringResource(R.string.home_see_all), onSeeAll)
            }
            data.recent.forEach { d ->
                val t = d.transaction
                val isTransfer = t.type == TransactionType.TRANSFER
                ListItem(
                    headlineContent = {
                        Text(
                            if (isTransfer) stringResource(R.string.home_transfer) else d.category?.name ?: stringResource(R.string.home_uncategorized),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(listOfNotNull(d.account?.name, t.note).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { CategoryBadge(if (isTransfer) "transfer" else d.category?.iconKey, d.category?.colorIndex ?: 5) },
                    trailingContent = { MoneyText(t.amountMinor, t.currency, type = t.type) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickable { onOpen(t.id) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** Spec §4: upcoming payments, money to receive / pay and savings. Hidden until the user has any of them. */
@Composable
private fun PlanningCard(
    p: PlanningSummary,
    currency: String,
    onOpenSubscriptions: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenDebts: () -> Unit,
) {
    val hasDebts = p.debts.toReceiveMinor > 0 || p.debts.toPayMinor > 0
    if (p.upcoming.isEmpty() && !hasDebts && p.goalCount == 0) return
    val today = LocalDate.now()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (p.upcoming.isNotEmpty()) {
                SectionHeader(stringResource(R.string.home_upcoming), stringResource(R.string.home_see_all), onOpenSubscriptions)
                p.upcoming.take(3).forEach { u ->
                    val days = ChronoUnit.DAYS.between(today, u.date).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.rule.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (days <= 0) stringResource(R.string.home_due_today)
                                else androidx.compose.ui.res.pluralStringResource(R.plurals.home_due_in, days, days),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (days <= 1) LocalStatusColors.current.upcoming else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MoneyText(u.rule.amountMinor, u.rule.currency)
                    }
                }
            }
            if (hasDebts || p.goalCount > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (hasDebts) {
                        AmountStat(stringResource(R.string.home_to_receive), p.debts.toReceiveMinor, currency, Modifier.clickable(onClick = onOpenDebts), color = LocalStatusColors.current.income)
                        AmountStat(stringResource(R.string.home_to_pay), p.debts.toPayMinor, currency, Modifier.clickable(onClick = onOpenDebts))
                    }
                    if (p.goalCount > 0) AmountStat(stringResource(R.string.home_savings), p.savedMinor, currency, Modifier.clickable(onClick = onOpenGoals))
                }
            }
        }
    }
}

/** Spec §4: pending reminders and upcoming events. Overdue first, then today, then the next few. */
@Composable
private fun RemindersCard(o: RemindersOverview, onOpenAll: () -> Unit, onOpen: (String, Long) -> Unit) {
    val items = (o.overdue + o.today + o.upcoming).take(4)
    if (items.isEmpty()) return
    val today = LocalDate.now()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionHeader(
                if (o.overdue.isNotEmpty()) stringResource(R.string.home_reminders_overdue, o.overdue.size) else stringResource(R.string.home_reminders),
                stringResource(R.string.home_see_all),
                onOpenAll,
            )
            items.forEach { occ ->
                val days = ChronoUnit.DAYS.between(today, occ.date).toInt()
                val whenText = when {
                    occ.status == OccurrenceStatus.OVERDUE -> stringResource(R.string.home_overdue)
                    days == 0 -> stringResource(R.string.home_due_today)
                    else -> androidx.compose.ui.res.pluralStringResource(R.plurals.home_due_in, days, days)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(occ.reminder.id, occ.originalDate.toEpochDay()) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReminderBadge(occ.reminder.kind, size = 32.dp)
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
                        Text(occ.reminder.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            whenText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (occ.status) {
                                OccurrenceStatus.OVERDUE -> LocalStatusColors.current.overdue
                                OccurrenceStatus.DUE_TODAY -> LocalStatusColors.current.upcoming
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    occ.reminder.amountMinor?.let { MoneyText(it, occ.reminder.currency, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun QuickAddButton(actions: QuickAddActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { open = true }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_quick_add))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                Triple(R.string.home_qa_expense, Icons.Filled.RemoveCircleOutline, actions.expense),
                Triple(R.string.home_qa_income, Icons.Filled.AddCircleOutline, actions.income),
                Triple(R.string.home_qa_reminder, Icons.Filled.NotificationAdd, actions.reminder),
                Triple(R.string.home_qa_task, Icons.Filled.AddTask, actions.task),
            ).forEach { (label, icon, action) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/** Spec §4 "pending tasks": overdue and today's tasks, tick to complete. */
@Composable
private fun TasksCard(o: TodoOverview, onOpenAll: () -> Unit, onOpen: (String) -> Unit, onComplete: (String) -> Unit) {
    val items = (o.overdue + o.today).take(4)
    if (o.openCount == 0) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Spacing.sm)) {
            Row(Modifier.padding(horizontal = Spacing.md)) {
                SectionHeader(androidx.compose.ui.res.pluralStringResource(R.plurals.home_tasks_open, o.openCount, o.openCount), stringResource(R.string.home_see_all), onOpenAll)
            }
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.home_tasks_none_today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
            items.forEach { item ->
                ListItem(
                    headlineContent = { Text(item.todo.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            stringResource(if (item.status == TodoStatus.OVERDUE) R.string.home_overdue else R.string.home_due_today),
                            color = if (item.status == TodoStatus.OVERDUE) LocalStatusColors.current.overdue else LocalStatusColors.current.upcoming,
                        )
                    },
                    leadingContent = {
                        IconButton(onClick = { onComplete(item.todo.id) }) {
                            Icon(Icons.Outlined.Circle, contentDescription = stringResource(R.string.home_complete_task))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickable { onOpen(item.todo.id) },
                )
            }
        }
    }
}
