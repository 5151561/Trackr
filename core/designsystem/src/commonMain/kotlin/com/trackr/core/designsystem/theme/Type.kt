package com.trackr.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * M3 Typography 之外的两个 v3 字号（M3 的 Typography 不能加字段，所以单独一组，
 * 通过 `MaterialTheme.typography.eta` / `.item` 读取）。其余用 M3 默认。
 */
@Immutable
data class TrackrTypography(
    /** 线路页、追踪卡的大数字：40sp Bold，等宽数字。 */
    val eta: TextStyle,
    /** 列表主行：15sp SemiBold。 */
    val item: TextStyle,
)

internal val StandardTrackrTypography = TrackrTypography(
    eta = TextStyle(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    item = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

internal val StandardTypography = Typography()

val LocalTrackrTypography = staticCompositionLocalOf { StandardTrackrTypography }

/** 大数字（到站分钟数等）。 */
val Typography.eta: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = LocalTrackrTypography.current.eta

/** 列表主行。 */
val Typography.item: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = LocalTrackrTypography.current.item
