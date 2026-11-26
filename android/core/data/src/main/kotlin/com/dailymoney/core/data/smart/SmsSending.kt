package com.dailymoney.core.data.smart

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.sharing.SmsSendResult
import com.dailymoney.core.domain.sharing.SmsSender
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends a reminder SMS from the phone's own SIM and waits for the radio to report the result. Only the
 * "personal" build declares SEND_SMS (Google Play allows it only for default SMS apps).
 */
@Singleton
class AndroidSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: AppConfig,
) : SmsSender {

    override val supported: Boolean get() = config.smsSendBuild

    override fun hasPermission(): Boolean =
        supported && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    override suspend fun send(phone: String, text: String): SmsSendResult {
        if (!supported) return SmsSendResult.NOT_SUPPORTED
        if (!hasPermission()) return SmsSendResult.NO_PERMISSION
        val manager = smsManager() ?: return SmsSendResult.FAILED
        val action = "com.dailymoney.SMS_SENT." + UUID.randomUUID()
        val parts = manager.divideMessage(text)
        return withTimeoutOrNull(SEND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var pending = parts.size
                var failed = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (resultCode != Activity.RESULT_OK) failed = true
                        pending--
                        if (pending == 0) {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(if (failed) SmsSendResult.FAILED else SmsSendResult.SENT)
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val sent = ArrayList(parts.indices.map { i ->
                    PendingIntent.getBroadcast(context, i, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                })
                try {
                    if (parts.size == 1) manager.sendTextMessage(phone, null, text, sent[0], null)
                    else manager.sendMultipartTextMessage(phone, null, parts, sent, null)
                } catch (e: SecurityException) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(SmsSendResult.NO_PERMISSION)
                } catch (e: Exception) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(SmsSendResult.FAILED)
                }
            }
        } ?: SmsSendResult.FAILED
    }

    /** The phone's default SMS SIM; on dual-SIM phones with no default, Android picks or asks. */
    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private companion object {
        const val SEND_TIMEOUT_MS = 30_000L
    }
}
