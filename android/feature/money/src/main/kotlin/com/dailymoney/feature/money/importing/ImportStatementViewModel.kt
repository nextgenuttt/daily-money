package com.dailymoney.feature.money.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.smart.ColumnMapping
import com.dailymoney.core.domain.smart.ImportCandidate
import com.dailymoney.core.domain.smart.ImportStatementUseCase
import com.dailymoney.core.domain.smart.ImportStatus
import com.dailymoney.core.domain.smart.PreviewImportUseCase
import com.dailymoney.core.domain.smart.ReadStatementUseCase
import com.dailymoney.core.domain.smart.StatementFile
import com.dailymoney.core.domain.smart.StatementParser
import com.dailymoney.core.domain.smart.StatementResult
import com.dailymoney.core.domain.smart.StatementRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportStep { PICK, LOADING, MAPPING, PREVIEW, DONE }

enum class ImportProblem { UNREADABLE, NO_ROWS }

data class ImportUiState(
    val step: ImportStep = ImportStep.PICK,
    val problem: ImportProblem? = null,
    /** Header names, for choosing columns by hand. */
    val columns: List<String> = emptyList(),
    val mapping: ColumnMapping? = null,
    val skipped: Int = 0,
    val accounts: List<Account> = emptyList(),
    val accountId: String? = null,
    val candidates: List<ImportCandidate> = emptyList(),
    val categories: List<Category> = emptyList(),
    val importing: Boolean = false,
    val imported: Int = 0,
) {
    val selectedCount get() = candidates.count { it.selected && it.status != ImportStatus.ALREADY_IMPORTED }
    val account get() = accounts.firstOrNull { it.id == accountId }
}

@HiltViewModel
class ImportStatementViewModel @Inject constructor(
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val readStatement: ReadStatementUseCase,
    private val preview: PreviewImportUseCase,
    private val importStatement: ImportStatementUseCase,
    private val config: AppConfig,
) : ViewModel() {

    private val ui = MutableStateFlow(ImportUiState())
    private var fileRows: List<List<String>> = emptyList()
    private var rows: List<StatementRow> = emptyList()

    val state: StateFlow<ImportUiState> = combine(ui, accounts.observeBalances(), categories.observe()) { s, balances, cats ->
        val open = balances.map { it.account }.filter { !it.archived }
        s.copy(accounts = open, accountId = s.accountId ?: open.firstOrNull()?.id, categories = cats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUiState())

    fun onFile(uri: String) {
        ui.update { ImportUiState(step = ImportStep.LOADING, accountId = it.accountId) }
        viewModelScope.launch {
            when (val file = readStatement(uri, currency())) {
                StatementFile.Unreadable -> ui.update { it.copy(step = ImportStep.PICK, problem = ImportProblem.UNREADABLE) }
                is StatementFile.Read -> {
                    fileRows = file.rows
                    show(file.result)
                }
            }
        }
    }

    /** Columns chosen by hand when the headers weren't recognised. */
    fun useMapping(mapping: ColumnMapping) {
        if (!mapping.isComplete) return
        viewModelScope.launch { show(StatementParser.parse(fileRows, mapping, currency())) }
    }

    /** For a single amount column: flips whether plus means money in or money spent. */
    fun setPositiveIsIncome(value: Boolean) {
        val mapping = ui.value.mapping ?: return
        useMapping(mapping.copy(positiveIsIncome = value))
    }

    fun selectAccount(id: String) {
        if (id == state.value.accountId) return
        ui.update { it.copy(accountId = id) }
        viewModelScope.launch { refreshCandidates() }
    }

    fun toggle(index: Int) = ui.update { s ->
        s.copy(candidates = s.candidates.mapIndexed { i, c -> if (i == index && c.status != ImportStatus.ALREADY_IMPORTED) c.copy(selected = !c.selected) else c })
    }

    fun setCategory(index: Int, categoryId: String) = ui.update { s ->
        s.copy(candidates = s.candidates.mapIndexed { i, c -> if (i == index) c.copy(categoryId = categoryId) else c })
    }

    fun import() {
        val s = state.value
        val account = s.account ?: return
        if (s.importing || s.selectedCount == 0) return
        ui.update { it.copy(importing = true) }
        viewModelScope.launch {
            val added = importStatement(account.id, account.currency, s.candidates)
            ui.update { it.copy(importing = false, step = ImportStep.DONE, imported = added) }
        }
    }

    fun startOver() {
        fileRows = emptyList()
        rows = emptyList()
        ui.update { ImportUiState(accountId = it.accountId) }
    }

    private suspend fun show(result: StatementResult) {
        when (result) {
            StatementResult.Empty -> ui.update { it.copy(step = ImportStep.PICK, problem = ImportProblem.NO_ROWS) }
            is StatementResult.NeedsMapping -> ui.update {
                it.copy(step = ImportStep.MAPPING, columns = result.columns, mapping = ColumnMapping(result.headerRow, -1, -1), problem = null)
            }
            is StatementResult.Ok -> {
                if (result.rows.isEmpty()) {
                    // Headers looked right but nothing could be read: let the user pick the columns.
                    ui.update { it.copy(step = ImportStep.MAPPING, columns = fileRows.getOrNull(result.mapping.headerRow).orEmpty(), mapping = result.mapping, problem = ImportProblem.NO_ROWS) }
                    return
                }
                rows = result.rows
                ui.update { it.copy(step = ImportStep.PREVIEW, mapping = result.mapping, skipped = result.skipped, problem = null,
                    columns = fileRows.getOrNull(result.mapping.headerRow).orEmpty()) }
                refreshCandidates()
            }
        }
    }

    private suspend fun refreshCandidates() {
        val accountId = state.first { it.accountId != null || it.accounts.isEmpty() }.accountId ?: return
        val candidates = preview(accountId, rows)
        ui.update { it.copy(candidates = candidates) }
    }

    private fun currency() = state.value.account?.currency ?: config.defaultCurrency
}
