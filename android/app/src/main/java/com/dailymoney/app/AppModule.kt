package com.dailymoney.app

import com.dailymoney.core.domain.config.AppConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun appConfig(): AppConfig = AppConfig(
        applicationId = BuildConfig.APPLICATION_ID,
        appVersion = BuildConfig.VERSION_NAME,
        googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
        authContinueUrl = BuildConfig.AUTH_CONTINUE_URL,
        apiBaseUrl = BuildConfig.API_BASE_URL,
        privacyPolicyUrl = BuildConfig.PRIVACY_URL,
        payPageUrl = BuildConfig.PAY_URL,
        smsReaderBuild = BuildConfig.SMS_READER,
        smsSendBuild = BuildConfig.SMS_SENDER,
        apiCertificatePins = BuildConfig.API_CERT_PINS.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )
}
