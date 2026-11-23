package com.dailymoney.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.SecondaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupKind
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.groups.GroupSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateForm(val name: String = "", val kind: GroupKind = GroupKind.FAMILY, val invalid: Boolean = false, val saving: Boolean = false)

data class GroupsUiState(
    val loading: Boolean = true,
    val groups: List<GroupSummary> = emptyList(),
    val error: GroupError? = null,
    val create: CreateForm? = null,
    val createError: GroupError? = null,
)

@HiltViewModel
class GroupsViewModel @Inject constructor(private val repository: GroupRepository) : ViewModel() {

    private val _state = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = _state.asStateFlow()
    private val _opened = Channel<String>(Channel.BUFFERED)
    val openedEvents = _opened.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch {
            when (val r = repository.list()) {
                is GroupResult.Ok -> _state.update { it.copy(loading = false, groups = r.value, error = null) }
                is GroupResult.Err -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    fun startCreate() = _state.update { it.copy(create = CreateForm(), createError = null) }
    fun dismissCreate() = _state.update { it.copy(create = null) }
    fun updateCreate(block: (CreateForm) -> CreateForm) = _state.update { it.copy(create = it.create?.let(block), createError = null) }

    fun create() {
        val f = _state.value.create ?: return
        if (f.name.isBlank() || f.name.trim().length > 60) {
            updateCreate { it.copy(invalid = true) }
            return
        }
        updateCreate { it.copy(saving = true) }
        viewModelScope.launch {
            when (val r = repository.create(f.name, f.kind)) {
                is GroupResult.Ok -> {
                    _state.update { it.copy(create = null) }
                    refresh()
                    _opened.send(r.value.id)
                }
                is GroupResult.Err -> _state.update { it.copy(create = it.create?.copy(saving = false), createError = r.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupsScreen(onOpen: (String) -> Unit, onJoin: () -> Unit, viewModel: GroupsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(Unit) { viewModel.openedEvents.collect(onOpen) }
    val backendOff = state.error == GroupError.BackendDisabled

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups_title)) },
                actions = {
                    if (!backendOff) IconButton(onClick = onJoin) { Icon(Icons.Filled.Link, contentDescription = stringResource(R.string.groups_join)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (!backendOff) FloatingActionButton(onClick = viewModel::startCreate) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.groups_create)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            when {
                state.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                backendOff -> item { EmptyState(Icons.Filled.CloudOff, stringResource(R.string.groups_backend_off_title), stringResource(R.string.groups_backend_off_body)) }
                state.error != null && state.groups.isEmpty() -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ErrorBanner(state.error!!.message())
                        SecondaryButton(stringResource(R.string.groups_retry), viewModel::refresh)
                    }
                }
                state.groups.isEmpty() -> item {
                    EmptyState(Icons.Filled.Groups, stringResource(R.string.groups_empty_title), stringResource(R.string.groups_empty_body)) {
                        SecondaryButton(stringResource(R.string.groups_join), onJoin)
                    }
                }
                else -> items(state.groups, key = { it.id }) { g ->
                    ListItem(
                        headlineContent = { Text(g.name) },
                        supportingContent = { Text("${g.kind.label()} · ${pluralStringResource(R.plurals.groups_members, g.memberCount.toInt(), g.memberCount.toInt())} · ${g.myRole.label()}") },
                        leadingContent = { Icon(g.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                        modifier = Modifier.clickable { onOpen(g.id) },
                    )
                }
            }
        }
    }
    state.create?.let { CreateDialog(it, state.createError, viewModel) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateDialog(form: CreateForm, error: GroupError?, viewModel: GroupsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissCreate,
        title = { Text(stringResource(R.string.create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { v -> viewModel.updateCreate { it.copy(name = v.take(60), invalid = false) } },
                    label = { Text(stringResource(R.string.create_name)) },
                    isError = form.invalid,
                    supportingText = if (form.invalid) { { Text(stringResource(R.string.name_required)) } } else null,
                    singleLine = true,
                )
                Text(stringResource(R.string.create_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    GroupKind.entries.forEach { k ->
                        FilterChip(form.kind == k, { viewModel.updateCreate { it.copy(kind = k) } }, { Text(k.label()) }, leadingIcon = { Icon(k.icon(), null) })
                    }
                }
                error?.let { ErrorBanner(it.message()) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::create, enabled = !form.saving) { Text(stringResource(R.string.create_save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissCreate) { Text(stringResource(R.string.cancel)) } },
    )
}
