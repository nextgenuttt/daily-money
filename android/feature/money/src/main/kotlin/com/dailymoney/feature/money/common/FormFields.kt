package com.dailymoney.feature.money.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dailymoney.core.domain.money.AmountError
import com.dailymoney.core.domain.money.Currencies
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.feature.money.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    currency: String,
    error: AmountError?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(sanitizeAmountInput(it, Currencies.exponent(currency))) },
        label = { Text(label) },
        prefix = { Text(Currencies.symbol(currency)) },
        isError = error != null,
        supportingText = error?.let { { Text(it.message()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun AmountError.message(): String = stringResource(
    when (this) {
        AmountError.REQUIRED -> R.string.edit_err_amount_required
        AmountError.INVALID -> R.string.edit_err_amount_invalid
    },
)

/** Button showing a date; opens the Material date picker. [onClear] adds a "Clear" action for optional dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onClear: (() -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  $label: ${date?.mediumLabel() ?: placeholder}")
    }
    if (!open) return
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                open = false
            }) { Text(stringResource(R.string.edit_ok)) }
        },
        dismissButton = {
            if (onClear != null) {
                TextButton(onClick = {
                    onClear()
                    open = false
                }) { Text(stringResource(R.string.clear)) }
            } else {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    ) { DatePicker(state) }
}

@Composable
fun ConfirmDeleteDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun Frequency.label(): String = stringResource(
    when (this) {
        Frequency.DAILY -> R.string.freq_daily
        Frequency.WEEKLY -> R.string.freq_weekly
        Frequency.MONTHLY -> R.string.freq_monthly
        Frequency.YEARLY -> R.string.freq_yearly
    },
)

@Composable
fun Frequency.everyLabel(): String = stringResource(
    when (this) {
        Frequency.DAILY -> R.string.every_day
        Frequency.WEEKLY -> R.string.every_week
        Frequency.MONTHLY -> R.string.every_month
        Frequency.YEARLY -> R.string.every_year
    },
)
