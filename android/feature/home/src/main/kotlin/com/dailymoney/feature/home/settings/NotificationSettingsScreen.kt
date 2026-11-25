package com.dailymoney.feature.home.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.sharing.NotificationCategory
import com.dailymoney.core.domain.sharing.NotificationPrefs
import com.dailymoney.core.domain.sharing.NotificationRepository
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import com.dailymoney.core.domain.sharing.SharedGroup
import com.dailymoney.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationSettingsState(val prefs: NotificationPrefs = NotificationPrefs(), val groups: List<SharedGroup> = emptyList())

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    shared: SharedExpenseRepository,
) : ViewModel() {

    val state: StateFlow<NotificationSettingsState> = combine(repository.observePrefs(), shared.observeGroups()) { prefs, groups ->
        NotificationSettingsState(prefs, groups)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsState())

    private fun change(block: (NotificationPrefs) -> NotificationPrefs) {
        viewModelScope.launch { repository.savePrefs(block(state.value.prefs)) }
    }

    fun setCategory(category: NotificationCategory, on: Boolean) = change { it.copy(disabled = if (on) it.disabled - category else it.disabled + category) }
    fun setGroupMuted(groupId: String, muted: Boolean) = change { it.copy(mutedGroups = if (muted) it.mutedGroups + groupId else it.mutedGroups - groupId) }
    fun setQuiet(on: Boolean) = change { it.copy(quietHours = it.quietHours.copy(enabled = on)) }
    fun setQuietStart(hour: Int) = change { it.copy(quietHours = it.quietHours.copy(startHour = hour)) }
    fun setQuietEnd(hour: Int) = change { it.copy(quietHours = it.quietHours.copy(endHour = hour)) }
    fun setDigest(on: Boolean) = change { it.copy(dailyDigest = on) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NotificationSettingsScreen(onBack: () -> Unit, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs = state.prefs
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notif_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.notif_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NotificationCategory.entries.forEach { c ->
                Toggle(c.label(), c.hint(), c !in prefs.disabled) { viewModel.setCategory(c, it) }
            }
            HorizontalDivider()
            Toggle(stringResource(R.string.notif_settings_quiet), stringResource(R.string.notif_settings_quiet_hint), prefs.quietHours.enabled, viewModel::setQuiet)
            if (prefs.quietHours.enabled) {
                Text(stringResource(R.string.notif_settings_from), style = MaterialTheme.typography.labelLarge)
                HourChips(prefs.quietHours.startHour, viewModel::setQuietStart)
                Text(stringResource(R.string.notif_settings_until), style = MaterialTheme.typography.labelLarge)
                HourChips(prefs.quietHours.endHour, viewModel::setQuietEnd)
            }
            Toggle(stringResource(R.string.notif_settings_digest), stringResource(R.string.notif_settings_digest_hint), prefs.dailyDigest, viewModel::setDigest)
            if (state.groups.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.notif_settings_groups), style = MaterialTheme.typography.titleMedium)
                state.groups.forEach { g ->
                    Toggle(g.name, null, g.id !in prefs.mutedGroups) { viewModel.setGroupMuted(g.id, !it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourChips(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        listOf(20, 21, 22, 23, 0, 5, 6, 7, 8, 9).forEach { h -> FilterChip(selected == h, { onSelect(h) }, { Text("%02d:00".format(h)) }) }
    }
}

@Composable
private fun Toggle(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NotificationCategory.label() = stringResource(
    when (this) {
        NotificationCategory.APPROVALS -> R.string.notif_cat_approvals
        NotificationCategory.PAYMENTS -> R.string.notif_cat_payments
        NotificationCategory.MESSAGES -> R.string.notif_cat_messages
        NotificationCategory.GROUP_ACTIVITY -> R.string.notif_cat_activity
    },
)

@Composable
private fun NotificationCategory.hint() = stringResource(
    when (this) {
        NotificationCategory.APPROVALS -> R.string.notif_cat_approvals_hint
        NotificationCategory.PAYMENTS -> R.string.notif_cat_payments_hint
        NotificationCategory.MESSAGES -> R.string.notif_cat_messages_hint
        NotificationCategory.GROUP_ACTIVITY -> R.string.notif_cat_activity_hint
    },
)
