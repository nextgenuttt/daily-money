package com.dailymoney.core.network

import com.dailymoney.core.network.model.MessageDto
import com.dailymoney.core.network.model.NewMessageDto
import com.dailymoney.core.network.model.NewSettlementDto
import com.dailymoney.core.network.model.ReadNotificationsDto
import com.dailymoney.core.network.model.ThreadDto
import com.dailymoney.core.network.model.SettlementDto
import com.dailymoney.core.network.model.SyncPullDto
import com.dailymoney.core.network.model.PhoneRequestDto
import com.dailymoney.core.network.model.ReminderRequestDto
import com.dailymoney.core.network.model.UpiRequestDto
import com.dailymoney.core.network.model.SyncPushRequestDto
import com.dailymoney.core.network.model.SyncPushResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Backend SyncController (LLD §6.2, §6.3). */
interface SyncApi {
    @POST("api/v1/sync/push")
    suspend fun push(@Body body: SyncPushRequestDto): SyncPushResponseDto

    @GET("api/v1/sync/pull")
    suspend fun pull(@Query("cursor") cursor: Long, @Query("limit") limit: Int): SyncPullDto

    @POST("api/v1/organizations/{orgId}/settlements")
    suspend fun recordSettlement(@Path("orgId") orgId: String, @Body body: NewSettlementDto): SettlementDto

    @POST("api/v1/settlements/{id}/confirm")
    suspend fun confirmSettlement(@Path("id") id: String): SettlementDto

    @POST("api/v1/settlements/{id}/cancel")
    suspend fun cancelSettlement(@Path("id") id: String): SettlementDto

    @PUT("api/v1/me/upi")
    suspend fun setUpiId(@Body body: UpiRequestDto): Response<Unit>

    @PUT("api/v1/me/phone")
    suspend fun setPhone(@Body body: PhoneRequestDto): Response<Unit>

    @POST("api/v1/organizations/{orgId}/payment-reminders")
    suspend fun remind(@Path("orgId") orgId: String, @Body body: ReminderRequestDto): Response<Unit>

    @PUT("api/v1/notifications/read")
    suspend fun readNotifications(@Body body: ReadNotificationsDto): Response<Unit>

    @GET("api/v1/transactions/{id}/messages")
    suspend fun thread(@Path("id") expenseId: String): ThreadDto

    @POST("api/v1/transactions/{id}/messages")
    suspend fun sendMessage(@Path("id") expenseId: String, @Body body: NewMessageDto): MessageDto

    @POST("api/v1/transactions/{id}/messages/read")
    suspend fun readMessages(@Path("id") expenseId: String): Response<Unit>

    @DELETE("api/v1/messages/{id}")
    suspend fun deleteMessage(@Path("id") messageId: String): Response<Unit>
}
