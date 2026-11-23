package com.dailymoney.feature.money.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Savings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.categoryColor
import com.dailymoney.core.designsystem.theme.CategoryPalette
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.AmountError
import com.dailymoney.core.domain.money.GoalContribution
import com.dailymoney.core.domain.money.GoalProgress
import com.dailymoney.core.domain.money.GoalRepository
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.SavingsGoal
import com.dailymoney.core.domain.money.parsePositiveAmount
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.AmountField
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.ConfirmDeleteDialog
import com.dailymoney.feature.money.common.DateField
import com.dailymoney.feature.money.common.mediumLabel
import com.dailymoney.feature.money.navigation.GoalDetailRoute
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

data class GoalEditor(
    val id: String?,
    val name: String,
    val targetText: String,
    val targetDate: LocalDate?,
    val colorIndex: Int,
    val nameError: Boolean = false,
    val amountError: AmountError? = null,
)

/** Add money (positive) or take some out (negative). */
data class MoneyMoveEditor(val withdraw: Boolean, val amountText: String, val date: LocalDate, val note: String, val error: AmountError? = null)

data class GoalsUiState(
    val loading: Boolean = true,
    val goals: List<GoalProgress> = emptyList(),
    val currency: String,
    val today: LocalDate,
    val editor: GoalEditor? = null,
    val move: MoneyMoveEditor? = null,
    val confirmDeleteGoal: Boolean = false,
)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GoalRepository,
    config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    /** Set when this ViewModel backs the detail screen of one goal. */
    val goalId: String? = runCatching { savedStateHandle.toRoute<GoalDetailRoute>().goalId }.getOrNull()
    private val currency = config.defaultCurrency
    private val ui = MutableStateFlow(GoalsUiState(currency = currency, today = LocalDate.now(clock)))

    val state: StateFlow<GoalsUiState> = combine(ui, repository.observe()) { u, goals -> u.copy(loading = false, goals = goals) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    fun add() = ui.update { it.copy(editor = GoalEditor(null, "", "", null, it.goals.size % CategoryPalette.size)) }
    fun edit(goal: SavingsGoal) = ui.update {
        it.copy(editor = GoalEditor(goal.id, goal.name, MoneyFormatter.toInput(goal.targetMinor, goal.currency), goal.targetDate, goal.colorIndex))
    }
    fun dismissEditor() = ui.update { it.copy(editor = null) }
    fun updateEditor(block: (GoalEditor) -> GoalEditor) = ui.update { it.copy(editor = it.editor?.let(block)) }

    fun saveGoal() {
        val e = ui.value.editor ?: return
        val (target, error) = parsePositiveAmount(e.targetText, currency)
        val nameError = e.name.isBlank() || e.name.trim().length > 40
        if (error != null || nameError) {
            updateEditor { it.copy(amountError = error, nameError = nameError) }
            return
        }
        viewModelScope.launch {
            repository.save(SavingsGoal(e.id ?: Ids.newId(), e.name.trim(), target!!, currency, e.targetDate, e.colorIndex))
            ui.update { it.copy(editor = null) }
        }
    }

    fun startMove(withdraw: Boolean) = ui.update { it.copy(move = MoneyMoveEditor(withdraw, "", LocalDate.now(clock), "")) }
    fun dismissMove() = ui.update { it.copy(move = null) }
    fun updateMove(block: (MoneyMoveEditor) -> MoneyMoveEditor) = ui.update { it.copy(move = it.move?.let(block)) }

    fun saveMove() {
        val m = ui.value.move ?: return
        val id = goalId ?: return
        val (amount, error) = parsePositiveAmount(m.amountText, currency)
        if (error != null) {
            updateMove { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            repository.addContribution(GoalContribution(Ids.newId(), id, if (m.withdraw) -amount!! else amount!!, m.date, m.note.trim().ifEmpty { null }))
            ui.update { it.copy(move = null) }
        }
    }

    fun deleteContribution(id: String) {
        viewModelScope.launch { repository.deleteContribution(id) }
    }

    fun askDeleteGoal() = ui.update { it.copy(confirmDeleteGoal = true) }
    fun dismissDeleteGoal() = ui.update { it.copy(confirmDeleteGoal = false) }
    fun deleteGoal(onDone: () -> Unit) {
        val id = goalId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: GoalsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.goals_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::add) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.goals_add)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (!state.loading && state.goals.isEmpty()) {
                item { EmptyState(Icons.Filled.Savings, stringResource(R.string.goals_empty_title), stringResource(R.string.goals_empty_body)) }
            }
            items(state.goals, key = { it.goal.id }) { g -> GoalCard(g, state.today, onClick = { onOpen(g.goal.id) }) }
        }
    }
    state.editor?.let { GoalDialog(it, state.currency, viewModel) }
}

