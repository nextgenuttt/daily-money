package com.dailymoney.core.designsystem.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dailymoney.core.designsystem.theme.CategoryPalette
import com.dailymoney.core.designsystem.theme.LocalStatusColors
import com.dailymoney.core.designsystem.theme.MoneyTextStyle
import com.dailymoney.core.domain.money.AccountType
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.TransactionType

fun categoryIcon(key: String?): ImageVector = when (key) {
    "food" -> Icons.Filled.Restaurant
    "groceries" -> Icons.Filled.LocalGroceryStore
    "transport" -> Icons.Filled.DirectionsBus
    "fuel" -> Icons.Filled.LocalGasStation
    "shopping" -> Icons.Filled.ShoppingBag
    "rent" -> Icons.Filled.Home
    "electricity" -> Icons.Filled.Bolt
    "internet" -> Icons.Filled.Wifi
    "mobile" -> Icons.Filled.PhoneAndroid
    "medical" -> Icons.Filled.LocalHospital
    "education" -> Icons.Filled.School
    "entertainment" -> Icons.Filled.Movie
    "travel" -> Icons.Filled.Flight
    "insurance" -> Icons.Filled.Shield
    "emi" -> Icons.Filled.CreditCard
    "family" -> Icons.Filled.FamilyRestroom
    "personal" -> Icons.Filled.Person
    "salary" -> Icons.Filled.Work
    "business" -> Icons.Filled.Storefront
    "interest" -> Icons.Filled.Savings
    "gift" -> Icons.Filled.CardGiftcard
    "refund" -> Icons.Filled.Replay
    "other_income" -> Icons.Filled.Payments
    "transfer" -> Icons.Filled.SwapHoriz
    else -> Icons.Filled.Category
}

fun accountIcon(type: AccountType): ImageVector = when (type) {
    AccountType.CASH -> Icons.Filled.Payments
    AccountType.BANK -> Icons.Filled.AccountBalance
    AccountType.UPI -> Icons.Filled.QrCode
    AccountType.CREDIT_CARD -> Icons.Filled.CreditCard
    AccountType.WALLET -> Icons.Filled.AccountBalanceWallet
}

fun categoryColor(index: Int): Color = CategoryPalette[Math.floorMod(index, CategoryPalette.size)]

/** Category icon in a tinted circle. */
@Composable
fun CategoryBadge(iconKey: String?, colorIndex: Int, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = if (iconKey == "transfer") MaterialTheme.colorScheme.onSurfaceVariant else categoryColor(colorIndex)
    Box(
        modifier = modifier.size(size).background(color.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(iconKey), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * Amount with tabular figures. Income is green with "+"; expenses stay neutral (doc 05 §A.3)
 * so the app doesn't feel alarming; transfers are muted.
 */
@Composable
fun MoneyText(
    minor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    type: TransactionType? = null,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color? = null,
) {
    val text = when (type) {
        TransactionType.INCOME -> MoneyFormatter.format(minor, currency, signed = true)
        TransactionType.EXPENSE -> MoneyFormatter.format(-minor, currency, signed = true)
        else -> MoneyFormatter.format(minor, currency)
    }
    val resolved = color ?: when (type) {
        TransactionType.INCOME -> LocalStatusColors.current.income
        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(text, modifier = modifier, style = style.merge(MoneyTextStyle), color = resolved, maxLines = 1)
}

@Composable
fun MonthSelector(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousLabel: String,
    nextLabel: String,
    modifier: Modifier = Modifier,
    nextEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = previousLabel)
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext, enabled = nextEnabled) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = nextLabel)
        }
    }
}

/** Label over a big amount, used in summary strips. */
@Composable
fun AmountStat(
    label: String,
    minor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(minor, currency, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
