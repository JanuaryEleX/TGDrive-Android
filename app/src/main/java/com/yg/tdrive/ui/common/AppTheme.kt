package com.yg.tgdrive.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.tgdrive.ServiceLocator

// ---------------------------------------------------------------- 品牌色
//
// 取「影院深色」基调：近黑蓝底 + 单一高饱和蓝主色。
// 参考 CloudStream / Aniyomi / Next Player 的做法——深色页面里只留一个强调色，
// 其余全部靠 surfaceContainer 分层，不用彩色铺满。

private val BrandBlue = Color(0xFF4EA1FF)
private val BrandBlueDim = Color(0xFF2C6FBF)

private val DarkColors = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFF04121E),
    primaryContainer = Color(0xFF14334D),
    onPrimaryContainer = Color(0xFFBEE0FA),
    secondary = Color(0xFF9DB4C9),
    onSecondary = Color(0xFF0E1B26),
    secondaryContainer = Color(0xFF1E2A36),
    onSecondaryContainer = Color(0xFFD3E2EF),
    tertiary = Color(0xFF6FD3C7),
    onTertiary = Color(0xFF04211D),

    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFE9EEF5),
    surface = Color(0xFF10151C),
    onSurface = Color(0xFFE9EEF5),
    surfaceVariant = Color(0xFF222B38),
    onSurfaceVariant = Color(0xFF94A4B6),
    surfaceContainerLowest = Color(0xFF07090D),
    surfaceContainerLow = Color(0xFF0F141B),
    surfaceContainer = Color(0xFF141A23),
    surfaceContainerHigh = Color(0xFF1B222D),
    surfaceContainerHighest = Color(0xFF222B38),
    outline = Color(0xFF3A4553),
    outlineVariant = Color(0xFF26303D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0505),
)

// 浅色仅作系统亮色兜底（本应用主推深色影音风）
private val LightColors = lightColorScheme(
    primary = BrandBlueDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FB),
    onPrimaryContainer = Color(0xFF06253C),
    secondary = Color(0xFF4C6072),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6EF),
    onSecondaryContainer = Color(0xFF0B1D29),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF12161B),
    surface = Color.White,
    onSurface = Color(0xFF12161B),
    surfaceVariant = Color(0xFFE6EBF1),
    onSurfaceVariant = Color(0xFF4A5663),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFEFF3F8),
    surfaceContainerHigh = Color(0xFFE9EEF5),
    surfaceContainerHighest = Color(0xFFE2E9F1),
    outline = Color(0xFFB6C0CB),
    outlineVariant = Color(0xFFD5DDE6),
    error = Color(0xFFC0392B),
    onError = Color.White,
)

// ---------------------------------------------------------------- 字阶
//
// 紧凑、偏小的标题 + 明显层级差，避免大标题把内容挤下去。

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 25.sp, lineHeight = 31.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 21.sp, lineHeight = 27.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontSize = 13.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 11.5.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 10.5.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 应用主题。默认深色影音风（不跟随系统亮色），保证海报与播放器的观感一致。
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/**
 * 运行时主题：读主题模式（system/light/dark）+ 系统明暗，动态切换。
 * 默认跟随系统；设置里可手动锁定深色 / 浅色。
 */
@Composable
fun TgTheme(content: @Composable () -> Unit) {
    val mode by ServiceLocator.settings.themeMode.collectAsState(initial = "system")
    val sysDark = isSystemInDarkTheme()
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> sysDark
    }
    AppTheme(darkTheme = dark, content = content)
}
