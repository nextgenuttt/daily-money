package com.dailymoney.feature.reminders.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.money.reminderColor
import com.dailymoney.core.designsystem.money.reminderIcon
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.reminders.PrepStep
import com.dailymoney.core.domain.reminders.PrepTemplates
import com.dailymoney.core.domain.reminders.Reminder
import com.dailymoney.core.domain.reminders.ReminderError
import com.dailymoney.core.domain.reminders.ReminderGroup
import com.dailymoney.core.domain.reminders.ReminderKind
import com.dailymoney.core.domain.reminders.ReminderRepository
import com.dailymoney.core.domain.reminders.SaveReminderUseCase
import com.dailymoney.feature.reminders.R
import com.dailymoney.feature.reminders.common.BackButton
import com.dailymoney.feature.reminders.common.TimePickerDialog
import com.dailymoney.feature.reminders.common.label
import com.dailymoney.feature.reminders.common.mediumLabel
import com.dailymoney.feature.reminders.common.shortLabel
import com.dailymoney.feature.reminders.navigation.AddEditReminderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StepForm(val id: String, val title: String, val daysText: String)

data class ReminderForm(
    val kind: ReminderKind = ReminderKind.BILL,
    val title: String = "",
    val amountText: String = "",
    val date: LocalDate,
    val time: LocalTime? = null,
    val endTime: LocalTime? = null,
    val location: String = "",
    val repeat: Frequency? = null,
    val alerts: Set<Int> = defaultAlerts(ReminderKind.BILL),
    val steps: List<StepForm> = emptyList(),
    val notes: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
)

data class AddEditReminderUiState(
    val isEdit: Boolean,
    val loading: Boolean,
    val form: ReminderForm,
    val currency: String,
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val errors: Set<ReminderError> = emptySet(),
    val saving: Boolean = false,
)

val ALERT_CHOICES = listOf(0, 60, 1440, 4320, 10080)

fun defaultAlerts(kind: ReminderKind): Set<Int> = when (kind.group) {
    ReminderGroup.PAYMENT, ReminderGroup.COLLECTION -> setOf(1440, 0)
    ReminderGroup.APPOINTMENT -> setOf(1440, 60)
    ReminderGroup.EVENT -> if (kind == ReminderKind.TRAVEL) setOf(1440, 0) else setOf(60)
    else -> setOf(0)
}

/** Payments are all-day; appointments and meetings default to 10:00. */
private fun defaultTime(kind: ReminderKind): LocalTime? = when (kind) {
    ReminderKind.APPOINTMENT, ReminderKind.MEETING -> LocalTime.of(10, 0)
    else -> null
}

private fun defaultRepeat(kind: ReminderKind): Frequency? = when (kind) {
    ReminderKind.BILL, ReminderKind.EMI, ReminderKind.CREDIT_CARD, ReminderKind.RECHARGE -> Frequency.MONTHLY
    ReminderKind.BIRTHDAY, ReminderKind.ANNIVERSARY, ReminderKind.INSURANCE -> Frequency.YEARLY
    else -> null
}

/** Suggested category for the expense a payment reminder creates. */
private fun defaultCategoryKey(kind: ReminderKind): String? = when (kind) {
    ReminderKind.EMI, ReminderKind.CREDIT_CARD -> "sys_emi"
    ReminderKind.INSURANCE -> "sys_insurance"
    ReminderKind.RECHARGE -> "sys_mobile"
    ReminderKind.BILL -> "sys_electricity"
    ReminderKind.COLLECTION -> "sys_other_income"
    else -> null
}

