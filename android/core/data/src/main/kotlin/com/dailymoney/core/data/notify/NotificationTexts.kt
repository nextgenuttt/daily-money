package com.dailymoney.core.data.notify

import android.content.Context
import com.dailymoney.core.data.R
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.sharing.AppNotification
import com.dailymoney.core.domain.sharing.NotificationType

/** Title and body built on the phone from the type and placeholders; the push itself carries nothing. */
object NotificationTexts {

    data class Text(val title: String, val body: String)

    fun of(context: Context, n: AppNotification): Text {
        val p = n.params
        val actor = p["actor"].orEmpty()
        val group = p["group"].orEmpty()
        val currency = p["currency"] ?: "INR"
        fun money(key: String) = p[key]?.toLongOrNull()?.let { MoneyFormatter.format(it, currency) }.orEmpty()
        val what = p["category"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notif_an_expense)
        return when (n.type) {
            NotificationType.APPROVAL_REQUESTED ->
                Text(context.getString(R.string.notif_approval_requested_title, group), context.getString(R.string.notif_approval_requested_body, actor, what, money("amountMinor")))
            NotificationType.EXPENSE_APPROVED ->
                Text(context.getString(R.string.notif_approved_title), context.getString(R.string.notif_approved_body, what, money("amountMinor"), p["approver"].orEmpty()))
            NotificationType.EXPENSE_REJECTED ->
                Text(
                    context.getString(R.string.notif_rejected_title),
                    context.getString(R.string.notif_rejected_body, what, money("amountMinor"), p["reason"].orEmpty()),
                )
            NotificationType.EXPENSE_ADDED ->
                Text(context.getString(R.string.notif_added_title, group), context.getString(R.string.notif_added_body, actor, what, money("amountMinor")))
            NotificationType.SPLIT_SHARE ->
                Text(context.getString(R.string.notif_share_title, group), context.getString(R.string.notif_share_body, what, money("shareMinor"), actor))
            NotificationType.SETTLEMENT_REQUESTED ->
                Text(context.getString(R.string.notif_settle_requested_title), context.getString(R.string.notif_settle_requested_body, actor, money("amountMinor")))
            NotificationType.SETTLEMENT_RECORDED ->
                Text(context.getString(R.string.notif_settle_recorded_title), context.getString(R.string.notif_settle_recorded_body, actor, money("amountMinor")))
            NotificationType.SETTLEMENT_CONFIRMED ->
                Text(context.getString(R.string.notif_settle_confirmed_title), context.getString(R.string.notif_settle_confirmed_body, actor, money("amountMinor")))
            NotificationType.SETTLEMENT_CANCELLED ->
                Text(context.getString(R.string.notif_settle_cancelled_title), context.getString(R.string.notif_settle_cancelled_body, actor, money("amountMinor")))
            NotificationType.PAYMENT_REMINDER ->
                Text(context.getString(R.string.notif_pay_reminder_title, actor), context.getString(R.string.notif_pay_reminder_body, actor, money("amountMinor")))
            NotificationType.MESSAGE ->
                Text(context.getString(R.string.notif_message_title, actor, what), p["preview"].orEmpty())
        }
    }
}
