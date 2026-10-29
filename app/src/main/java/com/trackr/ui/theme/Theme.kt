package com.trackr.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 公交语义色彩体系（精心调校的在途、即将到站、已过站、站点轨道等语义色）
 */
@Immutable
data class BusSemanticColors(
    val busRunningGreen: Color,
    val busArrivingAmber: Color,
    val busPassedGray: Color,
    val routeTrackActive: Color,
    val routeTrackInactive: Color,
    val conditionStopBorder: Color,
    val busChipContainer: Color,
    val busChipContent: Color,
    val stationCardContainer: Color,
    val alertAccent: Color
)

val LightBusSemanticColors = BusSemanticColors(
    busRunningGreen = Color(0xFF15803D),      // 翡翠绿 700
    busArrivingAmber = Color(0xFFD97706),     // 活力橙 600
    busPassedGray = Color(0xFF94A3B8),        // 静音灰 400
    routeTrackActive = Color(0xFF16A34A),     // 活跃轨道绿 600
    routeTrackInactive = Color(0xFFCBD5E1),   // 未行经轨道浅灰 300
    conditionStopBorder = Color(0xFFE11D48),  // 条件启用停靠站描边红
    busChipContainer = Color(0xFFDCFCE7),     // 公交徽标浅绿背景
    busChipContent = Color(0xFF14532D),       // 公交徽标深绿文本
    stationCardContainer = Color(0xFFF1F5F9), // 站点到站卡柔和底色
    alertAccent = Color(0xFFEA580C)           // 到站预警高亮橙红
)

val DarkBusSemanticColors = BusSemanticColors(
    busRunningGreen = Color(0xFF22C55E),      // 翡翠绿 500
    busArrivingAmber = Color(0xFFFBBF24),     // 活力橙 400
    busPassedGray = Color(0xFF64748B),        // 静音灰 500
    routeTrackActive = Color(0xFF22C55E),     // 活跃轨道绿 500
    routeTrackInactive = Color(0xFF334155),   // 未行经轨道深灰 700
    conditionStopBorder = Color(0xFFFB7185),  // 条件启用停靠站描边浅红
    busChipContainer = Color(0xFF14532D),     // 公交徽标深绿背景
    busChipContent = Color(0xFF86EFAC),       // 公交徽标荧光绿文本
    stationCardContainer = Color(0xFF1E293B), // 站点到站卡深邃底色
    alertAccent = Color(0xFFF97316)           // 到站预警高亮橙
)

val LocalBusSemanticColors = staticCompositionLocalOf { LightBusSemanticColors }

/**
 * 快捷获取当前主题的公交语义色彩
 */
val MaterialTheme.busColors: BusSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBusSemanticColors.current

// Material 3 基础基础调色板
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF89F8C7),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D8),
    onSecondaryContainer = Color(0xFF0A1F16),
    tertiary = Color(0xFF3D6373),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC1E8FB),
    onTertiaryContainer = Color(0xFF001F29),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF404944),
    outline = Color(0xFF707973)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6CDBAC),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFF89F8C7),
    secondary = Color(0xFFB4CCBC),
    onSecondary = Color(0xFF1F352A),
    secondaryContainer = Color(0xFF364B40),
    onSecondaryContainer = Color(0xFFCFE9D8),
    tertiary = Color(0xFFA5CCDE),
    onTertiary = Color(0xFF073543),
    tertiaryContainer = Color(0xFF244C5B),
    onTertiaryContainer = Color(0xFFC1E8FB),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF8A938D)
)

/**
 * 通用 M3E 弹性物理动画规格辅助函数
 */
fun <T> expressiveSpringSpec(
    dampingRatio: Float = Spring.DampingRatioMediumBouncy,
    stiffness: Float = Spring.StiffnessMediumLow
): SpringSpec<T> = spring(
    dampingRatio = dampingRatio,
    stiffness = stiffness
)

/**
 * 公交车徽标两站之间平滑物理推进动画规格
 */
fun <T> busChipSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * 换向 180° 旋转弹簧动画规格
 */
fun <T> expressiveRotateSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * Material Design 3 Expressive (M3E) 应用主题
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrackrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val busSemanticColors = if (darkTheme) DarkBusSemanticColors else LightBusSemanticColors

    CompositionLocalProvider(
        LocalBusSemanticColors provides busSemanticColors
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
