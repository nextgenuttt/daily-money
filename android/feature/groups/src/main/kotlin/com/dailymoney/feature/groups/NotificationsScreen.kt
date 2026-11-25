package com.dailymoney.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.sharing.AppNotification
import com.dailymoney.core.domain.sharing.NotificationCategory
import com.dailymoney.core.domain.sharing.NotificationRepository
import com.dailymoney.core.domain.sharing.NotificationType
import com.dailymoney.core.domain.sharing.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where tapping a notification leads. */
sealed interface NotificationTarget {
    data class Group(val groupId: String) : NotificationTarget
    data class Messages(val expenseId: String, val title: String) : NotificationTarget
}

internal fun AppNotification.target(): NotificationTarget? {
    val expense = entityId
    val group = groupId
    return when {
        type == NotificationType.MESSAGE && expense != null -> NotificationTarget.Messages(expense, params["category"].orEmpty())
        group != null -> NotificationTarget.Group(group)
        else -> null
    }
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NotificationRepository,
    sync: SyncScheduler,
) : ViewModel() {

    private val openId = savedStateHandle.toRoute<NotificationsRoute>().openId
    val notifications: StateFlow<List<AppNotification>?> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _open = Channel<NotificationTarget>(Channel.BUFFERED)
    val openEvents = _open.receiveAsFlow()

    init {
        sync.requestSync()
        // Opened from a system notification: go straight to what it's about.
        if (openId != null) {
            viewModelScope.launch {
                repository.observe().first().firstOrNull { it.id == openId }?.let { open(it) }
            }
        }
    }

    fun open(n: AppNotification) {
        viewModelScope.launch {
            repository.markRead(listOf(n.id))
            n.target()?.let { _open.send(it) }
        }
    }

    fun markAllRead() {
        viewModelScope.launch { repository.markAllRead() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsScreen(
    onBack: () -> Unit,
    onOpen: (NotificationTarget) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val items by viewModel.notifications.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.openEvents.collect(onOpen) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (items.orEmpty().any { !it.read }) TextButton(onClick = viewModel::markAllRead) { Text(stringResource(R.string.notifications_read_all)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val list = items ?: return@Scaffold
        if (list.isEmpty()) {
            Text(stringResource(R.string.notifications_empty), Modifier.padding(padding).padding(Spacing.screenGutter), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = Spacing.lg)) {
            items(list, key = { it.id }) { n ->
                ListItem(
                    headlineContent = { Text(n.headline(), fontWeight = if (n.read) FontWeight.Normal else FontWeight.SemiBold) },
                    supportingContent = {
                        Text(listOf(n.detail(), n.createdAt.atZone(ZoneId.systemDefault()).format(dateFormat)).filter { it.isNotBlank() }.joinToString("\n"))
                    },
                    leadingContent = {
                        Icon(
                            n.type.category.icon(), contentDescription = null,
                            tint = if (n.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (n.read) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.open(n) },
                )
            }
        }
    }
}

private fun NotificationCategory.icon() = when (this) {
    NotificationCategory.APPROVALS -> Icons.Filled.Gavel
    NotificationCategory.PAYMENTS -> Icons.Filled.Payments
    NotificationCategory.MESSAGES -> Icons.Filled.Chat
    NotificationCategory.GROUP_ACTIVITY -> Icons.Filled.Groups
}

@Composable
private fun AppNotification.headline(): String {
    val actor = params["actor"].orEmpty()
    val group = params["group"].orEmpty()
    return when (type) {
        NotificationType.APPROVAL_REQUESTED -> stringResource(R.string.notifications_approval_requested, actor, group)
        NotificationType.EXPENSE_APPROVED -> stringResource(R.string.notifications_approved)
        NotificationType.EXPENSE_REJECTED -> stringResource(R.string.notifications_rejected)
        NotificationType.EXPENSE_ADDED -> stringResource(R.string.notifications_added, actor, group)
        NotificationType.SPLIT_SHARE -> stringResource(R.string.notifications_share, money("shareMinor"), group)
        NotificationType.SETTLEMENT_REQUESTED -> stringResource(R.string.notifications_settle_requested, actor, money("amountMinor"))
        NotificationType.SETTLEMENT_RECORDED -> stringResource(R.string.notifications_settle_recorded, actor, money("amountMinor"))
        NotificationType.SETTLEMENT_CONFIRMED -> stringResource(R.string.notifications_settle_confirmed, actor, money("amountMinor"))
        NotificationType.SETTLEMENT_CANCELLED -> stringResource(R.string.notifications_settle_cancelled, actor, money("amountMinor"))
        NotificationType.MESSAGE -> stringResource(R.string.notifications_message, actor)
    }
}

@Composable
private fun AppNotification.detail(): String {
    val what = params["category"]?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shared_uncategorized)
    return when (type) {
        NotificationType.MESSAGE -> "$what: ${params["preview"].orEmpty()}"
        NotificationType.EXPENSE_REJECTED -> "$what · ${money("amountMinor")} · ${params["reason"].orEmpty()}"
        NotificationType.SETTLEMENT_REQUESTED, NotificationType.SETTLEMENT_RECORDED,
        NotificationType.SETTLEMENT_CONFIRMED, NotificationType.SETTLEMENT_CANCELLED -> params["group"].orEmpty()
        else -> "$what · ${money("amountMinor")}"
    }
}

private fun AppNotification.money(key: String) =
    params[key]?.toLongOrNull()?.let { MoneyFormatter.format(it, params["currency"] ?: "INR") }.orEmpty()