@HiltViewModel
class AddEditReminderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ReminderRepository,
    private val saveReminder: SaveReminderUseCase,
    categories: CategoryRepository,
    accounts: AccountRepository,
    config: AppConfig,
    clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AddEditReminderRoute>()
    private val currency = config.defaultCurrency
    private val form = MutableStateFlow(
        ReminderForm(date = route.epochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now(clock)).let {
            it.copy(repeat = defaultRepeat(it.kind), categoryId = defaultCategoryKey(it.kind))
        },
    )
    private val meta = MutableStateFlow(AddEditReminderUiState(route.reminderId != null, route.reminderId != null, form.value, currency))

    private val _close = Channel<Unit>(Channel.BUFFERED)
    val closeEvents = _close.receiveAsFlow()

    val state: StateFlow<AddEditReminderUiState> = combine(meta, form, categories.observe(), accounts.observeBalances()) { m, f, cats, accs ->
        val wanted = if (f.kind.group == ReminderGroup.COLLECTION) CategoryKind.INCOME else CategoryKind.EXPENSE
        m.copy(form = f, categories = cats.filter { it.kind == wanted }, accounts = accs.map { it.account }.filterNot { it.archived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), meta.value)

    init {
        route.reminderId?.let { id ->
            viewModelScope.launch {
                val r = repository.get(id)
                if (r != null) {
                    form.value = ReminderForm(
                        kind = r.kind, title = r.title, amountText = r.amountMinor?.let { MoneyFormatter.toInput(it, r.currency) }.orEmpty(),
                        date = r.startDate, time = r.time, endTime = r.endTime, location = r.location.orEmpty(),
                        repeat = r.recurrence?.frequency, alerts = r.alertOffsets.toSet(),
                        steps = r.steps.map { StepForm(it.id, it.title, it.daysBefore.toString()) },
                        notes = r.notes.orEmpty(), categoryId = r.categoryId, accountId = r.accountId,
                    )
                }
                meta.update { it.copy(loading = false) }
            }
        }
    }

    fun setKind(kind: ReminderKind) = form.update {
        if (it.kind == kind) return@update it
        // New reminders adopt sensible defaults for the type; edits keep what the user set.
        if (meta.value.isEdit) {
            it.copy(kind = kind)
        } else {
            it.copy(
                kind = kind, alerts = defaultAlerts(kind), time = defaultTime(kind), endTime = null, repeat = defaultRepeat(kind),
                categoryId = defaultCategoryKey(kind), steps = emptyList(),
            )
        }
    }.also { meta.update { m -> m.copy(errors = emptySet()) } }

    fun update(block: (ReminderForm) -> ReminderForm) = form.update(block).also { meta.update { it.copy(errors = emptySet()) } }

    fun setAmount(text: String) = update { it.copy(amountText = sanitize(text)) }

    fun toggleAlert(minutes: Int) = update { it.copy(alerts = if (minutes in it.alerts) it.alerts - minutes else it.alerts + minutes) }

    fun useSuggestedSteps() = update { f ->
        f.copy(steps = PrepTemplates.forKind(f.kind).map { (title, days) -> StepForm(Ids.newId(), title, days.toString()) })
    }

    fun addStep() = update { it.copy(steps = it.steps + StepForm(Ids.newId(), "", "1")) }
    fun updateStep(id: String, block: (StepForm) -> StepForm) = update { f -> f.copy(steps = f.steps.map { if (it.id == id) block(it) else it }) }
    fun removeStep(id: String) = update { f -> f.copy(steps = f.steps.filterNot { it.id == id }) }

    fun save() {
        if (meta.value.saving) return
        val f = form.value
        val reminder = Reminder(
            id = route.reminderId ?: Ids.newId(),
            kind = f.kind,
            title = f.title,
            notes = f.notes,
            amountMinor = null,
            currency = currency,
            categoryId = f.categoryId?.takeIf { f.kind.hasAmount },
            accountId = f.accountId?.takeIf { f.kind.hasAmount },
            startDate = f.date,
            time = f.time,
            endTime = f.endTime?.takeIf { f.time != null },
            location = f.location.takeIf { f.kind.isEvent },
            recurrence = f.repeat?.let { Recurrence(it) },
            // All-day items can't be "1 hour before"; that alert becomes "at the time" (9:00).
            alertOffsets = f.alerts.map { if (f.time == null && it in 1..1439) 0 else it }.distinct().sortedDescending(),
            steps = f.steps.map { PrepStep(it.id, it.title, it.daysText.toIntOrNull() ?: -1) },
        )
        meta.update { it.copy(saving = true) }
        viewModelScope.launch {
            val errors = saveReminder(reminder, if (f.kind.hasAmount) f.amountText else "")
            if (errors.isEmpty()) _close.send(Unit) else meta.update { it.copy(saving = false, errors = errors) }
        }
    }

    private fun sanitize(raw: String): String {
        val exp = Currencies.exponent(currency)
        val filtered = raw.filter { it.isDigit() || it == '.' }
        val dot = filtered.indexOf('.')
        if (dot < 0) return filtered.take(11)
        val whole = filtered.substring(0, dot).take(11)
        if (exp == 0) return whole
        return whole + "." + filtered.substring(dot + 1).replace(".", "").take(exp)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddEditReminderScreen(onClose: () -> Unit, viewModel: AddEditReminderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val f = state.form
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var pickEnd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.closeEvents.collect { onClose() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.rem_edit else R.string.rem_new)) },
                navigationIcon = { BackButton(onClose) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Label(stringResource(R.string.rem_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ReminderKind.entries.forEach { kind ->
                    FilterChip(
                        selected = f.kind == kind,
                        onClick = { viewModel.setKind(kind) },
                        label = { Text(kind.label()) },
                        leadingIcon = { Icon(reminderIcon(kind), contentDescription = null, tint = reminderColor(kind.group), modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            OutlinedTextField(
                value = f.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text(stringResource(R.string.rem_title_label)) },
                isError = ReminderError.TITLE_REQUIRED in state.errors || ReminderError.TITLE_TOO_LONG in state.errors,
                supportingText = when {
                    ReminderError.TITLE_REQUIRED in state.errors -> { { Text(stringResource(R.string.rem_err_title)) } }
                    ReminderError.TITLE_TOO_LONG in state.errors -> { { Text(stringResource(R.string.rem_err_title_long)) } }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (f.kind.hasAmount) {
                OutlinedTextField(
                    value = f.amountText,
                    onValueChange = viewModel::setAmount,
                    label = { Text(stringResource(R.string.rem_amount)) },
                    prefix = { Text(Currencies.symbol(state.currency)) },
                    isError = ReminderError.AMOUNT_INVALID in state.errors,
                    supportingText = if (ReminderError.AMOUNT_INVALID in state.errors) { { Text(stringResource(R.string.rem_err_amount)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  ${stringResource(R.string.rem_date)}: ${f.date.mediumLabel()}")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rem_all_day), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = f.time == null, onCheckedChange = { allDay ->
                    viewModel.update { it.copy(time = if (allDay) null else LocalTime.of(10, 0), endTime = null) }
                })
            }
            if (f.time != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  ${f.time.shortLabel()}")
                    }
                    if (f.kind.isEvent) {
                        OutlinedButton(onClick = { pickEnd = true }, modifier = Modifier.weight(1f)) {
                            Text("${stringResource(R.string.rem_end_time)}: ${f.endTime?.shortLabel() ?: "—"}")
                        }
                    }
                }
                if (ReminderError.END_BEFORE_START in state.errors) {
                    Text(stringResource(R.string.rem_err_end), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (f.kind.isEvent) {
                OutlinedTextField(
                    value = f.location,
                    onValueChange = { v -> viewModel.update { it.copy(location = v.take(100)) } },
                    label = { Text(stringResource(R.string.rem_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Label(stringResource(R.string.rem_repeat))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FilterChip(f.repeat == null, { viewModel.update { it.copy(repeat = null) } }, { Text(stringResource(R.string.rem_never)) })
                Frequency.entries.forEach { freq ->
                    FilterChip(f.repeat == freq, { viewModel.update { it.copy(repeat = freq) } }, { Text(frequencyLabel(freq)) })
                }
            }

            Label(stringResource(R.string.rem_alerts))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ALERT_CHOICES.filter { f.time != null || it == 0 || it >= 1440 }.forEach { m ->
                    FilterChip(m in f.alerts, { viewModel.toggleAlert(m) }, { Text(alertLabel(m)) })
                }
            }

            if (f.kind.isEvent) {
                Label(stringResource(R.string.rem_steps))
                Text(stringResource(R.string.rem_steps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                f.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = step.title,
                            onValueChange = { v -> viewModel.updateStep(step.id) { it.copy(title = v.take(60)) } },
                            label = { Text(stringResource(R.string.rem_step_title)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = step.daysText,
                            onValueChange = { v -> viewModel.updateStep(step.id) { it.copy(daysText = v.filter(Char::isDigit).take(2)) } },
                            label = { Text(stringResource(R.string.rem_step_days)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(110.dp).padding(start = Spacing.xs),
                        )
                        IconButton(onClick = { viewModel.removeStep(step.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rem_remove_step))
                        }
                    }
                }
                if (ReminderError.STEP_INVALID in state.errors) {
                    Text(stringResource(R.string.rem_err_step), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = viewModel::addStep) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" ${stringResource(R.string.rem_add_step)}")
                    }
                    if (f.steps.isEmpty() && PrepTemplates.forKind(f.kind).isNotEmpty()) {
                        TextButton(onClick = viewModel::useSuggestedSteps) { Text(stringResource(R.string.rem_use_suggested)) }
                    }
                }
            }

            if (f.kind.hasAmount) {
                Label(stringResource(R.string.rem_category))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    state.categories.forEach { c -> FilterChip(f.categoryId == c.id, { viewModel.update { it.copy(categoryId = c.id) } }, { Text(c.name) }) }
                }
                Label(stringResource(R.string.rem_account))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    state.accounts.forEach { a -> FilterChip(f.accountId == a.id, { viewModel.update { it.copy(accountId = if (it.accountId == a.id) null else a.id) } }, { Text(a.name) }) }
                }
            }

            OutlinedTextField(
                value = f.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v.take(500)) } },
                label = { Text(stringResource(R.string.rem_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(stringResource(R.string.rem_save), viewModel::save, loading = state.saving, modifier = Modifier.padding(bottom = Spacing.lg))
        }
    }

    if (pickDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = f.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms -> viewModel.update { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    pickDate = false
                }) { Text(stringResource(R.string.rem_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.rem_cancel)) } },
        ) { DatePicker(pickerState) }
    }
    if (pickTime) {
        TimePickerDialog(f.time ?: LocalTime.of(10, 0), { t -> viewModel.update { it.copy(time = t) }; pickTime = false }, { pickTime = false })
    }
    if (pickEnd) {
        TimePickerDialog(f.endTime ?: (f.time ?: LocalTime.of(10, 0)).plusHours(1), { t -> viewModel.update { it.copy(endTime = t) }; pickEnd = false }, { pickEnd = false })
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun frequencyLabel(f: Frequency): String = stringResource(
    when (f) {
        Frequency.DAILY -> R.string.freq_daily
        Frequency.WEEKLY -> R.string.freq_weekly
        Frequency.MONTHLY -> R.string.freq_monthly
        Frequency.YEARLY -> R.string.freq_yearly
    },
)

@Composable
private fun alertLabel(minutes: Int): String = stringResource(
    when (minutes) {
        0 -> R.string.rem_alert_at_time
        60 -> R.string.rem_alert_1h
        1440 -> R.string.rem_alert_1d
        4320 -> R.string.rem_alert_3d
        else -> R.string.rem_alert_1w
    },
)
