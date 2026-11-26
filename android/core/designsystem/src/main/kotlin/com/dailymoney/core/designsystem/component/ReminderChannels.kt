package com.dailymoney.core.designsystem.component

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.dailymoney.core.designsystem.R
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.sharing.SmsSendResult

/** "Send SMS now" from the user's SIM: only offered in the build that may send SMS, and only with a number. */
class DirectSms(val result: SmsSendResult?, val sending: Boolean, val send: () -> Unit)

/**
 * The ways to send a "you owe me" message from the user's own phone: the message preview, WhatsApp
 * (straight to their chat when [phone] is known), the SMS app, and optionally a direct SMS.
 */
@Composable
fun ReminderChannels(phone: String?, message: String, directSms: DirectSms?) {
    val context = LocalContext.current
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) directSms?.send?.invoke() }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.sm))
        }
        if (directSms != null && phone != null) {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                    if (granted) directSms.send() else askSms.launch(Manifest.permission.SEND_SMS)
                },
                enabled = !directSms.sending && directSms.result != SmsSendResult.SENT,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text(stringResource(R.string.reminder_send_sms_now, phone), modifier = Modifier.padding(start = Spacing.sm))
            }
            when (directSms.result) {
                SmsSendResult.SENT -> InfoBanner(stringResource(R.string.reminder_sms_sent))
                SmsSendResult.NO_PERMISSION -> ErrorBanner(stringResource(R.string.reminder_sms_no_permission))
                SmsSendResult.FAILED, SmsSendResult.NOT_SUPPORTED -> ErrorBanner(stringResource(R.string.reminder_sms_failed))
                null -> Unit
            }
        }
        OutlinedButton(onClick = { openWhatsApp(context, phone, message) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
            Text(stringResource(R.string.reminder_whatsapp), modifier = Modifier.padding(start = Spacing.sm))
        }
        OutlinedButton(onClick = { openSmsApp(context, phone, message) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Sms, contentDescription = null)
            Text(stringResource(R.string.reminder_sms_app), modifier = Modifier.padding(start = Spacing.sm))
        }
    }
}

/** Straight to their chat when the number is known; otherwise WhatsApp's "send to" picker, then any app. */
fun openWhatsApp(context: Context, phone: String?, text: String) {
    val direct = phone?.let { Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${it.filter(Char::isDigit)}?text=${Uri.encode(text)}")) }
    val picker = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.whatsapp")
    val anyApp = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
    listOfNotNull(direct, picker, anyApp).firstOrNull { launch(context, it) }
}

/**
 * Lets the user pick the UPI app each time: if one (often Google Pay) refuses a payment started from
 * another app, they can simply pick another. False when no UPI app is installed.
 */
fun openUpiApp(context: Context, upiUri: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(upiUri))
    // Needs the <queries> entry for the upi scheme in the app manifest (Android 11+ package visibility).
    if (context.packageManager.queryIntentActivities(view, 0).isEmpty()) return false
    return launch(context, Intent.createChooser(view, null))
}

fun openSmsApp(context: Context, phone: String?, text: String) {
    launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.orEmpty()}")).putExtra("sms_body", text))
}

private fun launch(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
