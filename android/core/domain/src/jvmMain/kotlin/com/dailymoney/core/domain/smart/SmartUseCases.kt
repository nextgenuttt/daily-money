package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.TransactionRepository
import com.dailymoney.core.domain.money.DateRange
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Builds a [Categorizer] from the user's rules and the last year of their own choices. */
class LoadCategorizerUseCase @Inject constructor(
    private val rules: CategoryRuleRepository,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Categorizer =
        Categorizer(rules.observe().first(), transactions.labelledNotes(clock.instant().minus(LEARN_WINDOW)), categories.observe().first())

    private companion object {
        val LEARN_WINDOW: Duration = Duration.ofDays(365)
    }
}

class ScanReceiptUseCase @Inject constructor(private val reader: ReceiptTextReader, private val clock: Clock) {
    /** Null when the photo couldn't be read at all. */
    suspend operator fun invoke(uri: String, currency: String): ReceiptScan? {
        val lines = reader.read(uri) ?: return null
        return ReceiptParser.parse(ReceiptParser.rows(lines), currency, LocalDate.now(clock))
    }
}

sealed interface StatementFile {
    data class Read(val rows: List<List<String>>, val result: StatementResult) : StatementFile
    data object Unreadable : StatementFile
}

class ReadStatementUseCase @Inject constructor(private val files: StatementFileReader) {
    suspend operator fun invoke(uri: String, currency: String): StatementFile {
        val text = files.readText(uri) ?: return StatementFile.Unreadable
        val rows = Csv.parse(text)
        return StatementFile.Read(rows, StatementParser.read(rows, currency))
    }
}

class PreviewImportUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val loadCategorizer: LoadCategorizerUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(accountId: String, rows: List<StatementRow>): List<ImportCandidate> {
        if (rows.isEmpty()) return emptyList()
        val zone = clock.zone
        val from = rows.minOf { it.date }.minusDays(3).atStartOfDay(zone).toInstant()
        val to = rows.maxOf { it.date }.plusDays(4).atStartOfDay(zone).toInstant()
        val existing = transactions.observeInRange(DateRange(from, to)).first()
        val imported = transactions.importedHashes(StatementImport.hashes(accountId, rows))
        val all = categories.observe().first()
        return StatementImport.candidates(accountId, rows, imported, existing, loadCategorizer(), fallbacks(all), zone)
    }

    private fun fallbacks(all: List<Category>) = mapOf(
        CategoryKind.EXPENSE to (all.firstOrNull { it.id == "sys_others" } ?: all.firstOrNull { it.kind == CategoryKind.EXPENSE }),
        CategoryKind.INCOME to (all.firstOrNull { it.id == "sys_other_income" } ?: all.firstOrNull { it.kind == CategoryKind.INCOME }),
    )
}

class ImportStatementUseCase @Inject constructor(private val transactions: TransactionRepository, private val clock: Clock) {
    /** Returns how many transactions were added. */
    suspend operator fun invoke(accountId: String, currency: String, candidates: List<ImportCandidate>): Int =
        transactions.importAll(StatementImport.toTransactions(accountId, currency, candidates.filter { it.selected }, clock))
}
