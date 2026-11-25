package com.dailymoney.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dailymoney.core.database.entity.NotificationEntity
import com.dailymoney.core.database.entity.PendingOpEntity
import com.dailymoney.core.database.entity.SettlementEntity
import com.dailymoney.core.database.entity.SharedDecisionEntity
import com.dailymoney.core.database.entity.SharedExpenseEntity
import com.dailymoney.core.database.entity.SharedSplitEntity
import com.dailymoney.core.database.entity.SyncMemberEntity
import com.dailymoney.core.database.entity.SyncGroupEntity
import com.dailymoney.core.database.entity.SyncStateEntity
import com.dailymoney.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** One change from a pull, applied in server order: an upsert, or the removal of a row I can no longer see. */
sealed interface SharedChange {
    data class Upsert(
        val expense: SharedExpenseEntity,
        val splits: List<SharedSplitEntity> = emptyList(),
        val decisions: List<SharedDecisionEntity> = emptyList(),
    ) : SharedChange
    data class Remove(val id: String) : SharedChange
    data class SettlementChange(val settlement: SettlementEntity) : SharedChange
    data class NotificationChange(val notification: NotificationEntity) : SharedChange
}

data class PullPage(
    val groups: List<SyncGroupEntity>,
    val changes: List<SharedChange>,
    val nextCursor: Long,
    val members: List<SyncMemberEntity> = emptyList(),
    val me: String? = null,
    val myUpiId: String? = null,
)

object SyncStatus {
    const val LOCAL = "LOCAL"
    const val PENDING = "PENDING_SYNC"
    const val SYNCED = "SYNCED"
    const val FAILED = "SYNC_FAILED"
}

@Dao
interface SyncDao {

    // ---- groups and group expenses

    @Query("SELECT * FROM sync_groups ORDER BY name COLLATE NOCASE")
    fun observeGroups(): Flow<List<SyncGroupEntity>>

    @Query("SELECT * FROM sync_groups")
    suspend fun groups(): List<SyncGroupEntity>

