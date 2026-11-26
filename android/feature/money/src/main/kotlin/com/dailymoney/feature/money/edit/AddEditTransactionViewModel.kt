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
import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.money.TransactionSource
import com.dailymoney.core.domain.smart.BankSmsParser
import com.dailymoney.core.domain.smart.Categorizer
import com.dailymoney.core.domain.smart.LoadCategorizerUseCase
import com.dailymoney.core.domain.smart.PaymentSuggestionRepository
import com.dailymoney.core.domain.smart.ScanReceiptUseCase
import com.dailymoney.core.domain.smart.SuggestionStatus
import com.dailymoney.core.domain.sharing.ApprovalStatus
import com.dailymoney.core.domain.sharing.GroupPerson
import com.dailymoney.core.domain.sharing.ShareStatus
import com.dailymoney.core.domain.sharing.SplitCalculator
import com.dailymoney.core.domain.sharing.SplitEntry
import com.dailymoney.core.domain.sharing.SplitMethod
import com.dailymoney.core.domain.sharing.SplitProblem
import com.dailymoney.core.domain.sharing.SplitResult
import com.dailymoney.core.domain.sharing.SplitSpec
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import com.dailymoney.core.domain.sharing.SharedGroup
import com.dailymoney.core.domain.sharing.Sharing
import com.dailymoney.core.domain.sharing.Visibility
import com.dailymoney.feature.money.common.sanitizeAmountInput
import com.dailymoney.feature.money.navigation.AddEditTransactionRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
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
    /** Group to share with; null keeps it personal. */
    val shareGroupId: String? = null,
    val shareVisibility: Visibility = Visibility.ORGANIZATION,
    val shareMembers: Set<String> = emptySet(),
    val split: SplitForm = SplitForm(),
)

/** Split inputs as typed: percent, share count, or an amount in the transaction's currency. */
data class SplitForm(
    val on: Boolean = false,
    val method: SplitMethod = SplitMethod.EQUAL,
    val people: Set<String> = emptySet(),
    val values: Map<String, String> = emptyMap(),
)

data class ShareUi(
    /** Groups I may add to, plus the one this entry is already in. */
    val groups: List<SharedGroup> = emptyList(),
    val group: SharedGroup? = null,
    /** Other members of the chosen group (for "selected people"). */
    val others: List<GroupPerson> = emptyList(),
    /** Who can be in the split: everyone who will see the expense, me first. */
    val splitCandidates: List<GroupPerson> = emptyList(),
    val preview: Map<String, Long> = emptyMap(),
    val splitProblem: SplitProblem? = null,
    /** Already on the server: the group is fixed, only who sees it and the split can change. */
    val locked: Boolean = false,
    val status: ShareStatus? = null,
    val approval: ApprovalStatus = ApprovalStatus.APPROVED,
)

/** The split to save, from what's typed; null when not splitting or not an expense. */
internal fun buildSplit(form: TransactionForm, candidates: List<GroupPerson>, currency: String): SplitSpec? {
    if (!form.split.on || form.type != TransactionType.EXPENSE) return null
    val entries = candidates.filter { it.userId in form.split.people }.map { person ->
        val text = form.split.values[person.userId].orEmpty()
        val value = when (form.split.method) {
            SplitMethod.EQUAL -> BigDecimal.ONE
            SplitMethod.AMOUNT -> MoneyParser.parse(text, currency)?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO
            SplitMethod.PERCENT, SplitMethod.SHARES -> text.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
        }
        SplitEntry(person.userId, value)
    }
    return SplitSpec(form.split.method, entries)
}

internal fun splitCandidates(group: SharedGroup?, form: TransactionForm): List<GroupPerson> {
    val members = group?.members.orEmpty()
    val audience = if (form.shareVisibility == Visibility.ORGANIZATION) members else members.filter { it.isMe || it.userId in form.shareMembers }
    return audience.sortedByDescending { it.isMe }
}

