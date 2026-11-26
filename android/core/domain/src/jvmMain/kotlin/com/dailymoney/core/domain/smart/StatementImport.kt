package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.money.SaveTransactionUseCase
import com.dailymoney.core.domain.money.Transaction
import com.dailymoney.core.domain.money.TransactionSource
import com.dailymoney.core.domain.money.TransactionType
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Reads a statement file the user picked with the system file picker. */
interface StatementFileReader {
    /** The file's text, or null when it can't be opened or is too big. */
    suspend fun readText(uri: String): String?
}

enum class ImportStatus {
    NEW,
    /** The same line was imported into this account before. */
    ALREADY_IMPORTED,
    /** Looks like something already entered by hand (same amount and account within two days). */
    POSSIBLE_DUPLICATE,
}

data class ImportCandidate(
    val row: StatementRow,
    val hash: String,
    val status: ImportStatus,
    val categoryId: String?,
    /** Starts on for new lines only; the user can still tick a possible duplicate. */
    val selected: Boolean = status == ImportStatus.NEW,
)

object StatementImport {

    private const val DUPLICATE_DAYS = 2L

    /**
     * Stable per line: the same statement imported twice gives the same hashes, while two identical
     * coffees on one day stay two lines (the occurrence number tells them apart).
     */
    fun hashes(accountId: String, rows: List<StatementRow>): List<String> {
        val seen = HashMap<String, Int>()
        return rows.map { r ->
            val base = listOf(accountId, r.date, r.type, r.amountMinor, MerchantKey.words(r.description).joinToString(" ")).joinToString("|")
            val n = seen.merge(base, 1, Int::plus)!!
            sha256("$base|$n")
        }
    }

    fun candidates(
        accountId: String,
        rows: List<StatementRow>,
        alreadyImported: Set<String>,
        existing: List<Transaction>,
        categorizer: Categorizer,
        fallback: Map<CategoryKind, Category?>,
        zone: ZoneId,
    ): List<ImportCandidate> {
        val hashes = hashes(accountId, rows)
        // Each hand-entered transaction can explain at most one statement line.
        val manual = existing.filter { it.accountId == accountId && it.dedupeHash == null && it.type != TransactionType.TRANSFER }.toMutableList()
        return rows.mapIndexed { i, row ->
            val hash = hashes[i]
            val status = when {
                hash in alreadyImported -> ImportStatus.ALREADY_IMPORTED
                else -> manual.firstOrNull { t ->
                    t.type == row.type && t.amountMinor == row.amountMinor &&
                        abs(ChronoUnit.DAYS.between(t.occurredAt.atZone(zone).toLocalDate(), row.date)) <= DUPLICATE_DAYS
                }?.let { manual.remove(it); ImportStatus.POSSIBLE_DUPLICATE } ?: ImportStatus.NEW
            }
            val kind = if (row.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            val category = categorizer.suggest(row.description, row.type) ?: fallback[kind]
            ImportCandidate(row, hash, status, category?.id)
        }
    }

    /** Imported lines become ordinary personal transactions, at midday so time zones don't move the date. */
    fun toTransactions(accountId: String, currency: String, picked: List<ImportCandidate>, clock: Clock): List<Transaction> =
        picked.filter { it.status != ImportStatus.ALREADY_IMPORTED && it.categoryId != null }.map { c ->
            Transaction(
                id = Ids.newId(clock.millis()),
                type = c.row.type,
                amountMinor = c.row.amountMinor,
                currency = currency,
                categoryId = c.categoryId,
                accountId = accountId,
                toAccountId = null,
                occurredAt = c.row.date.atTime(LocalTime.NOON).atZone(clock.zone).toInstant(),
                note = c.row.description.take(SaveTransactionUseCase.NOTE_MAX).ifBlank { null },
                paymentMethod = null,
                source = TransactionSource.STATEMENT_IMPORT,
                dedupeHash = c.hash,
            )
        }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
