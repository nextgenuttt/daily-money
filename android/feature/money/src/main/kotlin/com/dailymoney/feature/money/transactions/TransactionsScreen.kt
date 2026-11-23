package com.dailymoney.feature.money.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.money.AmountStat
import com.dailymoney.core.designsystem.money.CategoryBadge
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.MonthSelector
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.TransactionDetails
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.dayLabel
import com.dailymoney.feature.money.common.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionsScreen(
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onAccounts: () -> Unit,
    onMenu: (MoneyMenuItem) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.money_title)) },
                actions = {
                    IconButton(onClick = onAccounts) {
                        Icon(Icons.Filled.AccountBalance, contentDescription = stringResource(R.string.money_accounts))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.money_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            MoneyMenuItem.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(item.label)) },
                                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onMenu(item)
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.money_add_transaction))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            item {
                MonthSelector(
                    label = state.month.label(),
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                    previousLabel = stringResource(R.string.money_previous_month),
                    nextLabel = stringResource(R.string.money_next_month),
                    nextEnabled = !state.isCurrentMonth,
                )
            }
            item { SummaryCard(state) }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.money_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(vertical = Spacing.sm),
                ) {
                    item {
                        FilterChip(state.type == null, { viewModel.setType(null) }, { Text(stringResource(R.string.money_all)) })
                    }
                    items(TransactionType.entries) { type ->
                        FilterChip(state.type == type, { viewModel.setType(type) }, { Text(type.label()) })
                    }
                }
            }
            val data = state.data
            when {
                data == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                data.days.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Filled.ReceiptLong,
                        title = stringResource(if (state.query.isBlank() && state.type == null) R.string.money_empty_title else R.string.money_no_results),
                        message = if (state.query.isBlank() && state.type == null) stringResource(R.string.money_empty_body) else "",
                    )
                }
                else -> data.days.forEach { (date, entries) ->
                    item(key = date.toString()) {
                        Text(
                            date.dayLabel(state.today),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
                        )
                    }
                    items(entries, key = { it.transaction.id }) { details ->
                        TransactionRow(details, onClick = { onOpen(details.transaction.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: TransactionsUiState) {
    val totals = state.data?.totals
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
            AmountStat(stringResource(R.string.money_income), totals?.incomeMinor ?: 0, state.currency, color = LocalStatusColors.current.income)
            AmountStat(stringResource(R.string.money_expenses), totals?.expenseMinor ?: 0, state.currency)
            AmountStat(stringResource(R.string.money_net), totals?.netMinor ?: 0, state.currency)
        }
    }
}

@Composable
internal fun TransactionRow(details: TransactionDetails, onClick: () -> Unit) {
    val t = details.transaction
    val isTransfer = t.type == TransactionType.TRANSFER
    val title = when {
        isTransfer -> stringResource(R.string.money_transfer)
        else -> details.category?.name ?: stringResource(R.string.money_uncategorized)
    }
    val subtitle = when {
        isTransfer -> stringResource(R.string.money_transfer_to, details.account?.name.orEmpty(), details.toAccount?.name.orEmpty())
        else -> listOfNotNull(details.account?.name, t.note).joinToString(" · ")
    }
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            CategoryBadge(if (isTransfer) "transfer" else details.category?.iconKey, details.category?.colorIndex ?: 5)
        },
        trailingContent = { MoneyText(t.amountMinor, t.currency, type = t.type) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onClick),
    )
}


enum class MoneyMenuItem(@StringRes val label: Int, val icon: ImageVector) {
    CATEGORIES(R.string.money_categories, Icons.Filled.Category),
    RECURRING(R.string.recurring_title, Icons.Filled.Autorenew),
    SUBSCRIPTIONS(R.string.subs_title, Icons.Filled.Subscriptions),
    GOALS(R.string.goals_title, Icons.Filled.Savings),
    DEBTS(R.string.debts_title, Icons.Filled.Handshake),
    EXPORT(R.string.export_title, Icons.Filled.FileDownload),
}
