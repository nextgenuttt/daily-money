package com.dailymoney.feature.reminders.detail

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.ReminderBadge
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.reminders.CompleteOccurrenceUseCase
import com.dailymoney.core.domain.reminders.CompleteResult
import com.dailymoney.core.domain.reminders.DeleteReminderUseCase
import com.dailymoney.core.domain.reminders.ObserveReminderUseCase
import com.dailymoney.core.domain.reminders.Occurrence
import com.dailymoney.core.domain.reminders.OccurrenceAction
import com.dailymoney.core.domain.reminders.OccurrenceStatus
import com.dailymoney.core.domain.reminders.PaymentEntry
import com.dailymoney.core.domain.reminders.ReminderGroup
import com.dailymoney.core.domain.reminders.ReopenOccurrenceUseCase
import com.dailymoney.core.domain.reminders.UpdateOccurrenceUseCase
import com.dailymoney.feature.reminders.R
import com.dailymoney.feature.reminders.common.BackButton
import com.dailymoney.feature.reminders.common.TimePickerDialog
import com.dailymoney.feature.reminders.common.completeAction
import com.dailymoney.feature.reminders.common.label
import com.dailymoney.feature.reminders.common.mediumLabel
import com.dailymoney.feature.reminders.common.question
import com.dailymoney.feature.reminders.common.shortLabel
import com.dailymoney.feature.reminders.common.statusColor
import com.dailymoney.feature.reminders.common.whenText
import com.dailymoney.feature.reminders.edit.frequencyLabel
import com.dailymoney.feature.reminders.navigation.ReminderDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PayForm(
    val amountText: String,
    val date: LocalDate,
    val accountId: String?,
    val categoryId: String?,
    val addToMoney: Boolean,
    val invalid: Boolean = false,
)

data class DetailUiState(
    val loading: Boolean = true,
    val details: ObserveReminderUseCase.Details? = null,
    val target: Occurrence? = null,
    val today: LocalDate,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val pay: PayForm? = null,
    val confirmDelete: Boolean = false,
)

