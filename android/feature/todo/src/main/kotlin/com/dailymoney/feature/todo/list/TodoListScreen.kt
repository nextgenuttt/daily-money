package com.dailymoney.feature.todo.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.todo.CompleteTodoUseCase
import com.dailymoney.core.domain.todo.ObserveTodoOverviewUseCase
import com.dailymoney.core.domain.todo.Priority
import com.dailymoney.core.domain.todo.QuickAddTodoUseCase
import com.dailymoney.core.domain.todo.ReopenTodoUseCase
import com.dailymoney.core.domain.reminders.ReminderScheduler
import com.dailymoney.core.domain.todo.Todo
import com.dailymoney.core.domain.todo.TodoListItem
import com.dailymoney.core.domain.todo.TodoOverview
import com.dailymoney.core.domain.todo.TodoRepository
import com.dailymoney.core.domain.todo.TodoStatus
import com.dailymoney.feature.todo.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TodoFilter { ALL, TODAY, UPCOMING, SOMEDAY, COMPLETED }

data class TodoListUiState(
    val overview: TodoOverview? = null,
    val filter: TodoFilter = TodoFilter.ALL,
    val category: String? = null,
    val today: LocalDate,
)

/** What a completion did, so the snackbar can say it and Undo can reverse it. */
sealed interface CompletionEvent {
    data class Completed(val todoId: String) : CompletionEvent
    data class Repeated(val todoId: String, val nextDue: LocalDate) : CompletionEvent
}

@HiltViewModel
class TodoListViewModel @Inject constructor(
    observeOverview: ObserveTodoOverviewUseCase,
    private val repository: TodoRepository,
    private val complete: CompleteTodoUseCase,
    private val reopen: ReopenTodoUseCase,
    private val quickAdd: QuickAddTodoUseCase,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) : ViewModel() {

    private val filter = MutableStateFlow(TodoFilter.ALL)
    private val category = MutableStateFlow<String?>(null)
    private val _events = Channel<CompletionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<TodoListUiState> = combine(observeOverview(), filter, category) { o, f, c ->
        TodoListUiState(o, f, c, LocalDate.now(clock))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoListUiState(today = LocalDate.now(clock)))

    fun setFilter(f: TodoFilter) {
        filter.value = f
    }

    fun setCategory(c: String?) {
        category.value = if (category.value == c) null else c
    }

    fun add(title: String) {
        viewModelScope.launch { quickAdd(title) }
    }

    fun toggle(item: TodoListItem) {
        viewModelScope.launch {
            if (item.todo.isDone) {
                reopen(item.todo.id)
                return@launch
            }
            val before = item.todo
            undoSnapshot = before
            complete(before.id)
            val after = repository.get(before.id)
            _events.send(
                if (after != null && !after.isDone && after.dueDate != before.dueDate) {
                    CompletionEvent.Repeated(before.id, after.dueDate!!)
                } else {
                    CompletionEvent.Completed(before.id)
                },
            )
        }
    }

    private var undoSnapshot: Todo? = null

    /** Restores the task exactly as it was (also rolls a repeating task back to its old due date). */
    fun undo() {
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        viewModelScope.launch {
            repository.save(snapshot)
            scheduler.reschedule()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoListScreen(
    onReminders: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: TodoListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val completedText = stringResource(R.string.todo_completed_snack)
    val undoText = stringResource(R.string.todo_undo)
    val repeatedTemplate = stringResource(R.string.todo_repeated_snack, "%s")
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            val message = when (e) {
                is CompletionEvent.Completed -> completedText
                is CompletionEvent.Repeated -> repeatedTemplate.format(e.nextDue.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            }
            if (snackbar.showSnackbar(message, undoText, withDismissAction = true) == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.todo_planner)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.todo_add)) } },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val o = state.overview
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            item { PlannerToggle(onReminders) }
            item { QuickAdd(viewModel::add) }
            if (o == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                return@LazyColumn
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(vertical = Spacing.xs)) {
                    items(TodoFilter.entries) { f ->
                        FilterChip(state.filter == f, { viewModel.setFilter(f) }, { Text(filterLabel(f)) })
                    }
                }
            }
            val usedCategories = (o.overdue + o.today + o.upcoming + o.someday).mapNotNull { it.todo.category }.distinct()
            if (usedCategories.size > 1) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = Spacing.xs)) {
                        items(usedCategories) { c -> FilterChip(state.category == c, { viewModel.setCategory(c) }, { Text(c) }) }
                    }
                }
            }
            if (o.openCount == 0 && o.completed.isEmpty()) {
                item { EmptyState(Icons.AutoMirrored.Filled.PlaylistAddCheck, stringResource(R.string.todo_empty_title), stringResource(R.string.todo_empty_body)) }
                return@LazyColumn
            }
            val pick: (List<TodoListItem>) -> List<TodoListItem> = { list -> state.category?.let { c -> list.filter { it.todo.category == c } } ?: list }
            val show = { f: TodoFilter -> state.filter == TodoFilter.ALL || state.filter == f }
            var shown = 0
            fun LazyListScope.sec(title: Int, list: List<TodoListItem>, overdue: Boolean = false) {
                val items = pick(list)
                shown += items.size
                section(title, items, state.today, viewModel::toggle, onOpen, overdue)
            }
            if (show(TodoFilter.TODAY)) {
                sec(R.string.todo_overdue, o.overdue, overdue = true)
                sec(R.string.todo_today, o.today)
            }
            if (show(TodoFilter.UPCOMING)) sec(R.string.todo_upcoming, o.upcoming)
            if (show(TodoFilter.SOMEDAY)) sec(R.string.todo_someday, o.someday)
            if (state.filter == TodoFilter.COMPLETED) sec(R.string.todo_completed, o.completed)
            if (shown == 0) {
                item {
                    Text(
                        stringResource(R.string.todo_nothing_here),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                    )
                }
            }
        }
    }
}

