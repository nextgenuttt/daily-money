package com.dailymoney.feature.todo.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.todo.ChecklistItem
import com.dailymoney.core.domain.todo.DEFAULT_TODO_CATEGORIES
import com.dailymoney.core.domain.todo.Priority
import com.dailymoney.core.domain.todo.SaveTodoUseCase
import com.dailymoney.core.domain.todo.Todo
import com.dailymoney.core.domain.todo.TodoError
import com.dailymoney.core.domain.todo.TodoRepository
import com.dailymoney.feature.todo.R
import com.dailymoney.feature.todo.navigation.AddEditTodoRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddEditTodoUiState(
    val isEdit: Boolean,
    val loading: Boolean,
    val todo: Todo,
    val categories: List<String> = DEFAULT_TODO_CATEGORIES,
    val errors: Set<TodoError> = emptySet(),
    val saving: Boolean = false,
)

@HiltViewModel
class AddEditTodoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TodoRepository,
    private val saveTodo: SaveTodoUseCase,
    clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AddEditTodoRoute>()
    private val draft = MutableStateFlow(
        Todo(
            id = route.todoId ?: Ids.newId(), title = "", notes = null, priority = Priority.MEDIUM, category = null, startDate = null,
            dueDate = route.epochDay?.let(LocalDate::ofEpochDay), dueTime = null, recurrence = null, recurrenceAnchor = null,
            alertOffsets = listOf(0), checklist = emptyList(), parentId = route.parentId, completedAt = null, snoozedUntil = null,
            createdAt = clock.instant(),
        ),
    )
    private val meta = MutableStateFlow(AddEditTodoUiState(route.todoId != null, route.todoId != null, draft.value))
    private val _close = Channel<Unit>(Channel.BUFFERED)
    val closeEvents = _close.receiveAsFlow()

    val state: StateFlow<AddEditTodoUiState> = combine(meta, draft, repository.observe().map { all ->
        (DEFAULT_TODO_CATEGORIES + all.mapNotNull { it.category }).distinct()
    }) { m, t, cats -> m.copy(todo = t, categories = (cats + listOfNotNull(t.category)).distinct()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), meta.value)

    init {
        route.todoId?.let { id ->
            viewModelScope.launch {
                repository.get(id)?.let { draft.value = it }
                meta.update { it.copy(loading = false) }
            }
        }
    }

    fun update(block: (Todo) -> Todo) {
        draft.update(block)
        meta.update { it.copy(errors = emptySet()) }
    }

    fun setRepeat(f: Frequency?) = update { it.copy(recurrence = f?.let { freq -> Recurrence(freq) }, recurrenceAnchor = null) }

    fun toggleAlert(m: Int) = update { it.copy(alertOffsets = if (m in it.alertOffsets) it.alertOffsets - m else it.alertOffsets + m) }

    fun addItem() = update { it.copy(checklist = it.checklist + ChecklistItem(Ids.newId(), "", false)) }
    fun updateItem(id: String, block: (ChecklistItem) -> ChecklistItem) = update { t -> t.copy(checklist = t.checklist.map { if (it.id == id) block(it) else it }) }
    fun removeItem(id: String) = update { t -> t.copy(checklist = t.checklist.filterNot { it.id == id }) }

    fun save() {
        if (meta.value.saving) return
        // Blank checklist rows the user never filled are simply dropped.
        val t = draft.value.let { it.copy(checklist = it.checklist.filter { c -> c.text.isNotBlank() }) }
        meta.update { it.copy(saving = true) }
        viewModelScope.launch {
            val errors = saveTodo(t, isNew = route.todoId == null)
            if (errors.isEmpty()) _close.send(Unit) else meta.update { it.copy(saving = false, errors = errors) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddEditTodoScreen(onClose: () -> Unit, viewModel: AddEditTodoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = state.todo
    var pickDue by rememberSaveable { mutableStateOf(false) }
    var pickStart by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var newCategory by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.closeEvents.collect { onClose() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(when {
                        state.isEdit -> R.string.todo_edit
                        t.parentId != null -> R.string.todo_new_subtask
                        else -> R.string.todo_new
                    }))
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.todo_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = t.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text(stringResource(R.string.todo_title)) },
                isError = TodoError.TITLE_REQUIRED in state.errors || TodoError.TITLE_TOO_LONG in state.errors,
                supportingText = when {
                    TodoError.TITLE_REQUIRED in state.errors -> { { Text(stringResource(R.string.todo_err_title)) } }
                    TodoError.TITLE_TOO_LONG in state.errors -> { { Text(stringResource(R.string.todo_err_title_long)) } }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Label(stringResource(R.string.todo_priority))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Priority.LOW to R.string.todo_low, Priority.MEDIUM to R.string.todo_medium, Priority.HIGH to R.string.todo_high).forEachIndexed { i, (p, label) ->
                    SegmentedButton(t.priority == p, { viewModel.update { it.copy(priority = p) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
                }
            }

            Label(stringResource(R.string.todo_category))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                state.categories.forEach { c ->
                    FilterChip(t.category == c, { viewModel.update { it.copy(category = if (it.category == c) null else c) } }, { Text(c) })
                }
                FilterChip(false, { newCategory = "" }, { Text(stringResource(R.string.todo_new_category)) }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { pickStart = true }, modifier = Modifier.weight(1f)) {
                    Text("${stringResource(R.string.todo_start)}: ${t.startDate?.medium() ?: stringResource(R.string.todo_none)}")
                }
                OutlinedButton(onClick = { pickDue = true }, modifier = Modifier.weight(1f)) {
                    Text("${stringResource(R.string.todo_due)}: ${t.dueDate?.medium() ?: stringResource(R.string.todo_none)}")
                }
            }
            if (TodoError.START_AFTER_DUE in state.errors) {
                Text(stringResource(R.string.todo_err_start), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (t.dueDate == null) {
                Text(stringResource(R.string.todo_date_needed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${stringResource(R.string.todo_time)}: ${t.dueTime?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: stringResource(R.string.todo_no_time)}")
                }
                Label(stringResource(R.string.todo_repeat))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(t.recurrence == null, { viewModel.setRepeat(null) }, { Text(stringResource(R.string.todo_never)) })
                    listOf(Frequency.DAILY to R.string.todo_daily, Frequency.WEEKLY to R.string.todo_weekly, Frequency.MONTHLY to R.string.todo_monthly, Frequency.YEARLY to R.string.todo_yearly)
                        .forEach { (f, label) -> FilterChip(t.recurrence?.frequency == f, { viewModel.setRepeat(f) }, { Text(stringResource(label)) }) }
                }
                Label(stringResource(R.string.todo_remind))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    val choices = if (t.dueTime != null) listOf(0 to R.string.todo_remind_at_time, 60 to R.string.todo_remind_1h, 1440 to R.string.todo_remind_1d)
                    else listOf(0 to R.string.todo_remind_at_time, 1440 to R.string.todo_remind_1d)
                    choices.forEach { (m, label) -> FilterChip(m in t.alertOffsets, { viewModel.toggleAlert(m) }, { Text(stringResource(label)) }) }
                }
            }

            Label(stringResource(R.string.todo_checklist))
            t.checklist.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.done, onCheckedChange = { v -> viewModel.updateItem(item.id) { it.copy(done = v) } })
                    OutlinedTextField(
                        value = item.text,
                        onValueChange = { v -> viewModel.updateItem(item.id) { it.copy(text = v.take(100)) } },
                        placeholder = { Text(stringResource(R.string.todo_checklist_item)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.removeItem(item.id) }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.todo_remove_item)) }
                }
            }
            TextButton(onClick = viewModel::addItem) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" ${stringResource(R.string.todo_add_item)}")
            }

            OutlinedTextField(
                value = t.notes.orEmpty(),
                onValueChange = { v -> viewModel.update { it.copy(notes = v.take(1000)) } },
                label = { Text(stringResource(R.string.todo_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(stringResource(R.string.todo_save), viewModel::save, loading = state.saving, modifier = Modifier.padding(bottom = Spacing.lg))
        }
    }

    if (pickDue) DateDialog(t.dueDate, { d -> viewModel.update { it.copy(dueDate = d) } }, { viewModel.update { it.copy(dueDate = null, dueTime = null, recurrence = null) } }) { pickDue = false }
    if (pickStart) DateDialog(t.startDate, { d -> viewModel.update { it.copy(startDate = d) } }, { viewModel.update { it.copy(startDate = null) } }) { pickStart = false }
    if (pickTime) {
        val timeState = rememberTimePickerState((t.dueTime ?: LocalTime.of(10, 0)).hour, (t.dueTime ?: LocalTime.of(10, 0)).minute)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.update { it.copy(dueTime = LocalTime.of(timeState.hour, timeState.minute)) }
                    pickTime = false
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.update { it.copy(dueTime = null, alertOffsets = it.alertOffsets.filter { m -> m == 0 || m >= 1440 }) }
                    pickTime = false
                }) { Text(stringResource(R.string.todo_clear)) }
            },
        )
    }
    newCategory?.let { value ->
        AlertDialog(
            onDismissRequest = { newCategory = null },
            title = { Text(stringResource(R.string.todo_new_category)) },
            text = { OutlinedTextField(value, { newCategory = it.take(30) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (value.isNotBlank()) viewModel.update { it.copy(category = value.trim()) }
                    newCategory = null
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = { TextButton(onClick = { newCategory = null }) { Text(stringResource(R.string.todo_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate?, onPick: (LocalDate) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(R.string.todo_ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onClear()
                onDismiss()
            }) { Text(stringResource(R.string.todo_clear)) }
        },
    ) { DatePicker(state) }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun LocalDate.medium(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
