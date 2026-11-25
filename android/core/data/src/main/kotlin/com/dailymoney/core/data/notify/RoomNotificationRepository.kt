package com.dailymoney.core.data.notify

import com.dailymoney.core.data.money.stream
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.NotificationEntity
import com.dailymoney.core.database.entity.SyncStateEntity
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.sharing.AppNotification
import com.dailymoney.core.domain.sharing.NotificationCategory
import com.dailymoney.core.domain.sharing.NotificationPrefs
import com.dailymoney.core.domain.sharing.NotificationRepository
import com.dailymoney.core.domain.sharing.NotificationType
import com.dailymoney.core.domain.sharing.QuietHours
import com.dailymoney.core.network.SyncApi
import com.dailymoney.core.network.model.ReadNotificationsDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Singleton
class RoomNotificationRepository @Inject constructor(
    private val db: DatabaseManager,
    private val api: SyncApi,
    private val config: AppConfig,
    private val json: Json,
    private val digest: DigestScheduler,
) : NotificationRepository {

    @Serializable
    private data class StoredPrefs(
        val disabled: List<String> = emptyList(),
        val muted: List<String> = emptyList(),
        val quietOn: Boolean = false,
        val quietStart: Int = 22,
        val quietEnd: Int = 7,
        val digest: Boolean = false,
    )

    override fun observe(): Flow<List<AppNotification>> =
        db.stream { syncDao().observeNotifications() }.map { rows -> rows.mapNotNull { it.toDomain(json) } }

    override fun observeUnreadCount(): Flow<Int> = db.stream { syncDao().observeUnreadCount() }

    /** Read here at once; the server is told when possible, and a later pull never marks it unread again. */
    override suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.get().syncDao().markNotificationsRead(ids)
            tellServer(ids)
        }
    }

    override suspend fun markAllRead() = withContext(Dispatchers.IO) {
        db.get().syncDao().markAllNotificationsRead()
        tellServer(emptyList())
    }

    private suspend fun tellServer(ids: List<String>) {
        if (!config.backendEnabled) return
        try {
            api.readNotifications(ReadNotificationsDto(ids))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline: this phone already shows it read.
        }
    }

    override fun observePrefs(): Flow<NotificationPrefs> = db.stream { syncDao().observeState(KEY_PREFS) }.map { decode(it) }

    override suspend fun savePrefs(prefs: NotificationPrefs) {
        withContext(Dispatchers.IO) {
            val stored = StoredPrefs(
                disabled = prefs.disabled.map { it.name }.sorted(),
                muted = prefs.mutedGroups.sorted(),
                quietOn = prefs.quietHours.enabled,
                quietStart = prefs.quietHours.startHour,
                quietEnd = prefs.quietHours.endHour,
                digest = prefs.dailyDigest,
            )
            db.get().syncDao().setState(SyncStateEntity(KEY_PREFS, json.encodeToString(StoredPrefs.serializer(), stored)))
        }
        digest.schedule(prefs.dailyDigest)
    }

    suspend fun prefs(): NotificationPrefs = withContext(Dispatchers.IO) { decode(db.get().syncDao().state(KEY_PREFS)) }

    private fun decode(text: String?): NotificationPrefs {
        val s = text?.let { runCatching { json.decodeFromString(StoredPrefs.serializer(), it) }.getOrNull() } ?: StoredPrefs()
        return NotificationPrefs(
            disabled = s.disabled.mapNotNull { runCatching { NotificationCategory.valueOf(it) }.getOrNull() }.toSet(),
            mutedGroups = s.muted.toSet(),
            quietHours = QuietHours(s.quietOn, s.quietStart, s.quietEnd),
            dailyDigest = s.digest,
        )
    }

    companion object {
        const val KEY_PREFS = "notification_prefs"
    }
}

/** Unknown types (from a newer server) are skipped rather than shown wrong. */
internal fun NotificationEntity.toDomain(json: Json): AppNotification? {
    val kind = runCatching { NotificationType.valueOf(type) }.getOrNull() ?: return null
    val params = runCatching { json.parseToJsonElement(paramsJson).jsonObject }.getOrNull()
        ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
    return AppNotification(id, groupId, kind, entityType, entityId, params, Instant.ofEpochMilli(createdAt), read)
}

