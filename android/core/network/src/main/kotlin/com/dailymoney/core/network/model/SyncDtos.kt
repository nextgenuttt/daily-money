package com.dailymoney.core.network.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncPushRequestDto(val deviceId: String, val ops: List<SyncOpDto>)

@Serializable
data class SyncOpDto(
    val opId: String,
    val entityType: String,
    val entityId: String,
    val opType: String,
    val baseVersion: Int,
    val payload: ExpensePayloadDto? = null,
)

@Serializable
data class ExpensePayloadDto(
    val orgId: String,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val categoryName: String? = null,
    val categoryIcon: String? = null,
    val occurredAt: String,
    val note: String? = null,
    val paymentMethod: String? = null,
    val visibility: String,
    val allowedMemberIds: List<String> = emptyList(),
    val split: SplitDto? = null,
)

@Serializable
data class SplitDto(val method: String, val entries: List<SplitEntryDto>)

/** [value] is sent as a decimal string so percentages keep their exact digits. */
@Serializable
data class SplitEntryDto(val userId: String, val value: String)

@Serializable
data class ShareDto(val userId: String, val value: JsonElement? = null, val shareMinor: Long)

@Serializable
data class SplitViewDto(val method: String, val shares: List<ShareDto>)

@Serializable
data class SyncPushResponseDto(val results: List<OpResultDto>)

@Serializable
data class OpResultDto(
    val opId: String,
    val status: String,
    val version: Int? = null,
    val errorCode: String? = null,
    val approvalStatus: String? = null,
    val current: ExpenseViewDto? = null,
)

@Serializable
data class ExpenseViewDto(
    val id: String,
    val orgId: String,
    val creatorName: String,
    val mine: Boolean,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val categoryName: String? = null,
    val categoryIcon: String? = null,
    val occurredAt: String,
    val note: String? = null,
    val paymentMethod: String? = null,
    val visibility: String,
    val version: Int,
    val creatorId: String = "",
    val split: SplitViewDto? = null,
    val approvalStatus: String = "APPROVED",
    val approvals: List<DecisionDto> = emptyList(),
)

@Serializable
data class DecisionDto(
    val approverId: String,
    val approverName: String,
    val decision: String,
    val reason: String? = null,
    val decidedAt: String,
    val version: Int,
)

@Serializable
data class SyncPullDto(
    val me: String? = null,
    val myUpiId: String? = null,
    val myPhone: String? = null,
    val orgs: List<SyncOrgDto>,
    val changes: List<SyncChangeDto>,
    val nextCursor: Long,
    val hasMore: Boolean,
)

@Serializable
data class SyncOrgDto(
    val id: String,
    val name: String,
    val currency: String,
    val canView: Boolean,
    val canAdd: Boolean,
    val canReport: Boolean,
    val canSplit: Boolean = false,
    val members: List<SyncMemberDto> = emptyList(),
    val approvalMode: String = "INSTANT",
    val requiredApprovals: Int = 1,
    val isApprover: Boolean = false,
    val allowSelfApproval: Boolean = false,
)

@Serializable
data class SyncMemberDto(val userId: String, val name: String, val upiId: String? = null, val phone: String? = null)

@Serializable
data class SettlementDto(
    val id: String,
    val orgId: String,
    val payerId: String,
    val receiverId: String,
    val amountMinor: Long,
    val currency: String,
    val method: String,
    val status: String,
    val note: String? = null,
    val createdBy: String,
    val createdAt: String,
)

@Serializable
data class NewSettlementDto(
    val id: String,
    val payerId: String,
    val receiverId: String,
    val amountMinor: Long,
    val currency: String,
    val method: String,
    val note: String? = null,
)

@Serializable
data class UpiRequestDto(val upiId: String?)

@Serializable
data class PhoneRequestDto(val phone: String?)

@Serializable
data class ReminderRequestDto(val userId: String, val amountMinor: Long, val currency: String)

@Serializable
data class SyncChangeDto(
    val entityType: String,
    val op: String,
    val id: String,
    val data: ExpenseViewDto? = null,
    val settlement: SettlementDto? = null,
    val notification: NotificationDto? = null,
)

@Serializable
data class NotificationDto(
    val id: String,
    val orgId: String? = null,
    val type: String,
    val entityType: String? = null,
    val entityId: String? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    val createdAt: String,
    val read: Boolean = false,
)

@Serializable
data class ReadNotificationsDto(val ids: List<String>)

@Serializable
data class MessageDto(
    val id: String,
    val senderName: String,
    val mine: Boolean,
    val parentId: String? = null,
    val body: String? = null,
    val createdAt: String,
    val deleted: Boolean = false,
    val readBy: Int = 0,
)

@Serializable
data class ThreadDto(val canSend: Boolean, val canReply: Boolean, val messages: List<MessageDto>)

@Serializable
data class NewMessageDto(val id: String, val body: String, val parentId: String? = null)
