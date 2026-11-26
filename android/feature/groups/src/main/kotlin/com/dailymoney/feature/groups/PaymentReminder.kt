package com.dailymoney.feature.groups

import com.dailymoney.core.domain.sharing.UpiPayment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dailymoney.core.designsystem.component.DirectSms
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.InfoBanner
import com.dailymoney.core.designsystem.component.ReminderChannels
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.sharing.GroupPerson
import com.dailymoney.core.domain.sharing.ReminderText
import com.dailymoney.core.domain.sharing.SettleResult
import com.dailymoney.core.domain.sharing.SmsSendResult

/** The reminder being prepared for one member who owes me. */
data class RemindUi(
    val person: GroupPerson,
    val amountMinor: Long,
    val sending: Boolean = false,
    val result: SettleResult? = null,
    val smsSending: Boolean = false,
    val smsResult: SmsSendResult? = null,
)

@Composable
internal fun RemindDialog(
    remind: RemindUi,
    groupName: String,
    currency: String,
    myUpiId: String?,
    myName: String?,
    payPageUrl: String,
    canSendSms: Boolean,
    onSendInApp: () -> Unit,
    onSendSms: (String) -> Unit,
    onClose: () -> Unit,
) {
    val person = remind.person
    val amount = MoneyFormatter.format(remind.amountMinor, currency)
    val payLink = myUpiId?.takeIf { UpiPayment.isValidId(it) && payPageUrl.isNotBlank() }
        ?.let { UpiPayment.webLink(payPageUrl, it, myName, remind.amountMinor, groupName) }
    val message = ReminderText.build(person.name, amount, "for \"$groupName\"", myUpiId, payLink)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.remind_title, person.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.remind_body, person.name, amount, groupName))
                when (val r = remind.result) {
                    SettleResult.Ok -> InfoBanner(stringResource(R.string.remind_sent, person.name))
                    is SettleResult.Refused -> ErrorBanner(
                        stringResource(if (r.code == "ALREADY_REMINDED") R.string.remind_already else R.string.remind_failed),
                    )
                    SettleResult.Offline -> ErrorBanner(stringResource(R.string.remind_offline))
                    else -> Unit
                }
                Button(onClick = onSendInApp, enabled = !remind.sending && remind.result != SettleResult.Ok, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remind_in_app))
                }
                ReminderChannels(
                    phone = person.phone,
                    message = message,
                    directSms = if (canSendSms) DirectSms(remind.smsResult, remind.smsSending) { onSendSms(message) } else null,
                )
                Text(
                    stringResource(if (person.phone != null) R.string.remind_phone_known else R.string.remind_phone_unknown, person.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.remind_close)) } },
    )
}
