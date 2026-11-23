package com.dailymoney.feature.money.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.Attachment
import com.dailymoney.core.domain.money.AttachmentRepository
import com.dailymoney.core.domain.money.AttachmentResult
import com.dailymoney.core.domain.money.CreateRecurringFromTransactionUseCase
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.DeleteTransactionUseCase
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.SaveResult
import com.dailymoney.core.domain.money.SaveTransactionUseCase
import com.dailymoney.core.domain.money.TransactionDraft
import com.dailymoney.core.domain.money.TransactionError
import com.dailymoney.core.domain.money.TransactionRepository
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.feature.money.common.sanitizeAmountInput
import com.dailymoney.feature.money.navigation.AddEditTransactionRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransactionForm(
    val type: TransactionType = TransactionType.EXPENSE,
    val amountText: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val date: LocalDate,
    val note: String = "",
    val paymentMethod: PaymentMethod? = null,
    val repeat: Frequency? = null,
)

enum class AttachmentMessage { TOO_LARGE, UNSUPPORTED, FAILED }

data class AddEditUiState(
    val isEdit: Boolean,
    val loading: Boolean,
    val notFound: Boolean = false,
    val form: TransactionForm,
    val currency: String,
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val errors: Set<TransactionError> = emptySet(),
    val saving: Boolean = false,
    val confirmDelete: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val attachmentMessage: AttachmentMessage? = null,
)

