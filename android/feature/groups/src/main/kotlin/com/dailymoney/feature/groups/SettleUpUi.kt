package com.dailymoney.feature.groups

import com.dailymoney.core.domain.sharing.UpiPayment
import com.dailymoney.core.designsystem.component.openUpiApp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.sharing.SettleMethod
import com.dailymoney.core.domain.sharing.SettleResult
import com.dailymoney.core.domain.sharing.SettlementStatus

@Composable
internal fun BalancesSection(
    state: GroupExpensesUiState,
    onSettle: (BalanceRow) -> Unit,
    onRemind: (BalanceRow) -> Unit,
    onConfirm: (PendingPayment) -> Unit,
    onCancel: (PendingPayment) -> Unit,
) {
    val currency = state.group?.currency ?: return
    if (state.balances.isEmpty() && state.pending.isEmpty() && state.history.isEmpty()) return
    OutlinedCard(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.settle_balances), style = MaterialTheme.typography.titleMedium)
            if (state.balances.isEmpty()) {
                Text(stringResource(R.string.settle_all_square), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.balances.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val amount = MoneyFormatter.format(kotlin.math.abs(row.netMinor), currency)
                    Text(
                        if (row.netMinor > 0) stringResource(R.string.settle_owes_you, row.person.name, amount)
                        else stringResource(R.string.settle_you_owe, row.person.name, amount),
                        modifier = Modifier.weight(1f),
                        color = if (row.netMinor > 0) LocalStatusColors.current.income else MaterialTheme.colorScheme.onSurface,
                    )
                    // Someone who owes me can be nudged; settling stays available either way.
                    if (row.netMinor > 0) TextButton(onClick = { onRemind(row) }, enabled = !state.working) { Text(stringResource(R.string.remind_action)) }
                    TextButton(onClick = { onSettle(row) }, enabled = !state.working) { Text(stringResource(R.string.settle_up)) }
                }
            }
            state.pending.forEach { p ->
                val amount = MoneyFormatter.format(p.settlement.amountMinor, p.settlement.currency)
                Text(
                    if (p.incoming) stringResource(R.string.settle_incoming, p.otherName, amount)
                    else stringResource(R.string.settle_outgoing, p.otherName, amount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (p.incoming) {
                        TextButton(onClick = { onConfirm(p) }, enabled = !state.working) { Text(stringResource(R.string.settle_confirm)) }
                        TextButton(onClick = { onCancel(p) }, enabled = !state.working) { Text(stringResource(R.string.settle_not_received)) }
                    } else {
                        TextButton(onClick = { onCancel(p) }, enabled = !state.working) { Text(stringResource(R.string.settle_withdraw)) }
                    }
                }
            }
            if (state.dialog == null) state.error?.let { ErrorBanner(it.message()) }
            if (state.history.isNotEmpty()) {
                Text(stringResource(R.string.settle_history), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.sm))
                state.history.forEach { p ->
                    val amount = MoneyFormatter.format(p.settlement.amountMinor, p.settlement.currency)
                    val text = if (p.incoming) stringResource(R.string.settle_history_in, p.otherName, amount) else stringResource(R.string.settle_history_out, p.otherName, amount)
                    val status = if (p.settlement.status == SettlementStatus.CANCELLED) " · ${stringResource(R.string.settle_cancelled)}" else ""
                    Text(text + status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettleUpDialog(dialog: SettleDialog, state: GroupExpensesUiState, viewModel: GroupExpensesViewModel) {
    val currency = state.group?.currency ?: return
    val context = LocalContext.current
    var upiFailed by remember { mutableStateOf(false) }
    val amount = MoneyParser.parse(dialog.amountText, currency)
    val upiId = dialog.person.upiId
    AlertDialog(
        onDismissRequest = viewModel::closeSettle,
        title = {
            Text(
                if (dialog.iOwe) stringResource(R.string.settle_pay_title, dialog.person.name)
                else stringResource(R.string.settle_receive_title, dialog.person.name),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = dialog.amountText,
                    onValueChange = { v -> viewModel.updateSettle { it.copy(amountText = v.filter { c -> c.isDigit() || c == '.' }.take(14)) } },
                    label = { Text(stringResource(R.string.settle_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SettleMethod.entries.forEach { m ->
                        FilterChip(dialog.method == m, { viewModel.updateSettle { it.copy(method = m) } }, { Text(m.label()) })
                    }
                }
                OutlinedTextField(
                    value = dialog.note,
                    onValueChange = { v -> viewModel.updateSettle { it.copy(note = v.take(200)) } },
                    label = { Text(stringResource(R.string.settle_note)) },
                    singleLine = true,
                )
                if (dialog.iOwe && upiId != null && amount != null && amount > 0) {
                    OutlinedButton(onClick = {
                        val opened = openUpiApp(context, UpiPayment.appUri(upiId, dialog.person.name, amount, state.group.name))
                        if (opened) viewModel.updateSettle { it.copy(method = SettleMethod.UPI) }
                        upiFailed = !opened
                    }) { Text(stringResource(R.string.settle_open_upi)) }
                }
                Text(
                    stringResource(if (dialog.iOwe) R.string.settle_pay_hint else R.string.settle_receive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (upiFailed) ErrorBanner(stringResource(R.string.settle_no_upi_app))
                state.error?.let { ErrorBanner(it.message()) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::recordSettle, enabled = !state.working && amount != null && amount > 0) {
                Text(stringResource(if (dialog.iOwe) R.string.settle_i_paid else R.string.settle_i_received))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeSettle) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettleMethod.label() = stringResource(
    when (this) {
        SettleMethod.CASH -> R.string.settle_cash
        SettleMethod.UPI -> R.string.settle_upi
        SettleMethod.BANK -> R.string.settle_bank
        SettleMethod.OTHER -> R.string.settle_other
    },
)

@Composable
internal fun SettleResult.message() = stringResource(
    when (this) {
        SettleResult.Offline -> R.string.settle_err_offline
        SettleResult.BackendDisabled -> R.string.settle_err_disabled
        is SettleResult.Refused -> when (code) {
            "ALREADY_DECIDED" -> R.string.settle_err_decided
            "PERMISSION_DENIED", "NOT_FOUND" -> R.string.settle_err_denied
            else -> R.string.settle_err_generic
        }
        SettleResult.Ok -> R.string.settle_err_generic
    },
)
