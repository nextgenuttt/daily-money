package com.dailymoney.core.data.sync

import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.dao.SyncDao
import com.dailymoney.core.database.entity.SyncStateEntity
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.sharing.SettleMethod
import com.dailymoney.core.domain.sharing.SettleResult
import com.dailymoney.core.domain.sharing.SettlementRepository
import com.dailymoney.core.network.SyncApi
import com.dailymoney.core.network.model.NewSettlementDto
import com.dailymoney.core.network.model.ProblemDto
import com.dailymoney.core.network.model.SettlementDto
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
