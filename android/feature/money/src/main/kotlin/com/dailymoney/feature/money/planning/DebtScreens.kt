package com.dailymoney.feature.money.planning

import androidx.compose.material3.Button
import com.dailymoney.core.domain.auth.SessionState
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.designsystem.component.openUpiApp
import com.dailymoney.core.domain.sharing.UpiPayment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.ContactsContract
import android.content.Intent
import com.dailymoney.core.domain.sharing.ReminderText
import com.dailymoney.core.designsystem.component.ReminderChannels
import com.dailymoney.core.designsystem.component.DirectSms
import androidx.compose.material.icons.filled.NotificationsActive
import com.dailymoney.core.domain.sharing.PhoneNumbers
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import com.dailymoney.core.domain.sharing.SmsSender
import com.dailymoney.core.domain.sharing.SmsSendResult
import com.dailymoney.core.designsystem.component.rememberErrorScroller
import com.dailymoney.core.designsystem.component.errorTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.money.AmountStat
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.AmountError
import com.dailymoney.core.domain.money.Debt
import com.dailymoney.core.domain.money.DebtDirection
import com.dailymoney.core.domain.money.DebtRepayment
import com.dailymoney.core.domain.money.DebtRepository
import com.dailymoney.core.domain.money.DebtStatus
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.PlanningMath
import com.dailymoney.core.domain.money.parsePositiveAmount
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.AmountField
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.ConfirmDeleteDialog
import com.dailymoney.feature.money.common.DateField
import com.dailymoney.feature.money.common.mediumLabel
import com.dailymoney.feature.money.navigation.DebtDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DebtEditor(
    val id: String?,
    val person: String,
    val direction: DebtDirection,
    val amountText: String,
    val startDate: LocalDate,
    val dueDate: LocalDate?,
    val note: String,
    val personError: Boolean = false,
    val amountError: AmountError? = null,
    val phone: String = "",
    val phoneError: Boolean = false,
    val upiId: String = "",
    val upiError: Boolean = false,
)

/** A reminder being sent for money someone owes me (they don't need the app). */
data class DebtRemind(val smsSending: Boolean = false, val smsResult: SmsSendResult? = null)

data class RepaymentEditor(val amountText: String, val date: LocalDate, val note: String, val error: AmountError? = null)

data class DebtsUiState(
    val loading: Boolean = true,
    val debts: List<DebtStatus> = emptyList(),
    val showSettled: Boolean = false,
    val currency: String,
    val today: LocalDate,
    val editor: DebtEditor? = null,
    val repayment: RepaymentEditor? = null,
    val confirmDelete: Boolean = false,
    val remind: DebtRemind? = null,
    val myUpiId: String? = null,
    val myName: String? = null,
    /** Set when a UPI app was opened to pay; on return we ask whether to record the repayment. */
    val paying: Long? = null,
    val noUpiApp: Boolean = false,
) {
    val visible get() = debts.filter { it.settled == showSettled }
    val summary get() = PlanningMath.debtSummary(debts)
}

