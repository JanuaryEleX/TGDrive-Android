package com.yg.tgdrive.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---- Telegram 风格品牌色板 ----

private val TgBlue = Color(0xFF2AABEE)
private val TgBlueDark = Color(0xFF229ED9)
private val TgBlueLight = Color(0xFF6EC9F5)

private val LightColors = lightColorScheme(
    primary = TgBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBFF),
    onPrimaryContainer = Color(0xFF00344F),
    secondary = Color(0xFF4E626E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E7F2),
    onSecondaryContainer = Color(0xFF0A1E28),
    tertiary = Color(0xFF00696E),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF41484D),
    surfaceContainer = Color(0xFFF0F3F6),
    surfaceContainerHigh = Color(0xFFEAEFF3),
    outlineVariant = Color(0xFFD5DDE4),
)

private val DarkColors = darkColorScheme(
    primary = TgBlue,
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B6D),
    onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFFB0C8D4),
    onSecondary = Color(0xFF1F333D),
    secondaryContainer = Color(0xFF354B57),
    onSecondaryContainer = Color(0xFFD1E7F2),
    tertiary = Color(0xFF5ADAE1),
    background = Color(0xFF0E1621),        // Telegram 深色底
    onBackground = Color(0xFFF1F4F7),
    surface = Color(0xFF17212B),           // Telegram 深色卡片
    onSurface = Color(0xFFF1F4F7),
    surfaceVariant = Color(0xFF232E3C),
    onSurfaceVariant = Color(0xFFA8B8C4),
    surfaceContainer = Color(0xFF1C2733),
    surfaceContainerHigh = Color(0xFF232E3C),
    outlineVariant = Color(0xFF2B3A4A),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