/** Planner tab switch between Reminders and To-Do (doc 05 §A.4: To-Do lives under Reminders). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerToggle(onReminders: () -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
        SegmentedButton(false, onReminders, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.todo_tab_reminders)) }
        SegmentedButton(true, {}, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.todo_tab_todo)) }
    }
}

@Composable
private fun QuickAdd(onAdd: (String) -> Unit) {
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
        placeholder = { Text(stringResource(R.string.todo_quick_add)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = { if (text.isNotBlank()) TextButton(onClick = submit) { Text(stringResource(R.string.todo_quick_add_button)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun LazyListScope.section(
    title: Int,
    items: List<TodoListItem>,
    today: LocalDate,
    onToggle: (TodoListItem) -> Unit,
    onOpen: (String) -> Unit,
    overdue: Boolean,
) {
    if (items.isEmpty()) return
    item(key = "h$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = if (overdue) LocalStatusColors.current.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
        )
    }
    items(items, key = { "${it.todo.id}:$title" }) { TodoRow(it, today, onToggle, onOpen) }
}

@Composable
fun TodoRow(item: TodoListItem, today: LocalDate, onToggle: (TodoListItem) -> Unit, onOpen: (String) -> Unit) {
    val t = item.todo
    val status = LocalStatusColors.current
    ListItem(
        headlineContent = {
            Text(
                t.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.isDone) TextDecoration.LineThrough else null,
                color = if (t.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            val parts = listOfNotNull(dueText(item, today), t.category, item.progress?.let { progressText(item) })
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    color = when (item.status) {
                        TodoStatus.OVERDUE -> status.overdue
                        TodoStatus.DUE_TODAY -> status.upcoming
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            IconButton(onClick = { onToggle(item) }) {
                if (t.isDone) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.todo_reopen), tint = status.paid)
                } else {
                    Icon(Icons.Outlined.Circle, contentDescription = stringResource(R.string.todo_complete))
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.recurrence != null) Icon(Icons.Filled.Repeat, contentDescription = stringResource(R.string.todo_repeats), modifier = Modifier.size(16.dp))
                PriorityFlag(t.priority)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable { onOpen(t.id) },
    )
}

@Composable
fun PriorityFlag(priority: Priority) {
    if (priority == Priority.LOW) return
    val status = LocalStatusColors.current
    Icon(
        Icons.Filled.Flag,
        contentDescription = stringResource(if (priority == Priority.HIGH) R.string.todo_priority_high else R.string.todo_priority_medium),
        tint = if (priority == Priority.HIGH) status.overdue else status.upcoming,
        modifier = Modifier.padding(start = Spacing.xs).size(18.dp),
    )
}

@Composable
fun dueText(item: TodoListItem, today: LocalDate): String? {
    val due = item.todo.dueDate ?: return null
    if (item.todo.isDone) return null
    val days = ChronoUnit.DAYS.between(today, due).toInt()
    val time = item.todo.dueTime
    return when {
        days < 0 -> pluralStringResource(R.plurals.todo_overdue_by, -days, -days)
        days == 0 && time != null -> stringResource(R.string.todo_due_today_at, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
        days == 0 -> stringResource(R.string.todo_due_today)
        days <= 7 -> pluralStringResource(R.plurals.todo_due_in, days, days)
        else -> stringResource(R.string.todo_due_on, due.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    }
}

@Composable
private fun progressText(item: TodoListItem): String {
    val total = item.todo.checklist.size + item.subtaskCount
    val done = ((item.progress ?: 0f) * total + 0.5f).toInt()
    return stringResource(R.string.todo_progress, done, total)
}

@Composable
private fun filterLabel(f: TodoFilter) = stringResource(
    when (f) {
        TodoFilter.ALL -> R.string.todo_all
        TodoFilter.TODAY -> R.string.todo_today
        TodoFilter.UPCOMING -> R.string.todo_upcoming
        TodoFilter.SOMEDAY -> R.string.todo_someday
        TodoFilter.COMPLETED -> R.string.todo_completed
    },
)
