package com.dailymoney.core.data.groups

import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.groups.CreatedInvite
import com.dailymoney.core.domain.groups.GroupDetail
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupInvitation
import com.dailymoney.core.domain.groups.GroupKind
import com.dailymoney.core.domain.groups.GroupMember
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.groups.GroupRole
import com.dailymoney.core.domain.groups.GroupSummary
import com.dailymoney.core.domain.groups.InvitePreview
import com.dailymoney.core.domain.groups.InviteStatus
import com.dailymoney.core.domain.groups.MemberPermissions
import com.dailymoney.core.domain.groups.PermissionEffect
import com.dailymoney.core.domain.groups.PermissionMatrix
import com.dailymoney.core.domain.groups.RolePermissions
import com.dailymoney.core.network.GroupsApi
import com.dailymoney.core.network.model.CreateGroupDto
import com.dailymoney.core.network.model.GroupDetailDto
import com.dailymoney.core.network.model.GroupSummaryDto
import com.dailymoney.core.network.model.InvitationDto
import com.dailymoney.core.network.model.InviteRequestDto
import com.dailymoney.core.network.model.OverridesDto
import com.dailymoney.core.network.model.RoleUpdateDto
import com.dailymoney.core.network.model.ProblemDto
import com.dailymoney.core.network.model.RoleChangeDto
import com.dailymoney.core.network.model.TransferDto
import com.dailymoney.core.network.model.UpdateGroupDto
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response

/**
 * Groups live on the server (shared with other people). Phase 7 adds a local copy and live updates;
 * until then every screen loads fresh data and shows a clear offline state.
 */
@Singleton
class RemoteGroupRepository @Inject constructor(
    private val api: GroupsApi,
    private val config: AppConfig,
    private val json: Json,
) : GroupRepository {

    override suspend fun list() = call { api.list().map { it.toDomain() } }
    override suspend fun create(name: String, kind: GroupKind) = call { api.create(CreateGroupDto(name.trim(), kind.name, config.defaultCurrency)).toDomain() }
    override suspend fun get(id: String) = call { api.get(id).toDomain() }
    override suspend fun update(id: String, name: String, kind: GroupKind) = call { api.update(id, UpdateGroupDto(name.trim(), kind.name)).toDomain() }
    override suspend fun delete(id: String) = unit { api.delete(id) }

    override suspend fun members(id: String) = call {
        api.members(id).map {
            GroupMember(it.userId, it.displayName, it.email, role(it.role), parseInstant(it.joinedAt), it.isMe)
        }
    }

    override suspend fun changeRole(id: String, userId: String, role: GroupRole) = unit { api.changeRole(id, userId, RoleChangeDto(role.name)) }
    override suspend fun removeMember(id: String, userId: String) = unit { api.removeMember(id, userId) }
    override suspend fun leave(id: String) = unit { api.leave(id) }
    override suspend fun transferOwnership(id: String, userId: String) = unit { api.transfer(id, TransferDto(userId)) }

    override suspend fun invite(id: String, role: GroupRole, email: String?, maxUses: Int) = call {
        val created = api.invite(id, InviteRequestDto(role.name, email?.trim()?.ifEmpty { null }, maxUses))
        CreatedInvite(created.invitation.toDomain(), created.link)
    }

    override suspend fun invitations(id: String) = call { api.invitations(id).map { it.toDomain() } }
    override suspend fun revoke(invitationId: String) = unit { api.revoke(invitationId) }

    override suspend fun preview(token: String) = call {
        val p = api.preview(token)
        InvitePreview(
            groupId = p.organizationId, groupName = p.organizationName, kind = kind(p.kind), invitedBy = p.invitedBy,
            role = role(p.role), expiresAt = parseInstant(p.expiresAt),
            status = runCatching { InviteStatus.valueOf(p.status) }.getOrDefault(InviteStatus.EXPIRED),
            alreadyMember = p.alreadyMember, emailBound = p.emailBound,
        )
    }

    override suspend fun accept(token: String) = call { api.accept(token).toDomain() }
    override suspend fun decline(token: String) = unit { api.decline(token) }

    override suspend fun permissions(id: String) = call {
        val m = api.permissions(id)
        PermissionMatrix(m.definitions, m.roles.map { RolePermissions(it.id, role(it.code), it.permissions.toSet(), it.editable) }, m.canManage)
    }

    override suspend fun updateRole(id: String, roleId: String, permissions: Set<String>) =
        unit { api.updateRole(id, roleId, RoleUpdateDto(permissions.sorted())) }

    override suspend fun memberPermissions(id: String, userId: String) = call {
        val p = api.memberPermissions(id, userId)
        MemberPermissions(
            role = role(p.role),
            roleDefaults = p.roleDefaults.toSet(),
            overrides = p.overrides.mapNotNull { (k, v) -> runCatching { PermissionEffect.valueOf(v) }.getOrNull()?.let { k to it } }.toMap(),
            effective = p.effective.toSet(),
        )
    }

    override suspend fun setOverrides(id: String, userId: String, overrides: Map<String, PermissionEffect>) =
        unit { api.setOverrides(id, userId, OverridesDto(overrides.mapValues { it.value.name })) }

    private suspend fun unit(block: suspend () -> Response<Unit>): GroupResult<Unit> = call {
        val response = block()
        if (!response.isSuccessful) throw HttpException(response)
    }

    private suspend fun <T> call(block: suspend () -> T): GroupResult<T> {
        if (!config.backendEnabled) return GroupResult.Err(GroupError.BackendDisabled)
        return withContext(Dispatchers.IO) {
            try {
                GroupResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                GroupResult.Err(mapHttp(e.code(), e.response()?.errorBody()?.string()))
            } catch (e: IOException) {
                GroupResult.Err(GroupError.Offline)
            } catch (e: Exception) {
                GroupResult.Err(GroupError.Unknown(e.message))
            }
        }
    }

    internal fun mapHttp(status: Int, body: String?): GroupError {
        val code = body?.let { runCatching { json.decodeFromString(ProblemDto.serializer(), it).code }.getOrNull() }
        return when {
            status == 401 -> GroupError.SessionExpired
            status == 404 -> GroupError.NotFound
            code == "PERMISSION_DENIED" -> GroupError.PermissionDenied
            code != null -> GroupError.Rule(code)
            status == 403 -> GroupError.PermissionDenied
            else -> GroupError.Unknown("HTTP $status")
        }
    }

    private fun GroupSummaryDto.toDomain() = GroupSummary(id, name, kind(kind), currency, role(myRole), memberCount)
    private fun GroupDetailDto.toDomain() = GroupDetail(id, name, kind(kind), currency, role(myRole), myPermissions.toSet(), memberCount)
    private fun InvitationDto.toDomain() = GroupInvitation(id, role(role), email, maxUses, uses, parseInstant(expiresAt))
    private fun role(code: String) = runCatching { GroupRole.valueOf(code) }.getOrDefault(GroupRole.VIEWER)
    private fun kind(code: String) = runCatching { GroupKind.valueOf(code) }.getOrDefault(GroupKind.OTHER)
    private fun parseInstant(s: String) = runCatching { Instant.parse(s) }.getOrDefault(Instant.EPOCH)
}
