package com.dailymoney.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.sharing.ExpenseMessage
import com.dailymoney.core.domain.sharing.MessageRepository
import com.dailymoney.core.domain.sharing.MessageThread
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MessagesUiState(
    val loading: Boolean = true,
    val thread: MessageThread? = null,
    val error: GroupError? = null,
    val draft: String = "",
    val replyTo: ExpenseMessage? = null,
    val sending: Boolean = false,
    val sendError: GroupError? = null,
)

@HiltViewModel
class ExpenseMessagesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ExpenseMessagesRoute>()
    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            when (val r = repository.thread(route.expenseId)) {
                is GroupResult.Ok -> {
                    _state.update { it.copy(loading = false, thread = r.value, error = null) }
                    if (r.value.messages.any { !it.mine }) repository.markRead(route.expenseId)
                }
                is GroupResult.Err -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text.take(MAX), sendError = null) }
    fun replyTo(message: ExpenseMessage?) = _state.update { it.copy(replyTo = message) }

    fun send() {
        val s = _state.value
        if (s.draft.isBlank() || s.sending) return
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            when (val r = repository.send(route.expenseId, s.draft, s.replyTo?.id)) {
                is GroupResult.Ok -> {
                    _state.update { it.copy(sending = false, draft = "", replyTo = null) }
                    load()
                }
                is GroupResult.Err -> _state.update { it.copy(sending = false, sendError = r.error) }
            }
        }
    }

    fun delete(message: ExpenseMessage) {
        viewModelScope.launch {
            if (repository.delete(message.id) is GroupResult.Ok) load()
        }
    }

    private companion object {
        const val MAX = 2000
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpenseMessagesScreen(onBack: () -> Unit, title: String, viewModel: ExpenseMessagesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val messages = state.thread?.messages.orEmpty()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { stringResource(R.string.messages_title) }) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorBanner(it.message(), Modifier.padding(Spacing.md)) }
            if (state.thread != null && messages.isEmpty()) {
                Text(stringResource(R.string.messages_empty), Modifier.padding(Spacing.screenGutter), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(messages, key = { it.id }) { m ->
                    Bubble(m, parent = messages.firstOrNull { it.id == m.parentId }, canReply = state.thread?.canReply == true, viewModel)
                }
            }
            val thread = state.thread
            if (thread != null && (thread.canSend || (thread.canReply && state.replyTo != null))) {
                Composer(state, viewModel)
            } else if (thread != null) {
                Text(stringResource(R.string.messages_read_only), Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Bubble(m: ExpenseMessage, parent: ExpenseMessage?, canReply: Boolean, viewModel: ExpenseMessagesViewModel) {
    val time = m.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenGutter), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(Spacing.sm)) {
                if (!m.mine) Text(m.senderName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                parent?.let {
                    Text(
                        "↪ ${it.senderName}: ${it.body ?: stringResource(R.string.messages_deleted)}".take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (m.body == null) {
                    Text(stringResource(R.string.messages_deleted), fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(m.body.orEmpty())
                }
                val seen = if (m.mine && m.readBy > 0) " · ${pluralStringResource(R.plurals.messages_seen, m.readBy, m.readBy)}" else ""
                Text(time + seen, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.body != null) {
                    Row {
                        if (canReply) TextButton(onClick = { viewModel.replyTo(m) }) { Text(stringResource(R.string.messages_reply)) }
                        if (m.mine) TextButton(onClick = { viewModel.delete(m) }) { Text(stringResource(R.string.messages_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(state: MessagesUiState, viewModel: ExpenseMessagesViewModel) {
    Column(Modifier.fillMaxWidth().padding(Spacing.sm)) {
        state.replyTo?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.messages_replying_to, r.senderName),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                IconButton(onClick = { viewModel.replyTo(null) }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
            }
        }
        state.sendError?.let { ErrorBanner(it.message()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::setDraft,
                placeholder = { Text(stringResource(R.string.messages_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            IconButton(onClick = viewModel::send, enabled = state.draft.isNotBlank() && !state.sending) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.messages_send))
            }
        }
    }
}
