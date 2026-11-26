package com.dailymoney.core.data.sync

import com.dailymoney.core.data.money.RoomTransactionRepository
import com.dailymoney.core.data.notify.NotificationPoster
import com.dailymoney.core.database.AppDatabase
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.dao.PullPage
import com.dailymoney.core.database.dao.SharedChange
import com.dailymoney.core.database.dao.SyncDao
import com.dailymoney.core.database.entity.NotificationEntity
import com.dailymoney.core.database.entity.SettlementEntity
import com.dailymoney.core.database.entity.SharedDecisionEntity
import com.dailymoney.core.database.entity.SharedExpenseEntity
import com.dailymoney.core.database.entity.SharedSplitEntity
import com.dailymoney.core.database.entity.SyncMemberEntity
import com.dailymoney.core.database.entity.SyncGroupEntity
import com.dailymoney.core.database.entity.TransactionEntity
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.network.SyncApi
import com.dailymoney.core.network.model.ExpensePayloadDto
import com.dailymoney.core.network.model.ExpenseViewDto
import com.dailymoney.core.network.model.NotificationDto
import com.dailymoney.core.network.model.SettlementDto
import com.dailymoney.core.network.model.SplitDto
import com.dailymoney.core.network.model.SplitEntryDto
import com.dailymoney.core.network.model.SyncOpDto
import com.dailymoney.core.network.model.SyncPullDto
import com.dailymoney.core.network.model.SyncPushRequestDto
import com.dailymoney.core.security.DeviceIdentity
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

/**
 * Sends my queued group-expense changes, then pulls what changed in my groups (LLD §6). Each pushed
 * op carries a stable id, so a retry after a lost response is applied once.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val database: DatabaseManager,
    private val api: SyncApi,
    private val config: AppConfig,
    private val deviceIdentity: DeviceIdentity,
    private val poster: NotificationPoster,
) {
    private val mutex = Mutex()

    /** False when the server couldn't be reached or failed; the caller retries later. */
    suspend fun sync(): Boolean {
        if (!config.backendEnabled) return true
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val runner = SyncRunner(database.get(), api, deviceIdentity.deviceId())
                    runner.push()
                    runner.pull()
                    poster.postNew()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    false
                } catch (e: HttpException) {
                    false
                } catch (e: SerializationException) {
                    false
                }
            }
        }
    }
}

/** One push + pull against [db]; split out of [SyncEngine] so it can be tested with a fake server. */
internal class SyncRunner(private val db: AppDatabase, private val api: SyncApi, private val deviceId: String) {

    suspend fun push() {
        val dao = db.syncDao()
        repeat(MAX_PUSH_ROUNDS) {
            val ops = dao.pending(RoomTransactionRepository.ENTITY_TRANSACTION)
            if (ops.isEmpty()) return
            val batch = mutableListOf<Pair<SyncOpDto, List<String>>>()
            for ((entityId, entityOps) in ops.groupBy { it.entityId }) {
                val opIds = entityOps.map { it.opId }
                // Several edits made offline go out as one op: the row's current state.
                val op = dao.transaction(entityId)?.let { toOp(it, entityOps.last().opId) }
                if (op == null) dao.pushDropped(entityId, opIds) else batch += op to opIds
            }
            var rebased = false
            for (chunk in batch.chunked(MAX_OPS)) {
                val sent = chunk.associateBy { it.first.opId }
                // Recorded before sending: if the reply is lost, the server may have the row even though
                // we never learnt its version, so a later delete or un-share must still be sent.
                dao.markPushAttempted(chunk.map { it.first.entityId })
                val response = api.push(SyncPushRequestDto(deviceId, chunk.map { it.first }))
                for (result in response.results) {
                    val (op, opIds) = sent[result.opId] ?: continue
                    when (result.status) {
                        "APPLIED" ->
                            // Deleting the group copy of a row I made personal: it's a plain personal row again.
                            if (op.opType == "DELETE" && dao.transaction(op.entityId)?.deletedAt == null) dao.pushGone(op.entityId, opIds)
                            else dao.pushApplied(op.entityId, opIds, result.version ?: op.baseVersion, result.approvalStatus)
                        "GONE" -> dao.pushGone(op.entityId, opIds)
                        // Only the creator edits an expense, so this phone's latest edit wins over an older one
                        // from another of my devices: rebase on the server's version and send again.
                        "CONFLICT" -> result.current?.let {
                            dao.setServerVersion(op.entityId, it.version)
                            rebased = true
                        }
                        else -> dao.pushRejected(op.entityId, opIds)
                    }
                }
            }
            if (!rebased) return
        }
    }

