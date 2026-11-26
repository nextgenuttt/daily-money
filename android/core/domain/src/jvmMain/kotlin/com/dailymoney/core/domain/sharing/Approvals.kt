package com.dailymoney.core.domain.sharing

import java.time.Instant

enum class ApprovalStatus { APPROVED, PENDING_APPROVAL, REJECTED }

enum class ApprovalMode { INSTANT, REQUIRE_APPROVAL, CONDITIONAL }

data class ApprovalDecision(
    val approverId: String,
    val approverName: String,
    val approved: Boolean,
    val reason: String?,
    val decidedAt: Instant,
    val version: Int,
)

enum class RuleType { AMOUNT_GTE, CATEGORY_IN, CREATOR_IN }

data class ApprovalRule(
    val type: RuleType,
    val amountMinor: Long? = null,
    val categories: List<String> = emptyList(),
    val userIds: List<String> = emptyList(),
    val enabled: Boolean = true,
)

data class ApprovalPolicy(
    val mode: ApprovalMode,
    val allowSelfApproval: Boolean,
    val requiredApprovals: Int,
    val reapproveOnMaterialChange: Boolean,
    val approverIds: Set<String>,
    val rules: List<ApprovalRule>,
    /** Members whose permissions allow approving; only they can be chosen as approvers. */
    val eligibleApprovers: List<Pair<String, String>> = emptyList(),
    val canEdit: Boolean = false,
)

object Approvals {

    /** Decisions on the version now waiting; earlier ones were made on content that has since changed. */
    fun current(expense: SharedExpense): List<ApprovalDecision> = expense.decisions.filter { it.version == expense.version }

    /** Expenses I should decide on: pending, not decided by me on this version, and not my own unless allowed. */
    fun waitingFor(meId: String, group: SharedGroup, expenses: List<SharedExpense>): List<SharedExpense> {
        if (!group.isApprover) return emptyList()
        return expenses.filter { e ->
            e.approval == ApprovalStatus.PENDING_APPROVAL &&
                (e.creatorId != meId || group.allowSelfApproval) &&
                current(e).none { it.approverId == meId }
        }
    }
}
