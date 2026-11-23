package com.dailymoney.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.money.AmountStat
import com.dailymoney.core.designsystem.money.BarGroup
import com.dailymoney.core.designsystem.money.CategoryBadge
import com.dailymoney.core.designsystem.money.DonutChart
import com.dailymoney.core.designsystem.money.DonutSlice
import com.dailymoney.core.designsystem.money.IncomeExpenseBars
import com.dailymoney.core.designsystem.money.MoneyText
import com.dailymoney.core.designsystem.money.MonthSelector
import com.dailymoney.core.designsystem.money.categoryColor
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.CategorySlice
import com.dailymoney.core.domain.money.MonthlyReport
import com.dailymoney.core.domain.money.ObserveMonthlyReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable data object ReportsRoute

fun NavGraphBuilder.reportsGraph() {
    composable<ReportsRoute> { ReportsScreen() }
}

data class ReportsUiState(val month: YearMonth, val isCurrentMonth: Boolean, val currency: String, val report: MonthlyReport? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val observeReport: ObserveMonthlyReportUseCase,
    config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    private val currency = config.defaultCurrency
    private val month = MutableStateFlow(YearMonth.now(clock))

    val state: StateFlow<ReportsUiState> = month.flatMapLatest { m ->
        observeReport(m).map { ReportsUiState(m, m == YearMonth.now(clock), currency, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState(month.value, true, currency))

    fun previous() = month.update { it.minusMonths(1) }
    fun next() = month.update { if (it < YearMonth.now(clock)) it.plusMonths(1) else it }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportsScreen(viewModel: ReportsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reports_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MonthSelector(
                label = state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                previousLabel = stringResource(R.string.reports_previous_month),
                nextLabel = stringResource(R.string.reports_next_month),
                nextEnabled = !state.isCurrentMonth,
            )
            val report = state.report
            if (report == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            TotalsCard(report, state.currency)
            if (report.expenseByCategory.isEmpty() && report.incomeByCategory.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.BarChart,
                    title = stringResource(R.string.reports_empty_title),
                    message = stringResource(R.string.reports_empty_body),
                )
            }
            if (report.expenseByCategory.isNotEmpty()) {
                BreakdownCard(stringResource(R.string.reports_spending_by_category), report.expenseByCategory, report.totals.expenseMinor, state.currency, showDonut = true)
            }
            if (report.incomeByCategory.isNotEmpty()) {
                BreakdownCard(stringResource(R.string.reports_income_by_category), report.incomeByCategory, report.totals.incomeMinor, state.currency, showDonut = false)
            }
            if (report.trend.any { it.incomeMinor > 0 || it.expenseMinor > 0 }) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(stringResource(R.string.reports_trend), style = MaterialTheme.typography.titleMedium)
                        IncomeExpenseBars(
                            groups = report.trend.map {
                                BarGroup(it.month.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())), it.incomeMinor, it.expenseMinor)
                            },
                            incomeLabel = stringResource(R.string.reports_income),
                            expenseLabel = stringResource(R.string.reports_expenses),
                            description = stringResource(R.string.reports_trend_description),
                            modifier = Modifier.padding(top = Spacing.md),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(report: MonthlyReport, currency: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AmountStat(stringResource(R.string.reports_income), report.totals.incomeMinor, currency, color = LocalStatusColors.current.income)
                AmountStat(stringResource(R.string.reports_expenses), report.totals.expenseMinor, currency)
                AmountStat(stringResource(R.string.reports_net), report.totals.netMinor, currency)
            }
            AmountStat(stringResource(R.string.reports_daily_average), report.dailyAverageMinor, currency, modifier = Modifier.padding(top = Spacing.sm))
        }
    }
}

@Composable
private fun BreakdownCard(title: String, slices: List<CategorySlice>, total: Long, currency: String, showDonut: Boolean) {
    val uncategorized = stringResource(R.string.reports_uncategorized)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (showDonut) {
                val summary = slices.joinToString { "${it.category?.name ?: uncategorized} ${(it.share * 100).roundToInt()}%" }
                DonutChart(
                    slices = slices.map { DonutSlice(it.share, categoryColor(it.category?.colorIndex ?: 5)) },
                    description = stringResource(R.string.reports_donut_description, summary),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { MoneyText(total, currency, style = MaterialTheme.typography.titleMedium) }
            }
            slices.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(slice.category?.iconKey, slice.category?.colorIndex ?: 5, size = 32.dp)
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
                        Text(slice.category?.name ?: uncategorized, style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(
                            progress = { slice.share },
                            color = categoryColor(slice.category?.colorIndex ?: 5),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(slice.amountMinor, currency, style = MaterialTheme.typography.bodyMedium)
                        Text("${(slice.share * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
