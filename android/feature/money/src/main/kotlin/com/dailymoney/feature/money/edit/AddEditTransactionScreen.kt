package com.dailymoney.feature.money.edit

import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.DropdownField
import com.dailymoney.core.designsystem.component.ErrorBanner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
import com.dailymoney.core.designsystem.component.InfoBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.accountIcon
import com.dailymoney.core.designsystem.money.categoryColor
import com.dailymoney.core.designsystem.money.categoryIcon
import com.dailymoney.core.designsystem.theme.MoneyTextStyle
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.TransactionError
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.core.domain.sharing.ApprovalStatus
import com.dailymoney.core.domain.sharing.ShareStatus
import com.dailymoney.core.domain.sharing.SplitMethod
import com.dailymoney.core.domain.sharing.SplitProblem
import com.dailymoney.core.domain.sharing.Visibility
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
    val receipts = rememberReceiptLaunchers(viewModel::addAttachment)
    var scanMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.edit_edit_title else R.string.edit_new_title)) },
                navigationIcon = { BackButton(onClose) },
                actions = {
                    if (!state.isEdit) {
                        Box {
                            IconButton(onClick = { scanMenu = true }) {
                                Icon(Icons.Filled.DocumentScanner, contentDescription = stringResource(R.string.scan_receipt))
                            }
                            DropdownMenu(expanded = scanMenu, onDismissRequest = { scanMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.receipts_take_photo)) }, onClick = { scanMenu = false; receipts.takePhoto() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.receipts_choose_photo)) }, onClick = { scanMenu = false; receipts.choosePhoto() })
                            }
                        }
                    }
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
        // A failed save scrolls to the first field that needs fixing (category may be off screen).
        val amountField = remember { BringIntoViewRequester() }
        val categoryField = remember { BringIntoViewRequester() }
        val accountField = remember { BringIntoViewRequester() }
        val noteField = remember { BringIntoViewRequester() }
        LaunchedEffect(state.invalidAttempts) {
            if (state.invalidAttempts == 0) return@LaunchedEffect
            val e = state.errors
            when {
                TransactionError.AMOUNT_REQUIRED in e || TransactionError.AMOUNT_INVALID in e -> amountField
                TransactionError.CATEGORY_REQUIRED in e -> categoryField
                TransactionError.ACCOUNT_REQUIRED in e || TransactionError.SAME_ACCOUNT in e -> accountField
                TransactionError.NOTE_TOO_LONG in e -> noteField
                else -> null
            }?.bringIntoView()
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
            if (state.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.scan_reading), style = MaterialTheme.typography.bodySmall)
            }
            state.assist?.let { assist ->
                val text = stringResource(
                    when (assist) {
                        AssistMessage.RECEIPT_READ -> R.string.scan_filled
                        AssistMessage.RECEIPT_UNREADABLE -> R.string.scan_unreadable
                        AssistMessage.MESSAGE_READ -> R.string.message_filled
                        AssistMessage.MESSAGE_UNREADABLE -> R.string.message_unreadable
                    },
                )
                if (assist == AssistMessage.RECEIPT_READ || assist == AssistMessage.MESSAGE_READ) {
                    InfoBanner(text, Modifier.clickable(onClick = viewModel::dismissAssist))
                } else {
                    ErrorBanner(text, Modifier.clickable(onClick = viewModel::dismissAssist))
                }
            }
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
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(amountField),
            )

            if (form.type != TransactionType.TRANSFER) {
                DropdownField(
                    modifier = Modifier.bringIntoViewRequester(categoryField),
                    label = stringResource(R.string.edit_category),
                    options = state.categories,
                    selected = state.categories.firstOrNull { it.id == form.categoryId },
                    onSelect = { c -> c?.let { viewModel.setCategory(it.id) } },
                    optionLabel = { it?.name.orEmpty() },
                    optionIcon = { c -> c?.let { CategoryIcon(it) } },
                    placeholder = stringResource(R.string.edit_choose),
                    error = state.errors.firstOrNull { it == TransactionError.CATEGORY_REQUIRED }?.message(),
                )
                state.suggestedCategory?.let { suggested ->
                    AssistChip(
                        onClick = { viewModel.setCategory(suggested.id) },
                        label = { Text(stringResource(R.string.category_suggested, suggested.name)) },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }

            AccountDropdown(
                label = stringResource(if (form.type == TransactionType.TRANSFER) R.string.edit_from_account else R.string.edit_account),
                accounts = state.accounts,
                selectedId = form.accountId,
                onSelect = viewModel::setAccount,
                error = state.errors.firstOrNull { it == TransactionError.ACCOUNT_REQUIRED }?.message(),
                modifier = Modifier.bringIntoViewRequester(accountField),
            )

            if (form.type == TransactionType.TRANSFER) {
                AccountDropdown(
                    label = stringResource(R.string.edit_to_account),
                    accounts = state.accounts.filter { it.id != form.accountId },
                    selectedId = form.toAccountId,
                    onSelect = viewModel::setToAccount,
                    error = state.errors.firstOrNull { it == TransactionError.SAME_ACCOUNT }?.message(),
                )
            }

            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  ${stringResource(R.string.edit_date)}: ${form.date.mediumLabel()}")
            }

            if (form.type != TransactionType.TRANSFER) {
                DropdownField(
                    label = stringResource(R.string.edit_payment_method),
                    options = listOf<PaymentMethod?>(null) + PaymentMethod.entries,
                    selected = form.paymentMethod,
                    onSelect = viewModel::choosePaymentMethod,
                    optionLabel = { it?.label() ?: stringResource(R.string.edit_not_set) },
                )
            }

            if (form.type != TransactionType.TRANSFER && (state.share.groups.isNotEmpty() || form.shareGroupId != null)) {
                ShareSection(state, viewModel)
            }

            if (!state.isEdit) {
                DropdownField(
                    label = stringResource(R.string.edit_repeat),
                    options = listOf<Frequency?>(null) + Frequency.entries,
                    selected = form.repeat,
                    onSelect = viewModel::setRepeat,
                    optionLabel = { it?.label() ?: stringResource(R.string.repeat_never) },
                )
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
                read = viewModel::readAttachment,
                viewableUri = viewModel::viewableUri,
                launchers = receipts,
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
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(noteField),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareSection(state: AddEditUiState, viewModel: AddEditTransactionViewModel) {
    val form = state.form
    val share = state.share
    val groupName = share.group?.name
    SectionLabel(stringResource(R.string.edit_share_title), state.errors.firstOrNull { it == TransactionError.MEMBERS_REQUIRED })
    if (share.locked) {
        Text(
            stringResource(R.string.edit_share_locked, groupName ?: stringResource(R.string.edit_share_unknown_group)),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(form.shareGroupId == null, { viewModel.setShareGroup(null) }, { Text(stringResource(R.string.edit_share_none)) })
            share.groups.forEach { g ->
                FilterChip(
                    selected = form.shareGroupId == g.id,
                    onClick = { viewModel.setShareGroup(g.id) },
                    label = { Text(g.name) },
                    leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
    if (form.shareGroupId == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FilterChip(
            form.shareVisibility == Visibility.ORGANIZATION,
            { viewModel.setShareVisibility(Visibility.ORGANIZATION) },
            { Text(stringResource(R.string.edit_share_everyone)) },
        )
        FilterChip(
            form.shareVisibility == Visibility.SELECTED_MEMBERS,
            { viewModel.setShareVisibility(Visibility.SELECTED_MEMBERS) },
            { Text(stringResource(R.string.edit_share_selected)) },
        )
    }
    if (form.shareVisibility == Visibility.SELECTED_MEMBERS) {
        if (share.others.isEmpty()) {
            Text(stringResource(R.string.edit_share_no_members), style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                share.others.forEach { m ->
                    FilterChip(m.userId in form.shareMembers, { viewModel.toggleShareMember(m.userId) }, { Text(m.name) })
                }
            }
        }
    }
    if (form.type == TransactionType.EXPENSE) SplitSection(state, viewModel)
    val status = when {
        share.status == ShareStatus.PENDING -> R.string.edit_share_pending
        share.status == ShareStatus.FAILED -> R.string.edit_share_failed
        share.approval == ApprovalStatus.PENDING_APPROVAL -> R.string.edit_share_awaiting_approval
        share.approval == ApprovalStatus.REJECTED -> R.string.edit_share_rejected
        else -> R.string.edit_share_hint
    }
    Text(
        stringResource(status),
        style = MaterialTheme.typography.bodySmall,
        color = if (share.status == ShareStatus.FAILED || share.approval == ApprovalStatus.REJECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitSection(state: AddEditUiState, viewModel: AddEditTransactionViewModel) {
    val split = state.form.split
    val share = state.share
    val canSplit = share.group?.canSplit == true
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.split_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!split.on) {
                Text(
                    stringResource(if (canSplit) R.string.split_off_hint else R.string.split_no_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = split.on, onCheckedChange = viewModel::setSplitOn, enabled = canSplit || split.on)
    }
    if (!split.on) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SplitMethod.entries.forEach { m -> FilterChip(split.method == m, { viewModel.setSplitMethod(m) }, { Text(m.label()) }) }
    }
    share.splitCandidates.forEach { person ->
        val included = person.userId in split.people
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = included, onCheckedChange = { viewModel.toggleSplitPerson(person.userId) })
            Text(
                if (person.isMe) stringResource(R.string.split_you) else person.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (included && split.method != SplitMethod.EQUAL) {
                OutlinedTextField(
                    value = split.values[person.userId].orEmpty(),
                    onValueChange = { viewModel.setSplitValue(person.userId, it) },
                    label = { Text(split.method.valueLabel()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp).padding(end = Spacing.sm),
                )
            }
            share.preview[person.userId]?.let { MoneyText(it, state.currency, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    val problem = share.splitProblem
    if (problem != null || TransactionError.SPLIT_INVALID in state.errors) {
        Text(
            problem?.message() ?: stringResource(R.string.edit_err_split),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SplitMethod.label() = stringResource(
    when (this) {
        SplitMethod.EQUAL -> R.string.split_equal
        SplitMethod.PERCENT -> R.string.split_percent
        SplitMethod.AMOUNT -> R.string.split_amount
        SplitMethod.SHARES -> R.string.split_shares
    },
)

@Composable
private fun SplitMethod.valueLabel() = stringResource(
    when (this) {
        SplitMethod.PERCENT -> R.string.split_value_percent
        SplitMethod.SHARES -> R.string.split_value_shares
        else -> R.string.split_value_amount
    },
)

@Composable
private fun SplitProblem.message() = stringResource(
    when (this) {
        SplitProblem.NOBODY -> R.string.split_problem_nobody
        SplitProblem.TOO_MANY -> R.string.split_problem_too_many
        SplitProblem.DUPLICATE, SplitProblem.NOT_POSITIVE -> R.string.split_problem_not_positive
        SplitProblem.PERCENT_NOT_100 -> R.string.split_problem_percent
        SplitProblem.AMOUNT_NOT_TOTAL, SplitProblem.AMOUNT_NOT_WHOLE -> R.string.split_problem_amount
    },
)

@Composable
private fun SectionLabel(text: String, error: TransactionError?) {
    Column {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) Text(error.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun AccountDropdown(
    label: String,
    accounts: List<Account>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    DropdownField(
        modifier = modifier,
        label = label,
        options = accounts,
        selected = accounts.firstOrNull { it.id == selectedId },
        onSelect = { a -> a?.let { onSelect(it.id) } },
        optionLabel = { it?.name.orEmpty() },
        optionIcon = { a -> a?.let { Icon(accountIcon(it.type), contentDescription = null, modifier = Modifier.size(20.dp)) } },
        placeholder = stringResource(R.string.edit_choose),
        error = error,
    )
}

@Composable
private fun CategoryIcon(category: Category) {
    Icon(categoryIcon(category.iconKey), contentDescription = null, tint = categoryColor(category.colorIndex), modifier = Modifier.size(20.dp))
}
