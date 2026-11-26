package com.dailymoney.feature.money.transactions

import kotlinx.coroutines.launch
import com.dailymoney.core.domain.money.AttachmentRepository
import com.dailymoney.core.domain.money.DeleteTransactionUseCase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.MonthTransactions
import com.dailymoney.core.domain.money.ObserveMonthTransactionsUseCase
import com.dailymoney.core.domain.money.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class TransactionsUiState(
    val month: YearMonth,
    val isCurrentMonth: Boolean,
    val type: TransactionType? = null,
    val query: String = "",
    val currency: String,
    val today: LocalDate,
    val data: MonthTransactions? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val observeMonth: ObserveMonthTransactionsUseCase,
    private val deleteTransaction: DeleteTransactionUseCase,
    private val attachments: AttachmentRepository,
    config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    private data class Filters(val month: YearMonth, val type: TransactionType?, val query: String)

    private val filters = MutableStateFlow(Filters(YearMonth.now(clock), null, ""))
    private val currency = config.defaultCurrency

    val state: StateFlow<TransactionsUiState> = combine(
        filters,
        filters.debounce(250).flatMapLatest { f -> observeMonth(f.month, f.type, f.query).map<MonthTransactions, MonthTransactions?> { it }.onStart { emit(null) } },
    ) { f, data ->
        TransactionsUiState(
            month = f.month,
            isCurrentMonth = f.month == YearMonth.now(clock),
            type = f.type,
            query = f.query,
            currency = currency,
            today = LocalDate.now(clock),
            data = data,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TransactionsUiState(YearMonth.now(clock), true, currency = currency, today = LocalDate.now(clock)),
    )

    fun previousMonth() = filters.update { it.copy(month = it.month.minusMonths(1)) }
    fun nextMonth() = filters.update { if (it.month < YearMonth.now(clock)) it.copy(month = it.month.plusMonths(1)) else it }
    fun setType(type: TransactionType?) = filters.update { it.copy(type = type) }
    fun setQuery(query: String) = filters.update { it.copy(query = query) }

    /** Same as deleting from the transaction screen: its receipts go too; a shared one leaves the group. */
    fun delete(id: String) {
        viewModelScope.launch {
            deleteTransaction(id)
            attachments.deleteFor(id)
        }
    }
}
