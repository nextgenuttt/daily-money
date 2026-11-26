package com.dailymoney.feature.groups

import com.dailymoney.core.domain.config.AppConfig
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.dailymoney.core.domain.sharing.SmsSender
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.money.AmountStat
import com.dailymoney.core.designsystem.money.CategoryBadge
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.MonthSelector
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.designsystem.theme.currentLocale
import com.dailymoney.core.domain.money.DateRange
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.sharing.ApprovalStatus
import com.dailymoney.core.domain.sharing.Approvals
import com.dailymoney.core.domain.sharing.Balances
import com.dailymoney.core.domain.sharing.Breakdown
import com.dailymoney.core.domain.sharing.GroupPerson
import com.dailymoney.core.domain.sharing.SettleMethod
import com.dailymoney.core.domain.sharing.SettleResult
import com.dailymoney.core.domain.sharing.Settlement
import com.dailymoney.core.domain.sharing.SettlementRepository
import com.dailymoney.core.domain.sharing.SettlementStatus
import com.dailymoney.core.domain.sharing.GroupDashboard
import com.dailymoney.core.domain.sharing.SharedExpense
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import com.dailymoney.core.domain.sharing.SharedGroup
import com.dailymoney.core.domain.sharing.SyncScheduler
import com.dailymoney.core.domain.sharing.Visibility
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.YearMonth
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

data class BalanceRow(val person: GroupPerson, val netMinor: Long)

data class PendingPayment(val settlement: Settlement, val otherName: String, val incoming: Boolean)

/** [iOwe]: I'm paying them; otherwise I'm recording money they gave me. */
data class SettleDialog(
    val person: GroupPerson,
    val iOwe: Boolean,
    val amountText: String,
    val method: SettleMethod = SettleMethod.UPI,
    val note: String = "",
)

data class RejectDialog(val expense: SharedExpense, val reason: String = "")

data class ApprovalUi(val reject: RejectDialog? = null, val deciding: Boolean = false, val error: GroupError? = null)

data class GroupExpensesUiState(
    val loading: Boolean = true,
    val group: SharedGroup? = null,
    val meId: String? = null,
    val month: YearMonth,
    val isCurrentMonth: Boolean = true,
    val dashboard: GroupDashboard? = null,
    val expenses: List<SharedExpense> = emptyList(),
    val balances: List<BalanceRow> = emptyList(),
    val pending: List<PendingPayment> = emptyList(),
    val history: List<PendingPayment> = emptyList(),
    val dialog: SettleDialog? = null,
    val working: Boolean = false,
    val error: SettleResult? = null,
    /** Pending expenses this person should approve or reject, any month. */
    val waiting: List<SharedExpense> = emptyList(),
    val approval: ApprovalUi = ApprovalUi(),
)

