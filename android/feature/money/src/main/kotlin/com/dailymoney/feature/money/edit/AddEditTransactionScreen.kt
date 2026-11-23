package com.dailymoney.feature.money.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.money.accountIcon
import com.dailymoney.core.designsystem.money.categoryColor
import com.dailymoney.core.designsystem.money.categoryIcon
import com.dailymoney.core.designsystem.theme.MoneyTextStyle
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.TransactionError
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.everyLabel
import com.dailymoney.feature.money.common.label
import com.dailymoney.feature.money.common.mediumLabel
import com.dailymoney.feature.money.common.message
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddEditTransactionScreen(
    onClose: () -> Unit,
    viewModel: AddEditTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.closeEvents.collect { onClose() } }
    val form = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.edit_edit_title else R.string.edit_new_title)) },
                navigationIcon = { BackButton(onClose) },
                actions = {
                    if (state.isEdit && !state.notFound) {
                        IconButton(onClick = viewModel::askDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_delete))
                        }
                    }
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
        if (state.notFound) {
            ErrorBanner(stringResource(R.string.edit_not_found), Modifier.padding(padding).padding(Spacing.md))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TransactionType.entries.forEachIndexed { i, type ->
                    SegmentedButton(
                        selected = form.type == type,
                        onClick = { viewModel.setType(type) },
                        shape = SegmentedButtonDefaults.itemShape(i, TransactionType.entries.size),
                    ) { Text(type.label()) }
                }
            }

            OutlinedTextField(
                value = form.amountText,
                onValueChange = viewModel::setAmount,
                label = { Text(stringResource(R.string.edit_amount)) },
                prefix = { Text(Currencies.symbol(state.currency)) },
                textStyle = MaterialTheme.typography.headlineSmall.merge(MoneyTextStyle),
                singleLine = true,
                isError = state.errors.any { it == TransactionError.AMOUNT_REQUIRED || it == TransactionError.AMOUNT_INVALID },
                supportingText = state.errors.firstOrNull { it == TransactionError.AMOUNT_REQUIRED || it == TransactionError.AMOUNT_INVALID }
                    ?.let { { Text(it.message()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            if (form.type != TransactionType.TRANSFER) {
                SectionLabel(stringResource(R.string.edit_category), state.errors.takeIf { TransactionError.CATEGORY_REQUIRED in it }?.let { TransactionError.CATEGORY_REQUIRED })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    state.categories.forEach { category ->
                        FilterChip(
                            selected = form.categoryId == category.id,
                            onClick = { viewModel.setCategory(category.id) },
                            label = { Text(category.name) },
                            leadingIcon = {
                                Icon(
                                    categoryIcon(category.iconKey),
                                    contentDescription = null,
                                    tint = categoryColor(category.colorIndex),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }

            SectionLabel(
                stringResource(if (form.type == TransactionType.TRANSFER) R.string.edit_from_account else R.string.edit_account),
                state.errors.firstOrNull { it == TransactionError.ACCOUNT_REQUIRED },
            )
            AccountChips(state.accounts, form.accountId, viewModel::setAccount)

            if (form.type == TransactionType.TRANSFER) {
                SectionLabel(stringResource(R.string.edit_to_account), state.errors.firstOrNull { it == TransactionError.SAME_ACCOUNT })
                AccountChips(state.accounts.filter { it.id != form.accountId }, form.toAccountId, viewModel::setToAccount)
            }

            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  ${stringResource(R.string.edit_date)}: ${form.date.mediumLabel()}")
            }

            if (form.type != TransactionType.TRANSFER) {
                SectionLabel(stringResource(R.string.edit_payment_method), null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PaymentMethod.entries.forEach { method ->
                        FilterChip(
                            selected = form.paymentMethod == method,
                            onClick = { viewModel.setPaymentMethod(method) },
                            label = { Text(method.label()) },
                        )
                    }
                }
            }

            if (!state.isEdit) {
                SectionLabel(stringResource(R.string.edit_repeat), null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(form.repeat == null, { viewModel.setRepeat(null) }, { Text(stringResource(R.string.repeat_never)) })
                    Frequency.entries.forEach { f ->
                        FilterChip(form.repeat == f, { viewModel.setRepeat(f) }, { Text(f.label()) })
                    }
                }
                if (form.repeat != null) {
                    Text(
                        stringResource(R.string.repeat_hint, form.repeat.everyLabel()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AttachmentSection(
                attachments = state.attachments,
                pathOf = viewModel::attachmentPath,
                shareUriOf = viewModel::attachmentShareUri,
                onAdd = viewModel::addAttachment,
                onRemove = viewModel::removeAttachment,
                message = state.attachmentMessage?.let {
                    stringResource(
                        when (it) {
                            AttachmentMessage.TOO_LARGE -> R.string.receipts_too_large
                            AttachmentMessage.UNSUPPORTED -> R.string.receipts_unsupported
                            AttachmentMessage.FAILED -> R.string.receipts_failed
                        },
                    )
                },
            )

            OutlinedTextField(
                value = form.note,
                onValueChange = viewModel::setNote,
                label = { Text(stringResource(R.string.edit_note)) },
                isError = TransactionError.NOTE_TOO_LONG in state.errors,
                supportingText = { Text("${form.note.length}/200") },
                modifier = Modifier.fillMaxWidth(),
            )

            PrimaryButton(
                text = stringResource(R.string.edit_save),
                onClick = viewModel::save,
                loading = state.saving,
                modifier = Modifier.padding(bottom = Spacing.lg),
            )
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        viewModel.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.edit_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.edit_cancel)) } },
        ) { DatePicker(pickerState) }
    }

    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.edit_delete_title)) },
            text = { Text(stringResource(R.string.edit_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) {
                    Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.edit_cancel)) } },
        )
    }
}

@Composable
private fun SectionLabel(text: String, error: TransactionError?) {
    Column {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) Text(error.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountChips(accounts: List<Account>, selectedId: String?, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        accounts.forEach { account ->
            FilterChip(
                selected = selectedId == account.id,
                onClick = { onSelect(account.id) },
                label = { Text(account.name) },
                leadingIcon = { Icon(accountIcon(account.type), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}
