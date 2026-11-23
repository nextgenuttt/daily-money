package com.dailymoney.feature.groups

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.groups.MemberPermissions
import com.dailymoney.core.domain.groups.PermissionEffect
import com.dailymoney.core.domain.groups.PermissionGroups
import com.dailymoney.core.domain.groups.PermissionMatrix
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val SECTIONS = listOf(
    R.string.perm_group_expenses to PermissionGroups.EXPENSES,
    R.string.perm_group_members to PermissionGroups.MEMBERS,
    R.string.perm_group_messages to PermissionGroups.MESSAGES,
    R.string.perm_group_planning to PermissionGroups.PLANNING,
)

private val PERMISSION_LABELS = mapOf(
    "EXPENSE_VIEW" to R.string.p_EXPENSE_VIEW,
    "EXPENSE_ADD" to R.string.p_EXPENSE_ADD,
    "EXPENSE_EDIT_OWN" to R.string.p_EXPENSE_EDIT_OWN,
    "EXPENSE_DELETE_OWN" to R.string.p_EXPENSE_DELETE_OWN,
    "EXPENSE_APPROVE" to R.string.p_EXPENSE_APPROVE,
    "EXPENSE_SPLIT_MANAGE" to R.string.p_EXPENSE_SPLIT_MANAGE,
    "EXPENSE_REPORT_VIEW" to R.string.p_EXPENSE_REPORT_VIEW,
    "EXPENSE_BUDGET_MANAGE" to R.string.p_EXPENSE_BUDGET_MANAGE,
    "ATTACHMENT_VIEW" to R.string.p_ATTACHMENT_VIEW,
    "MEMBER_INVITE" to R.string.p_MEMBER_INVITE,
    "MEMBER_MANAGE" to R.string.p_MEMBER_MANAGE,
    "PERMISSION_MANAGE" to R.string.p_PERMISSION_MANAGE,
    "ORGANIZATION_SETTINGS_MANAGE" to R.string.p_ORGANIZATION_SETTINGS_MANAGE,
    "EXPENSE_MESSAGE_SEND" to R.string.p_EXPENSE_MESSAGE_SEND,
    "EXPENSE_MESSAGE_RECEIVE" to R.string.p_EXPENSE_MESSAGE_RECEIVE,
    "EXPENSE_MESSAGE_REPLY" to R.string.p_EXPENSE_MESSAGE_REPLY,
    "EXPENSE_MESSAGE_ATTACH" to R.string.p_EXPENSE_MESSAGE_ATTACH,
    "EXPENSE_NOTIFICATION_RECEIVE" to R.string.p_EXPENSE_NOTIFICATION_RECEIVE,
    "REMINDER_MANAGE" to R.string.p_REMINDER_MANAGE,
    "EVENT_MANAGE" to R.string.p_EVENT_MANAGE,
    "TASK_MANAGE" to R.string.p_TASK_MANAGE,
    "TASK_ASSIGN" to R.string.p_TASK_ASSIGN,
)

@Composable
private fun permissionLabel(code: String): String = PERMISSION_LABELS[code]?.let { stringResource(it) } ?: code

@Composable
private fun GroupError.permissionMessage(): String = when ((this as? GroupError.Rule)?.code) {
    "CANNOT_GRANT_UNHELD" -> stringResource(R.string.perm_err_grant)
    "OWNER_KEEPS_MANAGEMENT" -> stringResource(R.string.perm_err_owner_keeps)
    "CANNOT_CHANGE_SELF" -> stringResource(R.string.perm_err_self)
    else -> message()
}

// ---------------------------------------------------------------- roles

