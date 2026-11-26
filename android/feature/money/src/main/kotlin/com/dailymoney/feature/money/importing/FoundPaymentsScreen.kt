package com.dailymoney.feature.money.importing

import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.core.domain.smart.PaymentSuggestion
import com.dailymoney.core.domain.smart.PaymentSuggestionRepository
import com.dailymoney.core.domain.smart.SuggestionStatus
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.mediumLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class FoundPaymentsViewModel @Inject constructor(
    private val repository: PaymentSuggestionRepository,
    config: AppConfig,
) : ViewModel() {
    val currency = config.defaultCurrency
    val pending: StateFlow<List<PaymentSuggestion>?> = repository.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    sealed interface Event {
        data class Added(val suggestion: PaymentSuggestion) : Event
        data class Dismissed(val id: String) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var opened: String? = null

    /** Remembered so that, back on this screen, we can say whether it was saved. */
    fun open(id: String) {
        opened = id
    }

    fun onReturn() {
        val id = opened ?: return
        opened = null
        viewModelScope.launch {
            repository.get(id)?.takeIf { it.status == SuggestionStatus.ADDED }?.let { _events.send(Event.Added(it)) }
        }
    }

    fun dismiss(id: String) {
        viewModelScope.launch {
            repository.setStatus(id, SuggestionStatus.DISMISSED)
            _events.send(Event.Dismissed(id))
        }
    }

    fun undoDismiss(id: String) {
        viewModelScope.launch { repository.setStatus(id, SuggestionStatus.NEW) }
    }

    fun dismissAll() {
        viewModelScope.launch { repository.dismissAll() }
    }
}

/** Payments read from bank SMS, each added (through the normal form) or dismissed by the user. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoundPaymentsScreen(onBack: () -> Unit, onAdd: (String) -> Unit, viewModel: FoundPaymentsViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val addedText = stringResource(R.string.found_added_snack)
    val dismissedText = stringResource(R.string.found_dismissed_snack)
    val undoText = stringResource(R.string.found_undo)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onReturn() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is FoundPaymentsViewModel.Event.Added -> {
                    val p = e.suggestion.payment
                    snackbar.showSnackbar(addedText.format(MoneyFormatter.format(p.amountMinor, viewModel.currency)))
                }
                is FoundPaymentsViewModel.Event.Dismissed ->
                    if (snackbar.showSnackbar(dismissedText, actionLabel = undoText) == SnackbarResult.ActionPerformed) viewModel.undoDismiss(e.id)
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.found_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (!pending.isNullOrEmpty()) TextButton(onClick = viewModel::dismissAll) { Text(stringResource(R.string.found_dismiss_all)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val list = pending ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(Icons.Filled.Sms, stringResource(R.string.found_empty_title), stringResource(R.string.found_empty_body), Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Text(stringResource(R.string.found_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.id }) { s ->
                FoundCard(s, viewModel.currency, onAdd = {
                    viewModel.open(s.id)
                    onAdd(s.id)
                }, onDismiss = { viewModel.dismiss(s.id) })
            }
        }
    }
}

@Composable
private fun FoundCard(s: PaymentSuggestion, currency: String, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val p = s.payment
    val income = p.type == TransactionType.INCOME
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.counterparty ?: stringResource(if (income) R.string.found_money_in else R.string.found_money_out),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    MoneyFormatter.format(if (income) p.amountMinor else -p.amountMinor, currency, signed = true),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                listOfNotNull(p.date?.mediumLabel(), s.sender, p.accountHint?.let { stringResource(R.string.found_account, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xs)) {
                Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.found_add)) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.found_dismiss)) }
            }
        }
    }
}
