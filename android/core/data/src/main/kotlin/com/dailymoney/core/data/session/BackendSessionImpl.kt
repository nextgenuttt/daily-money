package com.dailymoney.core.data.session

import android.os.Build
import com.dailymoney.core.domain.auth.BackendSession
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.network.DailyMoneyApi
import com.dailymoney.core.network.model.BootstrapRequestDto
import com.dailymoney.core.security.DeviceIdentity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@Singleton
class BackendSessionImpl @Inject constructor(
    private val config: AppConfig,
    private val api: DailyMoneyApi,
    private val deviceIdentity: DeviceIdentity,
    private val auth: FirebaseAuth,
) : BackendSession {

    /** Sign-in and a new push token can both ask at once on first launch; one call at a time. */
    private val bootstrapLock = Mutex()

    override suspend fun bootstrap(): Boolean {
        if (!config.backendEnabled) return true
        return bootstrapLock.withLock { register() }
    }

    private suspend fun register(): Boolean {
        return safe {
            api.bootstrap(
                BootstrapRequestDto(
                    deviceId = deviceIdentity.deviceId(),
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                    appVersion = config.appVersion,
                    fcmToken = pushToken(),
                    displayName = auth.currentUser?.displayName,
                    timeZone = TimeZone.getDefault().id,
                ),
            )
            true
        }
    }

    override suspend fun unregisterDevice() {
        if (config.backendEnabled) {
            safe { api.removeDevice(deviceIdentity.deviceId()).isSuccessful }
        }
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
    }

    override suspend fun signOutEverywhere(): Boolean {
        if (!config.backendEnabled) return false
        return safe { api.logoutAll().isSuccessful }
    }

    private suspend fun pushToken(): String? = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()

    private suspend fun safe(block: suspend () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
