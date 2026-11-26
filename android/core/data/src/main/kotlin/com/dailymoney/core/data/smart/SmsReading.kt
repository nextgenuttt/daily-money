package com.dailymoney.core.data.smart

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dailymoney.core.data.R
import com.dailymoney.core.data.money.io
import com.dailymoney.core.data.money.stream
import com.dailymoney.core.data.reminders.AppLinks
import com.dailymoney.core.data.reminders.Channels
import com.dailymoney.core.data.reminders.canPostNotifications
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.PaymentSuggestionEntity
import com.dailymoney.core.database.entity.SyncStateEntity
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.core.domain.smart.MessageTransaction
import com.dailymoney.core.domain.smart.PaymentSuggestion
import com.dailymoney.core.domain.smart.PaymentSuggestionRepository
import com.dailymoney.core.domain.smart.SmsInbox
import com.dailymoney.core.domain.smart.SmsIntake
import com.dailymoney.core.domain.smart.SuggestionStatus
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class RoomPaymentSuggestionRepository @Inject constructor(private val db: DatabaseManager) : PaymentSuggestionRepository {

    override fun observePending(): Flow<List<PaymentSuggestion>> =
        db.stream { paymentSuggestionDao().observePending() }.map { rows -> rows.map { it.toDomain() } }

    override fun observePendingCount(): Flow<Int> = db.stream { paymentSuggestionDao().observePendingCount() }

    override suspend fun get(id: String): PaymentSuggestion? = io { db.get().paymentSuggestionDao().get(id)?.toDomain() }

    override suspend fun addAll(suggestions: List<PaymentSuggestion>): List<PaymentSuggestion> = io {
        if (suggestions.isEmpty()) return@io emptyList()
        val ids = db.get().paymentSuggestionDao().insertAll(suggestions.map { it.toEntity() })
        suggestions.filterIndexed { i, _ -> ids[i] != -1L }
    }

    override suspend fun setStatus(id: String, status: SuggestionStatus) = io { db.get().paymentSuggestionDao().setStatus(id, status.name) }

    override suspend fun dismissAll() = io { db.get().paymentSuggestionDao().dismissAll() }

    override fun observeEnabled(): Flow<Boolean> = db.stream { syncDao().observeState(KEY_ENABLED) }.map { it == "on" }

    override suspend fun isEnabled(): Boolean = io { db.get().syncDao().state(KEY_ENABLED) == "on" }

    override suspend fun setEnabled(enabled: Boolean) = io {
        db.get().syncDao().setState(SyncStateEntity(KEY_ENABLED, if (enabled) "on" else "off"))
        // Turning it off clears the waiting list; what was already added stays.
        if (!enabled) db.get().paymentSuggestionDao().dismissAll()
    }

    private companion object {
        const val KEY_ENABLED = "sms_reader"
    }
}

