package com.dailymoney.core.network

import com.dailymoney.core.network.model.BootstrapRequestDto
import com.dailymoney.core.network.model.ProfileDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Path

interface DailyMoneyApi {

    @POST("api/v1/me/bootstrap")
    suspend fun bootstrap(@Body body: BootstrapRequestDto): ProfileDto

    @DELETE("api/v1/me/devices/{deviceId}")
    suspend fun removeDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @POST("api/v1/auth/logout-all")
    suspend fun logoutAll(): Response<Unit>

    @DELETE("api/v1/me")
    suspend fun deleteAccount(): Response<Unit>
}
