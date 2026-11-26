package com.dailymoney.feature.home.home

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.smart.PaymentSuggestionRepository
import com.dailymoney.core.domain.smart.SmsInbox
import com.dailymoney.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class FoundPaymentsCardViewModel @Inject constructor(inbox: SmsInbox, repository: PaymentSuggestionRepository) : ViewModel() {
    val count = (if (inbox.supported) repository.observePendingCount() else flowOf(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        if (inbox.supported) viewModelScope.launch { inbox.refreshIfRulesChanged() }
    }
}

/** "3 payments found in your SMS · Review": shown only while some are waiting. */
@Composable
internal fun FoundPaymentsCard(onOpen: () -> Unit, viewModel: FoundPaymentsCardViewModel = hiltViewModel()) {
    val count by viewModel.count.collectAsStateWithLifecycle()
    if (count == 0) return
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Sms, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                pluralStringResource(R.plurals.home_found_payments, count, count),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
            )
            TextButton(onClick = onOpen) { Text(stringResource(R.string.home_found_review)) }
        }
    }
}