@HiltViewModel
class AddEditTransactionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactions: TransactionRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
    private val saveTransaction: SaveTransactionUseCase,
    private val deleteTransaction: DeleteTransactionUseCase,
    private val createRecurring: CreateRecurringFromTransactionUseCase,
    private val attachments: AttachmentRepository,
    config: AppConfig,
    clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AddEditTransactionRoute>()
    private val transactionId = route.transactionId

    /** Id used for receipts before a new transaction is saved; becomes the transaction's id on save. */
    private val entityId: String = transactionId ?: savedStateHandle.get<String>(KEY_DRAFT_ID) ?: Ids.newId().also { savedStateHandle[KEY_DRAFT_ID] = it }
    private var saved = false
    private val currency = config.defaultCurrency
    private var existingOccurredAt: Instant? = null

    private val form = MutableStateFlow(
        TransactionForm(
            type = route.type?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() } ?: TransactionType.EXPENSE,
            date = LocalDate.now(clock),
        ),
    )
    private val meta = MutableStateFlow(
        AddEditUiState(isEdit = transactionId != null, loading = transactionId != null, form = form.value, currency = currency),
    )

    private val _closeEvents = Channel<Unit>(Channel.BUFFERED)
    val closeEvents = _closeEvents.receiveAsFlow()

    val state: StateFlow<AddEditUiState> = combine(
        meta, form, categories.observe(), accounts.observeBalances(), attachments.observeFor(entityId),
    ) { m, f, cats, balances, files ->
        val kind = if (f.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        m.copy(
            form = f,
            categories = cats.filter { it.kind == kind },
            // Archived accounts stay selectable only when an old transaction already uses them.
            accounts = balances.map { it.account }.filter { !it.archived || it.id == f.accountId || it.id == f.toAccountId },
            attachments = files,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), meta.value)

    init {
        viewModelScope.launch {
            if (transactionId != null) {
                val existing = transactions.get(transactionId)
                if (existing == null) {
                    meta.update { it.copy(loading = false, notFound = true) }
                    return@launch
                }
                existingOccurredAt = existing.occurredAt
                form.value = TransactionForm(
                    type = existing.type,
                    amountText = MoneyFormatter.toInput(existing.amountMinor, existing.currency),
                    categoryId = existing.categoryId,
                    accountId = existing.accountId,
                    toAccountId = existing.toAccountId,
                    date = existing.occurredAt.atZone(clock.zone).toLocalDate(),
                    note = existing.note.orEmpty(),
                    paymentMethod = existing.paymentMethod,
                )
                meta.update { it.copy(loading = false) }
            } else {
                val firstAccount = accounts.observeBalances().first().firstOrNull { !it.account.archived }?.account?.id
                form.update { if (it.accountId == null) it.copy(accountId = firstAccount) else it }
            }
        }
    }

    fun setType(type: TransactionType) = form.update {
        if (it.type == type) it else it.copy(type = type, categoryId = null, toAccountId = null)
    }.also { clearError() }

    fun setAmount(text: String) = form.update { it.copy(amountText = sanitizeAmountInput(text, Currencies.exponent(currency))) }
        .also { clearError(TransactionError.AMOUNT_REQUIRED, TransactionError.AMOUNT_INVALID) }

    fun setCategory(id: String) = form.update { it.copy(categoryId = id) }.also { clearError(TransactionError.CATEGORY_REQUIRED) }
    fun setAccount(id: String) = form.update { it.copy(accountId = id) }.also { clearError(TransactionError.ACCOUNT_REQUIRED, TransactionError.SAME_ACCOUNT) }
    fun setToAccount(id: String) = form.update { it.copy(toAccountId = id) }.also { clearError(TransactionError.SAME_ACCOUNT) }
    fun setDate(date: LocalDate) = form.update { it.copy(date = date) }
    fun setNote(note: String) = form.update { it.copy(note = note) }.also { clearError(TransactionError.NOTE_TOO_LONG) }
    fun setPaymentMethod(method: PaymentMethod) = form.update { it.copy(paymentMethod = if (it.paymentMethod == method) null else method) }
    fun setRepeat(frequency: Frequency?) = form.update { it.copy(repeat = frequency) }

    fun addAttachment(uri: Uri) {
        meta.update { it.copy(attachmentMessage = null) }
        viewModelScope.launch {
            val result = attachments.import(entityId, uri.toString())
            // Camera photos are written to a temporary file first.
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            val message = when (result) {
                is AttachmentResult.Added -> null
                AttachmentResult.TooLarge -> AttachmentMessage.TOO_LARGE
                AttachmentResult.Unsupported -> AttachmentMessage.UNSUPPORTED
                AttachmentResult.Failed -> AttachmentMessage.FAILED
            }
            meta.update { it.copy(attachmentMessage = message) }
        }
    }

    fun removeAttachment(attachment: Attachment) {
        viewModelScope.launch { attachments.delete(attachment.id) }
    }

    suspend fun readAttachment(attachment: Attachment): ByteArray? = attachments.read(attachment)
    suspend fun viewableUri(attachment: Attachment): String? = attachments.viewableUri(attachment)

    fun save() {
        if (meta.value.saving) return
        val f = form.value
        val draft = TransactionDraft(entityId, f.type, f.amountText, currency, f.categoryId, f.accountId, f.toAccountId, f.date, f.note, f.paymentMethod)
        meta.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val result = saveTransaction(draft, existingOccurredAt)) {
                is SaveResult.Saved -> {
                    saved = true
                    val repeat = f.repeat
                    if (repeat != null && transactionId == null) {
                        val t = result.transaction
                        val name = state.value.categories.firstOrNull { it.id == t.categoryId }?.name ?: t.note ?: t.type.name
                        createRecurring(t, Recurrence(repeat), f.date, name)
                    }
                    _closeEvents.send(Unit)
                }
                is SaveResult.Invalid -> meta.update { it.copy(saving = false, errors = result.errors) }
            }
        }
    }

    fun askDelete() = meta.update { it.copy(confirmDelete = true) }
    fun dismissDelete() = meta.update { it.copy(confirmDelete = false) }

    fun delete() {
        val id = transactionId ?: return
        meta.update { it.copy(confirmDelete = false, saving = true) }
        viewModelScope.launch {
            deleteTransaction(id)
            attachments.deleteFor(id)
            _closeEvents.send(Unit)
        }
    }

    /** Receipts added to a new transaction that was never saved are removed with the draft. */
    override fun onCleared() {
        if (transactionId == null && !saved) {
            CoroutineScope(Dispatchers.IO + NonCancellable).launch { attachments.deleteFor(entityId) }
        }
    }

    private fun clearError(vararg errors: TransactionError) = meta.update {
        it.copy(errors = if (errors.isEmpty()) emptySet() else it.errors - errors.toSet())
    }

    private companion object {
        const val KEY_DRAFT_ID = "draft_id"
    }
}
