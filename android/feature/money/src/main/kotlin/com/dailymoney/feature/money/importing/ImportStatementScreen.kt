package com.dailymoney.feature.money.importing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.InfoBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.core.domain.smart.ColumnMapping
import com.dailymoney.core.domain.smart.ImportCandidate
import com.dailymoney.core.domain.smart.ImportStatus
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.mediumLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportStatementScreen(onBack: () -> Unit, viewModel: ImportStatementViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.onFile(it.toString()) } }
    val openPicker = { pick.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.step) {
                ImportStep.PICK -> PickStep(state.problem, openPicker)
                ImportStep.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                ImportStep.MAPPING -> MappingStep(state, viewModel)
                ImportStep.PREVIEW -> PreviewStep(state, viewModel)
                ImportStep.DONE -> EmptyState(
                    Icons.Filled.UploadFile,
                    pluralStringResource(R.plurals.import_done, state.imported, state.imported),
                    stringResource(R.string.import_done_body),
                    action = { PrimaryButton(stringResource(R.string.import_finish), onClick = onBack) },
                )
            }
        }
    }
}

@Composable
private fun PickStep(problem: ImportProblem?, openPicker: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.screenGutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        problem?.let {
            ErrorBanner(stringResource(if (it == ImportProblem.UNREADABLE) R.string.import_unreadable else R.string.import_no_rows))
        }
        EmptyState(
            Icons.Filled.UploadFile,
            stringResource(R.string.import_pick_title),
            stringResource(R.string.import_pick_body),
            action = { PrimaryButton(stringResource(R.string.import_pick), onClick = openPicker) },
        )
        Text(stringResource(R.string.import_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown when we can't tell the columns apart: the user says which column is which. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MappingStep(state: ImportUiState, viewModel: ImportStatementViewModel) {
    var mapping by remember(state.mapping) { mutableStateOf(state.mapping ?: ColumnMapping(0, -1, -1)) }
    var split by remember(state.mapping) { mutableStateOf(state.mapping?.amount == null && state.mapping?.debit != null) }
    val columns = state.columns
    Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.screenGutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (state.problem == ImportProblem.NO_ROWS) ErrorBanner(stringResource(R.string.import_no_rows))
        InfoBanner(stringResource(R.string.import_mapping_hint))
        ColumnPicker(stringResource(R.string.import_col_date), columns, mapping.date) { mapping = mapping.copy(date = it) }
        ColumnPicker(stringResource(R.string.import_col_description), columns, mapping.description) { mapping = mapping.copy(description = it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(!split, { split = false; mapping = mapping.copy(debit = null, credit = null) }, { Text(stringResource(R.string.import_one_amount)) })
            FilterChip(split, { split = true; mapping = mapping.copy(amount = null, drCr = null) }, { Text(stringResource(R.string.import_two_amounts)) })
        }
        if (split) {
            ColumnPicker(stringResource(R.string.import_col_debit), columns, mapping.debit ?: -1) { mapping = mapping.copy(debit = it) }
            ColumnPicker(stringResource(R.string.import_col_credit), columns, mapping.credit ?: -1) { mapping = mapping.copy(credit = it) }
        } else {
            ColumnPicker(stringResource(R.string.import_col_amount), columns, mapping.amount ?: -1) { mapping = mapping.copy(amount = it) }
        }
        PrimaryButton(stringResource(R.string.import_continue), onClick = { viewModel.useMapping(mapping) }, enabled = mapping.isComplete)
    }
}

@Composable
private fun ColumnPicker(label: String, columns: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${columns.getOrNull(selected)?.ifBlank { stringResource(R.string.import_col_n, selected + 1) } ?: stringResource(R.string.import_choose)}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            columns.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text(name.ifBlank { stringResource(R.string.import_col_n, i + 1) }) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewStep(state: ImportUiState, viewModel: ImportStatementViewModel) {
    val counts = state.candidates.groupingBy { it.status }.eachCount()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Spacing.md)) {
            item {
                Column(Modifier.padding(horizontal = Spacing.screenGutter, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.import_into), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        state.accounts.forEach { a -> FilterChip(a.id == state.accountId, { viewModel.selectAccount(a.id) }, { Text(a.name) }) }
                    }
                    Text(
                        stringResource(
                            R.string.import_summary,
                            counts[ImportStatus.NEW] ?: 0, counts[ImportStatus.POSSIBLE_DUPLICATE] ?: 0, counts[ImportStatus.ALREADY_IMPORTED] ?: 0,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.skipped > 0) {
                        Text(pluralStringResource(R.plurals.import_skipped, state.skipped, state.skipped), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val mapping = state.mapping
                    if (mapping?.amount != null && mapping.drCr == null) {
                        // One signed column: banks use minus for money out, many card exports use plus for spending.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FilterChip(mapping.positiveIsIncome, { viewModel.setPositiveIsIncome(true) }, { Text(stringResource(R.string.import_plus_is_in)) })
                            FilterChip(!mapping.positiveIsIncome, { viewModel.setPositiveIsIncome(false) }, { Text(stringResource(R.string.import_plus_is_out)) })
                        }
                    }
                }
                HorizontalDivider()
            }
            itemsIndexed(state.candidates) { index, c -> CandidateRow(c, state, onToggle = { viewModel.toggle(index) }, onCategory = { viewModel.setCategory(index, it) }) }
        }
        PrimaryButton(
            text = pluralStringResource(R.plurals.import_action, state.selectedCount, state.selectedCount),
            onClick = viewModel::import,
            enabled = state.selectedCount > 0,
            loading = state.importing,
            modifier = Modifier.padding(Spacing.screenGutter),
        )
    }
}

@Composable
private fun CandidateRow(c: ImportCandidate, state: ImportUiState, onToggle: () -> Unit, onCategory: (String) -> Unit) {
    val done = c.status == ImportStatus.ALREADY_IMPORTED
    val currency = state.account?.currency ?: "INR"
    var pickCategory by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onToggle).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = c.selected && !done, onCheckedChange = { onToggle() }, enabled = !done)
        Column(Modifier.weight(1f)) {
            Text(c.row.description.ifBlank { "—" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val status = when (c.status) {
                ImportStatus.NEW -> null
                ImportStatus.ALREADY_IMPORTED -> stringResource(R.string.import_status_done)
                ImportStatus.POSSIBLE_DUPLICATE -> stringResource(R.string.import_status_maybe)
            }
            Text(
                listOfNotNull(c.row.date.mediumLabel(), status).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (status != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                Text(
                    state.categories.firstOrNull { it.id == c.categoryId }?.name ?: stringResource(R.string.import_choose),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !done) { pickCategory = true }.padding(vertical = Spacing.xs),
                )
                val kind = if (c.row.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                DropdownMenu(expanded = pickCategory, onDismissRequest = { pickCategory = false }) {
                    state.categories.filter { it.kind == kind }.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat.name) }, onClick = { pickCategory = false; onCategory(cat.id) })
                    }
                }
            }
        }
        val signed = if (c.row.type == TransactionType.INCOME) c.row.amountMinor else -c.row.amountMinor
        Text(
            MoneyFormatter.format(signed, currency, signed = true),
            style = MaterialTheme.typography.bodyMedium,
            color = if (c.row.type == TransactionType.INCOME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
