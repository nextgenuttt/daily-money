package com.dailymoney.core.designsystem.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dailymoney.core.designsystem.theme.CategoryPalette
import com.dailymoney.core.domain.reminders.ReminderGroup
import com.dailymoney.core.domain.reminders.ReminderKind

fun reminderIcon(kind: ReminderKind): ImageVector = when (kind) {
    ReminderKind.BILL -> Icons.Filled.ReceiptLong
    ReminderKind.EMI -> Icons.Filled.CalendarMonth
    ReminderKind.CREDIT_CARD -> Icons.Filled.CreditCard
    ReminderKind.INSURANCE -> Icons.Filled.Shield
    ReminderKind.RECHARGE -> Icons.Filled.PhoneAndroid
    ReminderKind.COLLECTION -> Icons.Filled.Payments
    ReminderKind.BIRTHDAY -> Icons.Filled.Cake
    ReminderKind.ANNIVERSARY -> Icons.Filled.Favorite
    ReminderKind.FESTIVAL -> Icons.Filled.Celebration
    ReminderKind.APPOINTMENT -> Icons.Filled.MedicalServices
    ReminderKind.MEETING -> Icons.Filled.Groups
    ReminderKind.TRAVEL -> Icons.Filled.Flight
    ReminderKind.EXAM -> Icons.Filled.School
    ReminderKind.CUSTOM -> Icons.Filled.NotificationsActive
}

/** One colour per group, from the shared category palette, so calendar dots match list badges. */
fun reminderColor(group: ReminderGroup): Color = when (group) {
    ReminderGroup.PAYMENT -> CategoryPalette[0]
    ReminderGroup.COLLECTION -> CategoryPalette[3]
    ReminderGroup.CELEBRATION -> CategoryPalette[1]
    ReminderGroup.APPOINTMENT -> CategoryPalette[2]
    ReminderGroup.EVENT -> CategoryPalette[4]
    ReminderGroup.TASK -> CategoryPalette[5]
}

@Composable
fun ReminderBadge(kind: ReminderKind, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = reminderColor(kind.group)
    Box(modifier.size(size).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(reminderIcon(kind), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}