@HiltViewModel
class ReminderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observe: ObserveReminderUseCase,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val complete: CompleteOccurrenceUseCase,
    private val reopen: ReopenOccurrenceUseCase,
    private val update: UpdateOccurrenceUseCase,
    private val delete: DeleteReminderUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ReminderDetailRoute>()
    private val ui = MutableStateFlow(DetailUiState(today = LocalDate.now(clock)))
    private var autoOpenedPay = false

    val state: StateFlow<DetailUiState> = combine(ui, observe(route.reminderId), accounts.observeBalances(), categories.observe()) { u, d, a, c ->
        val target = d?.occurrenceOn(route.epochDay?.let(LocalDate::ofEpochDay), LocalDateTime.now(clock))
        val kind = if (d?.reminder?.kind?.group == ReminderGroup.COLLECTION) CategoryKind.INCOME else CategoryKind.EXPENSE
        u.copy(loading = false, details = d, target = target, accounts = a.map { it.account }.filterNot { it.archived }, categories = c.filter { it.kind == kind })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    /** Called once data is loaded: "Mark paid" from a notification opens the payment sheet directly. */
    fun maybeAutoOpenPay() {
        val s = state.value
        if (route.complete && !autoOpenedPay && s.target != null && s.target.record?.action == null) {
            autoOpenedPay = true
            startComplete()
        }
    }

    fun startComplete() {
        val s = state.value
        val target = s.target ?: return
        val r = target.reminder
        if (!r.kind.hasAmount) {
            viewModelScope.launch { complete(r, target.originalDate) }
            return
        }
        ui.update {
            it.copy(
                pay = PayForm(
                    amountText = r.amountMinor?.let { a -> MoneyFormatter.toInput(a, r.currency) }.orEmpty(),
                    date = LocalDate.now(clock),
                    accountId = r.accountId ?: s.accounts.firstOrNull()?.id,
                    categoryId = r.categoryId,
                    addToMoney = s.accounts.isNotEmpty(),
                ),
            )
        }
    }

    fun updatePay(block: (PayForm) -> PayForm) = ui.update { it.copy(pay = it.pay?.let(block)) }
    fun dismissPay() = ui.update { it.copy(pay = null) }

    fun confirmPay() {
        val p = ui.value.pay ?: return
        val target = state.value.target ?: return
        val entry = if (p.addToMoney && p.accountId != null) PaymentEntry(p.amountText, p.date, p.accountId, p.categoryId) else null
        viewModelScope.launch {
            when (complete(target.reminder, target.originalDate, entry)) {
                CompleteResult.Done -> ui.update { it.copy(pay = null) }
                CompleteResult.AmountInvalid -> updatePay { it.copy(invalid = true) }
            }
        }
    }

    fun snooze() {
        val t = state.value.target ?: return
        viewModelScope.launch { update.snooze(t) }
    }

    fun skip() {
        val t = state.value.target ?: return
        viewModelScope.launch { update.skip(t.reminder.id, t.originalDate) }
    }

    fun reschedule(to: LocalDateTime) {
        val t = state.value.target ?: return
        viewModelScope.launch { update.reschedule(t.reminder.id, t.originalDate, to) }
    }

    fun toggleStep(stepId: String) {
        val t = state.value.target ?: return
        viewModelScope.launch { update.toggleStep(t.reminder.id, t.originalDate, stepId) }
    }

    fun reopen(o: Occurrence) {
        viewModelScope.launch { reopen(o.reminder.id, o.originalDate) }
    }

    fun askDelete() = ui.update { it.copy(confirmDelete = true) }
    fun dismissDelete() = ui.update { it.copy(confirmDelete = false) }
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            delete(route.reminderId)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderDetailScreen(onBack: () -> Unit, onEdit: (String) -> Unit, viewModel: ReminderDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var rescheduleDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickRescheduleDate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.target) { viewModel.maybeAutoOpenPay() }
    val d = state.details

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.reminder?.kind?.label().orEmpty()) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { onEdit(d.reminder.id) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.det_edit)) }
                        IconButton(onClick = viewModel::askDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.det_delete)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (d == null) {
            if (!state.loading) ErrorBanner(stringResource(R.string.det_not_found), Modifier.padding(padding).padding(Spacing.md))
            return@Scaffold
        }
        val r = d.reminder
        val target = state.target
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReminderBadge(r.kind)
                        Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                            Text(r.title, style = MaterialTheme.typography.titleLarge)
                            r.recurrence?.let { Text(stringResource(R.string.det_repeats, frequencyLabel(it.frequency).lowercase()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        r.amountMinor?.let { MoneyText(it, r.currency, style = MaterialTheme.typography.titleMedium) }
                    }
                    if (target == null) {
                        Text(stringResource(R.string.det_no_upcoming), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            "${statusLabel(target.status)} · ${target.whenText(state.today)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = target.statusColor(),
                        )
                        val end = r.endTime
                        if (end != null && !target.isAllDay) {
                            Text("${target.at.toLocalTime().shortLabel()} – ${end.shortLabel()}", style = MaterialTheme.typography.bodyMedium)
                        }
                        target.record?.rescheduledTo?.let { Text(stringResource(R.string.det_moved_from, target.originalDate.mediumLabel()), style = MaterialTheme.typography.bodySmall) }
                        target.record?.snoozedUntil?.takeIf { it.isAfter(Instant.now()) }?.let {
                            Text(
                                stringResource(R.string.det_snoozed_until, LocalDateTime.ofInstant(it, java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    r.location?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    r.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            if (target != null && target.record?.action == null) {
                Text(r.kind.question(), style = MaterialTheme.typography.titleMedium)
                PrimaryButton(r.kind.completeAction(), viewModel::startComplete)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ActionButton(stringResource(R.string.det_snooze), viewModel::snooze, Modifier.weight(1f))
                    ActionButton(stringResource(R.string.det_reschedule), { pickRescheduleDate = true }, Modifier.weight(1f))
                    ActionButton(stringResource(R.string.det_skip), viewModel::skip, Modifier.weight(1f))
                }
                if (r.steps.isNotEmpty()) {
                    Text(stringResource(R.string.rem_steps), style = MaterialTheme.typography.titleMedium)
                    r.steps.sortedByDescending { it.daysBefore }.forEach { step ->
                        val done = step.id in target.record?.doneStepIds.orEmpty()
                        Row(Modifier.fillMaxWidth().clickable { viewModel.toggleStep(step.id) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = done, onCheckedChange = { viewModel.toggleStep(step.id) })
                            Column {
                                Text(step.title, style = MaterialTheme.typography.bodyLarge)
                                Text(target.date.minusDays(step.daysBefore.toLong()).mediumLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (d.history.isNotEmpty()) {
                Text(stringResource(R.string.det_history), style = MaterialTheme.typography.titleMedium)
                d.history.forEach { h ->
                    val rec = h.record
                    ListItem(
                        headlineContent = { Text(h.originalDate.mediumLabel()) },
                        supportingContent = {
                            val paid = rec?.paidAmountMinor
                            Text(
                                when {
                                    rec?.action == OccurrenceAction.SKIPPED -> stringResource(R.string.det_history_skipped)
                                    paid != null -> stringResource(
                                        R.string.det_history_paid,
                                        MoneyFormatter.format(paid, r.currency),
                                        rec.completedAt?.let { LocalDateTime.ofInstant(it, java.time.ZoneId.systemDefault()).toLocalDate().mediumLabel() }.orEmpty(),
                                    )
                                    else -> stringResource(R.string.det_history_done, rec?.completedAt?.let { LocalDateTime.ofInstant(it, java.time.ZoneId.systemDefault()).toLocalDate().mediumLabel() }.orEmpty())
                                },
                                color = if (rec?.action == OccurrenceAction.DONE) LocalStatusColors.current.paid else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { TextButton(onClick = { viewModel.reopen(h) }) { Text(stringResource(R.string.det_reopen)) } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            }
        }
    }

    state.pay?.let { PayDialog(it, state, viewModel) }

    if (pickRescheduleDate) {
        val initial = state.target?.date ?: state.today
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickRescheduleDate = false },
            confirmButton = {
                TextButton(onClick = {
                    rescheduleDate = pickerState.selectedDateMillis
                    pickRescheduleDate = false
                }) { Text(stringResource(R.string.rem_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickRescheduleDate = false }) { Text(stringResource(R.string.rem_cancel)) } },
        ) { DatePicker(pickerState) }
    }
    rescheduleDate?.let { ms ->
        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
        TimePickerDialog(
            initial = state.target?.at?.toLocalTime() ?: LocalTime.of(10, 0),
            onConfirm = { t ->
                viewModel.reschedule(date.atTime(t))
                rescheduleDate = null
            },
            onDismiss = { rescheduleDate = null },
        )
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.det_delete_title)) },
            text = { Text(stringResource(R.string.det_delete_body)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(onBack) }) { Text(stringResource(R.string.det_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.rem_cancel)) } },
        )
    }
}

@Composable
private fun statusLabel(status: OccurrenceStatus): String = stringResource(
    when (status) {
        OccurrenceStatus.OVERDUE -> R.string.det_status_overdue
        OccurrenceStatus.DUE_TODAY -> R.string.det_status_today
        OccurrenceStatus.DONE -> R.string.rem_done
        else -> R.string.det_status_upcoming
    },
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PayDialog(p: PayForm, state: DetailUiState, viewModel: ReminderDetailViewModel) {
    val r = state.target?.reminder ?: return
    val income = r.kind.group == ReminderGroup.COLLECTION
    var pickDate by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::dismissPay,
        title = { Text(stringResource(if (income) R.string.pay_title_received else R.string.pay_title_paid)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = state.accounts.isNotEmpty()) { viewModel.updatePay { it.copy(addToMoney = !it.addToMoney) } }) {
                    Checkbox(checked = p.addToMoney, onCheckedChange = { v -> viewModel.updatePay { it.copy(addToMoney = v) } }, enabled = state.accounts.isNotEmpty())
                    Text(stringResource(if (income) R.string.pay_add_income else R.string.pay_add_expense))
                }
                if (p.addToMoney) {
                    OutlinedTextField(
                        value = p.amountText,
                        onValueChange = { v -> viewModel.updatePay { it.copy(amountText = v.filter { c -> c.isDigit() || c == '.' }.take(14), invalid = false) } },
                        label = { Text(stringResource(R.string.pay_amount)) },
                        prefix = { Text(Currencies.symbol(r.currency)) },
                        isError = p.invalid,
                        supportingText = if (p.invalid) { { Text(stringResource(R.string.rem_err_amount)) } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedButton(onClick = { pickDate = true }) { Text("${stringResource(R.string.pay_date)}: ${p.date.mediumLabel()}") }
                    Text(stringResource(R.string.pay_account), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        state.accounts.forEach { a -> FilterChip(p.accountId == a.id, { viewModel.updatePay { it.copy(accountId = a.id) } }, { Text(a.name) }) }
                    }
                    Text(stringResource(R.string.pay_category), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        state.categories.forEach { c -> FilterChip(p.categoryId == c.id, { viewModel.updatePay { it.copy(categoryId = c.id) } }, { Text(c.name) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmPay) { Text(stringResource(R.string.pay_confirm)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissPay) { Text(stringResource(R.string.rem_cancel)) } },
    )
    if (pickDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = p.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms -> viewModel.updatePay { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    pickDate = false
                }) { Text(stringResource(R.string.rem_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.rem_cancel)) } },
        ) { DatePicker(pickerState) }
    }
}

/** Three side by side: one line each, so the row stays even with long words or large text. */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
