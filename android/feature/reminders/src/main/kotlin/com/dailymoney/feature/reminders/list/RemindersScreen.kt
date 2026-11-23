package com.dailymoney.feature.reminders.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.ReminderBadge
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.reminders.CompleteOccurrenceUseCase
import com.dailymoney.core.domain.reminders.ObserveRemindersOverviewUseCase
import com.dailymoney.core.domain.reminders.Occurrence
import com.dailymoney.core.domain.reminders.OccurrenceStatus
import com.dailymoney.core.domain.reminders.RemindersOverview
import com.dailymoney.core.domain.reminders.ReopenOccurrenceUseCase
import com.dailymoney.feature.reminders.R
import com.dailymoney.feature.reminders.common.PermissionBanners
import com.dailymoney.feature.reminders.common.label
import com.dailymoney.feature.reminders.common.statusColor
import com.dailymoney.feature.reminders.common.whenText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ReminderFilter { ALL, OVERDUE, UPCOMING, DONE }

data class RemindersUiState(
    val loading: Boolean = true,
    val overview: RemindersOverview? = null,
    val filter: ReminderFilter = ReminderFilter.ALL,
    val today: LocalDate,
)

@HiltViewModel
class RemindersViewModel @Inject constructor(
    observeOverview: ObserveRemindersOverviewUseCase,
    private val complete: CompleteOccurrenceUseCase,
    private val reopen: ReopenOccurrenceUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val filter = MutableStateFlow(ReminderFilter.ALL)
    private val _undo = Channel<Occurrence>(Channel.BUFFERED)
    val undoEvents = _undo.receiveAsFlow()

    val state: StateFlow<RemindersUiState> = combine(observeOverview(), filter) { o, f ->
        RemindersUiState(false, o, f, LocalDate.now(clock))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersUiState(today = LocalDate.now(clock)))

    fun setFilter(f: ReminderFilter) {
        filter.value = f
    }

    /** Quick tick for non-payment reminders; payments go to the detail screen to enter the amount. */
    fun quickComplete(o: Occurrence) {
        viewModelScope.launch {
            complete(o.reminder, o.originalDate)
            _undo.send(o)
        }
    }

    fun undo(o: Occurrence) {
        viewModelScope.launch { reopen(o.reminder.id, o.originalDate) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemindersScreen(
    onAdd: () -> Unit,
    onCalendar: () -> Unit,
    onOpen: (Occurrence, Boolean) -> Unit,
    onTodo: () -> Unit,
    viewModel: RemindersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val doneText = stringResource(R.string.rem_completed_snack)
    val undoText = stringResource(R.string.rem_undo)
    LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { o ->
            if (snackbar.showSnackbar(doneText, undoText, withDismissAction = true) == SnackbarResult.ActionPerformed) viewModel.undo(o)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rem_planner)) },
                actions = {
                    IconButton(onClick = onCalendar) { Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.rem_calendar)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rem_add)) } },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val o = state.overview
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                    SegmentedButton(true, {}, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.rem_title)) }
                    SegmentedButton(false, onTodo, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.rem_tab_todo)) }
                }
            }
            item { PermissionBanners() }
            if (o == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                return@LazyColumn
            }
            item { Counters(o, state.filter, viewModel::setFilter) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(vertical = Spacing.sm)) {
                    items(ReminderFilter.entries) { f ->
                        FilterChip(state.filter == f, { viewModel.setFilter(f) }, {
                            Text(
                                stringResource(
                                    when (f) {
                                        ReminderFilter.ALL -> R.string.rem_all
                                        ReminderFilter.OVERDUE -> R.string.rem_overdue
                                        ReminderFilter.UPCOMING -> R.string.rem_upcoming
                                        ReminderFilter.DONE -> R.string.rem_done
                                    },
                                ),
                            )
                        })
                    }
                }
            }
            val nothing = o.overdue.isEmpty() && o.today.isEmpty() && o.upcoming.isEmpty() && o.done.isEmpty()
            if (nothing) {
                item { EmptyState(Icons.Filled.NotificationsActive, stringResource(R.string.rem_empty_title), stringResource(R.string.rem_empty_body)) }
                return@LazyColumn
            }
            val onComplete: (Occurrence) -> Unit = { occ ->
                if (occ.reminder.kind.hasAmount) onOpen(occ, true) else viewModel.quickComplete(occ)
            }
            val show = { f: ReminderFilter -> state.filter == ReminderFilter.ALL || state.filter == f }
            if (show(ReminderFilter.OVERDUE)) section(R.string.rem_overdue, o.overdue, state.today, onOpen, onComplete, overdue = true)
            if (show(ReminderFilter.UPCOMING)) {
                section(R.string.rem_today, o.today, state.today, onOpen, onComplete)
                section(R.string.rem_upcoming, o.upcoming, state.today, onOpen, onComplete)
            }
            if (state.filter == ReminderFilter.DONE) section(R.string.rem_done, o.done, state.today, onOpen, null)
            val filteredEmpty = when (state.filter) {
                ReminderFilter.ALL -> false
                ReminderFilter.OVERDUE -> o.overdue.isEmpty()
                ReminderFilter.UPCOMING -> o.today.isEmpty() && o.upcoming.isEmpty()
                ReminderFilter.DONE -> o.done.isEmpty()
            }
            if (filteredEmpty) {
                item {
                    Text(
                        stringResource(R.string.rem_nothing_here),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                    )
                }
            }
        }
    }
}

@Composable
private fun Counters(o: RemindersOverview, filter: ReminderFilter, onFilter: (ReminderFilter) -> Unit) {
    val status = LocalStatusColors.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
            Counter(stringResource(R.string.rem_upcoming), o.upcomingCount, status.upcoming, Modifier.weight(1f)) { onFilter(ReminderFilter.UPCOMING) }
            Counter(stringResource(R.string.rem_done), o.done.size, status.paid, Modifier.weight(1f)) { onFilter(ReminderFilter.DONE) }
            Counter(stringResource(R.string.rem_overdue), o.overdue.size, status.overdue, Modifier.weight(1f)) { onFilter(ReminderFilter.OVERDUE) }
        }
    }
}

@Composable
private fun Counter(label: String, count: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = color)
    }
}

private fun LazyListScope.section(
    title: Int,
    items: List<Occurrence>,
    today: LocalDate,
    onOpen: (Occurrence, Boolean) -> Unit,
    onComplete: ((Occurrence) -> Unit)?,
    overdue: Boolean = false,
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
    items(items, key = { "${it.reminder.id}:${it.originalDate}:$title" }) { o -> OccurrenceRow(o, today, onOpen, onComplete) }
}

@Composable
internal fun OccurrenceRow(o: Occurrence, today: LocalDate, onOpen: (Occurrence, Boolean) -> Unit, onComplete: ((Occurrence) -> Unit)?) {
    val r = o.reminder
    ListItem(
        headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(r.kind.label(), o.whenText(today)).joinToString(" · "),
                color = o.statusColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { ReminderBadge(r.kind) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                r.amountMinor?.let { MoneyText(it, r.currency, style = MaterialTheme.typography.bodyMedium) }
                if (onComplete != null) {
                    IconButton(onClick = { onComplete(o) }) {
                        Icon(Icons.Outlined.Circle, contentDescription = stringResource(R.string.rem_complete), tint = o.statusColor())
                    }
                } else if (o.status == OccurrenceStatus.DONE) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = LocalStatusColors.current.paid, modifier = Modifier.padding(Spacing.sm))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable { onOpen(o, false) },
    )
}