@Composable
private fun GoalCard(p: GoalProgress, today: LocalDate, onClick: (() -> Unit)?) {
    val color = categoryColor(p.goal.colorIndex)
    OutlinedCard(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Savings, contentDescription = null, tint = color)
                Text(p.goal.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
                Text("${(p.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = color)
            }
            LinearProgressIndicator(
                progress = { p.fraction },
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text(
                stringResource(R.string.goals_saved_of, MoneyFormatter.format(p.savedMinor, p.goal.currency), MoneyFormatter.format(p.goal.targetMinor, p.goal.currency)),
                style = MaterialTheme.typography.bodyMedium,
            )
            val monthly = p.monthlyNeeded(today)
            val targetDate = p.goal.targetDate
            val line = when {
                p.reached -> stringResource(R.string.goals_reached)
                monthly != null && targetDate != null ->
                    stringResource(R.string.goals_monthly_needed, MoneyFormatter.format(monthly, p.goal.currency), targetDate.mediumLabel())
                else -> stringResource(R.string.goals_left, MoneyFormatter.format(p.remainingMinor, p.goal.currency))
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (p.reached) LocalStatusColors.current.paid else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalDetailScreen(onBack: () -> Unit, viewModel: GoalsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress = state.goals.firstOrNull { it.goal.id == viewModel.goalId }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(progress?.goal?.name.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (progress != null) {
                        IconButton(onClick = { viewModel.edit(progress.goal) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.goals_edit)) }
                        IconButton(onClick = viewModel::askDeleteGoal) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_delete)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (progress == null) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { GoalCard(progress, state.today, onClick = null) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PrimaryButton(stringResource(R.string.goals_add_money), { viewModel.startMove(withdraw = false) }, Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.startMove(withdraw = true) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.goals_withdraw))
                    }
                }
            }
            item { Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm)) }
            items(progress.contributions, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { MoneyText(kotlin.math.abs(c.amountMinor), progress.goal.currency, type = if (c.amountMinor >= 0) com.dailymoney.core.domain.money.TransactionType.INCOME else com.dailymoney.core.domain.money.TransactionType.EXPENSE) },
                    supportingContent = { Text(listOfNotNull(c.date.mediumLabel(), c.note).joinToString(" · ")) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.deleteContribution(c.id) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_delete)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
    state.editor?.let { GoalDialog(it, state.currency, viewModel) }
    state.move?.let { MoneyMoveDialog(it, state.currency, viewModel) }
    if (state.confirmDeleteGoal) {
        ConfirmDeleteDialog(stringResource(R.string.goals_delete_title), stringResource(R.string.goals_delete_body), { viewModel.deleteGoal(onBack) }, viewModel::dismissDeleteGoal)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalDialog(editor: GoalEditor, currency: String, viewModel: GoalsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissEditor,
        title = { Text(stringResource(if (editor.id == null) R.string.goals_add else R.string.goals_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { v -> viewModel.updateEditor { it.copy(name = v, nameError = false) } },
                    label = { Text(stringResource(R.string.goals_name)) },
                    isError = editor.nameError,
                    supportingText = if (editor.nameError) { { Text(stringResource(R.string.name_err_required)) } } else null,
                    singleLine = true,
                )
                AmountField(editor.targetText, { v -> viewModel.updateEditor { it.copy(targetText = v, amountError = null) } }, stringResource(R.string.goals_target), currency, editor.amountError)
                DateField(
                    stringResource(R.string.goals_target_date), editor.targetDate, { d -> viewModel.updateEditor { it.copy(targetDate = d) } },
                    placeholder = stringResource(R.string.optional), onClear = { viewModel.updateEditor { it.copy(targetDate = null) } },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CategoryPalette.indices.forEach { i ->
                        Box(
                            Modifier.size(32.dp).background(categoryColor(i), CircleShape)
                                .border(if (editor.colorIndex == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable(role = Role.RadioButton) { viewModel.updateEditor { it.copy(colorIndex = i) } },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveGoal) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissEditor) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MoneyMoveDialog(move: MoneyMoveEditor, currency: String, viewModel: GoalsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissMove,
        title = { Text(stringResource(if (move.withdraw) R.string.goals_withdraw else R.string.goals_add_money)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AmountField(move.amountText, { v -> viewModel.updateMove { it.copy(amountText = v, error = null) } }, stringResource(R.string.edit_amount), currency, move.error)
                DateField(stringResource(R.string.edit_date), move.date, { d -> viewModel.updateMove { it.copy(date = d) } })
                OutlinedTextField(move.note, { v -> viewModel.updateMove { it.copy(note = v.take(200)) } }, label = { Text(stringResource(R.string.edit_note)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveMove) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissMove) { Text(stringResource(R.string.cancel)) } },
    )
}
