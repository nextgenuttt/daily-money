package com.dailymoney.core.designsystem.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dailymoney.core.designsystem.theme.LocalStatusColors

data class DonutSlice(val fraction: Float, val color: Color)

/**
 * Ring chart drawn on Canvas (no chart library, keeps the APK small). [description] is read by
 * TalkBack because the drawing itself carries no text.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    thickness: Dp = 22.dp,
    center: @Composable () -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            var start = -90f
            val gap = if (slices.size > 1) 1.5f else 0f
            slices.forEach { slice ->
                val sweep = slice.fraction * 360f
                if (sweep > gap) drawArc(slice.color, start, sweep - gap, false, topLeft, arcSize, style = Stroke(stroke))
                start += sweep
            }
        }
        center()
    }
}

data class BarGroup(val label: String, val income: Long, val expense: Long)

/** Paired income/expense bars per month with a small legend. */
@Composable
fun IncomeExpenseBars(
    groups: List<BarGroup>,
    incomeLabel: String,
    expenseLabel: String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
) {
    val incomeColor = LocalStatusColors.current.income
    val expenseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val max = groups.maxOfOrNull { maxOf(it.income, it.expense) }?.takeIf { it > 0 } ?: 1L
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val slot = size.width / groups.size.coerceAtLeast(1)
            val barWidth = (slot * 0.28f).coerceAtMost(18.dp.toPx())
            val radius = CornerRadius(4.dp.toPx())
            groups.forEachIndexed { i, g ->
                val centerX = slot * i + slot / 2
                val incomeH = size.height * (g.income.toFloat() / max)
                val expenseH = size.height * (g.expense.toFloat() / max)
                drawRoundRect(incomeColor, Offset(centerX - barWidth - 2f, size.height - incomeH), Size(barWidth, incomeH), radius)
                drawRoundRect(expenseColor, Offset(centerX + 2f, size.height - expenseH), Size(barWidth, expenseH), radius)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            groups.forEach {
                Text(
                    it.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(incomeColor, incomeLabel)
            LegendDot(expenseColor, expenseLabel)
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp).clip(CircleShape)) { drawCircle(color) }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