/** Reads bank SMS on this phone. Only the "smsReader" build declares the permission; elsewhere this does nothing. */
@Singleton
class AndroidSmsInbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: DatabaseManager,
    private val config: AppConfig,
    private val suggestions: PaymentSuggestionRepository,
    private val clock: Clock,
) : SmsInbox {

    override val supported: Boolean get() = config.smsReaderBuild

    override fun hasPermission(): Boolean = supported && granted(context, Manifest.permission.READ_SMS) && granted(context, Manifest.permission.RECEIVE_SMS)

    override suspend fun scan(days: Int): Int = io {
        if (!supported || !granted(context, Manifest.permission.READ_SMS)) return@io 0
        val since = clock.instant().minus(Duration.ofDays(days.toLong())).toEpochMilli()
        val found = mutableListOf<PaymentSuggestion>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $MAX_SCAN",
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0)
                // People's messages are skipped before the text is even read.
                if (!SmsIntake.isBankSender(sender)) continue
                val body = c.getString(1) ?: continue
                SmsIntake.suggestion(sender, body, Instant.ofEpochMilli(c.getLong(2)), config.defaultCurrency, clock.zone)?.let(found::add)
            }
        }
        suggestions.addAll(found).size
    }

    override suspend fun refreshIfRulesChanged() = io {
        if (!hasPermission() || !suggestions.isEnabled()) return@io
        val dao = db.get().syncDao()
        if (dao.state(KEY_RULES)?.toIntOrNull() == RULES_VERSION) return@io
        db.get().paymentSuggestionDao().deletePending()
        scan(LOOK_BACK_DAYS)
        dao.setState(SyncStateEntity(KEY_RULES, RULES_VERSION.toString()))
    }

    /**
     * Debug builds only: a sample bank SMS goes through the same steps as a real one (parse → store →
     * notify), since a real bank SMS can't be faked on a physical phone.
     */
    override suspend fun simulateIncoming(): Boolean = io {
        val now = clock.instant()
        val day = now.atZone(clock.zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yy"))
        val body = "Rs.149.00 debited from A/c XX1234 on $day to VPA swiggy@icici (UPI Ref ${now.epochSecond}). Not you? Call 1800-000-0000"
        val found = SmsIntake.suggestion(SAMPLE_SENDER, body, now, config.defaultCurrency, clock.zone) ?: return@io false
        suggestions.addAll(listOf(found)).forEach { FoundPaymentNotifier.show(context, it, config.defaultCurrency) }
        true
    }

    private companion object {
        const val MAX_SCAN = 2000
        const val SAMPLE_SENDER = "AX-TESTBK"
        const val LOOK_BACK_DAYS = 30
        const val KEY_RULES = "sms_rules_version"

        /** Bump when BankSmsParser/SmsIntake rules change, so old wrong suggestions are re-checked. */
        const val RULES_VERSION = 2
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmsReceiverDeps {
    fun suggestions(): PaymentSuggestionRepository
    fun config(): AppConfig
    fun clock(): Clock
    fun auth(): FirebaseAuth
}

/**
 * New SMS (registered only in the smsReader build's manifest, guarded by BROADCAST_SMS). Does nothing
 * unless the user switched the feature on, is signed in, and the sender is a bank ID.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long message arrives in parts; join them per sender.
        val bySender = messages.filter { SmsIntake.isBankSender(it.originatingAddress) }
            .groupBy { it.originatingAddress!! }
            .mapValues { (_, parts) -> parts.joinToString("") { it.messageBody.orEmpty() } to Instant.ofEpochMilli(parts.first().timestampMillis) }
        if (bySender.isEmpty()) return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, SmsReceiverDeps::class.java)
        val pending = goAsync()
        scope.launch {
            try {
                if (deps.auth().currentUser == null || !deps.suggestions().isEnabled()) return@launch
                val config = deps.config()
                val found = bySender.mapNotNull { (sender, pair) ->
                    SmsIntake.suggestion(sender, pair.first, pair.second, config.defaultCurrency, deps.clock().zone)
                }
                deps.suggestions().addAll(found).forEach { FoundPaymentNotifier.show(context, it, config.defaultCurrency) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** "Payment found in SMS · tap to add", for a real SMS and for the debug test alike. */
internal object FoundPaymentNotifier {

    fun show(context: Context, s: PaymentSuggestion, currency: String) {
        if (!canPostNotifications(context)) return
        Channels.ensure(context)
        val notification = NotificationCompat.Builder(context, Channels.FOUND_PAYMENTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.sms_found_title))
            .setContentText(describe(context, s.payment, currency))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, Channels.FOUND_PAYMENTS)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(context.getString(R.string.sms_found_title))
                    .build(),
            )
            .setContentIntent(AppLinks.open(context, AppLinks.OPEN_FOUND_PAYMENTS, requestKey = s.id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(s.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notifications were turned off; the payment still waits on Home.
        }
    }

    private fun describe(context: Context, p: MessageTransaction, currency: String): String {
        val amount = MoneyFormatter.format(p.amountMinor, currency)
        val who = p.counterparty
        return when {
            p.type == TransactionType.INCOME && who != null -> context.getString(R.string.sms_found_in_from, amount, who)
            p.type == TransactionType.INCOME -> context.getString(R.string.sms_found_in, amount)
            who != null -> context.getString(R.string.sms_found_out_to, amount, who)
            else -> context.getString(R.string.sms_found_out, amount)
        }
    }
}

private fun PaymentSuggestionEntity.toDomain() = PaymentSuggestion(
    id = id,
    sender = sender,
    payment = MessageTransaction(
        type = if (type == "CREDIT") TransactionType.INCOME else TransactionType.EXPENSE,
        amountMinor = amountMinor,
        counterparty = counterparty,
        date = LocalDate.ofEpochDay(epochDay),
        accountHint = accountHint,
    ),
    receivedAt = Instant.ofEpochMilli(receivedAt),
    status = runCatching { SuggestionStatus.valueOf(status) }.getOrDefault(SuggestionStatus.NEW),
)

private fun PaymentSuggestion.toEntity() = PaymentSuggestionEntity(
    id = id,
    sender = sender,
    type = if (payment.type == TransactionType.INCOME) "CREDIT" else "DEBIT",
    amountMinor = payment.amountMinor,
    currency = "INR",
    counterparty = payment.counterparty,
    epochDay = (payment.date ?: receivedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()).toEpochDay(),
    accountHint = payment.accountHint,
    receivedAt = receivedAt.toEpochMilli(),
    status = status.name,
)
