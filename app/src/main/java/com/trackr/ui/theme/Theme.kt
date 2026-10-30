package com.trackr.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.trackr.core.designsystem.theme.BusSemanticColors as CoreBusSemanticColors
import com.trackr.core.designsystem.theme.DarkBusSemanticColors as CoreDarkBusSemanticColors
import com.trackr.core.designsystem.theme.LightBusSemanticColors as CoreLightBusSemanticColors
import com.trackr.core.designsystem.theme.LocalBusSemanticColors as CoreLocalBusSemanticColors
import com.trackr.core.designsystem.theme.TrackrTheme as CoreTrackrTheme
import com.trackr.core.designsystem.theme.busChipSpringSpec as coreBusChipSpringSpec
import com.trackr.core.designsystem.theme.busColors as coreBusColors
import com.trackr.core.designsystem.theme.expressiveRotateSpringSpec as coreExpressiveRotateSpringSpec
import com.trackr.core.designsystem.theme.expressiveSpringSpec as coreExpressiveSpringSpec

typealias BusSemanticColors = CoreBusSemanticColors

val LightBusSemanticColors = CoreLightBusSemanticColors
val DarkBusSemanticColors = CoreDarkBusSemanticColors
val LocalBusSemanticColors = CoreLocalBusSemanticColors

/**
 * 快捷获取当前主题的公交语义色彩
 */
val MaterialTheme.busColors: BusSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = coreBusColors

/**
 * 通用 M3E 弹性物理动画规格辅助函数
 */
fun <T> expressiveSpringSpec(
    dampingRatio: Float = Spring.DampingRatioMediumBouncy,
    stiffness: Float = Spring.StiffnessMediumLow
): SpringSpec<T> = coreExpressiveSpringSpec(
    dampingRatio = dampingRatio,
    stiffness = stiffness
)

/**
 * 公交车徽标两站之间平滑物理推进动画规格
 */
fun <T> busChipSpringSpec(): SpringSpec<T> = coreBusChipSpringSpec()

/**
 * 换向 180° 旋转弹簧动画规格
 */
fun <T> expressiveRotateSpringSpec(): SpringSpec<T> = coreExpressiveRotateSpringSpec()

/**
 * Material Design 3 Expressive (M3E) 应用主题
 */
@Composable
fun TrackrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    CoreTrackrTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        content = content
    )
}
