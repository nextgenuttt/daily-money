package com.dailymoney.core.network.di

import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.network.AuthTokenProvider
import com.dailymoney.core.network.BuildConfig
import com.dailymoney.core.network.DailyMoneyApi
import com.dailymoney.core.network.FirebaseTokenInterceptor
import com.dailymoney.core.network.GroupsApi
import com.dailymoney.core.network.SyncApi
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
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    fun okHttp(tokens: AuthTokenProvider, config: AppConfig): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(FirebaseTokenInterceptor(tokens))
        .authenticator(TokenRefreshAuthenticator(tokens))
        .apply {
            pinner(config)?.let(::certificatePinner)
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                })
            }
        }
        .build()

    /**
     * Pins are opt-in (API_CERT_PINS in local.properties) because a wrong or stale pin locks every
     * installed app out of the server; always ship a backup pin for the next key.
     */
    internal fun pinner(config: AppConfig): CertificatePinner? {
        val host = config.apiBaseUrl.toHttpUrlOrNull()?.host ?: return null
        if (config.apiCertificatePins.isEmpty()) return null
        return CertificatePinner.Builder().apply { config.apiCertificatePins.forEach { add(host, it) } }.build()
    }

    @Provides
    @Singleton
    fun groupsApi(retrofit: Retrofit): GroupsApi = retrofit.create(GroupsApi::class.java)

    @Provides
    @Singleton
    fun syncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)

    @Provides
    @Singleton
    fun api(retrofit: Retrofit): DailyMoneyApi = retrofit.create(DailyMoneyApi::class.java)

    @Provides
    @Singleton
    fun retrofit(config: AppConfig, client: OkHttpClient, json: Json): Retrofit {
        val baseUrl = config.apiBaseUrl.ifBlank { DISABLED_BASE_URL }.let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