@HiltViewModel
class GroupExpensesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: SharedExpenseRepository,
    private val settlements: SettlementRepository,
    private val smsSender: SmsSender,
    config: AppConfig,
    private val groups: GroupRepository,
    private val sync: SyncScheduler,
    private val clock: Clock,
) : ViewModel() {

    private val groupId = savedStateHandle.toRoute<GroupExpensesRoute>().groupId
    private val month = MutableStateFlow(YearMonth.now(clock))
    private val ui = MutableStateFlow(Triple<SettleDialog?, Boolean, SettleResult?>(null, false, null))
    private val approvalUi = MutableStateFlow(ApprovalUi())

    val state: StateFlow<GroupExpensesUiState> = combine(
        repository.observeGroups(), repository.observeExpenses(groupId), repository.observeSettlements(groupId), repository.observeMe(),
        combine(month, ui, approvalUi, ::Triple),
    ) { groups, all, payments, me, (m, extra, approvals) ->
        val group = groups.firstOrNull { it.id == groupId }
        val range = DateRange.month(m, clock.zone)
        val people = group?.members.orEmpty().associateBy { it.userId }
        fun person(id: String) = people[id] ?: GroupPerson(id, all.firstOrNull { it.creatorId == id }?.creatorName ?: "", null, false)
        val mine = me?.userId
        val involvingMe = payments.filter { it.payerId == mine || it.receiverId == mine }.map { s ->
            val incoming = s.receiverId == mine
            PendingPayment(s, person(if (incoming) s.payerId else s.receiverId).name, incoming)
        }
        GroupExpensesUiState(
            loading = false,
            group = group,
            meId = mine,
            month = m,
            isCurrentMonth = m == YearMonth.now(clock),
            dashboard = group?.let { GroupDashboard.build(all, range, it.currency) },
            expenses = all.filter { it.occurredAt in range },
            balances = if (group == null || mine == null) emptyList()
            else Balances.compute(mine, all, payments, group.currency).map { BalanceRow(person(it.userId), it.netMinor) },
            pending = involvingMe.filter { it.settlement.status == SettlementStatus.REQUESTED },
            history = involvingMe.filter { it.settlement.status != SettlementStatus.REQUESTED }.take(HISTORY),
            dialog = extra.first,
            working = extra.second,
            error = extra.third,
            waiting = if (group == null || mine == null) emptyList() else Approvals.waitingFor(mine, group, all),
            approval = approvals,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupExpensesUiState(month = month.value))

    fun openSettle(row: BalanceRow) {
        val currency = state.value.group?.currency ?: return
        ui.value = Triple(SettleDialog(row.person, iOwe = row.netMinor < 0, MoneyFormatter.toInput(kotlin.math.abs(row.netMinor), currency)), false, null)
    }

    fun updateSettle(block: (SettleDialog) -> SettleDialog) = ui.update { (d, w, _) -> Triple(d?.let(block), w, null) }
    fun closeSettle() = ui.update { Triple(null, false, null) }
    fun dismissError() = ui.update { (d, w, _) -> Triple(d, w, null) }

    fun recordSettle() {
        val s = state.value
        val d = s.dialog ?: return
        val me = s.meId ?: return
        val group = s.group ?: return
        val amount = MoneyParser.parse(d.amountText, group.currency)?.takeIf { it > 0 } ?: return
        act {
            if (d.iOwe) settlements.record(groupId, me, d.person.userId, amount, group.currency, d.method, d.note)
            else settlements.record(groupId, d.person.userId, me, amount, group.currency, d.method, d.note)
        }
    }

    private val _remind = MutableStateFlow<RemindUi?>(null)
    val remind: StateFlow<RemindUi?> = _remind

    fun openRemind(row: BalanceRow) {
        if (row.netMinor > 0) _remind.value = RemindUi(row.person, row.netMinor)
    }

    fun closeRemind() {
        _remind.value = null
    }

    val canSendSms: Boolean get() = smsSender.supported
    val payPageUrl: String = config.payPageUrl

    /** From this phone's SIM, to the member's saved number. */
    fun sendRemindSms(text: String) {
        val r = _remind.value ?: return
        val phone = r.person.phone ?: return
        _remind.value = r.copy(smsSending = true, smsResult = null)
        viewModelScope.launch {
            val result = smsSender.send(phone, text)
            _remind.update { it?.copy(smsSending = false, smsResult = result) }
        }
    }

    fun sendRemind() {
        val r = _remind.value ?: return
        val currency = state.value.group?.currency ?: return
        _remind.value = r.copy(sending = true, result = null)
        viewModelScope.launch {
            val result = settlements.remind(groupId, r.person.userId, r.amountMinor, currency)
            _remind.update { it?.copy(sending = false, result = result) }
        }
    }

    fun confirm(p: PendingPayment) = act(closeDialog = false) { settlements.confirm(p.settlement.id) }
    fun cancel(p: PendingPayment) = act(closeDialog = false) { settlements.cancel(p.settlement.id) }

    private fun act(closeDialog: Boolean = true, block: suspend () -> SettleResult) {
        ui.update { (d, _, _) -> Triple(d, true, null) }
        viewModelScope.launch {
            val result = block()
            ui.update { (d, _, _) ->
                if (result == SettleResult.Ok) Triple(if (closeDialog) null else d, false, null) else Triple(d, false, result)
            }
        }
    }

    fun approve(e: SharedExpense) = decide { groups.approve(e.id, e.version) }
    fun askReject(e: SharedExpense) = approvalUi.update { ApprovalUi(reject = RejectDialog(e)) }
    fun setRejectReason(text: String) = approvalUi.update { it.copy(reject = it.reject?.copy(reason = text.take(300)), error = null) }
    fun closeReject() = approvalUi.update { ApprovalUi() }

    fun reject() {
        val dialog = approvalUi.value.reject ?: return
        if (dialog.reason.isBlank()) return
        decide { groups.reject(dialog.expense.id, dialog.expense.version, dialog.reason) }
    }

    private fun decide(block: suspend () -> GroupResult<Unit>) {
        approvalUi.update { it.copy(deciding = true, error = null) }
        viewModelScope.launch {
            when (val r = block()) {
                is GroupResult.Ok -> approvalUi.value = ApprovalUi()
                is GroupResult.Err -> approvalUi.update { it.copy(deciding = false, error = r.error) }
            }
        }
    }

    init {
        sync.requestSync()
    }

    private companion object {
        const val HISTORY = 20
    }

    fun previous() = month.update { it.minusMonths(1) }
    fun next() = month.update { if (it < YearMonth.now(clock)) it.plusMonths(1) else it }
    fun refresh() = sync.requestSync()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupExpensesScreen(
    onBack: () -> Unit,
    onApprovalRules: (String) -> Unit,
    onMessages: (expenseId: String, title: String) -> Unit,
    viewModel: GroupExpensesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val group = state.group
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shared_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    group?.let { g ->
                        IconButton(onClick = { onApprovalRules(g.id) }) { Icon(Icons.Filled.Gavel, contentDescription = stringResource(R.string.approval_rules)) }
                    }
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.shared_refresh)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            return@Scaffold
        }
        SettleHost(state, viewModel)
        state.approval.reject?.let { RejectReasonDialog(it, state.approval, viewModel) }
        if (group == null) {
            Text(stringResource(R.string.shared_not_synced), Modifier.padding(padding).padding(Spacing.screenGutter))
            return@Scaffold
        }
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale())
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                MonthSelector(
                    label = state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", currentLocale())),
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    previousLabel = stringResource(R.string.shared_previous_month),
                    nextLabel = stringResource(R.string.shared_next_month),
                    nextEnabled = !state.isCurrentMonth,
                )
            }
            if (state.waiting.isNotEmpty()) {
                item { WaitingForMe(state, group, dateFormat, viewModel) }
            }
            val dashboard = state.dashboard
            if (dashboard != null && group.canReport) {
                item { Summary(dashboard, group.currency) }
                if (dashboard.byCategory.isNotEmpty()) {
                    item { Breakdowns(stringResource(R.string.shared_by_category), dashboard.byCategory, dashboard.spentMinor, group.currency) }
                }
                if (dashboard.byMember.size > 1) {
                    item { Breakdowns(stringResource(R.string.shared_by_member), dashboard.byMember, dashboard.spentMinor, group.currency) }
                }
            }
            item {
                BalancesSection(state, onSettle = viewModel::openSettle, onRemind = viewModel::openRemind, onConfirm = viewModel::confirm, onCancel = viewModel::cancel)
            }
            if (!group.canView) {
                item { Text(stringResource(R.string.shared_only_yours), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                Text(
                    stringResource(R.string.shared_expenses_header),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Spacing.md),
                )
            }
            if (state.expenses.isEmpty()) {
                item { Text(stringResource(R.string.shared_empty), Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.expenses, key = { it.id }) { e ->
                val who = if (e.mine) stringResource(R.string.shared_you) else e.creatorName
                val visibility = if (e.visibility == Visibility.SELECTED_MEMBERS) " · ${stringResource(R.string.shared_selected_only)}" else ""
                val myShare = e.shares.firstOrNull { it.userId == state.meId }?.takeIf { !e.mine }
                val splitNote = when {
                    myShare != null -> " · ${stringResource(R.string.split_your_share, MoneyFormatter.format(myShare.shareMinor, e.currency))}"
                    e.shares.isNotEmpty() -> " · ${stringResource(R.string.split_ways, e.shares.size)}"
                    else -> ""
                }
                val approvalNote = when (e.approval) {
                    ApprovalStatus.PENDING_APPROVAL -> " · ${stringResource(R.string.approval_waiting)}"
                    ApprovalStatus.REJECTED -> " · " + (Approvals.current(e).firstOrNull { !it.approved }?.reason
                        ?.let { stringResource(R.string.approval_rejected_because, it) } ?: stringResource(R.string.approval_rejected))
                    ApprovalStatus.APPROVED -> ""
                }
                val title = e.categoryName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shared_uncategorized)
                ListItem(
                    modifier = Modifier.clickable { onMessages(e.id, title) },
                    headlineContent = { Text(title) },
                    supportingContent = {
                        Text(listOfNotNull(who, e.occurredAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dateFormat), e.note).joinToString(" · ") + visibility + splitNote + approvalNote)
                    },
                    leadingContent = { CategoryBadge(e.categoryIcon, 5, size = 36.dp) },
                    trailingContent = { MoneyText(e.amountMinor, e.currency, type = e.type) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}

@Composable
private fun WaitingForMe(state: GroupExpensesUiState, group: SharedGroup, dateFormat: DateTimeFormatter, viewModel: GroupExpensesViewModel) {
    OutlinedCard(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.approval_waiting_for_you, state.waiting.size), style = MaterialTheme.typography.titleMedium)
            state.waiting.forEach { e ->
                val done = Approvals.current(e).count { it.approved }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.creatorName} · ${e.categoryName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shared_uncategorized)}")
                        Text(
                            listOfNotNull(
                                e.occurredAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dateFormat),
                                e.note,
                                stringResource(R.string.approval_progress, done, group.requiredApprovals).takeIf { group.requiredApprovals > 1 },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MoneyText(e.amountMinor, e.currency, type = e.type)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { viewModel.approve(e) }, enabled = !state.approval.deciding) { Text(stringResource(R.string.approval_approve)) }
                    TextButton(onClick = { viewModel.askReject(e) }, enabled = !state.approval.deciding) {
                        Text(stringResource(R.string.approval_reject), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (state.approval.reject == null) state.approval.error?.let { ErrorBanner(it.message()) }
        }
    }
}

@Composable
private fun RejectReasonDialog(dialog: RejectDialog, ui: ApprovalUi, viewModel: GroupExpensesViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeReject,
        title = { Text(stringResource(R.string.approval_reject_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(dialog.reason, viewModel::setRejectReason, label = { Text(stringResource(R.string.approval_reason)) })
                ui.error?.let { ErrorBanner(it.message()) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::reject, enabled = !ui.deciding && dialog.reason.isNotBlank()) {
                Text(stringResource(R.string.approval_reject), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeReject) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettleHost(state: GroupExpensesUiState, viewModel: GroupExpensesViewModel) {
    state.dialog?.let { SettleUpDialog(it, state, viewModel) }
    val remind by viewModel.remind.collectAsStateWithLifecycle()
    remind?.let { r ->
        val group = state.group
        if (group != null) {
            RemindDialog(
                remind = r,
                groupName = group.name,
                currency = group.currency,
                myUpiId = group.members.firstOrNull { it.isMe }?.upiId,
                myName = group.members.firstOrNull { it.isMe }?.name,
                payPageUrl = viewModel.payPageUrl,
                canSendSms = viewModel.canSendSms,
                onSendInApp = viewModel::sendRemind,
                onSendSms = viewModel::sendRemindSms,
                onClose = viewModel::closeRemind,
            )
        }
    }
}

@Composable
private fun Summary(d: GroupDashboard, currency: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                AmountStat(stringResource(R.string.shared_spent), d.spentMinor, currency)
                if (d.receivedMinor > 0) AmountStat(stringResource(R.string.shared_received), d.receivedMinor, currency)
                if (d.pendingMinor > 0) AmountStat(stringResource(R.string.approval_pending_total), d.pendingMinor, currency)
            }
            if (d.otherCurrencyCount > 0) {
                Text(
                    stringResource(R.string.shared_other_currency, d.otherCurrencyCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Breakdowns(title: String, rows: List<Breakdown>, total: Long, currency: String) {
    val uncategorized = stringResource(R.string.shared_uncategorized)
    Column(Modifier.padding(top = Spacing.md, bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        rows.forEach { row ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label.ifBlank { uncategorized }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = Spacing.sm))
                    MoneyText(row.amountMinor, currency, style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(
                    progress = { if (total > 0) row.amountMinor.toFloat() / total else 0f },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}
