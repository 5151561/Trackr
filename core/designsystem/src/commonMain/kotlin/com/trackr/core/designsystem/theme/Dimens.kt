package com.trackr.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v3 间距与尺寸，通过 `TrackrTheme.dimens` 读取。长辈版（第 30 步）换一套值，字段名不变。
 */
@Immutable
data class TrackrDimens(
    /** 间距阶梯 4 / 8 / 12 / 16 / 24。 */
    val spaceXs: Dp,
    val spaceS: Dp,
    val spaceM: Dp,
    val spaceL: Dp,
    val spaceXl: Dp,
    /** 页面左右留白。 */
    val screenPadding: Dp,
    /** 卡片内边距（v3：14–16dp）。 */
    val cardPadding: Dp,
    /** 最小触控尺寸，同时提供给 M3 的 LocalMinimumInteractiveComponentSize。 */
    val minTouchTarget: Dp,
)

internal val StandardDimens = TrackrDimens(
    spaceXs = 4.dp,
    spaceS = 8.dp,
    spaceM = 12.dp,
    spaceL = 16.dp,
    spaceXl = 24.dp,
    screenPadding = 12.dp,
    cardPadding = 16.dp,
    minTouchTarget = 48.dp,
)

val LocalTrackrDimens = staticCompositionLocalOf { StandardDimens }
