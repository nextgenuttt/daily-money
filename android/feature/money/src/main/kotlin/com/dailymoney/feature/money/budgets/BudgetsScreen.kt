package com.dailymoney.feature.money.budgets

import com.dailymoney.core.designsystem.component.rememberErrorScroller
import com.dailymoney.core.designsystem.component.errorTarget
import com.dailymoney.core.designsystem.component.DropdownField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.money.CategoryBadge
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.BudgetPeriod
import com.dailymoney.core.domain.money.BudgetProgress
import com.dailymoney.core.domain.money.BudgetRepository
import com.dailymoney.core.domain.money.BudgetSaveResult
import com.dailymoney.core.domain.money.BudgetStatus
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.ObserveBudgetProgressUseCase
import com.dailymoney.core.domain.money.SaveBudgetUseCase
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.label
import com.dailymoney.feature.money.common.sanitizeAmountInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BudgetEditor(
    val id: String?,
    val categoryId: String?,
    val period: BudgetPeriod,
    val limitText: String,
    val error: BudgetSaveResult? = null,
)

data class BudgetsUiState(
    val loading: Boolean = true,
    val progress: List<BudgetProgress> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val currency: String,
    val editor: BudgetEditor? = null,
)

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    observeProgress: ObserveBudgetProgressUseCase,
    categories: CategoryRepository,
    private val budgets: BudgetRepository,
    private val saveBudget: SaveBudgetUseCase,
    config: AppConfig,
) : ViewModel() {

    private val currency = config.defaultCurrency
    private val editor = MutableStateFlow<BudgetEditor?>(null)

    val state: StateFlow<BudgetsUiState> = combine(observeProgress(), categories.observe(), editor) { progress, cats, e ->
        BudgetsUiState(false, progress, cats.filter { it.kind == CategoryKind.EXPENSE }, currency, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState(currency = currency))

    fun add() {
        editor.value = BudgetEditor(null, null, BudgetPeriod.MONTHLY, "")
    }

    fun edit(p: BudgetProgress) {
        editor.value = BudgetEditor(p.budget.id, p.budget.categoryId, p.budget.period, MoneyFormatter.toInput(p.budget.limitMinor, p.budget.currency))
    }

    fun dismiss() {
        editor.value = null
    }

    fun onCategory(id: String?) = editor.update { it?.copy(categoryId = id, error = null) }
    fun onPeriod(p: BudgetPeriod) = editor.update { it?.copy(period = p, error = null) }
    fun onLimit(v: String) = editor.update { it?.copy(limitText = sanitizeAmountInput(v, Currencies.exponent(currency)), error = null) }

    fun save() {
        val e = editor.value ?: return
        viewModelScope.launch {
            when (val result = saveBudget(e.id, e.categoryId, e.period, e.limitText, currency, state.value.progress.map { it.budget })) {
                BudgetSaveResult.Saved -> editor.value = null
                else -> editor.update { it?.copy(error = result) }
            }
        }
    }

    fun delete() {
        val id = editor.value?.id ?: return
        viewModelScope.launch {
            budgets.delete(id)
            editor.value = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetsScreen(onBack: () -> Unit, viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.money_budgets)) },
                navigationIcon = { com.dailymoney.feature.money.common.BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::add) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.budgets_add))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (!state.loading && state.progress.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.PieChart,
                        title = stringResource(R.string.budgets_empty_title),
                        message = stringResource(R.string.budgets_empty_body),
                    )
                }
            }
            items(state.progress, key = { it.budget.id }) { p -> BudgetCard(p, onClick = { viewModel.edit(p) }) }
        }
    }
    state.editor?.let { BudgetDialog(it, state, viewModel) }
}

@Composable
internal fun BudgetCard(p: BudgetProgress, onClick: () -> Unit) {
    val status = LocalStatusColors.current
    val (color, icon, statusText) = when (p.status) {
        BudgetStatus.ON_TRACK -> Triple(status.paid, Icons.Filled.CheckCircle, stringResource(R.string.budgets_status_ok))
        BudgetStatus.NEAR_LIMIT -> Triple(status.upcoming, Icons.Filled.Warning, stringResource(R.string.budgets_status_near))
        BudgetStatus.OVER -> Triple(status.overdue, Icons.Filled.Error, stringResource(R.string.budgets_status_over))
    }
    val currency = p.budget.currency
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(p.category?.iconKey ?: "others", p.category?.colorIndex ?: 0, size = 36.dp)
                Column(Modifier.padding(start = Spacing.sm).weight(1f)) {
                    Text(p.category?.name ?: stringResource(R.string.budgets_overall), style = MaterialTheme.typography.titleMedium)
                    Text(p.budget.period.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text(statusText, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(start = Spacing.xs))
            }
            LinearProgressIndicator(
                progress = { p.fraction.coerceIn(0f, 1f) },
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Row {
                Text(
                    stringResource(R.string.budgets_spent_of, MoneyFormatter.format(p.spentMinor, currency), MoneyFormatter.format(p.budget.limitMinor, currency)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer8()
                Text(
                    if (p.remainingMinor >= 0) {
                        stringResource(R.string.budgets_left, MoneyFormatter.format(p.remainingMinor, currency))
                    } else {
                        stringResource(R.string.budgets_over, MoneyFormatter.format(-p.remainingMinor, currency))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (p.remainingMinor >= 0) MaterialTheme.colorScheme.onSurfaceVariant else color,
                )
            }
        }
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetDialog(editor: BudgetEditor, state: BudgetsUiState, viewModel: BudgetsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(if (editor.id == null) R.string.budgets_add else R.string.budgets_edit)) },
        text = {
            val errorScroller = rememberErrorScroller(editor.error)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    modifier = Modifier.errorTarget(errorScroller, 1, editor.error == BudgetSaveResult.AmountInvalid),
                    value = editor.limitText,
                    onValueChange = viewModel::onLimit,
                    label = { Text(stringResource(R.string.budgets_limit)) },
                    prefix = { Text(Currencies.symbol(state.currency)) },
                    isError = editor.error == BudgetSaveResult.AmountInvalid,
                    supportingText = if (editor.error == BudgetSaveResult.AmountInvalid) {
                        { Text(stringResource(R.string.budgets_err_amount)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Text(stringResource(R.string.budgets_period), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    BudgetPeriod.entries.forEach { p -> FilterChip(editor.period == p, { viewModel.onPeriod(p) }, { Text(p.label()) }) }
                }
                DropdownField(
                    label = stringResource(R.string.budgets_for),
                    options = listOf<String?>(null) + state.expenseCategories.map { it.id },
                    selected = editor.categoryId,
                    onSelect = viewModel::onCategory,
                    optionLabel = { id -> state.expenseCategories.firstOrNull { it.id == id }?.name ?: stringResource(R.string.budgets_overall) },
                )
                if (editor.error == BudgetSaveResult.Duplicate) {
                    Text(
                        stringResource(R.string.budgets_err_duplicate), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.errorTarget(errorScroller, 2, true),
                    )
                }
                if (editor.id != null) {
                    TextButton(onClick = viewModel::delete) {
                        Text(stringResource(R.string.categories_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