data class RolePermissionsUiState(
    val loading: Boolean = true,
    val matrix: PermissionMatrix? = null,
    val selectedRoleId: String? = null,
    /** Unsaved edits per role id. */
    val drafts: Map<String, Set<String>> = emptyMap(),
    val error: GroupError? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class RolePermissionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GroupRepository,
) : ViewModel() {

    private val groupId = savedStateHandle.toRoute<RolePermissionsRoute>().groupId
    private val _state = MutableStateFlow(RolePermissionsUiState())
    val state: StateFlow<RolePermissionsUiState> = _state.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val savedEvents = _saved.receiveAsFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            when (val r = repository.permissions(groupId)) {
                is GroupResult.Ok -> _state.update {
                    it.copy(loading = false, matrix = r.value, selectedRoleId = it.selectedRoleId ?: r.value.roles.firstOrNull { role -> role.editable }?.id ?: r.value.roles.firstOrNull()?.id, drafts = emptyMap(), error = null)
                }
                is GroupResult.Err -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    fun select(roleId: String) = _state.update { it.copy(selectedRoleId = roleId, error = null) }

    fun toggle(code: String) = _state.update { s ->
        val role = s.matrix?.roles?.firstOrNull { it.id == s.selectedRoleId } ?: return@update s
        if (!role.editable) return@update s
        val current = s.drafts[role.id] ?: role.permissions
        s.copy(drafts = s.drafts + (role.id to if (code in current) current - code else current + code), error = null)
    }

    fun save() {
        val s = _state.value
        val roleId = s.selectedRoleId ?: return
        val draft = s.drafts[roleId] ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.updateRole(groupId, roleId, draft)) {
                is GroupResult.Ok -> {
                    _state.update { it.copy(saving = false) }
                    _saved.send(Unit)
                    load()
                }
                is GroupResult.Err -> _state.update { it.copy(saving = false, error = r.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RolePermissionsScreen(onBack: () -> Unit, viewModel: RolePermissionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.perm_saved)
    LaunchedEffect(Unit) { viewModel.savedEvents.collect { snackbar.showSnackbar(savedText) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perm_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val matrix = state.matrix
        if (state.loading || matrix == null) {
            Column(Modifier.padding(padding).padding(Spacing.md)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { ErrorBanner(it.message()) }
            }
            return@Scaffold
        }
        val role = matrix.roles.firstOrNull { it.id == state.selectedRoleId }
        val current = role?.let { state.drafts[it.id] ?: it.permissions }.orEmpty()
        val dirty = role != null && state.drafts[role.id] != null && state.drafts[role.id] != role.permissions
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = Spacing.lg)) {
            item {
                LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)) {
                    items(matrix.roles, key = { it.id }) { r -> FilterChip(r.id == state.selectedRoleId, { viewModel.select(r.id) }, { Text(r.role.label()) }) }
                }
            }
            item {
                val hint = when {
                    !matrix.canManage -> stringResource(R.string.perm_view_only)
                    role != null && !role.editable -> stringResource(R.string.perm_role_locked)
                    else -> null
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.sm)) }
                state.error?.let { ErrorBanner(it.permissionMessage(), Modifier.padding(vertical = Spacing.sm)) }
            }
            SECTIONS.forEach { (title, codes) ->
                section(title, codes.filter { it in matrix.definitions }) { code ->
                    ListItem(
                        headlineContent = { Text(permissionLabel(code)) },
                        trailingContent = { Switch(checked = code in current, onCheckedChange = { viewModel.toggle(code) }, enabled = role?.editable == true) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            }
            if (role?.editable == true) {
                item { PrimaryButton(stringResource(R.string.perm_save), viewModel::save, enabled = dirty, loading = state.saving, modifier = Modifier.padding(top = Spacing.md)) }
            }
        }
    }
}

private fun LazyListScope.section(title: Int, codes: List<String>, row: @Composable (String) -> Unit) {
    if (codes.isEmpty()) return
    item(key = "s$title") {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs))
    }
    items(codes, key = { it }) { row(it) }
}

// ---------------------------------------------------------------- member

data class MemberPermissionsUiState(
    val loading: Boolean = true,
    val data: MemberPermissions? = null,
    val draft: Map<String, PermissionEffect> = emptyMap(),
    val error: GroupError? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class MemberPermissionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GroupRepository,
) : ViewModel() {

    val route = savedStateHandle.toRoute<MemberPermissionsRoute>()
    private val _state = MutableStateFlow(MemberPermissionsUiState())
    val state: StateFlow<MemberPermissionsUiState> = _state.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val savedEvents = _saved.receiveAsFlow()

    init {
        viewModelScope.launch {
            when (val r = repository.memberPermissions(route.groupId, route.userId)) {
                is GroupResult.Ok -> _state.update { it.copy(loading = false, data = r.value, draft = r.value.overrides) }
                is GroupResult.Err -> _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    /** null = follow the role. */
    fun set(code: String, effect: PermissionEffect?) = _state.update {
        it.copy(draft = if (effect == null) it.draft - code else it.draft + (code to effect), error = null)
    }

    fun save() {
        val draft = _state.value.draft
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.setOverrides(route.groupId, route.userId, draft)) {
                is GroupResult.Ok -> {
                    _state.update { s -> s.copy(saving = false, data = s.data?.copy(overrides = draft, effective = PermissionGroups.effective(s.data.roleDefaults, draft))) }
                    _saved.send(Unit)
                }
                is GroupResult.Err -> _state.update { it.copy(saving = false, error = r.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberPermissionsScreen(onBack: () -> Unit, viewModel: MemberPermissionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.perm_saved)
    LaunchedEffect(Unit) { viewModel.savedEvents.collect { snackbar.showSnackbar(savedText) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perm_member_title, viewModel.route.name)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val data = state.data
        if (state.loading || data == null) {
            Column(Modifier.padding(padding).padding(Spacing.md)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { ErrorBanner(it.message()) }
            }
            return@Scaffold
        }
        val preview = PermissionGroups.effective(data.roleDefaults, state.draft)
        val status = LocalStatusColors.current
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = Spacing.lg)) {
            item {
                Text("${data.role.label()} · ${stringResource(R.string.perm_member_hint)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { ErrorBanner(it.permissionMessage(), Modifier.padding(vertical = Spacing.sm)) }
            }
            SECTIONS.forEach { (title, codes) ->
                section(title, codes) { code ->
                    val effect = state.draft[code]
                    val can = code in preview
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(permissionLabel(code), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(if (code in data.roleDefaults) R.string.perm_role_says_yes else R.string.perm_role_says_no),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(if (can) R.string.perm_result_yes else R.string.perm_result_no),
                                color = if (can) status.paid else status.overdue,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            listOf(null to R.string.perm_follow_role, PermissionEffect.ALLOW to R.string.perm_allow, PermissionEffect.DENY to R.string.perm_deny)
                                .forEachIndexed { i, (value, label) ->
                                    SegmentedButton(effect == value, { viewModel.set(code, value) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
                                }
                        }
                    }
                }
            }
            item {
                PrimaryButton(
                    stringResource(R.string.perm_save), viewModel::save,
                    enabled = state.draft != data.overrides, loading = state.saving, modifier = Modifier.padding(top = Spacing.md),
                )
            }
        }
    }
}
