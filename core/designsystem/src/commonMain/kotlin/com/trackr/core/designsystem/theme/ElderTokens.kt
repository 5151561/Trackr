package com.trackr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 长辈版 Token（重构计划 §8.3，设计稿 docs/design/redesign_v3_elder.html）。
 * 语义名和标准版一样，组件不需要知道模式：
 * - 配色：黑字白底 / 白字黑底，正文对背景 ≥ 7:1；品牌蓝、go、warn 加深（深色里提亮）；
 * - 字号：eta 56sp，item 22sp，正文 20sp，最小 18sp（都用 sp，系统字体放大再叠加）；
 * - 触控：最小 64dp（LocalMinimumInteractiveComponentSize 跟着变，M3 组件自动放大点击区）；
 * - 动效：MotionScheme.standard()，少弹跳。
 */

internal val ElderLightTrackrColors = TrackrColors(
    go = Color(0xFF0A6B39),            // --go
    goContainer = Color(0xFFD2F2DF),   // --go-c
    warn = Color(0xFF8A4500),          // --warn
    warnContainer = Color(0xFFFFE2BF), // --warn-c
    walk = Color(0xFF4A515B),          // 比标准版的灰深，白底上 ≥ 7:1
)

internal val ElderDarkTrackrColors = TrackrColors(
    go = Color(0xFF6BE3A0),
    goContainer = Color(0xFF0E3A22),
    warn = Color(0xFFFFC266),
    warnContainer = Color(0xFF3D2A00),
    walk = Color(0xFFB8C0CA),
)

internal val ElderLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B47B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2FF),
    onPrimaryContainer = Color(0xFF001A4D),
    inversePrimary = Color(0xFFA8C4FF),
    secondary = Color(0xFF0B47B8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E2FF),
    onSecondaryContainer = Color(0xFF001A4D),
    tertiary = Color(0xFF0B47B8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6E2FF),
    onTertiaryContainer = Color(0xFF001A4D),
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE6E9EE),
    onSurfaceVariant = Color(0xFF33383F),
    surfaceTint = Color(0xFF0B47B8),
    inverseSurface = Color(0xFF1C2027),
    inverseOnSurface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE3E1),
    onErrorContainer = Color(0xFF5C0F0B),
    outline = Color(0xFF8A929D),
    outlineVariant = Color(0xFFC4CAD2),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4E7EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F3F5),
    surfaceContainerHighest = Color(0xFFE6E9EE),
)

internal val ElderDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA8C4FF),
    onPrimary = Color(0xFF001A4D),
    primaryContainer = Color(0xFF173A80),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF0B47B8),
    secondary = Color(0xFFA8C4FF),
    onSecondary = Color(0xFF001A4D),
    secondaryContainer = Color(0xFF173A80),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFA8C4FF),
    onTertiary = Color(0xFF001A4D),
    tertiaryContainer = Color(0xFF173A80),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF14171C),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF252A32),
    onSurfaceVariant = Color(0xFFD5DAE1),
    surfaceTint = Color(0xFFA8C4FF),
    inverseSurface = Color(0xFFE7EAEE),
    inverseOnSurface = Color(0xFF000000),
    error = Color(0xFFFF9C94),
    onError = Color(0xFF4A1612),
    errorContainer = Color(0xFF4A1612),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9AA3AF),
    outlineVariant = Color(0xFF3A404A),
    surfaceBright = Color(0xFF2A2E36),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF14171C),
    surfaceContainer = Color(0xFF14171C),
    surfaceContainerHigh = Color(0xFF1C2027),
    surfaceContainerHighest = Color(0xFF252A32),
)

internal val ElderTrackrTypography = TrackrTypography(
    eta = StandardTrackrTypography.eta.copy(fontSize = 56.sp, lineHeight = 60.sp),
    item = StandardTrackrTypography.item.copy(fontSize = 22.sp, lineHeight = 28.sp),
)

private fun TextStyle.sized(size: TextUnit, line: TextUnit) = copy(fontSize = size, lineHeight = line)

/** M3 各级字号放大：正文 20sp，最小 18sp；display 级本来就大，不动 */
internal val ElderTypography: Typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.sized(36.sp, 44.sp),
        headlineMedium = base.headlineMedium.sized(32.sp, 40.sp),
        headlineSmall = base.headlineSmall.sized(28.sp, 36.sp),
        titleLarge = base.titleLarge.sized(26.sp, 32.sp),
        titleMedium = base.titleMedium.sized(22.sp, 28.sp),
        titleSmall = base.titleSmall.sized(20.sp, 26.sp),
        bodyLarge = base.bodyLarge.sized(22.sp, 30.sp),
        bodyMedium = base.bodyMedium.sized(20.sp, 28.sp),
        bodySmall = base.bodySmall.sized(18.sp, 24.sp),
        labelLarge = base.labelLarge.sized(20.sp, 26.sp),
        labelMedium = base.labelMedium.sized(18.sp, 24.sp),
        labelSmall = base.labelSmall.sized(18.sp, 24.sp),
    )
}

internal val ElderDimens = StandardDimens.copy(
    screenPadding = 16.dp,
    cardPadding = 20.dp,
    minTouchTarget = 64.dp,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object ElderTokens : ThemeTokens {
    override fun colorScheme(darkTheme: Boolean) = if (darkTheme) ElderDarkColorScheme else ElderLightColorScheme
    override fun colors(darkTheme: Boolean) = if (darkTheme) ElderDarkTrackrColors else ElderLightTrackrColors
    override val typography = ElderTypography
    override val trackrTypography = ElderTrackrTypography
    // 圆角同级，按钮靠 64dp 触控变高
    override val shapes = StandardShapes
    override val dimens = ElderDimens
    override val motion = MotionScheme.standard()
}
