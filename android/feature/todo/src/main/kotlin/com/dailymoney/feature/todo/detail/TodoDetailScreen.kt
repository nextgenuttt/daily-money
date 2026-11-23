package com.dailymoney.feature.todo.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.todo.CompleteTodoUseCase
import com.dailymoney.core.domain.todo.ObserveTodoUseCase
import com.dailymoney.core.domain.todo.QuickAddTodoUseCase
import com.dailymoney.core.domain.todo.ReopenTodoUseCase
import com.dailymoney.core.domain.todo.TodoActivityType
import com.dailymoney.core.domain.todo.TodoListItem
import com.dailymoney.core.domain.todo.UpdateTodoUseCase
import com.dailymoney.feature.todo.R
import com.dailymoney.feature.todo.list.PriorityFlag
import com.dailymoney.feature.todo.list.TodoRow
import com.dailymoney.feature.todo.list.dueText
import com.dailymoney.feature.todo.navigation.TodoDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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

data class TodoDetailUiState(
    val loading: Boolean = true,
    val details: ObserveTodoUseCase.Details? = null,
    val today: LocalDate,
    val confirmDelete: Boolean = false,
)

@HiltViewModel
class TodoDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observe: ObserveTodoUseCase,
    private val complete: CompleteTodoUseCase,
    private val reopen: ReopenTodoUseCase,
    private val update: UpdateTodoUseCase,
    private val quickAdd: QuickAddTodoUseCase,
    clock: Clock,
) : ViewModel() {

    val todoId = savedStateHandle.toRoute<TodoDetailRoute>().todoId
    private val ui = MutableStateFlow(TodoDetailUiState(today = LocalDate.now(clock)))

    val state: StateFlow<TodoDetailUiState> = combine(ui, observe(todoId)) { u, d -> u.copy(loading = false, details = d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    fun toggle(item: TodoListItem) {
        viewModelScope.launch { if (item.todo.isDone) reopen(item.todo.id) else complete(item.todo.id) }
    }

    fun toggleItem(itemId: String) {
        viewModelScope.launch { update.toggleChecklistItem(todoId, itemId) }
    }

    fun addSubtask(title: String) {
        viewModelScope.launch { quickAdd(title, parentId = todoId) }
    }

    fun askDelete() = ui.update { it.copy(confirmDelete = true) }
    fun dismissDelete() = ui.update { it.copy(confirmDelete = false) }
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            update.delete(todoId)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpen: (String) -> Unit,
    viewModel: TodoDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val d = state.details
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.todo_back)) } },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { onEdit(viewModel.todoId) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.todo_edit)) }
                        IconButton(onClick = viewModel::askDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.todo_delete)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (d == null) {
            if (!state.loading) ErrorBanner(stringResource(R.string.todo_not_found), Modifier.padding(padding).padding(Spacing.md))
            return@Scaffold
        }
        val item = d.item
        val t = item.todo
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.toggle(item) }) {
                                if (t.isDone) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.todo_reopen), tint = LocalStatusColors.current.paid)
                                } else {
                                    Icon(Icons.Outlined.Circle, contentDescription = stringResource(R.string.todo_complete))
                                }
                            }
                            Text(
                                t.title,
                                style = MaterialTheme.typography.titleLarge,
                                textDecoration = if (t.isDone) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f),
                            )
                            PriorityFlag(t.priority)
                        }
                        val meta = listOfNotNull(dueText(item, state.today), t.category, t.recurrence?.let { stringResource(R.string.todo_repeats) })
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.progress?.let { p ->
                            LinearProgressIndicator(
                                progress = { p },
                                color = LocalStatusColors.current.paid,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                            )
                        }
                        t.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (t.checklist.isNotEmpty()) {
                item { Text(stringResource(R.string.todo_checklist), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm)) }
                items(t.checklist, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { viewModel.toggleItem(c.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.done, onCheckedChange = { viewModel.toggleItem(c.id) })
                        Text(c.text, textDecoration = if (c.done) TextDecoration.LineThrough else null)
                    }
                }
            }
            if (t.parentId == null) {
                item { Text(stringResource(R.string.todo_subtasks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm)) }
                items(d.subtasks, key = { it.todo.id }) { sub -> TodoRow(sub, state.today, viewModel::toggle, onOpen) }
                item { AddSubtask(viewModel::addSubtask) }
            }
            if (d.activity.isNotEmpty()) {
                item { Text(stringResource(R.string.todo_activity), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm)) }
                items(d.activity, key = { it.id }) { a ->
                    val label = when (a.type) {
                        TodoActivityType.CREATED -> stringResource(R.string.act_created)
                        TodoActivityType.EDITED -> stringResource(R.string.act_edited)
                        TodoActivityType.COMPLETED -> stringResource(R.string.act_completed)
                        TodoActivityType.REOPENED -> stringResource(R.string.act_reopened)
                        TodoActivityType.REPEATED -> stringResource(
                            R.string.act_repeated,
                            a.detail?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrNull() }.orEmpty(),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            LocalDateTime.ofInstant(a.at, ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.todo_delete_title)) },
            text = { Text(stringResource(R.string.todo_delete_body)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(onBack) }) { Text(stringResource(R.string.todo_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.todo_cancel)) } },
        )
    }
}

@Composable
private fun AddSubtask(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(100) },
        placeholder = { Text(stringResource(R.string.todo_add_subtask)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = { if (text.isNotBlank()) TextButton(onClick = submit) { Text(stringResource(R.string.todo_quick_add_button)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}
