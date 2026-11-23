package com.dailymoney.feature.reminders.common

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.reminders.Occurrence
import com.dailymoney.core.domain.reminders.OccurrenceStatus
import com.dailymoney.core.domain.reminders.ReminderGroup
import com.dailymoney.core.domain.reminders.ReminderKind
import com.dailymoney.feature.reminders.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

@Composable
fun ReminderKind.label(): String = stringResource(
    when (this) {
        ReminderKind.BILL -> R.string.kind_bill
        ReminderKind.EMI -> R.string.kind_emi
        ReminderKind.CREDIT_CARD -> R.string.kind_credit_card
        ReminderKind.INSURANCE -> R.string.kind_insurance
        ReminderKind.RECHARGE -> R.string.kind_recharge
        ReminderKind.COLLECTION -> R.string.kind_collection
        ReminderKind.BIRTHDAY -> R.string.kind_birthday
        ReminderKind.ANNIVERSARY -> R.string.kind_anniversary
        ReminderKind.FESTIVAL -> R.string.kind_festival
        ReminderKind.APPOINTMENT -> R.string.kind_appointment
        ReminderKind.MEETING -> R.string.kind_meeting
        ReminderKind.TRAVEL -> R.string.kind_travel
        ReminderKind.EXAM -> R.string.kind_exam
        ReminderKind.CUSTOM -> R.string.kind_custom
    },
)

/** Spec §7 contextual question: "Have you paid?", "Have you wished them?"… */
@Composable
fun ReminderKind.question(): String = stringResource(
    when {
        group == ReminderGroup.PAYMENT -> R.string.q_paid
        group == ReminderGroup.COLLECTION -> R.string.q_received
        group == ReminderGroup.CELEBRATION -> R.string.q_wished
        group == ReminderGroup.APPOINTMENT -> R.string.q_attended
        this == ReminderKind.MEETING -> R.string.q_meeting
        this == ReminderKind.TRAVEL -> R.string.q_travel
        else -> R.string.q_done
    },
)

@Composable
fun ReminderKind.completeAction(): String = stringResource(
    when {
        group == ReminderGroup.PAYMENT -> R.string.a_paid
        group == ReminderGroup.COLLECTION -> R.string.a_received
        group == ReminderGroup.CELEBRATION -> R.string.a_wished
        group == ReminderGroup.APPOINTMENT -> R.string.a_attended
        this == ReminderKind.TRAVEL -> R.string.a_checked_in
        else -> R.string.a_done
    },
)

/** "Today at 6:30 PM", "Tomorrow", "In 3 days", "2 days ago", or a date further out. */
@Composable
fun Occurrence.whenText(today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    return when {
        days == 0 && !isAllDay -> stringResource(R.string.rem_today_at, at.toLocalTime().shortLabel())
        days == 0 -> stringResource(R.string.rem_due_today)
        days in 1..7 -> pluralStringResource(R.plurals.rem_in_days, days, days)
        days in -30..-1 -> pluralStringResource(R.plurals.rem_days_ago, -days, -days)
        else -> date.mediumLabel() + if (isAllDay) "" else " · ${at.toLocalTime().shortLabel()}"
    }
}

@Composable
fun Occurrence.statusColor() = when (status) {
    OccurrenceStatus.OVERDUE -> LocalStatusColors.current.overdue
    OccurrenceStatus.DUE_TODAY -> LocalStatusColors.current.upcoming
    OccurrenceStatus.DONE -> LocalStatusColors.current.paid
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun LocalDate.mediumLabel(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
fun LocalTime.shortLabel(): String = format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.rem_back))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.rem_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rem_cancel)) } },
    )
}

/**
 * Notification and exact-alarm permission banners. Asks for notification permission once on first
 * show (Android 13+); afterwards a banner offers to turn it on.
 */
@Composable
fun PermissionBanners() {
    val context = LocalContext.current
    var notificationsGranted by remember { mutableStateOf(notificationsAllowed(context)) }
    var exactAllowed by remember { mutableStateOf(exactAlarmsAllowed(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsGranted = notificationsAllowed(context)
        exactAllowed = exactAlarmsAllowed(context)
    }
    LaunchedEffect(Unit) {
        if (!notificationsGranted && !asked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!notificationsGranted) {
        Banner(Icons.Filled.NotificationsOff, stringResource(R.string.rem_perm_notifications), stringResource(R.string.rem_perm_turn_on)) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    } else if (!exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Banner(Icons.Filled.Schedule, stringResource(R.string.rem_perm_exact), stringResource(R.string.rem_perm_allow)) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun notificationsAllowed(context: android.content.Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun exactAlarmsAllowed(context: android.content.Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
