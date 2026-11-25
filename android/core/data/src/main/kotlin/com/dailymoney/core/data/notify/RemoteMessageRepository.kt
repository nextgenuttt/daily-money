package com.dailymoney.core.data.notify

import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.sharing.ExpenseMessage
import com.dailymoney.core.domain.sharing.MessageRepository
import com.dailymoney.core.domain.sharing.MessageThread
import com.dailymoney.core.network.SyncApi
import com.dailymoney.core.network.model.NewMessageDto
import com.dailymoney.core.network.model.ProblemDto
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

@Singleton
class RemoteMessageRepository @Inject constructor(
    private val api: SyncApi,
    private val config: AppConfig,
    private val json: Json,
) : MessageRepository {

    override suspend fun thread(expenseId: String) = call {
        val t = api.thread(expenseId)
        MessageThread(t.canSend, t.canReply, t.messages.map {
            ExpenseMessage(it.id, it.senderName, it.mine, it.parentId, if (it.deleted) null else it.body,
                runCatching { Instant.parse(it.createdAt) }.getOrDefault(Instant.EPOCH), it.readBy)
        })
    }

    override suspend fun send(expenseId: String, body: String, parentId: String?) = call {
        api.sendMessage(expenseId, NewMessageDto(Ids.newId(), body.trim(), parentId))
        Unit
    }

    override suspend fun markRead(expenseId: String) = unit { api.readMessages(expenseId) }
    override suspend fun delete(messageId: String) = unit { api.deleteMessage(messageId) }

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
                val code = e.response()?.errorBody()?.string()?.let { b -> runCatching { json.decodeFromString(ProblemDto.serializer(), b).code }.getOrNull() }
                GroupResult.Err(
                    when {
                        e.code() == 404 -> GroupError.NotFound
                        e.code() == 401 -> GroupError.SessionExpired
                        code == "PERMISSION_DENIED" || e.code() == 403 -> GroupError.PermissionDenied
                        code != null -> GroupError.Rule(code)
                        else -> GroupError.Unknown("HTTP ${e.code()}")
                    },
                )
            } catch (e: IOException) {
                GroupResult.Err(GroupError.Offline)
            }
        }
    }
}
