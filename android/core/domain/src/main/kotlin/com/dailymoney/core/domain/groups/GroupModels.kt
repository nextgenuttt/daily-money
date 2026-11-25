package com.dailymoney.core.domain.groups

import com.dailymoney.core.domain.sharing.ApprovalPolicy
import java.time.Instant

enum class GroupKind { FAMILY, FRIENDS, OFFICE, TRAVEL, OTHER }

/** System roles; the server decides what each may do. */
enum class GroupRole { OWNER, ADMIN, MEMBER, VIEWER }

data class GroupSummary(
    val id: String,
    val name: String,
    val kind: GroupKind,
    val currency: String,
    val myRole: GroupRole,
    val memberCount: Long,
)

data class GroupDetail(
    val id: String,
    val name: String,
    val kind: GroupKind,
    val currency: String,
    val myRole: GroupRole,
    /** Permission codes from the server; only used to show or hide buttons, the server re-checks. */
    val myPermissions: Set<String>,
    val memberCount: Long,
) {
    val canInvite get() = "MEMBER_INVITE" in myPermissions
    val canManageMembers get() = "MEMBER_MANAGE" in myPermissions
    val canEditSettings get() = "ORGANIZATION_SETTINGS_MANAGE" in myPermissions
    val canManagePermissions get() = "PERMISSION_MANAGE" in myPermissions
    val isOwner get() = myRole == GroupRole.OWNER
}

data class GroupMember(
    val userId: String,
    val displayName: String,
    val email: String?,
    val role: GroupRole,
    val joinedAt: Instant,
    val isMe: Boolean,
)

data class GroupInvitation(
    val id: String,
    val role: GroupRole,
    val email: String?,
    val maxUses: Int,
    val uses: Int,
    val expiresAt: Instant,
)

data class CreatedInvite(val invitation: GroupInvitation, val link: String)

enum class InviteStatus { VALID, EXPIRED, USED_UP, REVOKED, DECLINED }

data class InvitePreview(
    val groupId: String,
    val groupName: String,
    val kind: GroupKind,
    val invitedBy: String?,
    val role: GroupRole,
    val expiresAt: Instant,
    val status: InviteStatus,
    val alreadyMember: Boolean,
    val emailBound: Boolean,
)

sealed interface GroupError {
    /** No server configured in this build (R1): groups aren't available yet. */
    data object BackendDisabled : GroupError
    data object Offline : GroupError
    data object NotFound : GroupError
    data object PermissionDenied : GroupError
    data object SessionExpired : GroupError

    /** A business rule from the server, e.g. TRANSFER_OWNERSHIP_FIRST, INVITE_EXPIRED, ALREADY_MEMBER. */
    data class Rule(val code: String) : GroupError
    data class Unknown(val message: String?) : GroupError
}

sealed interface GroupResult<out T> {
    data class Ok<T>(val value: T) : GroupResult<T>
    data class Err(val error: GroupError) : GroupResult<Nothing>
}

enum class PermissionEffect { ALLOW, DENY }

data class RolePermissions(val id: String, val role: GroupRole, val permissions: Set<String>, val editable: Boolean)

data class PermissionMatrix(val definitions: List<String>, val roles: List<RolePermissions>, val canManage: Boolean)

data class MemberPermissions(
    val role: GroupRole,
    val roleDefaults: Set<String>,
    val overrides: Map<String, PermissionEffect>,
    val effective: Set<String>,
)

/** UI grouping of permission codes (spec §12). */
object PermissionGroups {
    val EXPENSES = listOf(
        "EXPENSE_VIEW", "EXPENSE_ADD", "EXPENSE_EDIT_OWN", "EXPENSE_DELETE_OWN", "EXPENSE_APPROVE",
        "EXPENSE_SPLIT_MANAGE", "EXPENSE_REPORT_VIEW", "EXPENSE_BUDGET_MANAGE", "ATTACHMENT_VIEW",
    )
    val MEMBERS = listOf("MEMBER_INVITE", "MEMBER_MANAGE", "PERMISSION_MANAGE", "ORGANIZATION_SETTINGS_MANAGE")
    val MESSAGES = listOf("EXPENSE_MESSAGE_SEND", "EXPENSE_MESSAGE_RECEIVE", "EXPENSE_MESSAGE_REPLY", "EXPENSE_MESSAGE_ATTACH", "EXPENSE_NOTIFICATION_RECEIVE")
    val PLANNING = listOf("REMINDER_MANAGE", "EVENT_MANAGE", "TASK_MANAGE", "TASK_ASSIGN")
    val ALL = EXPENSES + MEMBERS + MESSAGES + PLANNING

    /** Effective result the server will compute (LLD §5.1); used to preview changes before saving. */
    fun effective(roleDefaults: Set<String>, overrides: Map<String, PermissionEffect>): Set<String> =
        (roleDefaults + overrides.filterValues { it == PermissionEffect.ALLOW }.keys) - overrides.filterValues { it == PermissionEffect.DENY }.keys
}

interface GroupRepository {
    suspend fun list(): GroupResult<List<GroupSummary>>
    suspend fun create(name: String, kind: GroupKind): GroupResult<GroupDetail>
    suspend fun get(id: String): GroupResult<GroupDetail>
    suspend fun update(id: String, name: String, kind: GroupKind): GroupResult<GroupDetail>
    suspend fun delete(id: String): GroupResult<Unit>
    suspend fun members(id: String): GroupResult<List<GroupMember>>
    suspend fun changeRole(id: String, userId: String, role: GroupRole): GroupResult<Unit>
    suspend fun removeMember(id: String, userId: String): GroupResult<Unit>
    suspend fun leave(id: String): GroupResult<Unit>
    suspend fun transferOwnership(id: String, userId: String): GroupResult<Unit>
    suspend fun invite(id: String, role: GroupRole, email: String?, maxUses: Int): GroupResult<CreatedInvite>
    suspend fun invitations(id: String): GroupResult<List<GroupInvitation>>
    suspend fun revoke(invitationId: String): GroupResult<Unit>
    suspend fun preview(token: String): GroupResult<InvitePreview>
    suspend fun accept(token: String): GroupResult<GroupSummary>
    suspend fun decline(token: String): GroupResult<Unit>
    suspend fun permissions(id: String): GroupResult<PermissionMatrix>
    suspend fun updateRole(id: String, roleId: String, permissions: Set<String>): GroupResult<Unit>
    suspend fun memberPermissions(id: String, userId: String): GroupResult<MemberPermissions>
    suspend fun setOverrides(id: String, userId: String, overrides: Map<String, PermissionEffect>): GroupResult<Unit>
    suspend fun approvalPolicy(id: String): GroupResult<ApprovalPolicy>
    suspend fun saveApprovalPolicy(id: String, policy: ApprovalPolicy): GroupResult<ApprovalPolicy>
    suspend fun approve(expenseId: String, version: Int): GroupResult<Unit>
    suspend fun reject(expenseId: String, version: Int, reason: String): GroupResult<Unit>
}

object InviteLinks {
    private val TOKEN = Regex("^[A-Za-z0-9_-]{20,100}$")

    /** Accepts a full link (…/invite/TOKEN, possibly with query/fragment) or a bare token; null if neither. */
    fun tokenFrom(input: String): String? {
        val trimmed = input.trim()
        val candidate = trimmed.substringAfter("/invite/", trimmed).substringBefore('?').substringBefore('#').trimEnd('/')
        return candidate.takeIf { TOKEN.matches(it) }
    }
}
