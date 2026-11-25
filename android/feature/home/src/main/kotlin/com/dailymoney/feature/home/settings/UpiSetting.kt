package com.dailymoney.feature.home.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.sharing.SettleResult
import com.dailymoney.core.domain.sharing.SettlementRepository
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import com.dailymoney.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpiUiState(
    val enabled: Boolean,
    val current: String? = null,
    /** Non-null while the edit dialog is open. */
    val editing: String? = null,
    val saving: Boolean = false,
    val error: SettleResult? = null,
)

/** The person's own UPI ID, shown to members of their groups so they can be paid through a UPI app. */
@HiltViewModel
class UpiViewModel @Inject constructor(
    config: AppConfig,
    shared: SharedExpenseRepository,
    private val settlements: SettlementRepository,
) : ViewModel() {

    private val edit = MutableStateFlow(UpiUiState(enabled = config.backendEnabled))

    val state: StateFlow<UpiUiState> = combine(edit, shared.observeMe()) { e, me -> e.copy(current = me?.upiId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), edit.value)

    fun open() = edit.update { it.copy(editing = state.value.current.orEmpty(), error = null) }
    fun change(text: String) = edit.update { it.copy(editing = text.filter { c -> !c.isWhitespace() }.take(100), error = null) }
    fun close() = edit.update { it.copy(editing = null, error = null) }

    fun save() {
        val value = edit.value.editing ?: return
        edit.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = settlements.setUpiId(value)
            edit.update { if (result == SettleResult.Ok) it.copy(saving = false, editing = null) else it.copy(saving = false, error = result) }
        }
    }
}

@Composable
internal fun UpiIdItem(viewModel: UpiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.enabled) return
    ListItem(
        headlineContent = { Text(stringResource(R.string.upi_title)) },
        supportingContent = { Text(state.current ?: stringResource(R.string.upi_not_set)) },
        leadingContent = { Icon(Icons.Filled.CurrencyRupee, contentDescription = null) },
        modifier = Modifier.clickable(onClick = viewModel::open),
    )
    val editing = state.editing ?: return
    AlertDialog(
        onDismissRequest = viewModel::close,
        title = { Text(stringResource(R.string.upi_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = editing,
                    onValueChange = viewModel::change,
                    label = { Text(stringResource(R.string.upi_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                Text(stringResource(R.string.upi_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { e ->
                    ErrorBanner(
                        stringResource(
                            when {
                                e is SettleResult.Refused && e.code == "INVALID_UPI_ID" -> R.string.upi_invalid
                                e == SettleResult.Offline -> R.string.upi_offline
                                else -> R.string.upi_failed
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save, enabled = !state.saving) { Text(stringResource(R.string.upi_save)) } },
        dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.upi_cancel)) } },
    )
}
