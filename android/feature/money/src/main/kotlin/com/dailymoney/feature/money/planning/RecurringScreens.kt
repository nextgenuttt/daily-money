package com.dailymoney.feature.money.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.money.AmountStat
import com.dailymoney.core.designsystem.money.CategoryBadge
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.AmountError
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.PostDueRecurringUseCase
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.money.RecurringRepository
import com.dailymoney.core.domain.money.RecurringRule
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.core.domain.money.parsePositiveAmount
import com.dailymoney.core.domain.reminders.ReminderScheduler
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.AmountField
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.ConfirmDeleteDialog
import com.dailymoney.feature.money.common.DateField
import com.dailymoney.feature.money.common.everyLabel
import com.dailymoney.feature.money.common.label
import com.dailymoney.feature.money.common.mediumLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionEditor(
    val id: String?,
    val name: String,
    val amountText: String,
    val frequency: Frequency,
    val nextDate: LocalDate,
    val categoryId: String?,
    val accountId: String?,
    val autoPost: Boolean,
    val nameError: Boolean = false,
    val amountError: AmountError? = null,
    val accountError: Boolean = false,
)

data class RecurringUiState(
    val loading: Boolean = true,
    val rules: List<RecurringRule> = emptyList(),
    val categories: Map<String, Category> = emptyMap(),
    val accounts: List<Account> = emptyList(),
    val today: LocalDate,
    val currency: String,
    val editor: SubscriptionEditor? = null,
    val selected: RecurringRule? = null,
    val confirmDelete: RecurringRule? = null,
) {
    val subscriptions get() = rules.filter { it.isSubscription }
    val regular get() = rules.filterNot { it.isSubscription }
    val monthlyTotal get() = subscriptions.filter { it.active }.sumOf { it.recurrence.monthlyEquivalent(it.amountMinor) }
}

