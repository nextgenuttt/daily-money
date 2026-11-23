package com.dailymoney.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.component.SecondaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.groups.InviteLinks
import com.dailymoney.core.domain.groups.InvitePreview
import com.dailymoney.core.domain.groups.InviteStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JoinUiState(
    val input: String = "",
    val invalidLink: Boolean = false,
    val token: String? = null,
    val loading: Boolean = false,
    val preview: InvitePreview? = null,
    val error: GroupError? = null,
    val working: Boolean = false,
)

@HiltViewModel
class JoinGroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GroupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(JoinUiState())
    val state: StateFlow<JoinUiState> = _state.asStateFlow()
    private val _joined = Channel<String>(Channel.BUFFERED)
    val joinedEvents = _joined.receiveAsFlow()

    init {
        savedStateHandle.toRoute<JoinGroupRoute>().token?.let { load(it) }
    }

    fun onInput(v: String) = _state.update { it.copy(input = v, invalidLink = false) }

    fun check() {
        val token = InviteLinks.tokenFrom(_state.value.input)
        if (token == null) {
            _state.update { it.copy(invalidLink = true) }
            return
        }
        load(token)
    }

    private fun load(token: String) {
        _state.update { it.copy(token = token, loading = true, error = null, preview = null) }
        viewModelScope.launch {
            when (val r = repository.preview(token)) {
                is GroupResult.Ok -> _state.update { it.copy(loading = false, preview = r.value) }
                is GroupResult.Err -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    fun accept() {
        val token = _state.value.token ?: return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.accept(token)) {
                is GroupResult.Ok -> _joined.send(r.value.id)
                is GroupResult.Err -> _state.update { it.copy(working = false, error = r.error) }
            }
        }
    }

    fun decline(onDone: () -> Unit) {
        val token = _state.value.token ?: return onDone()
        viewModelScope.launch {
            if (_state.value.preview?.emailBound == true) repository.decline(token)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JoinGroupScreen(onBack: () -> Unit, onJoined: (String) -> Unit, viewModel: JoinGroupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.joinedEvents.collect(onJoined) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.token == null || (state.preview == null && !state.loading && state.error == null)) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInput,
                    label = { Text(stringResource(R.string.join_paste)) },
                    isError = state.invalidLink,
                    supportingText = if (state.invalidLink) { { Text(stringResource(R.string.join_invalid_link)) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(stringResource(R.string.join_check), viewModel::check)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorBanner(it.message()) }
            state.preview?.let { p -> Preview(p, state.working, viewModel::accept, { viewModel.decline(onBack) }, { onJoined(p.groupId) }) }
        }
    }
}

@Composable
private fun Preview(p: InvitePreview, working: Boolean, onAccept: () -> Unit, onDecline: () -> Unit, onOpen: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(p.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            p.invitedBy?.let { Text(stringResource(R.string.join_invited_by, it), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            Text(p.groupName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(p.kind.label(), style = MaterialTheme.typography.bodyMedium)
            when {
                p.alreadyMember -> {
                    Text(stringResource(R.string.join_already))
                    SecondaryButton(stringResource(R.string.join_open_group), onOpen)
                }
                p.status != InviteStatus.VALID -> ErrorBanner(
                    stringResource(
                        when (p.status) {
                            InviteStatus.EXPIRED -> R.string.join_status_expired
                            InviteStatus.USED_UP -> R.string.join_status_used
                            InviteStatus.REVOKED -> R.string.join_status_revoked
                            else -> R.string.join_status_declined
                        },
                    ),
                )
                else -> {
                    Text(stringResource(R.string.join_as, p.role.label()), style = MaterialTheme.typography.bodyMedium)
                    PrimaryButton(stringResource(R.string.join_accept), onAccept, loading = working)
                    TextButton(onClick = onDecline) { Text(stringResource(R.string.join_decline)) }
                }
            }
        }
    }
}
