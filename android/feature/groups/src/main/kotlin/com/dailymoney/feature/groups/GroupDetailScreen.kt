package com.dailymoney.feature.groups

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.groups.CreatedInvite
import com.dailymoney.core.domain.groups.GroupDetail
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupInvitation
import com.dailymoney.core.domain.groups.GroupKind
import com.dailymoney.core.domain.groups.GroupMember
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.groups.GroupRole
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DetailDialog {
    data class Rename(val name: String, val kind: GroupKind) : DetailDialog
    data class Member(val member: GroupMember) : DetailDialog
    data class ConfirmRemove(val member: GroupMember) : DetailDialog
    data class ConfirmOwner(val member: GroupMember) : DetailDialog
    data object ConfirmLeave : DetailDialog
    data object ConfirmDelete : DetailDialog
    data class Invite(val role: GroupRole = GroupRole.MEMBER, val email: String = "", val maxUses: Int = 1) : DetailDialog
    data class InviteReady(val invite: CreatedInvite) : DetailDialog
}

data class GroupDetailUiState(
    val loading: Boolean = true,
    val group: GroupDetail? = null,
    val members: List<GroupMember> = emptyList(),
    val invitations: List<GroupInvitation> = emptyList(),
    val error: GroupError? = null,
    val actionError: GroupError? = null,
    val dialog: DetailDialog? = null,
    val working: Boolean = false,
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GroupRepository,
) : ViewModel() {

    private val groupId = savedStateHandle.toRoute<GroupDetailRoute>().groupId
    private val _state = MutableStateFlow(GroupDetailUiState())
    val state: StateFlow<GroupDetailUiState> = _state.asStateFlow()
    private val _left = Channel<Unit>(Channel.BUFFERED)
    val leftEvents = _left.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val detail = repository.get(groupId)
            if (detail is GroupResult.Err) {
                _state.update { it.copy(loading = false, error = detail.error) }
                return@launch
            }
            val group = (detail as GroupResult.Ok).value
            val members = (repository.members(groupId) as? GroupResult.Ok)?.value.orEmpty()
            val invites = if (group.canInvite) (repository.invitations(groupId) as? GroupResult.Ok)?.value.orEmpty() else emptyList()
            _state.update { it.copy(loading = false, group = group, members = members, invitations = invites, error = null) }
        }
    }

    fun show(dialog: DetailDialog?) = _state.update { it.copy(dialog = dialog, actionError = null) }

    fun updateDialog(block: (DetailDialog) -> DetailDialog) = _state.update { s -> s.copy(dialog = s.dialog?.let(block), actionError = null) }

    fun rename(name: String, kind: GroupKind) = act { repository.update(groupId, name, kind) }
    fun changeRole(member: GroupMember, role: GroupRole) = act { repository.changeRole(groupId, member.userId, role) }
    fun remove(member: GroupMember) = act { repository.removeMember(groupId, member.userId) }
    fun makeOwner(member: GroupMember) = act { repository.transferOwnership(groupId, member.userId) }
    fun revoke(invitation: GroupInvitation) = act { repository.revoke(invitation.id) }
    fun leave() = act(exitOnSuccess = true) { repository.leave(groupId) }
    fun delete() = act(exitOnSuccess = true) { repository.delete(groupId) }

    fun createInvite(role: GroupRole, email: String, maxUses: Int) {
        _state.update { it.copy(working = true, actionError = null) }
        viewModelScope.launch {
            when (val r = repository.invite(groupId, role, email, maxUses)) {
                is GroupResult.Ok -> {
                    _state.update { it.copy(working = false, dialog = DetailDialog.InviteReady(r.value)) }
                    refresh()
                }
                is GroupResult.Err -> _state.update { it.copy(working = false, actionError = r.error) }
            }
        }
    }

    private fun act(exitOnSuccess: Boolean = false, block: suspend () -> GroupResult<*>) {
        _state.update { it.copy(working = true, actionError = null) }
        viewModelScope.launch {
            when (val r = block()) {
                is GroupResult.Ok -> {
                    _state.update { it.copy(working = false, dialog = null) }
                    if (exitOnSuccess) _left.send(Unit) else refresh()
                }
                is GroupResult.Err -> _state.update { it.copy(working = false, actionError = r.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupDetailScreen(
    onBack: () -> Unit,
    onRoles: (String) -> Unit,
    onMemberPermissions: (groupId: String, userId: String, name: String) -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.leftEvents.collect { onBack() } }
    val g = state.group

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(g?.name.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (g != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.detail_more)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(stringResource(R.string.perm_title)) }, { menu = false; onRoles(g.id) })
                                if (g.canEditSettings) DropdownMenuItem({ Text(stringResource(R.string.detail_rename)) }, { menu = false; viewModel.show(DetailDialog.Rename(g.name, g.kind)) })
                                DropdownMenuItem({ Text(stringResource(R.string.detail_leave)) }, { menu = false; viewModel.show(DetailDialog.ConfirmLeave) })
                                if (g.isOwner) DropdownMenuItem({ Text(stringResource(R.string.detail_delete), color = MaterialTheme.colorScheme.error) }, { menu = false; viewModel.show(DetailDialog.ConfirmDelete) })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            return@Scaffold
        }
        if (g == null) {
            state.error?.let { ErrorBanner(it.message(), Modifier.padding(padding).padding(Spacing.md)) }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(g.name, style = MaterialTheme.typography.titleLarge) },
                        supportingContent = { Text("${g.kind.label()} · ${pluralStringResource(R.plurals.groups_members, g.memberCount.toInt(), g.memberCount.toInt())} · ${g.myRole.label()}") },
                        leadingContent = { Icon(g.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            state.actionError?.takeIf { state.dialog == null }?.let { e -> item { ErrorBanner(e.message()) } }
            if (g.canInvite) {
                item { PrimaryButton(stringResource(R.string.detail_invite), { viewModel.show(DetailDialog.Invite()) }, Modifier.padding(vertical = Spacing.sm)) }
            }
            item { Text(stringResource(R.string.detail_members), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm)) }
            items(state.members, key = { it.userId }) { m ->
                val manageable = g.canManageMembers && !m.isMe && m.role != GroupRole.OWNER && (m.role != GroupRole.ADMIN || g.isOwner)
                ListItem(
                    headlineContent = { Text(if (m.isMe) "${m.displayName} (${stringResource(R.string.detail_you)})" else m.displayName) },
                    supportingContent = { Text(listOfNotNull(m.role.label(), m.email).joinToString(" · ")) },
                    leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = if (manageable || (g.isOwner && !m.isMe) || (g.canManagePermissions && !m.isMe)) Modifier.clickable { viewModel.show(DetailDialog.Member(m)) } else Modifier,
                )
            }
            if (state.invitations.isNotEmpty()) {
                item { Text(stringResource(R.string.detail_invitations), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.md)) }
                items(state.invitations, key = { it.id }) { inv ->
                    ListItem(
                        headlineContent = { Text(inv.email?.let { stringResource(R.string.invite_for, it) } ?: inv.role.label()) },
                        supportingContent = {
                            Text(stringResource(R.string.invite_uses_left, inv.uses, inv.maxUses, inv.expiresAt.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
                        },
                        leadingContent = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                        trailingContent = { TextButton(onClick = { viewModel.revoke(inv) }) { Text(stringResource(R.string.invite_revoke)) } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            }
        }
    }
    state.dialog?.let { Dialogs(it, state, viewModel, snackbar, onMemberPermissions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dialogs(
    dialog: DetailDialog,
    state: GroupDetailUiState,
    viewModel: GroupDetailViewModel,
    snackbar: SnackbarHostState,
    onMemberPermissions: (String, String, String) -> Unit,
) {
    val g = state.group ?: return
    val dismiss = { viewModel.show(null) }
    val error: @Composable () -> Unit = { state.actionError?.let { ErrorBanner(it.message()) } }
    when (dialog) {
        is DetailDialog.Rename -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.detail_rename)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(dialog.name, { v -> viewModel.updateDialog { (it as DetailDialog.Rename).copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.create_name)) }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        GroupKind.entries.forEach { k -> FilterChip(dialog.kind == k, { viewModel.updateDialog { (it as DetailDialog.Rename).copy(kind = k) } }, { Text(k.label()) }) }
                    }
                    error()
                }
            },
            confirmButton = { TextButton(onClick = { if (dialog.name.isNotBlank()) viewModel.rename(dialog.name, dialog.kind) }, enabled = !state.working) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )
        is DetailDialog.Member -> {
            val m = dialog.member
            val canChange = g.canManageMembers && m.role != GroupRole.OWNER && (m.role != GroupRole.ADMIN || g.isOwner)
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(m.displayName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (canChange) {
                            Text(stringResource(R.string.member_change_role), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                listOfNotNull(GroupRole.ADMIN.takeIf { g.isOwner }, GroupRole.MEMBER, GroupRole.VIEWER).forEach { r ->
                                    FilterChip(m.role == r, { if (m.role != r) viewModel.changeRole(m, r) }, { Text(r.label()) })
                                }
                            }
                            TextButton(onClick = { viewModel.show(DetailDialog.ConfirmRemove(m)) }) { Text(stringResource(R.string.member_remove), color = MaterialTheme.colorScheme.error) }
                        }
                        val permissionsEditable = g.canManagePermissions && m.role != GroupRole.OWNER && (m.role != GroupRole.ADMIN || g.isOwner)
                        if (permissionsEditable) TextButton(onClick = { viewModel.show(null); onMemberPermissions(g.id, m.userId, m.displayName) }) { Text(stringResource(R.string.perm_open)) }
                        if (g.isOwner) TextButton(onClick = { viewModel.show(DetailDialog.ConfirmOwner(m)) }) { Text(stringResource(R.string.member_make_owner)) }
                        error()
                    }
                },
                confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.ok)) } },
            )
        }
        is DetailDialog.ConfirmRemove -> Confirm(
            stringResource(R.string.member_remove_title, dialog.member.displayName), stringResource(R.string.member_remove_body),
            stringResource(R.string.member_confirm_remove), state, { viewModel.remove(dialog.member) }, dismiss,
        )
        is DetailDialog.ConfirmOwner -> Confirm(
            stringResource(R.string.member_owner_title, dialog.member.displayName), stringResource(R.string.member_owner_body),
            stringResource(R.string.member_confirm_owner), state, { viewModel.makeOwner(dialog.member) }, dismiss,
        )
        DetailDialog.ConfirmLeave -> Confirm(
            stringResource(R.string.detail_leave_title),
            stringResource(if (g.isOwner && g.memberCount <= 1L) R.string.detail_leave_last_body else R.string.detail_leave_body),
            stringResource(R.string.detail_confirm_leave), state, viewModel::leave, dismiss,
        )
        DetailDialog.ConfirmDelete -> Confirm(
            stringResource(R.string.detail_delete_title), stringResource(R.string.detail_delete_body),
            stringResource(R.string.detail_confirm_delete), state, viewModel::delete, dismiss,
        )
        is DetailDialog.Invite -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.invite_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.invite_role), style = MaterialTheme.typography.labelLarge)
                    listOfNotNull(GroupRole.ADMIN.takeIf { g.isOwner }, GroupRole.MEMBER, GroupRole.VIEWER).forEach { r ->
                        ListItem(
                            headlineContent = { Text(r.label()) },
                            supportingContent = { Text(r.description()) },
                            leadingContent = { androidx.compose.material3.RadioButton(selected = dialog.role == r, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable { viewModel.updateDialog { (it as DetailDialog.Invite).copy(role = r) } },
                        )
                    }
                    OutlinedTextField(
                        dialog.email, { v -> viewModel.updateDialog { (it as DetailDialog.Invite).copy(email = v.trim().take(254)) } },
                        label = { Text(stringResource(R.string.invite_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                    )
                    Text(stringResource(R.string.invite_email_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dialog.email.isBlank()) {
                        Text(stringResource(R.string.invite_uses), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            listOf(1, 5, 10, 25).forEach { n -> FilterChip(dialog.maxUses == n, { viewModel.updateDialog { (it as DetailDialog.Invite).copy(maxUses = n) } }, { Text("$n") }) }
                        }
                    }
                    error()
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createInvite(dialog.role, dialog.email, if (dialog.email.isBlank()) dialog.maxUses else 1) }, enabled = !state.working) {
                    Text(stringResource(R.string.invite_create))
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )
        is DetailDialog.InviteReady -> {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val copiedText = stringResource(R.string.invite_copied)
            val shareText = stringResource(R.string.invite_share_text, g.name, dialog.invite.link)
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.invite_ready_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(R.string.invite_ready_body, dialog.invite.invitation.role.label()))
                        Text(dialog.invite.link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = {
                            copy(context, dialog.invite.link)
                            scope.launch { snackbar.showSnackbar(copiedText) }
                        }) { Text(stringResource(R.string.invite_copy)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), null))
                    }) { Text(stringResource(R.string.invite_share)) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.ok)) } },
            )
        }
    }
}

@Composable
private fun Confirm(title: String, body: String, confirm: String, state: GroupDetailUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(body)
                state.actionError?.let { ErrorBanner(it.message()) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !state.working) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Daily Money invite", text))
}
