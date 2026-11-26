package com.dailymoney.core.data.sync

import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.dao.SyncDao
import com.dailymoney.core.database.entity.SyncStateEntity
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.sharing.SettleMethod
import com.dailymoney.core.domain.sharing.SettleResult
import com.dailymoney.core.domain.sharing.SettlementRepository
import com.dailymoney.core.domain.sharing.SyncScheduler
import com.dailymoney.core.network.SyncApi
import com.dailymoney.core.network.model.NewSettlementDto
import com.dailymoney.core.network.model.ProblemDto
import com.dailymoney.core.network.model.SettlementDto
import com.dailymoney.core.network.model.PhoneRequestDto
import com.dailymoney.core.network.model.ReminderRequestDto
import com.dailymoney.core.network.model.UpiRequestDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Settlements need the receiver's answer, so they go straight to the server; the result is kept locally at once. */
@Singleton
class RemoteSettlementRepository @Inject constructor(
    private val api: SyncApi,
    private val db: DatabaseManager,
    private val config: AppConfig,
    private val json: Json,
    private val sync: SyncScheduler,
) : SettlementRepository {

    override suspend fun record(
        groupId: String, payerId: String, receiverId: String, amountMinor: Long, currency: String, method: SettleMethod, note: String?,
    ) = call { keep(api.recordSettlement(groupId, NewSettlementDto(Ids.newId(), payerId, receiverId, amountMinor, currency, method.name, note?.trim()?.ifEmpty { null }))) }

    override suspend fun confirm(settlementId: String) = call { keep(api.confirmSettlement(settlementId)) }

    override suspend fun cancel(settlementId: String) = call { keep(api.cancelSettlement(settlementId)) }

    override suspend fun setUpiId(upiId: String?) = call {
        val cleaned = upiId?.trim()?.ifEmpty { null }
        val response = api.setUpiId(UpiRequestDto(cleaned))
        if (!response.isSuccessful) throw HttpException(response)
        db.get().syncDao().setState(SyncStateEntity(SyncDao.MY_UPI, cleaned.orEmpty()))
    }

    /** The server returns the normalised number; the next pull stores it, so read it back from there. */
    override suspend fun setPhone(phone: String?) = call {
        val cleaned = phone?.trim()?.ifEmpty { null }
        val response = api.setPhone(PhoneRequestDto(cleaned))
        if (!response.isSuccessful) throw HttpException(response)
        sync.requestSync()
        if (cleaned == null) db.get().syncDao().setState(SyncStateEntity(SyncDao.MY_PHONE, ""))
    }

    override suspend fun remind(groupId: String, userId: String, amountMinor: Long, currency: String) = call {
        val response = api.remind(groupId, ReminderRequestDto(userId, amountMinor, currency))
        if (!response.isSuccessful) throw HttpException(response)
    }

    private suspend fun keep(dto: SettlementDto) = db.get().syncDao().upsertSettlement(dto.toEntity())

    private suspend fun call(block: suspend () -> Unit): SettleResult {
        if (!config.backendEnabled) return SettleResult.BackendDisabled
        return withContext(Dispatchers.IO) {
            try {
                block()
                SettleResult.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val code = e.response()?.errorBody()?.string()?.let { body -> runCatching { json.decodeFromString(ProblemDto.serializer(), body).code }.getOrNull() }
                SettleResult.Refused(code ?: if (e.code() == 404) "NOT_FOUND" else "HTTP_${e.code()}")
            } catch (e: IOException) {
                SettleResult.Offline
            }
        }
    }
}
