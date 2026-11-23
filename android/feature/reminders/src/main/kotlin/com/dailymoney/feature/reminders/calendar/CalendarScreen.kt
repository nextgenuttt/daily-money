package com.dailymoney.feature.reminders.calendar

import com.dailymoney.core.designsystem.theme.currentLocale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.money.MonthSelector
import com.dailymoney.core.designsystem.money.reminderColor
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.reminders.ObserveCalendarMonthUseCase
import com.dailymoney.core.domain.reminders.Occurrence
import com.dailymoney.core.domain.reminders.ReminderGroup
import com.dailymoney.core.domain.todo.CompleteTodoUseCase
import com.dailymoney.core.domain.todo.ReopenTodoUseCase
import com.dailymoney.core.domain.todo.Todo
import com.dailymoney.core.domain.todo.TodoRepository
import kotlinx.coroutines.launch
import com.dailymoney.feature.reminders.R
import com.dailymoney.feature.reminders.common.BackButton
import com.dailymoney.feature.reminders.common.mediumLabel
import com.dailymoney.feature.reminders.list.OccurrenceRow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class CalendarUiState(
    val month: YearMonth,
    val selected: LocalDate,
    val today: LocalDate,
    val days: Map<LocalDate, List<Occurrence>> = emptyMap(),
    val tasks: Map<LocalDate, List<Todo>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    observeMonth: ObserveCalendarMonthUseCase,
    todos: TodoRepository,
    private val completeTodo: CompleteTodoUseCase,
    private val reopenTodo: ReopenTodoUseCase,
    clock: Clock,
) : ViewModel() {

    private val today = LocalDate.now(clock)
    private val month = MutableStateFlow(YearMonth.from(today))
    private val selected = MutableStateFlow(today)

    val state: StateFlow<CalendarUiState> = combine(month, selected, month.flatMapLatest { observeMonth(it) }, todos.observe()) { m, s, days, all ->
        val tasks = all.filter { t -> t.dueDate?.let { YearMonth.from(it) == m } == true }.groupBy { it.dueDate!! }
        CalendarUiState(m, s, today, days, tasks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState(month.value, today, today))

    fun toggleTask(t: Todo) {
        viewModelScope.launch { if (t.isDone) reopenTodo(t.id) else completeTodo(t.id) }
    }

    fun previous() = move(-1)
    fun next() = move(1)
    fun select(date: LocalDate) {
        selected.value = date
    }

    private fun move(by: Long) {
        val m = month.value.plusMonths(by)
        month.value = m
        selected.value = if (YearMonth.from(today) == m) today else m.atDay(1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(
    onBack: () -> Unit,
    onAdd: (LocalDate) -> Unit,
    onOpen: (Occurrence) -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rem_calendar)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(state.selected) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cal_add_here)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val selectedItems = state.days[state.selected].orEmpty()
        val selectedTasks = state.tasks[state.selected].orEmpty()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            item {
                MonthSelector(
                    label = state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", currentLocale())),
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    previousLabel = stringResource(R.string.cal_previous),
                    nextLabel = stringResource(R.string.cal_next),
                )
            }
            item { MonthGrid(state, viewModel::select) }
            item {
                Text(
                    state.selected.mediumLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
                )
            }
            if (selectedItems.isEmpty() && selectedTasks.isEmpty()) {
                item {
                    Text(stringResource(R.string.cal_nothing), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.sm))
                }
            }
            items(selectedItems, key = { "${it.reminder.id}:${it.originalDate}" }) { o ->
                OccurrenceRow(o, state.today, onOpen = { occ, _ -> onOpen(occ) }, onComplete = null)
            }
            items(selectedTasks, key = { "task:${it.id}" }) { t -> TaskRow(t, { viewModel.toggleTask(t) }, { onOpenTask(t.id) }) }
        }
    }
}

@Composable
private fun MonthGrid(state: CalendarUiState, onSelect: (LocalDate) -> Unit) {
    val firstDay = WeekFields.of(currentLocale()).firstDayOfWeek
    val weekdays = (0..6).map { firstDay.plus(it.toLong()) }
    val leading = (state.month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val cells = List(leading) { null } + (1..state.month.lengthOfMonth()).map { state.month.atDay(it) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { d: DayOfWeek ->
                Text(
                    d.getDisplayName(TextStyle.SHORT, currentLocale()).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = Spacing.xs),
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date -> DayCell(date, state, onSelect, Modifier.weight(1f)) }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate?, state: CalendarUiState, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    if (date == null) {
        Spacer(modifier.aspectRatio(1f))
        return
    }
    val items = state.days[date].orEmpty()
    val taskCount = state.tasks[date].orEmpty().count { !it.isDone }
    val selected = date == state.selected
    val isToday = date == state.today
    val description = "${date.mediumLabel()}, ${items.size + taskCount}"
    Column(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clickable { onSelect(date) }
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                .then(if (isToday && !selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp).padding(top = 2.dp)) {
            (items.map { it.reminder.kind.group } + if (taskCount > 0) listOf(ReminderGroup.TASK) else emptyList()).distinct().take(3).forEach { group ->
                Box(Modifier.size(5.dp).background(reminderColor(group), CircleShape))
            }
        }
    }
}

@Composable
private fun TaskRow(task: Todo, onToggle: () -> Unit, onOpen: () -> Unit) {
    androidx.compose.material3.ListItem(
        headlineContent = {
            Text(task.title, textDecoration = if (task.isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
        },
        supportingContent = { Text(stringResource(R.string.rem_tab_todo) + (task.dueTime?.let { " · ${it.format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))}" } ?: "")) },
        leadingContent = {
            androidx.compose.material3.IconButton(onClick = onToggle) {
                Icon(
                    if (task.isDone) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Outlined.Circle,
                    contentDescription = stringResource(R.string.rem_complete),
                    tint = reminderColor(ReminderGroup.TASK),
                )
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
