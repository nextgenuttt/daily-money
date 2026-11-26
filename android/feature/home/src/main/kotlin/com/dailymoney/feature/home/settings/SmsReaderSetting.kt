package com.dailymoney.feature.home.settings

import android.content.pm.ApplicationInfo
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.smart.PaymentSuggestionRepository
import com.dailymoney.core.domain.smart.SmsInbox
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

data class SmsReaderUi(
    val enabled: Boolean = false,
    val permitted: Boolean = false,
    val showDisclosure: Boolean = false,
    val denied: Boolean = false,
    val scanning: Boolean = false,
    /** Payments found by the first look-back, shown once after turning it on. */
    val found: Int? = null,
)

@HiltViewModel
class SmsReaderViewModel @Inject constructor(
    private val inbox: SmsInbox,
    private val suggestions: PaymentSuggestionRepository,
) : ViewModel() {

    val supported = inbox.supported
    private val local = MutableStateFlow(SmsReaderUi(permitted = inbox.hasPermission()))

    val state: StateFlow<SmsReaderUi> = combine(local, suggestions.observeEnabled()) { ui, on -> ui.copy(enabled = on && ui.permitted) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun refresh() = local.update { it.copy(permitted = inbox.hasPermission()) }

    /** Play policy: say exactly what is read and why before Android asks for the permission. */
    fun onToggle(on: Boolean) {
        if (on) {
            local.update { it.copy(showDisclosure = true, denied = false, found = null) }
        } else {
            viewModelScope.launch { suggestions.setEnabled(false) }
            local.update { it.copy(found = null) }
        }
    }

    fun dismissDisclosure() = local.update { it.copy(showDisclosure = false) }

    private val _testResult = MutableStateFlow<Boolean?>(null)
    val testResult: StateFlow<Boolean?> = _testResult

    fun simulate() {
        viewModelScope.launch { _testResult.value = inbox.simulateIncoming() }
    }

    fun onPermissionResult(granted: Boolean) {
        local.update { it.copy(showDisclosure = false, permitted = granted, denied = !granted) }
        if (!granted) return
        local.update { it.copy(scanning = true) }
        viewModelScope.launch {
            suggestions.setEnabled(true)
            val found = inbox.scan(LOOK_BACK_DAYS)
            local.update { it.copy(scanning = false, found = found) }
        }
    }

    companion object {
        const val LOOK_BACK_DAYS = 30
    }
}

/** Only in the SMS build; the standard build shows nothing here. */
@Composable
internal fun SmsReaderSetting(viewModel: SmsReaderViewModel = hiltViewModel()) {
    if (!viewModel.supported) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onPermissionResult(result.values.isNotEmpty() && result.values.all { it })
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.sms_setting_title)) },
        supportingContent = {
            Text(
                when {
                    state.scanning -> stringResource(R.string.sms_setting_scanning)
                    state.found != null -> pluralStringResource(R.plurals.sms_setting_found, state.found!!, state.found!!, SmsReaderViewModel.LOOK_BACK_DAYS)
                    state.denied -> stringResource(R.string.sms_setting_denied)
                    state.enabled -> stringResource(R.string.sms_setting_on)
                    else -> stringResource(R.string.sms_setting_off)
                },
                color = if (state.denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(Icons.Filled.Sms, contentDescription = null) },
        trailingContent = { Switch(checked = state.enabled, onCheckedChange = viewModel::onToggle) },
    )
    // Only in debug builds: a real bank SMS can't be faked on a physical phone.
    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
        val tested by viewModel.testResult.collectAsStateWithLifecycle()
        TextButton(onClick = viewModel::simulate, modifier = Modifier.padding(start = Spacing.md)) {
            Text(stringResource(R.string.sms_test_button))
        }
        tested?.let {
            Text(
                stringResource(if (it) R.string.sms_test_done else R.string.sms_test_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
        }
    }
    if (state.denied) {
        TextButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            },
            modifier = Modifier.padding(start = Spacing.md),
        ) { Text(stringResource(R.string.sms_setting_open_settings)) }
    }

    if (state.showDisclosure) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDisclosure,
            icon = { Icon(Icons.Filled.Sms, contentDescription = null) },
            title = { Text(stringResource(R.string.sms_disclosure_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.sms_disclosure_what))
                    Text(stringResource(R.string.sms_disclosure_skip))
                    Text(stringResource(R.string.sms_disclosure_where))
                    Text(stringResource(R.string.sms_disclosure_off), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { request.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }) {
                    Text(stringResource(R.string.sms_disclosure_agree))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDisclosure) { Text(stringResource(R.string.sms_disclosure_no)) } },
        )
    }
}