    private suspend fun toOp(row: TransactionEntity, opId: String): SyncOpDto? {
        val orgId = row.orgId
        return when {
            // Deleted, or made personal: the group copy goes, if the server might have one.
            row.deletedAt != null || orgId == null || row.type == "TRANSFER" ->
                if (row.serverVersion == 0 && !row.pushAttempted) null else SyncOpDto(opId, ENTITY, row.id, "DELETE", row.serverVersion)
            else -> {
                val category = row.categoryId?.let { db.categoryDao().get(it) }
                SyncOpDto(
                    opId = opId,
                    entityType = ENTITY,
                    entityId = row.id,
                    opType = if (row.serverVersion == 0) "CREATE" else "UPDATE",
                    baseVersion = row.serverVersion,
                    payload = ExpensePayloadDto(
                        orgId = orgId,
                        type = row.type,
                        amountMinor = row.amountMinor,
                        currency = row.currency,
                        categoryName = category?.name?.take(60),
                        categoryIcon = category?.iconKey,
                        occurredAt = Instant.ofEpochMilli(row.occurredAt).toString(),
                        note = row.note,
                        paymentMethod = row.paymentMethod,
                        visibility = row.visibility,
                        allowedMemberIds = row.allowedMemberIds?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                        split = SplitSpecCodec.decode(row.splitSpec)?.let { spec ->
                            SplitDto(spec.method.name, spec.entries.map { SplitEntryDto(it.userId, it.value.toPlainString()) })
                        },
                    ),
                )
            }
        }
    }

    suspend fun pull() {
        val dao = db.syncDao()
        repeat(MAX_PULL_PAGES) {
            val cursor = dao.state(SyncDao.CURSOR)?.toLongOrNull() ?: 0L
            val page = api.pull(cursor, PAGE_SIZE)
            val restart = dao.applyPull(page.toPullPage())
            if (!restart && !page.hasMore) return
        }
    }

    private companion object {
        const val ENTITY = RoomTransactionRepository.ENTITY_TRANSACTION
        const val MAX_OPS = 100
        const val MAX_PUSH_ROUNDS = 3
        const val PAGE_SIZE = 500
        const val MAX_PULL_PAGES = 50
    }
}

internal fun SyncPullDto.toPullPage() = PullPage(
    groups = orgs.map {
        SyncGroupEntity(it.id, it.name, it.currency, it.canView, it.canAdd, it.canReport, it.canSplit, it.approvalMode, it.requiredApprovals, it.isApprover, it.allowSelfApproval)
    },
    changes = changes.mapNotNull { change ->
        when {
            change.entityType == ENTITY_SETTLEMENT -> change.settlement?.let { SharedChange.SettlementChange(it.toEntity()) }
            change.entityType == ENTITY_NOTIFICATION -> change.notification?.let { SharedChange.NotificationChange(it.toEntity()) }
            change.entityType != RoomTransactionRepository.ENTITY_TRANSACTION -> null
            change.op == "UPSERT" -> change.data?.let { data ->
                SharedChange.Upsert(
                    data.toEntity(),
                    data.split?.shares.orEmpty().map { SharedSplitEntity(data.id, it.userId, it.shareMinor, (it.value as? JsonPrimitive)?.content) },
                    data.approvals.map {
                        SharedDecisionEntity(
                            data.id, it.version, it.approverId, it.approverName, it.decision, it.reason,
                            runCatching { Instant.parse(it.decidedAt).toEpochMilli() }.getOrDefault(0L),
                        )
                    },
                )
            }
            change.op == "DELETE" || change.op == "REVOKE" -> SharedChange.Remove(change.id)
            else -> null
        }
    },
    nextCursor = nextCursor,
    members = orgs.flatMap { org -> org.members.map { SyncMemberEntity(org.id, it.userId, it.name, it.upiId, it.phone) } },
    me = me,
    myUpiId = myUpiId,
    myPhone = myPhone,
)

private const val ENTITY_SETTLEMENT = "SETTLEMENT"
private const val ENTITY_NOTIFICATION = "NOTIFICATION"

internal fun NotificationDto.toEntity() = NotificationEntity(
    id = id,
    groupId = orgId,
    type = type,
    entityType = entityType,
    entityId = entityId,
    paramsJson = params.toString(),
    createdAt = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L),
    read = read,
)

internal fun SettlementDto.toEntity() = SettlementEntity(
    id = id,
    groupId = orgId,
    payerId = payerId,
    receiverId = receiverId,
    amountMinor = amountMinor,
    currency = currency,
    method = method,
    status = status,
    note = note,
    createdBy = createdBy,
    createdAt = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L),
)

internal fun ExpenseViewDto.toEntity() = SharedExpenseEntity(
    id = id,
    groupId = orgId,
    creatorName = creatorName,
    mine = mine,
    type = type,
    amountMinor = amountMinor,
    currency = currency,
    categoryName = categoryName,
    categoryIcon = categoryIcon,
    occurredAt = Instant.parse(occurredAt).toEpochMilli(),
    note = note,
    paymentMethod = paymentMethod,
    visibility = visibility,
    version = version,
    creatorId = creatorId,
    splitMethod = split?.method,
    approvalStatus = approvalStatus,
)