@HiltViewModel
class DebtsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DebtRepository,
    private val smsSender: SmsSender,
    shared: SharedExpenseRepository,
    auth: AuthRepository,
    config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    val canSendSms: Boolean get() = smsSender.supported
    val payPageUrl: String = config.payPageUrl

    val debtId: String? = runCatching { savedStateHandle.toRoute<DebtDetailRoute>().debtId }.getOrNull()
    private val currency = config.defaultCurrency
    private val ui = MutableStateFlow(DebtsUiState(currency = currency, today = LocalDate.now(clock)))

    val state: StateFlow<DebtsUiState> = combine(ui, repository.observe(), shared.observeMe(), auth.session) { u, d, me, session ->
        u.copy(loading = false, debts = d, myUpiId = me?.upiId, myName = (session as? SessionState.SignedIn)?.user?.displayName)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    fun setShowSettled(v: Boolean) = ui.update { it.copy(showSettled = v) }

    fun add() = ui.update { it.copy(editor = DebtEditor(null, "", DebtDirection.LENT, "", LocalDate.now(clock), null, "")) }
    fun edit(d: Debt) = ui.update {
        it.copy(editor = DebtEditor(d.id, d.person, d.direction, MoneyFormatter.toInput(d.principalMinor, d.currency), d.startDate, d.dueDate, d.note.orEmpty(), phone = d.phone.orEmpty(), upiId = d.upiId.orEmpty()))
    }
    fun dismissEditor() = ui.update { it.copy(editor = null) }
    fun updateEditor(block: (DebtEditor) -> DebtEditor) = ui.update { it.copy(editor = it.editor?.let(block)) }

    fun saveDebt() {
        val e = ui.value.editor ?: return
        val (amount, error) = parsePositiveAmount(e.amountText, currency)
        val personError = e.person.isBlank() || e.person.trim().length > 40
        val phone = PhoneNumbers.normalise(e.phone)
        val phoneError = e.phone.isNotBlank() && phone == null
        val upi = e.upiId.trim().ifEmpty { null }
        val upiError = upi != null && !UpiPayment.isValidId(upi)
        if (error != null || personError || phoneError || upiError) {
            updateEditor { it.copy(amountError = error, personError = personError, phoneError = phoneError, upiError = upiError) }
            return
        }
        viewModelScope.launch {
            repository.save(Debt(e.id ?: Ids.newId(), e.person.trim(), e.direction, amount!!, currency, e.startDate, e.dueDate, e.note.trim().ifEmpty { null }, phone, upi))
            ui.update { it.copy(editor = null) }
        }
    }

    fun startRepayment(prefillMinor: Long) = ui.update {
        it.copy(repayment = RepaymentEditor(MoneyFormatter.toInput(prefillMinor, currency), LocalDate.now(clock), ""))
    }
    fun dismissRepayment() = ui.update { it.copy(repayment = null) }
    fun updateRepayment(block: (RepaymentEditor) -> RepaymentEditor) = ui.update { it.copy(repayment = it.repayment?.let(block)) }

    fun saveRepayment() {
        val r = ui.value.repayment ?: return
        val id = debtId ?: return
        val (amount, error) = parsePositiveAmount(r.amountText, currency)
        if (error != null) {
            updateRepayment { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            repository.addRepayment(DebtRepayment(Ids.newId(), id, amount!!, r.date, r.note.trim().ifEmpty { null }))
            ui.update { it.copy(repayment = null) }
        }
    }

    fun deleteRepayment(id: String) {
        viewModelScope.launch { repository.deleteRepayment(id) }
    }

    fun paymentOpened(amountMinor: Long, opened: Boolean) = ui.update { it.copy(paying = amountMinor.takeIf { opened }, noUpiApp = !opened) }
    fun paymentAnswered(paid: Boolean) {
        val amount = ui.value.paying ?: return
        ui.update { it.copy(paying = null) }
        if (paid) startRepayment(amount)
    }

    fun openRemind() = ui.update { it.copy(remind = DebtRemind()) }
    fun closeRemind() = ui.update { it.copy(remind = null) }

    fun sendRemindSms(phone: String, text: String) {
        ui.update { it.copy(remind = DebtRemind(smsSending = true)) }
        viewModelScope.launch {
            val result = smsSender.send(phone, text)
            ui.update { it.copy(remind = it.remind?.copy(smsSending = false, smsResult = result)) }
        }
    }

    fun askDelete() = ui.update { it.copy(confirmDelete = true) }
    fun dismissDelete() = ui.update { it.copy(confirmDelete = false) }
    fun deleteDebt(onDone: () -> Unit) {
        val id = debtId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebtsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: DebtsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debts_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::add) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.debts_add)) }
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
                        AmountStat(stringResource(R.string.debts_to_receive), state.summary.toReceiveMinor, state.currency, color = LocalStatusColors.current.income)
                        AmountStat(stringResource(R.string.debts_to_pay), state.summary.toPayMinor, state.currency)
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
                    listOf(false to R.string.debts_open, true to R.string.debts_settled).forEachIndexed { i, (settled, label) ->
                        SegmentedButton(state.showSettled == settled, { viewModel.setShowSettled(settled) }, SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
            if (!state.loading && state.visible.isEmpty()) {
                item { EmptyState(Icons.Filled.Handshake, stringResource(R.string.debts_empty_title), stringResource(R.string.debts_empty_body)) }
            }
            items(state.visible, key = { it.debt.id }) { d ->
                val overdue = d.overdue(state.today)
                ListItem(
                    headlineContent = { Text(d.debt.person) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                stringResource(if (d.debt.direction == DebtDirection.LENT) R.string.debts_lent else R.string.debts_borrowed),
                                when {
                                    d.settled -> stringResource(R.string.debts_settled_label)
                                    overdue -> stringResource(R.string.debts_overdue)
                                    else -> d.debt.dueDate?.let { stringResource(R.string.debts_due, it.mediumLabel()) }
                                },
                            ).joinToString(" · "),
                            color = if (overdue) LocalStatusColors.current.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { MoneyText(d.outstandingMinor, d.debt.currency) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { onOpen(d.debt.id) },
                )
            }
        }
    }
    state.editor?.let { DebtDialog(it, state.currency, viewModel) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebtDetailScreen(onBack: () -> Unit, viewModel: DebtsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.debts.firstOrNull { it.debt.id == viewModel.debtId }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(status?.debt?.person.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (status != null) {
                        IconButton(onClick = { viewModel.edit(status.debt) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.debts_edit)) }
                        IconButton(onClick = viewModel::askDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_delete)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (status == null) return@Scaffold
        val d = status.debt
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            stringResource(if (d.direction == DebtDirection.LENT) R.string.debts_owes_you else R.string.debts_you_owe, d.person),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MoneyText(status.outstandingMinor, d.currency, style = MaterialTheme.typography.displaySmall)
                        LinearProgressIndicator(
                            progress = { if (d.principalMinor == 0L) 1f else (status.repaidMinor.toFloat() / d.principalMinor).coerceIn(0f, 1f) },
                            color = LocalStatusColors.current.paid,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Text(
                            stringResource(R.string.debts_repaid_of, MoneyFormatter.format(status.repaidMinor, d.currency), MoneyFormatter.format(d.principalMinor, d.currency)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            listOfNotNull(
                                stringResource(R.string.debts_since, d.startDate.mediumLabel()),
                                d.dueDate?.let { stringResource(R.string.debts_due, it.mediumLabel()) },
                                d.note,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.overdue(state.today)) LocalStatusColors.current.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val payTo = d.upiId?.takeIf { UpiPayment.isValidId(it) }
            if (!status.settled && d.direction == DebtDirection.BORROWED && payTo != null) {
                item {
                    val context = LocalContext.current
                    Button(onClick = {
                        val opened = openUpiApp(context, UpiPayment.appUri(payTo, d.person, status.outstandingMinor, d.note ?: "Repayment"))
                        viewModel.paymentOpened(status.outstandingMinor, opened)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.debts_pay_upi, MoneyFormatter.format(status.outstandingMinor, d.currency), d.person))
                    }
                    if (state.noUpiApp) Text(stringResource(R.string.debts_no_upi_app), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!status.settled && d.direction == DebtDirection.LENT) {
                item {
                    OutlinedButton(onClick = viewModel::openRemind, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                        Text(stringResource(R.string.debts_remind, d.person), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
            }
            if (!status.settled) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PrimaryButton(stringResource(R.string.debts_record_repayment), { viewModel.startRepayment(0) }, Modifier.weight(1f))
                        OutlinedButton(onClick = { viewModel.startRepayment(status.outstandingMinor) }, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text(stringResource(R.string.debts_mark_settled))
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm)) }
            items(status.repayments, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { MoneyText(r.amountMinor, d.currency) },
                    supportingContent = { Text(listOfNotNull(r.date.mediumLabel(), r.note).joinToString(" · ")) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.deleteRepayment(r.id) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_delete)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
    // Back from the UPI app: only the user knows if the payment went through.
    state.paying?.let { amount ->
        AlertDialog(
            onDismissRequest = { viewModel.paymentAnswered(false) },
            title = { Text(stringResource(R.string.debts_paid_question)) },
            text = { Text(stringResource(R.string.debts_paid_body, MoneyFormatter.format(amount, state.currency))) },
            confirmButton = { TextButton(onClick = { viewModel.paymentAnswered(true) }) { Text(stringResource(R.string.debts_paid_yes)) } },
            dismissButton = { TextButton(onClick = { viewModel.paymentAnswered(false) }) { Text(stringResource(R.string.debts_paid_no)) } },
        )
    }
    val remindFor = state.debts.firstOrNull { it.debt.id == viewModel.debtId }
    if (state.remind != null && remindFor != null) DebtRemindDialog(remindFor, state, viewModel)
    state.editor?.let { DebtDialog(it, state.currency, viewModel) }
    state.repayment?.let { r ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRepayment,
            title = { Text(stringResource(R.string.debts_record_repayment)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AmountField(r.amountText, { v -> viewModel.updateRepayment { it.copy(amountText = v, error = null) } }, stringResource(R.string.edit_amount), state.currency, r.error)
                    DateField(stringResource(R.string.edit_date), r.date, { v -> viewModel.updateRepayment { it.copy(date = v) } })
                    OutlinedTextField(r.note, { v -> viewModel.updateRepayment { it.copy(note = v.take(200)) } }, label = { Text(stringResource(R.string.edit_note)) }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::saveRepayment) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissRepayment) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (state.confirmDelete) {
        ConfirmDeleteDialog(stringResource(R.string.debts_delete_title), stringResource(R.string.debts_delete_body), { viewModel.deleteDebt(onBack) }, viewModel::dismissDelete)
    }
}

@Composable
private fun DebtDialog(editor: DebtEditor, currency: String, viewModel: DebtsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissEditor,
        title = { Text(stringResource(if (editor.id == null) R.string.debts_add else R.string.debts_edit)) },
        text = {
            val errorScroller = rememberErrorScroller(editor.personError to editor.amountError)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(DebtDirection.LENT to R.string.debts_i_lent, DebtDirection.BORROWED to R.string.debts_i_borrowed).forEachIndexed { i, (dir, label) ->
                        SegmentedButton(editor.direction == dir, { viewModel.updateEditor { it.copy(direction = dir) } }, SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(stringResource(label))
                        }
                    }
                }
                OutlinedTextField(
                    value = editor.person,
                    onValueChange = { v -> viewModel.updateEditor { it.copy(person = v, personError = false) } },
                    label = { Text(stringResource(R.string.debts_person)) },
                    isError = editor.personError,
                    supportingText = if (editor.personError) { { Text(stringResource(R.string.name_err_required)) } } else null,
                    singleLine = true,
                    modifier = Modifier.errorTarget(errorScroller, 1, editor.personError),
                )
                AmountField(
                    editor.amountText, { v -> viewModel.updateEditor { it.copy(amountText = v, amountError = null) } }, stringResource(R.string.edit_amount),
                    currency, editor.amountError, Modifier.errorTarget(errorScroller, 2, editor.amountError != null),
                )
                DateField(stringResource(R.string.edit_date), editor.startDate, { d -> viewModel.updateEditor { it.copy(startDate = d) } })
                DateField(
                    stringResource(R.string.debts_due_date), editor.dueDate, { d -> viewModel.updateEditor { it.copy(dueDate = d) } },
                    placeholder = stringResource(R.string.optional), onClear = { viewModel.updateEditor { it.copy(dueDate = null) } },
                )
                PhoneField(editor, viewModel)
                OutlinedTextField(
                    value = editor.upiId,
                    onValueChange = { v -> viewModel.updateEditor { it.copy(upiId = v.filter { c -> !c.isWhitespace() }.take(100), upiError = false) } },
                    label = { Text(stringResource(R.string.debts_upi)) },
                    placeholder = { Text(stringResource(R.string.optional)) },
                    isError = editor.upiError,
                    supportingText = { Text(stringResource(if (editor.upiError) R.string.debts_upi_invalid else R.string.debts_upi_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                )
                OutlinedTextField(editor.note, { v -> viewModel.updateEditor { it.copy(note = v.take(200)) } }, label = { Text(stringResource(R.string.edit_note)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveDebt) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissEditor) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Their mobile: typed, or picked from contacts (one contact is shared with us; no contacts permission). */
@Composable
private fun PhoneField(editor: DebtEditor, viewModel: DebtsViewModel) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1).orEmpty() else null }
        }.getOrNull()?.let { (number, name) ->
            viewModel.updateEditor { it.copy(phone = number, phoneError = false, person = it.person.ifBlank { name.take(40) }) }
        }
    }
    OutlinedTextField(
        value = editor.phone,
        onValueChange = { v -> viewModel.updateEditor { it.copy(phone = v.filter { c -> c.isDigit() || c in "+ -" }.take(20), phoneError = false) } },
        label = { Text(stringResource(R.string.debts_phone)) },
        placeholder = { Text(stringResource(R.string.optional)) },
        isError = editor.phoneError,
        supportingText = { Text(stringResource(if (editor.phoneError) R.string.debts_phone_invalid else R.string.debts_phone_hint)) },
        trailingIcon = {
            IconButton(onClick = {
                runCatching { pick.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)) }
            }) { Icon(Icons.Filled.Contacts, contentDescription = stringResource(R.string.debts_pick_contact)) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
    )
}

@Composable
private fun DebtRemindDialog(status: DebtStatus, state: DebtsUiState, viewModel: DebtsViewModel) {
    val d = status.debt
    val amount = MoneyFormatter.format(status.outstandingMinor, d.currency)
    val payLink = state.myUpiId?.takeIf { UpiPayment.isValidId(it) && viewModel.payPageUrl.isNotBlank() }
        ?.let { UpiPayment.webLink(viewModel.payPageUrl, it, state.myName, status.outstandingMinor, d.note) }
    val message = ReminderText.build(d.person, amount, "(lent on ${d.startDate.mediumLabel()})", state.myUpiId, payLink)
    val remind = state.remind ?: return
    AlertDialog(
        onDismissRequest = viewModel::closeRemind,
        title = { Text(stringResource(R.string.debts_remind, d.person)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ReminderChannels(
                    phone = d.phone,
                    message = message,
                    directSms = if (viewModel.canSendSms) DirectSms(remind.smsResult, remind.smsSending) { d.phone?.let { viewModel.sendRemindSms(it, message) } } else null,
                )
                if (d.phone == null) {
                    Text(stringResource(R.string.debts_remind_no_phone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::closeRemind) { Text(stringResource(R.string.close)) } },
    )
}