    @Query("DELETE FROM sync_groups")
    suspend fun clearGroups()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<SyncGroupEntity>)

    @Query("SELECT * FROM shared_expenses WHERE groupId = :groupId ORDER BY occurredAt DESC")
    fun observeShared(groupId: String): Flow<List<SharedExpenseEntity>>

    @Query("SELECT * FROM shared_expenses WHERE id = :id")
    suspend fun shared(id: String): SharedExpenseEntity?

    @Upsert
    suspend fun upsertShared(expense: SharedExpenseEntity)

    @Query("DELETE FROM shared_expenses WHERE id = :id")
    suspend fun deleteShared(id: String)

    @Query("DELETE FROM shared_expenses WHERE groupId NOT IN (:groupIds)")
    suspend fun deleteSharedOutside(groupIds: List<String>)

    @Query("DELETE FROM shared_expenses WHERE groupId IN (:groupIds) AND mine = 0")
    suspend fun deleteOthersIn(groupIds: List<String>)

    @Query("DELETE FROM shared_expenses")
    suspend fun clearShared()

    @Query("SELECT s.* FROM shared_splits s JOIN shared_expenses e ON e.id = s.expenseId WHERE e.groupId = :groupId")
    fun observeSplits(groupId: String): Flow<List<SharedSplitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<SharedSplitEntity>)

    @Query("DELETE FROM shared_splits WHERE expenseId = :expenseId")
    suspend fun deleteSplits(expenseId: String)

    @Query("DELETE FROM shared_splits WHERE expenseId NOT IN (SELECT id FROM shared_expenses)")
    suspend fun deleteOrphanSplits()

    @Query("SELECT d.* FROM shared_decisions d JOIN shared_expenses e ON e.id = d.expenseId WHERE e.groupId = :groupId ORDER BY d.decidedAt")
    fun observeDecisions(groupId: String): Flow<List<SharedDecisionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecisions(decisions: List<SharedDecisionEntity>)

    @Query("DELETE FROM shared_decisions WHERE expenseId = :expenseId")
    suspend fun deleteDecisions(expenseId: String)

    @Query("DELETE FROM shared_decisions WHERE expenseId NOT IN (SELECT id FROM shared_expenses)")
    suspend fun deleteOrphanDecisions()

    @Query("SELECT * FROM sync_members ORDER BY name COLLATE NOCASE")
    fun observeMembers(): Flow<List<SyncMemberEntity>>

    @Query("DELETE FROM sync_members")
    suspend fun clearMembers()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<SyncMemberEntity>)

    @Query("SELECT * FROM settlements WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun observeSettlements(groupId: String): Flow<List<SettlementEntity>>

    @Upsert
    suspend fun upsertSettlement(settlement: SettlementEntity)

    @Query("DELETE FROM settlements WHERE groupId NOT IN (:groupIds)")
    suspend fun deleteSettlementsOutside(groupIds: List<String>)

    @Query("DELETE FROM settlements")
    suspend fun clearSettlements()

    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT 200")
    fun observeNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun notification(id: String): NotificationEntity?

    @Upsert
    suspend fun upsertNotification(notification: NotificationEntity)

    @Query("SELECT * FROM notifications WHERE shown = 0 AND read = 0 AND createdAt >= :since ORDER BY createdAt")
    suspend fun unshownSince(since: Long): List<NotificationEntity>

    @Query("UPDATE notifications SET shown = 1 WHERE id IN (:ids)")
    suspend fun markShown(ids: List<String>)

    @Query("UPDATE notifications SET read = 1 WHERE id IN (:ids)")
    suspend fun markNotificationsRead(ids: List<String>)

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllNotificationsRead()

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    suspend fun unreadCount(): Int

    @Query("DELETE FROM notifications WHERE groupId IS NOT NULL AND groupId NOT IN (:groupIds)")
    suspend fun deleteNotificationsOutside(groupIds: List<String>)

    @Query("DELETE FROM notifications WHERE groupId IS NOT NULL")
    suspend fun clearGroupNotifications()

    // ---- cursor and who I am

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    fun observeState(key: String): Flow<String?>

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun state(key: String): String?

    @Upsert
    suspend fun setState(state: SyncStateEntity)

    // ---- outbox for my shared transactions

    @Query("SELECT * FROM pending_ops WHERE entityType = :entityType ORDER BY createdAt")
    suspend fun pending(entityType: String): List<PendingOpEntity>

    @Query("SELECT COUNT(*) FROM pending_ops WHERE entityId = :entityId")
    suspend fun pendingFor(entityId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(op: PendingOpEntity)

    @Query("DELETE FROM pending_ops WHERE opId IN (:opIds)")
    suspend fun deleteOps(opIds: List<String>)

    /** Including soft-deleted rows, which still have to tell the server. */
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: String): TransactionEntity?

    @Upsert
    suspend fun upsertTransaction(transaction: TransactionEntity)

    @Query("UPDATE transactions SET deletedAt = :now, updatedAt = :now, version = version + 1 WHERE id = :id")
    suspend fun softDeleteTransaction(id: String, now: Long)

    @Query("UPDATE transactions SET localSyncStatus = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE transactions SET serverVersion = :version WHERE id = :id")
    suspend fun setServerVersion(id: String, version: Int)

    @Query("UPDATE transactions SET serverVersion = :version, approvalStatus = :approval, localSyncStatus = 'SYNCED' WHERE id = :id AND orgId IS NOT NULL AND deletedAt IS NULL")
    suspend fun confirmSynced(id: String, version: Int, approval: String)

    @Query("UPDATE transactions SET approvalStatus = :approval WHERE id = :id")
    suspend fun setApproval(id: String, approval: String)

    /** The group no longer has this row (deleted elsewhere): it stays on this phone as a personal entry. */
    @Query("UPDATE transactions SET orgId = NULL, visibility = 'PERSONAL', allowedMemberIds = NULL, splitSpec = NULL, serverVersion = 0, pushAttempted = 0, localSyncStatus = 'LOCAL' WHERE id = :id")
    suspend fun unshare(id: String)

    @Query("UPDATE transactions SET pushAttempted = 1 WHERE id IN (:ids)")
    suspend fun markPushAttempted(ids: List<String>)

    // ---- atomic writes

    /** The row and its outbox entry commit together, so a crash can't lose a change the group should get. */
    @Transaction
    suspend fun saveTransaction(transaction: TransactionEntity, op: PendingOpEntity?) {
        upsertTransaction(transaction)
        op?.let { enqueue(it) }
    }

    @Transaction
    suspend fun deleteTransaction(id: String, now: Long, op: PendingOpEntity?) {
        softDeleteTransaction(id, now)
        op?.let {
            enqueue(it)
            setStatus(id, SyncStatus.PENDING)
        }
    }

    @Transaction
    suspend fun pushApplied(entityId: String, opIds: List<String>, version: Int, approval: String?) {
        deleteOps(opIds)
        setServerVersion(entityId, version)
        approval?.let { setApproval(entityId, it) }
        // An edit made while the push was in flight is still queued and goes out next.
        setStatus(entityId, if (pendingFor(entityId) > 0) SyncStatus.PENDING else SyncStatus.SYNCED)
    }

    @Transaction
    suspend fun pushRejected(entityId: String, opIds: List<String>) {
        deleteOps(opIds)
        setStatus(entityId, SyncStatus.FAILED)
    }

    @Transaction
    suspend fun pushGone(entityId: String, opIds: List<String>) {
        deleteOps(opIds)
        unshare(entityId)
    }

    /** Nothing to tell the server (e.g. made personal or deleted before it was ever sent). */
    @Transaction
    suspend fun pushDropped(entityId: String, opIds: List<String>) {
        deleteOps(opIds)
        setStatus(entityId, SyncStatus.LOCAL)
    }

    /**
     * Applies one pulled page. Returns true when a group became visible since the last pull: its
     * history is older than my cursor, so the caller must pull again from the start.
     */
    @Transaction
    suspend fun applyPull(page: PullPage): Boolean {
        val firstSync = state(CURSOR) == null
        val visibleBefore = groups().filter { it.canView }.map { it.id }.toSet()
        clearGroups()
        insertGroups(page.groups)
        val ids = page.groups.map { it.id }
        if (ids.isEmpty()) {
            clearShared()
            clearSettlements()
            clearGroupNotifications()
        } else {
            deleteSharedOutside(ids)
            deleteSettlementsOutside(ids)
            deleteNotificationsOutside(ids)
        }
        clearMembers()
        insertMembers(page.members)
        page.me?.let { setState(SyncStateEntity(ME, it)) }
        setState(SyncStateEntity(MY_UPI, page.myUpiId.orEmpty()))
        val hidden = page.groups.filter { !it.canView }.map { it.id }
        if (hidden.isNotEmpty()) deleteOthersIn(hidden)

        page.changes.forEach { change ->
            when (change) {
                is SharedChange.Remove -> {
                    deleteShared(change.id)
                    deleteSplits(change.id)
                    deleteDecisions(change.id)
                }
                is SharedChange.SettlementChange -> upsertSettlement(change.settlement)
                is SharedChange.NotificationChange -> {
                    // Read here but not yet on the server: keep it read. Already popped: don't pop again.
                    val local = notification(change.notification.id)
                    upsertNotification(change.notification.copy(read = change.notification.read || local?.read == true, shown = local?.shown == true))
                }
                is SharedChange.Upsert -> {
                    upsertShared(change.expense)
                    deleteSplits(change.expense.id)
                    insertSplits(change.splits)
                    deleteDecisions(change.expense.id)
                    insertDecisions(change.decisions)
                    if (change.expense.mine) {
                        // An approval decision changes the status but not the version, so keep my copy's status current.
                        if (pendingFor(change.expense.id) == 0) confirmSynced(change.expense.id, change.expense.version, change.expense.approvalStatus)
                        else setApproval(change.expense.id, change.expense.approvalStatus)
                    }
                }
            }
        }

        deleteOrphanSplits()
        deleteOrphanDecisions()
        val gained = page.groups.any { it.canView && it.id !in visibleBefore }
        val restart = gained && !firstSync && page.nextCursor > 0
        setState(SyncStateEntity(CURSOR, if (restart) "0" else page.nextCursor.toString()))
        return restart
    }

    companion object {
        const val CURSOR = "cursor"
        const val ME = "me"
        const val MY_UPI = "my_upi"
    }
}