enum class AttachmentMessage { TOO_LARGE, UNSUPPORTED, FAILED }

/** What the app filled in for the user, shown above the form so they check it. */
enum class AssistMessage { RECEIPT_READ, RECEIPT_UNREADABLE, MESSAGE_READ, MESSAGE_UNREADABLE }

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
    val share: ShareUi = ShareUi(),
    val scanning: Boolean = false,
    val assist: AssistMessage? = null,
    /** Bumped on every failed save, so the screen scrolls to the problem even when it's the same one again. */
    val invalidAttempts: Int = 0,
    /** A category guessed from the note, offered while none is chosen. */
    val suggestedCategory: Category? = null,
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
    sharedExpenses: SharedExpenseRepository,
    private val scanReceipt: ScanReceiptUseCase,
    private val loadCategorizer: LoadCategorizerUseCase,
    private val suggestions: PaymentSuggestionRepository,
    config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AddEditTransactionRoute>()
    private val transactionId = route.transactionId

    /** Id used for receipts before a new transaction is saved; becomes the transaction's id on save. */
    private val entityId: String = transactionId ?: savedStateHandle.get<String>(KEY_DRAFT_ID) ?: Ids.newId().also { savedStateHandle[KEY_DRAFT_ID] = it }
    private var saved = false
    private val currency = config.defaultCurrency
    private var existingOccurredAt: Instant? = null
    private val categorizer = MutableStateFlow<Categorizer?>(null)

    /** Set when a receipt or message filled the form; plain edits afterwards don't change it. */
    private var source = TransactionSource.MANUAL

    private val form = MutableStateFlow(
        TransactionForm(
            type = route.type?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() } ?: TransactionType.EXPENSE,
            date = LocalDate.now(clock),
        ),
    )
    private val meta = MutableStateFlow(
        AddEditUiState(isEdit = transactionId != null, loading = transactionId != null, form = form.value, currency = currency),
    )

    private val share = MutableStateFlow(ShareUi())

    private val _closeEvents = Channel<Unit>(Channel.BUFFERED)
    val closeEvents = _closeEvents.receiveAsFlow()

    private val shareState = combine(share, sharedExpenses.observeGroups(), form) { sh, all, f ->
        val group = all.firstOrNull { it.id == f.shareGroupId }
        val candidates = splitCandidates(group, f)
        val spec = buildSplit(f, candidates, currency)
        val amount = MoneyParser.parse(f.amountText, currency)
        val result = spec?.takeIf { amount != null && amount > 0 }?.let { SplitCalculator.split(amount!!, it.method, it.entries) }
        sh.copy(
            groups = all.filter { it.canAdd || it.id == f.shareGroupId },
            group = group,
            others = group?.members.orEmpty().filter { !it.isMe },
            splitCandidates = candidates,
            preview = (result as? SplitResult.Ok)?.let { ok -> spec.entries.map { it.userId }.zip(ok.shares).toMap() }.orEmpty(),
            splitProblem = (result as? SplitResult.Invalid)?.problem,
        )
    }

    private val suggestion = combine(form, categorizer) { f, c -> if (f.categoryId == null) c?.suggest(f.note, f.type) else null }

    val state: StateFlow<AddEditUiState> = combine(
        combine(meta, form, ::Pair), categories.observe(), accounts.observeBalances(), attachments.observeFor(entityId),
        combine(shareState, suggestion, ::Pair),
    ) { (m, f), cats, balances, files, (sh, suggested) ->
        val kind = if (f.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        m.copy(
            form = f,
            categories = cats.filter { it.kind == kind },
            // Archived accounts stay selectable only when an old transaction already uses them.
            accounts = balances.map { it.account }.filter { !it.archived || it.id == f.accountId || it.id == f.toAccountId },
            attachments = files,
            share = sh,
            suggestedCategory = suggested,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), meta.value)

    init {
        viewModelScope.launch { categorizer.value = loadCategorizer() }
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
                    shareGroupId = existing.sharing?.groupId,
                    shareVisibility = existing.sharing?.visibility ?: Visibility.ORGANIZATION,
                    shareMembers = existing.sharing?.memberIds.orEmpty(),
                    split = existing.sharing?.split?.let { spec ->
                        SplitForm(
                            on = true,
                            method = spec.method,
                            people = spec.entries.map { it.userId }.toSet(),
                            values = spec.entries.associate { e ->
                                e.userId to when (spec.method) {
                                    SplitMethod.EQUAL -> ""
                                    SplitMethod.AMOUNT -> MoneyFormatter.toInput(e.value.toLong(), existing.currency)
                                    else -> e.value.stripTrailingZeros().toPlainString()
                                }
                            },
                        )
                    } ?: SplitForm(),
                )
                share.update {
                    it.copy(
                        locked = existing.sharing?.onServer == true,
                        status = existing.sharing?.status,
                        approval = existing.sharing?.approval ?: ApprovalStatus.APPROVED,
                    )
                }
                meta.update { it.copy(loading = false) }
            } else {
                val firstAccount = accounts.observeBalances().first().firstOrNull { !it.account.archived }?.account?.id
                form.update { if (it.accountId == null) it.copy(accountId = firstAccount) else it }
                route.sharedText?.let { fillFromMessage(it) }
                route.suggestionId?.let { fillFromSuggestion(it) }
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
    fun choosePaymentMethod(method: PaymentMethod?) = form.update { it.copy(paymentMethod = method) }
    fun setRepeat(frequency: Frequency?) = form.update { it.copy(repeat = frequency) }

    fun setShareGroup(groupId: String?) {
        if (share.value.locked) return
        form.update { if (it.shareGroupId == groupId) it else it.copy(shareGroupId = groupId, shareMembers = emptySet(), split = SplitForm()) }
    }

    fun setShareVisibility(visibility: Visibility) {
        form.update { it.copy(shareVisibility = visibility) }
        clearError(TransactionError.MEMBERS_REQUIRED, TransactionError.SPLIT_INVALID)
    }

    fun toggleShareMember(userId: String) {
        form.update {
            val removing = userId in it.shareMembers
            it.copy(
                shareMembers = if (removing) it.shareMembers - userId else it.shareMembers + userId,
                // Someone who can't see the expense can't be charged for it.
                split = if (removing) it.split.copy(people = it.split.people - userId) else it.split,
            )
        }
        clearError(TransactionError.MEMBERS_REQUIRED, TransactionError.SPLIT_INVALID)
    }

    /** Turning the split on starts with an equal split among everyone who sees the expense. */
    fun setSplitOn(on: Boolean) {
        val candidates = state.value.share.splitCandidates.map { it.userId }.toSet()
        form.update { it.copy(split = if (on) SplitForm(on = true, people = candidates) else SplitForm()) }
        clearError(TransactionError.SPLIT_INVALID)
    }

    fun setSplitMethod(method: SplitMethod) {
        form.update { it.copy(split = it.split.copy(method = method, values = emptyMap())) }
        clearError(TransactionError.SPLIT_INVALID)
    }

    fun toggleSplitPerson(userId: String) {
        form.update { f -> f.copy(split = f.split.copy(people = if (userId in f.split.people) f.split.people - userId else f.split.people + userId)) }
        clearError(TransactionError.SPLIT_INVALID)
    }

    fun setSplitValue(userId: String, text: String) {
        val cleaned = if (form.value.split.method == SplitMethod.AMOUNT) sanitizeAmountInput(text, Currencies.exponent(currency)) else sanitizeAmountInput(text, 4)
        form.update { f -> f.copy(split = f.split.copy(values = f.split.values + (userId to cleaned))) }
        clearError(TransactionError.SPLIT_INVALID)
    }

    /** [isPhoto] photos of a new, still empty transaction are read to fill the form. */
    fun addAttachment(uri: Uri, isPhoto: Boolean) {
        meta.update { it.copy(attachmentMessage = null) }
        viewModelScope.launch {
            if (isPhoto && transactionId == null && form.value.amountText.isBlank()) fillFromReceipt(uri)
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

    private suspend fun fillFromReceipt(uri: Uri) {
        meta.update { it.copy(scanning = true, assist = null) }
        val scan = scanReceipt(uri.toString(), currency)
        val amount = scan?.amountMinor
        if (amount == null) {
            meta.update { it.copy(scanning = false, assist = AssistMessage.RECEIPT_UNREADABLE) }
            return
        }
        prefill(TransactionType.EXPENSE, amount, scan.date, scan.merchant)
        source = TransactionSource.OCR
        meta.update { it.copy(scanning = false, assist = AssistMessage.RECEIPT_READ) }
    }

    private fun fillFromMessage(text: String) {
        val parsed = BankSmsParser.parse(text, currency, LocalDate.now(clock))
        if (parsed == null) {
            meta.update { it.copy(assist = AssistMessage.MESSAGE_UNREADABLE) }
            return
        }
        prefill(parsed.type, parsed.amountMinor, parsed.date, parsed.counterparty)
        source = TransactionSource.SMS
        meta.update { it.copy(assist = AssistMessage.MESSAGE_READ) }
    }

    private suspend fun fillFromSuggestion(id: String) {
        val found = suggestions.get(id) ?: return
        val p = found.payment
        prefill(p.type, p.amountMinor, p.date, p.counterparty)
        source = TransactionSource.SMS
        meta.update { it.copy(assist = AssistMessage.MESSAGE_READ) }
    }

    /** Only fills what the user hasn't typed yet. */
    private fun prefill(type: TransactionType, amountMinor: Long, date: LocalDate?, note: String?) {
        form.update { f ->
            val typed = if (f.amountText.isBlank()) f.copy(type = type, categoryId = if (f.type == type) f.categoryId else null) else f
            val withNote = typed.copy(
                amountText = typed.amountText.ifBlank { MoneyFormatter.toInput(amountMinor, currency) },
                date = date ?: typed.date,
                note = typed.note.ifBlank { note.orEmpty() },
            )
            withNote.copy(categoryId = withNote.categoryId ?: categorizer.value?.suggest(withNote.note, withNote.type)?.id)
        }
        clearError()
    }

    fun dismissAssist() = meta.update { it.copy(assist = null) }

    fun removeAttachment(attachment: Attachment) {
        viewModelScope.launch { attachments.delete(attachment.id) }
    }

    suspend fun readAttachment(attachment: Attachment): ByteArray? = attachments.read(attachment)
    suspend fun viewableUri(attachment: Attachment): String? = attachments.viewableUri(attachment)

    fun save() {
        if (meta.value.saving) return
        val f = form.value
        val sharing = f.shareGroupId?.let { group ->
            Sharing(
                group, f.shareVisibility, if (f.shareVisibility == Visibility.SELECTED_MEMBERS) f.shareMembers else emptySet(),
                split = buildSplit(f, state.value.share.splitCandidates, currency),
            )
        }
        val draft = TransactionDraft(entityId, f.type, f.amountText, currency, f.categoryId, f.accountId, f.toAccountId, f.date, f.note, f.paymentMethod, sharing, source)
        meta.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val result = saveTransaction(draft, existingOccurredAt)) {
                is SaveResult.Saved -> {
                    saved = true
                    route.suggestionId?.let { suggestions.setStatus(it, SuggestionStatus.ADDED) }
                    val repeat = f.repeat
                    if (repeat != null && transactionId == null) {
                        val t = result.transaction
                        val name = state.value.categories.firstOrNull { it.id == t.categoryId }?.name ?: t.note ?: t.type.name
                        createRecurring(t, Recurrence(repeat), f.date, name)
                    }
                    _closeEvents.send(Unit)
                }
                is SaveResult.Invalid -> meta.update { it.copy(saving = false, errors = result.errors, invalidAttempts = it.invalidAttempts + 1) }
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
