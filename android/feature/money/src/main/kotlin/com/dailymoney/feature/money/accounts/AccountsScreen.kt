package com.dailymoney.feature.money.accounts

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.money.AmountStat
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.accountIcon
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.AccountBalance
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.AccountType
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.money.NameError
import com.dailymoney.core.domain.money.SaveAccountUseCase
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.BackButton
import com.dailymoney.feature.money.common.label
import com.dailymoney.feature.money.common.message
import com.dailymoney.feature.money.common.sanitizeAmountInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditor(
    val id: String?,
    val name: String,
    val type: AccountType,
    val openingText: String,
    val archived: Boolean,
    val nameError: NameError? = null,
)

data class AccountsUiState(
    val accounts: List<AccountBalance> = emptyList(),
    val currency: String,
    val editor: AccountEditor? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val saveAccount: SaveAccountUseCase,
    config: AppConfig,
) : ViewModel() {

    private val currency = config.defaultCurrency
    private val editor = MutableStateFlow<AccountEditor?>(null)

    val state: StateFlow<AccountsUiState> = combine(repository.observeBalances(), editor) { accounts, e ->
        AccountsUiState(accounts, currency, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState(currency = currency))

    fun add() {
        editor.value = AccountEditor(null, "", AccountType.BANK, "", false)
    }

    fun edit(account: Account) {
        editor.value = AccountEditor(
            account.id, account.name, account.type,
            MoneyFormatter.toInput(account.openingMinor, account.currency), account.archived,
        )
    }

    fun dismiss() {
        editor.value = null
    }

    fun onName(v: String) = editor.update { it?.copy(name = v, nameError = null) }
    fun onType(v: AccountType) = editor.update { it?.copy(type = v) }
    fun onOpening(v: String) = editor.update { it?.copy(openingText = sanitizeAmountInput(v, Currencies.exponent(currency))) }
    fun onArchived(v: Boolean) = editor.update { it?.copy(archived = v) }

    fun save() {
        val e = editor.value ?: return
        viewModelScope.launch {
            val account = Account(
                id = e.id ?: Ids.newId(),
                name = e.name,
                type = e.type,
                currency = currency,
                openingMinor = MoneyParser.parse(e.openingText, currency) ?: 0,
                archived = e.archived,
            )
            val error = saveAccount(account, state.value.accounts.map { it.account })
            if (error == null) editor.value = null else editor.update { it?.copy(nameError = error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountsScreen(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.money_accounts)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::add) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.accounts_add))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenGutter, end = Spacing.screenGutter, bottom = 88.dp),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    AmountStat(
                        stringResource(R.string.accounts_total),
                        state.accounts.filterNot { it.account.archived }.sumOf { it.balanceMinor },
                        state.currency,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
            }
            items(state.accounts, key = { it.account.id }) { item ->
                val a = item.account
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = {
                        Text(if (a.archived) "${a.type.label()} · ${stringResource(R.string.accounts_archived)}" else a.type.label())
                    },
                    leadingContent = { Icon(accountIcon(a.type), contentDescription = null) },
                    trailingContent = { MoneyText(item.balanceMinor, a.currency) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { viewModel.edit(a) },
                )
            }
        }
    }
    state.editor?.let { AccountDialog(it, state.currency, viewModel) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountDialog(editor: AccountEditor, currency: String, viewModel: AccountsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(if (editor.id == null) R.string.accounts_add else R.string.accounts_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = viewModel::onName,
                    label = { Text(stringResource(R.string.accounts_name)) },
                    isError = editor.nameError != null,
                    supportingText = editor.nameError?.let { { Text(it.message()) } },
                    singleLine = true,
                )
                Text(stringResource(R.string.accounts_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    AccountType.entries.forEach { type ->
                        FilterChip(editor.type == type, { viewModel.onType(type) }, { Text(type.label()) })
                    }
                }
                OutlinedTextField(
                    value = editor.openingText,
                    onValueChange = viewModel::onOpening,
                    label = { Text(stringResource(R.string.accounts_opening)) },
                    prefix = { Text(Currencies.symbol(currency)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                if (editor.id != null) {
                    FilterChip(
                        selected = editor.archived,
                        onClick = { viewModel.onArchived(!editor.archived) },
                        label = { Text(stringResource(if (editor.archived) R.string.accounts_unarchive else R.string.accounts_archive)) },
                    )
                    Text(stringResource(R.string.accounts_archive_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
