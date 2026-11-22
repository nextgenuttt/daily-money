package com.dailymoney.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Tokens from docs/05-DESIGN-AND-STORAGE.md §A.3
internal val Green40 = Color(0xFF1E4D3B)
internal val Green80 = Color(0xFF8CD3B0)
internal val Green20 = Color(0xFF00382A)
internal val Green90 = Color(0xFFCDEBDC)
internal val Green30 = Color(0xFF0F3D2D)

internal val LightBackground = Color(0xFFF5F7F6)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightSurfaceVariant = Color(0xFFE8EEEB)
internal val LightOutline = Color(0xFFC3CCC8)
internal val LightTextPrimary = Color(0xFF16201C)
internal val LightTextSecondary = Color(0xFF5A6661)

internal val DarkBackground = Color(0xFF0C0F0E)
internal val DarkSurface = Color(0xFF121614)
internal val DarkSurfaceVariant = Color(0xFF1E2522)
internal val DarkOutline = Color(0xFF3B4541)
internal val DarkTextPrimary = Color(0xFFE6ECE9)
internal val DarkTextSecondary = Color(0xFFA3B0AA)

internal val ErrorLight = Color(0xFFC62828)
internal val ErrorDark = Color(0xFFFF8A80)

/** Meaning-based colours that Material's scheme has no slot for. Always paired with text + icon. */
@Immutable
data class StatusColors(
    val overdue: Color,
    val upcoming: Color,
    val paid: Color,
    val income: Color,
)

internal val LightStatus = StatusColors(
    overdue = Color(0xFFC62828),
    upcoming = Color(0xFFB26A00),
    paid = Color(0xFF2E7D32),
    income = Color(0xFF2E7D32),
)

internal val DarkStatus = StatusColors(
    overdue = Color(0xFFFF8A80),
    upcoming = Color(0xFFFFCC80),
    paid = Color(0xFFA5D6A7),
    income = Color(0xFFA5D6A7),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Category palette for calendar dots, chart slices and chips. */
val CategoryPalette = listOf(
    Color(0xFF3B6FD8),
    Color(0xFFD6336C),
    Color(0xFFE0A100),
    Color(0xFF14998A),
    Color(0xFF6B4FBB),
    Color(0xFF5F6B7A),
)
