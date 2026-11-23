package com.dailymoney.feature.money.planning

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.money.BuildExportUseCase
import com.dailymoney.core.domain.money.ExportFormat
import com.dailymoney.core.domain.money.ExportPeriod
import com.dailymoney.core.domain.money.ExportService
import com.dailymoney.feature.money.R
import com.dailymoney.feature.money.common.BackButton
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ExportOutcome {
    data class Saved(val count: Int) : ExportOutcome
    data object Failed : ExportOutcome
}

data class ExportUiState(
    val period: ExportPeriod = ExportPeriod.THIS_MONTH,
    val format: ExportFormat = ExportFormat.CSV,
    val working: Boolean = false,
    val outcome: ExportOutcome? = null,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val buildExport: BuildExportUseCase,
    private val exportService: ExportService,
    private val config: AppConfig,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun setPeriod(p: ExportPeriod) = _state.update { it.copy(period = p, outcome = null) }
    fun setFormat(f: ExportFormat) = _state.update { it.copy(format = f, outcome = null) }

    fun suggestedFileName(): String {
        val ext = if (_state.value.format == ExportFormat.CSV) "csv" else "pdf"
        return "daily-money-${_state.value.period.name.lowercase().replace('_', '-')}-${LocalDate.now(clock)}.$ext"
    }

    fun write(uri: String) {
        val s = _state.value
        _state.update { it.copy(working = true, outcome = null) }
        viewModelScope.launch {
            val data = buildExport(s.period, config.defaultCurrency)
            val ok = exportService.write(uri, s.format, data)
            _state.update { it.copy(working = false, outcome = if (ok) ExportOutcome.Saved(data.rows.size) else ExportOutcome.Failed) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ExportScreen(onBack: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The system "Save as" screen: the user picks where the file goes; no storage permission needed.
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { viewModel.write(it.toString()) } }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let { viewModel.write(it.toString()) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.export_period), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ExportPeriod.entries.forEach { p ->
                    FilterChip(state.period == p, { viewModel.setPeriod(p) }, {
                        Text(
                            stringResource(
                                when (p) {
                                    ExportPeriod.THIS_MONTH -> R.string.export_this_month
                                    ExportPeriod.LAST_MONTH -> R.string.export_last_month
                                    ExportPeriod.THIS_YEAR -> R.string.export_this_year
                                    ExportPeriod.LAST_12_MONTHS -> R.string.export_last_12
                                    ExportPeriod.ALL_TIME -> R.string.export_all_time
                                },
                            ),
                        )
                    })
                }
            }
            Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(state.format == ExportFormat.CSV, { viewModel.setFormat(ExportFormat.CSV) }, { Text(stringResource(R.string.export_csv)) })
                FilterChip(state.format == ExportFormat.PDF, { viewModel.setFormat(ExportFormat.PDF) }, { Text(stringResource(R.string.export_pdf)) })
            }
            Text(
                stringResource(if (state.format == ExportFormat.CSV) R.string.export_csv_hint else R.string.export_pdf_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(
                text = stringResource(R.string.export_button),
                onClick = {
                    val name = viewModel.suggestedFileName()
                    if (state.format == ExportFormat.CSV) saveCsv.launch(name) else savePdf.launch(name)
                },
                loading = state.working,
            )
            when (val outcome = state.outcome) {
                is ExportOutcome.Saved -> Text(
                    pluralStringResource(R.plurals.export_saved, outcome.count, outcome.count),
                    color = LocalStatusColors.current.paid,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ExportOutcome.Failed -> ErrorBanner(stringResource(R.string.export_failed))
                null -> Unit
            }
        }
    }
}
