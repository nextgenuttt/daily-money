package com.dailymoney.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateGroupDto(val name: String, val kind: String, val currency: String? = null)

@Serializable
data class UpdateGroupDto(val name: String, val kind: String)

@Serializable
data class GroupSummaryDto(
    val id: String,
    val name: String,
    val kind: String,
    val currency: String,
    val myRole: String,
    val memberCount: Long,
)

@Serializable
data class RoleDto(val id: String, val code: String, val name: String)

@Serializable
data class GroupDetailDto(
    val id: String,
    val name: String,
    val kind: String,
    val currency: String,
    val myRole: String,
    val myPermissions: List<String>,
    val roles: List<RoleDto>,
    val memberCount: Long,
)

@Serializable
data class MemberDto(
    val userId: String,
    val displayName: String,
    val email: String? = null,
    val role: String,
    val joinedAt: String,
    val isMe: Boolean,
)

@Serializable
data class RoleChangeDto(val role: String)

@Serializable
data class TransferDto(val userId: String)

@Serializable
data class InviteRequestDto(val role: String, val email: String? = null, val maxUses: Int = 1)

@Serializable
data class InvitationDto(
    val id: String,
    val role: String,
    val email: String? = null,
    val maxUses: Int,
    val uses: Int,
    val expiresAt: String,
)

@Serializable
data class CreatedInvitationDto(val invitation: InvitationDto, val token: String, val link: String)

@Serializable
data class InvitePreviewDto(
    val organizationId: String,
    val organizationName: String,
    val kind: String,
    val invitedBy: String? = null,
    val role: String,
    val expiresAt: String,
    val status: String,
    val alreadyMember: Boolean,
    val emailBound: Boolean,
)

/** RFC 9457 Problem Details body returned by the backend for every error. */
@Serializable
data class ProblemDto(val status: Int? = null, val code: String? = null, val detail: String? = null)

@Serializable
data class RolePermissionsDto(val id: String, val code: String, val name: String, val permissions: List<String>, val editable: Boolean)

@Serializable
data class PermissionMatrixDto(val definitions: List<String>, val roles: List<RolePermissionsDto>, val canManage: Boolean)

@Serializable
data class RoleUpdateDto(val permissions: List<String>)

@Serializable
data class MemberPermissionsDto(
    val userId: String,
    val role: String,
    val roleDefaults: List<String>,
    val overrides: Map<String, String>,
    val effective: List<String>,
)

@Serializable
data class OverridesDto(val overrides: Map<String, String>)

@Serializable
data class ApprovalRuleDto(
    val type: String,
    val amountMinor: Long? = null,
    val categories: List<String>? = null,
    val userIds: List<String>? = null,
    val enabled: Boolean? = true,
)

@Serializable
data class ApprovalPersonDto(val userId: String, val name: String)

@Serializable
data class ApprovalPolicyDto(
    val mode: String,
    val allowSelfApproval: Boolean = false,
    val requiredApprovals: Int = 1,
    val reapproveOnMaterialChange: Boolean = true,
    val approverIds: List<String> = emptyList(),
    val rules: List<ApprovalRuleDto> = emptyList(),
    val eligibleApprovers: List<ApprovalPersonDto> = emptyList(),
    val canEdit: Boolean = false,
)

@Serializable
data class DecisionRequestDto(val version: Int, val reason: String? = null)
