package com.dailymoney.core.network.di

import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.network.AuthTokenProvider
import com.dailymoney.core.network.BuildConfig
import com.dailymoney.core.network.DailyMoneyApi
import com.dailymoney.core.network.FirebaseTokenInterceptor
import com.dailymoney.core.network.TokenRefreshAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Retrofit needs a valid URL even when the backend is off (R1); no call is made in that case.
    private const val DISABLED_BASE_URL = "http://localhost/"

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun okHttp(tokens: AuthTokenProvider): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(FirebaseTokenInterceptor(tokens))
        .authenticator(TokenRefreshAuthenticator(tokens))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                })
            }
        }
        .build()

    @Provides
    @Singleton
    fun api(config: AppConfig, client: OkHttpClient, json: Json): DailyMoneyApi {
        val baseUrl = config.apiBaseUrl.ifBlank { DISABLED_BASE_URL }.let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DailyMoneyApi::class.java)
    }
}