/** Shared by the Recurring and Subscriptions screens: both are views of the same rules. */
@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val rules: RecurringRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
    private val postDue: PostDueRecurringUseCase,
    private val reminderScheduler: ReminderScheduler,
    config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    private val currency = config.defaultCurrency
    private val ui = MutableStateFlow(RecurringUiState(today = LocalDate.now(clock), currency = currency))

    val state: StateFlow<RecurringUiState> = combine(ui, rules.observe(), categories.observe(), accounts.observeBalances()) { u, r, c, a ->
        u.copy(
            loading = false,
            rules = r.sortedBy { it.nextDueDate(u.today) },
            categories = c.associateBy { it.id },
            accounts = a.map { it.account }.filterNot { it.archived },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    fun select(rule: RecurringRule?) = ui.update { it.copy(selected = rule) }

    fun setActive(rule: RecurringRule, active: Boolean) {
        viewModelScope.launch {
            // Resuming skips what was missed while paused instead of back-filling it.
            val resumed = if (active && !rule.active && rule.autoPost) {
                rule.copy(active = true, postedCount = rule.recurrence.firstIndexOnOrAfter(rule.startDate, LocalDate.now(clock)))
            } else {
                rule.copy(active = active)
            }
            rules.save(resumed)
            if (active) postDue()
            reminderScheduler.reschedule()
        }
        ui.update { it.copy(selected = null) }
    }

    fun askDelete(rule: RecurringRule) = ui.update { it.copy(confirmDelete = rule, selected = null, editor = null) }
    fun dismissDelete() = ui.update { it.copy(confirmDelete = null) }
    fun delete() {
        val rule = ui.value.confirmDelete ?: return
        viewModelScope.launch {
            rules.delete(rule.id)
            reminderScheduler.reschedule()
        }
        ui.update { it.copy(confirmDelete = null) }
    }

    fun addSubscription() = ui.update {
        it.copy(editor = SubscriptionEditor(null, "", "", Frequency.MONTHLY, LocalDate.now(clock), null, state.value.accounts.firstOrNull()?.id, autoPost = true))
    }

    fun editSubscription(rule: RecurringRule) = ui.update {
        it.copy(
            selected = null,
            editor = SubscriptionEditor(
                rule.id, rule.name, MoneyFormatter.toInput(rule.amountMinor, rule.currency), rule.recurrence.frequency,
                rule.nextDueDate(LocalDate.now(clock)), rule.categoryId, rule.accountId, rule.autoPost,
            ),
        )
    }

    fun dismissEditor() = ui.update { it.copy(editor = null) }
    fun updateEditor(block: (SubscriptionEditor) -> SubscriptionEditor) = ui.update { it.copy(editor = it.editor?.let(block)) }

    fun saveSubscription() {
        val e = ui.value.editor ?: return
        val (amount, amountError) = parsePositiveAmount(e.amountText, currency)
        val nameError = e.name.isBlank() || e.name.trim().length > 40
        if (amountError != null || nameError || e.accountId == null) {
            updateEditor { it.copy(amountError = amountError, nameError = nameError, accountError = e.accountId == null) }
            return
        }
        viewModelScope.launch {
            rules.save(
                RecurringRule(
                    id = e.id ?: Ids.newId(),
                    name = e.name.trim(),
                    isSubscription = true,
                    type = TransactionType.EXPENSE,
                    amountMinor = amount!!,
                    currency = currency,
                    categoryId = e.categoryId,
                    accountId = e.accountId,
                    toAccountId = null,
                    note = null,
                    paymentMethod = null,
                    recurrence = Recurrence(e.frequency),
                    // The schedule restarts at the chosen next payment date.
                    startDate = e.nextDate,
                    postedCount = 0,
                    autoPost = e.autoPost,
                    active = true,
                ),
            )
            postDue()
            reminderScheduler.reschedule()
            ui.update { it.copy(editor = null) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurringScreen(onBack: () -> Unit, onAdd: () -> Unit, viewModel: RecurringViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recurring_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.money_add_transaction)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            if (!state.loading && state.regular.isEmpty()) {
                item {
                    EmptyState(Icons.Filled.Autorenew, stringResource(R.string.recurring_empty_title), stringResource(R.string.recurring_empty_body))
                }
            }
            items(state.regular, key = { it.id }) { rule -> RuleRow(rule, state, onClick = { viewModel.select(rule) }) }
        }
    }
    state.selected?.let { rule ->
        AlertDialog(
            onDismissRequest = { viewModel.select(null) },
            title = { Text(rule.name) },
            text = { Text(stringResource(R.string.recurring_details, rule.recurrence.frequency.everyLabel(), rule.nextDueDate(state.today).mediumLabel())) },
            confirmButton = {
                TextButton(onClick = { viewModel.setActive(rule, !rule.active) }) {
                    Text(stringResource(if (rule.active) R.string.recurring_pause else R.string.recurring_resume))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.askDelete(rule) }) { Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    state.confirmDelete?.let {
        ConfirmDeleteDialog(stringResource(R.string.recurring_delete_title), stringResource(R.string.recurring_delete_body), viewModel::delete, viewModel::dismissDelete)
    }
}

@Composable
private fun RuleRow(rule: RecurringRule, state: RecurringUiState, onClick: () -> Unit, trailingSwitch: ((Boolean) -> Unit)? = null) {
    val category = rule.categoryId?.let { state.categories[it] }
    val next = rule.nextDueDate(state.today)
    val days = ChronoUnit.DAYS.between(state.today, next)
    val whenText = when {
        !rule.active -> stringResource(R.string.recurring_paused)
        days == 0L -> stringResource(R.string.recurring_due_today)
        days in 1..7 -> pluralStringResource(R.plurals.recurring_due_in, days.toInt(), days.toInt())
        else -> next.mediumLabel()
    }
    ListItem(
        headlineContent = { Text(rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${rule.recurrence.frequency.everyLabel()} · $whenText") },
        leadingContent = {
            CategoryBadge(if (rule.type == TransactionType.TRANSFER) "transfer" else category?.iconKey ?: "others", category?.colorIndex ?: 5)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(rule.amountMinor, rule.currency, type = rule.type)
                if (trailingSwitch != null) Switch(checked = rule.active, onCheckedChange = trailingSwitch)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionsScreen(onBack: () -> Unit, viewModel: RecurringViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subs_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::addSubscription) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.subs_add)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
                        AmountStat(stringResource(R.string.subs_per_month), state.monthlyTotal, state.currency)
                        AmountStat(stringResource(R.string.subs_per_year), state.monthlyTotal * 12, state.currency)
                    }
                }
            }
            if (!state.loading && state.subscriptions.isEmpty()) {
                item { EmptyState(Icons.Filled.Subscriptions, stringResource(R.string.subs_empty_title), stringResource(R.string.subs_empty_body)) }
            }
            items(state.subscriptions, key = { it.id }) { rule ->
                RuleRow(rule, state, onClick = { viewModel.editSubscription(rule) }, trailingSwitch = { viewModel.setActive(rule, it) })
            }
        }
    }
    state.editor?.let { SubscriptionDialog(it, state, viewModel) }
    state.confirmDelete?.let {
        ConfirmDeleteDialog(stringResource(R.string.subs_delete_title), stringResource(R.string.recurring_delete_body), viewModel::delete, viewModel::dismissDelete)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionDialog(editor: SubscriptionEditor, state: RecurringUiState, viewModel: RecurringViewModel) {
    var showAllCategories by remember { mutableStateOf(false) }
    val expenseCategories = state.categories.values.filter { it.kind == CategoryKind.EXPENSE }.sortedBy { it.name }
    AlertDialog(
        onDismissRequest = viewModel::dismissEditor,
        title = { Text(stringResource(if (editor.id == null) R.string.subs_add else R.string.subs_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { v -> viewModel.updateEditor { it.copy(name = v, nameError = false) } },
                    label = { Text(stringResource(R.string.subs_name)) },
                    isError = editor.nameError,
                    supportingText = if (editor.nameError) { { Text(stringResource(R.string.name_err_required)) } } else null,
                    singleLine = true,
                )
                AmountField(editor.amountText, { v -> viewModel.updateEditor { it.copy(amountText = v, amountError = null) } }, stringResource(R.string.edit_amount), state.currency, editor.amountError)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Frequency.entries.filter { it != Frequency.DAILY }.forEach { f ->
                        FilterChip(editor.frequency == f, { viewModel.updateEditor { it.copy(frequency = f) } }, { Text(f.label()) })
                    }
                }
                DateField(stringResource(R.string.subs_next_payment), editor.nextDate, { d -> viewModel.updateEditor { it.copy(nextDate = d) } })
                Text(stringResource(R.string.edit_category), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    val shown = if (showAllCategories) expenseCategories else expenseCategories.filter { it.iconKey in SUBSCRIPTION_KEYS || it.id == editor.categoryId }
                    shown.forEach { c -> FilterChip(editor.categoryId == c.id, { viewModel.updateEditor { it.copy(categoryId = c.id) } }, { Text(c.name) }) }
                    if (!showAllCategories) TextButton(onClick = { showAllCategories = true }) { Text(stringResource(R.string.more)) }
                }
                Text(stringResource(R.string.edit_account), style = MaterialTheme.typography.labelLarge)
                if (editor.accountError) Text(stringResource(R.string.edit_err_account), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    state.accounts.forEach { a -> FilterChip(editor.accountId == a.id, { viewModel.updateEditor { it.copy(accountId = a.id, accountError = false) } }, { Text(a.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.subs_auto_post), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(editor.autoPost, { v -> viewModel.updateEditor { it.copy(autoPost = v) } })
                }
                if (editor.id != null) {
                    TextButton(onClick = { state.rules.firstOrNull { it.id == editor.id }?.let(viewModel::askDelete) }) {
                        Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveSubscription) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissEditor) { Text(stringResource(R.string.cancel)) } },
    )
}

private val SUBSCRIPTION_KEYS = setOf("entertainment", "internet", "mobile", "insurance", "emi", "education", "others")
