package com.dailymoney.feature.money.planning

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
import androidx.hilt.navigation.compose.hiltViewModel
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
)

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
) {
    val visible get() = debts.filter { it.settled == showSettled }
    val summary get() = PlanningMath.debtSummary(debts)
}

@HiltViewModel
class DebtsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DebtRepository,
    config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    val debtId: String? = runCatching { savedStateHandle.toRoute<DebtDetailRoute>().debtId }.getOrNull()
    private val currency = config.defaultCurrency
    private val ui = MutableStateFlow(DebtsUiState(currency = currency, today = LocalDate.now(clock)))

    val state: StateFlow<DebtsUiState> = combine(ui, repository.observe()) { u, d -> u.copy(loading = false, debts = d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    fun setShowSettled(v: Boolean) = ui.update { it.copy(showSettled = v) }

    fun add() = ui.update { it.copy(editor = DebtEditor(null, "", DebtDirection.LENT, "", LocalDate.now(clock), null, "")) }
    fun edit(d: Debt) = ui.update {
        it.copy(editor = DebtEditor(d.id, d.person, d.direction, MoneyFormatter.toInput(d.principalMinor, d.currency), d.startDate, d.dueDate, d.note.orEmpty()))
    }
    fun dismissEditor() = ui.update { it.copy(editor = null) }
    fun updateEditor(block: (DebtEditor) -> DebtEditor) = ui.update { it.copy(editor = it.editor?.let(block)) }

    fun saveDebt() {
        val e = ui.value.editor ?: return
        val (amount, error) = parsePositiveAmount(e.amountText, currency)
        val personError = e.person.isBlank() || e.person.trim().length > 40
        if (error != null || personError) {
            updateEditor { it.copy(amountError = error, personError = personError) }
            return
        }
        viewModelScope.launch {
            repository.save(Debt(e.id ?: Ids.newId(), e.person.trim(), e.direction, amount!!, currency, e.startDate, e.dueDate, e.note.trim().ifEmpty { null }))
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
                )
                AmountField(editor.amountText, { v -> viewModel.updateEditor { it.copy(amountText = v, amountError = null) } }, stringResource(R.string.edit_amount), currency, editor.amountError)
                DateField(stringResource(R.string.edit_date), editor.startDate, { d -> viewModel.updateEditor { it.copy(startDate = d) } })
                DateField(
                    stringResource(R.string.debts_due_date), editor.dueDate, { d -> viewModel.updateEditor { it.copy(dueDate = d) } },
                    placeholder = stringResource(R.string.optional), onClear = { viewModel.updateEditor { it.copy(dueDate = null) } },
                )
                OutlinedTextField(editor.note, { v -> viewModel.updateEditor { it.copy(note = v.take(200)) } }, label = { Text(stringResource(R.string.edit_note)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveDebt) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissEditor) { Text(stringResource(R.string.cancel)) } },
    )
}
