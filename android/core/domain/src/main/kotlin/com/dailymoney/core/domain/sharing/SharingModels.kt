package com.dailymoney.core.domain.sharing

import com.dailymoney.core.domain.money.DateRange
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.TransactionType
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Who besides the creator can see a group expense (LLD §3). Personal expenses never leave the phone. */
enum class Visibility { ORGANIZATION, SELECTED_MEMBERS }

/**
 * How one of my transactions is shared. [onServer] is true once the group has it; from then on it
 * can't move to another group or back to personal (delete it instead), but who sees it can change.
 */
data class Sharing(
    val groupId: String,
    val visibility: Visibility,
    val memberIds: Set<String> = emptySet(),
    val status: ShareStatus = ShareStatus.PENDING,
    val onServer: Boolean = false,
    /** Null: shared for reporting only, nobody owes anything. */
    val split: SplitSpec? = null,
    val approval: ApprovalStatus = ApprovalStatus.APPROVED,
)

enum class ShareStatus { PENDING, SYNCED, FAILED }

/** A group I belong to, as of the last sync; permissions only decide what the UI offers. */
data class SharedGroup(
    val id: String,
    val name: String,
    val currency: String,
    val canView: Boolean,
    val canAdd: Boolean,
    val canReport: Boolean,
    val canSplit: Boolean = false,
    val members: List<GroupPerson> = emptyList(),
    val approvalMode: ApprovalMode = ApprovalMode.INSTANT,
    val requiredApprovals: Int = 1,
    val isApprover: Boolean = false,
    val allowSelfApproval: Boolean = false,
)

/** A group expense as the server sent it (mine or someone else's). */
data class SharedExpense(
    val id: String,
    val groupId: String,
    val creatorId: String,
    val creatorName: String,
    val mine: Boolean,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String,
    val categoryName: String?,
    val categoryIcon: String?,
    val occurredAt: Instant,
    val note: String?,
    val paymentMethod: PaymentMethod?,
    val visibility: Visibility,
    val shares: List<Share> = emptyList(),
    val approval: ApprovalStatus = ApprovalStatus.APPROVED,
    /** Only the creator and approvers receive decisions. */
    val decisions: List<ApprovalDecision> = emptyList(),
    val version: Int = 1,
)

interface SharedExpenseRepository {
    fun observeGroups(): Flow<List<SharedGroup>>
    fun observeExpenses(groupId: String): Flow<List<SharedExpense>>
    fun observeSettlements(groupId: String): Flow<List<Settlement>>

    /** My server user id and UPI ID, known after the first sync. */
    fun observeMe(): Flow<Me?>
}

data class Me(val userId: String, val upiId: String?)

/** Recording and answering payments needs a connection; results arrive on other phones through sync. */
interface SettlementRepository {
    suspend fun record(groupId: String, payerId: String, receiverId: String, amountMinor: Long, currency: String, method: SettleMethod, note: String?): SettleResult
    suspend fun confirm(settlementId: String): SettleResult
    suspend fun cancel(settlementId: String): SettleResult
    suspend fun setUpiId(upiId: String?): SettleResult
}

sealed interface SettleResult {
    data object Ok : SettleResult
    data object Offline : SettleResult
    data object BackendDisabled : SettleResult
    /** Server rule code, e.g. ALREADY_DECIDED, INVALID_UPI_ID, PERMISSION_DENIED. */
    data class Refused(val code: String) : SettleResult
}

interface SyncScheduler {
    /** Sync soon (debounced); safe to call often. No-op without a backend. */
    fun requestSync()

    /** Fallback every few hours in case a push wake-up was missed. */
    fun schedulePeriodic()
}

data class Breakdown(val label: String, val amountMinor: Long)

/** Org dashboard (spec §15), computed from the expenses this person can see. */
data class GroupDashboard(
    val spentMinor: Long,
    val receivedMinor: Long,
    /** Waiting for approval: not in the totals yet (spec §15). */
    val pendingMinor: Long,
    val count: Int,
    val byCategory: List<Breakdown>,
    val byMember: List<Breakdown>,
    /** Expenses in another currency than the group's are listed but not added up. */
    val otherCurrencyCount: Int,
) {
    companion object {
        const val UNCATEGORIZED = ""

        fun build(expenses: List<SharedExpense>, range: DateRange, currency: String): GroupDashboard {
            val inRange = expenses.filter { it.occurredAt in range && it.approval != ApprovalStatus.REJECTED }
            val counted = inRange.filter { it.currency == currency }
            val pending = counted.filter { it.approval == ApprovalStatus.PENDING_APPROVAL && it.type == TransactionType.EXPENSE }
            val approved = counted.filter { it.approval == ApprovalStatus.APPROVED }
            val spent = approved.filter { it.type == TransactionType.EXPENSE }
            fun breakdown(key: (SharedExpense) -> String) = spent.groupBy(key)
                .map { (label, rows) -> Breakdown(label, rows.sumOf { it.amountMinor }) }
                .sortedByDescending { it.amountMinor }
            return GroupDashboard(
                spentMinor = spent.sumOf { it.amountMinor },
                receivedMinor = approved.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor },
                pendingMinor = pending.sumOf { it.amountMinor },
                count = inRange.size,
                byCategory = breakdown { it.categoryName ?: UNCATEGORIZED },
                byMember = breakdown { it.creatorName },
                otherCurrencyCount = inRange.size - counted.size,
            )
        }
    }
}
