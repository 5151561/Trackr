package com.trackr.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.trackr.core.model.UiMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_retry
import com.trackr.core.designsystem.resources.ic_warning
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 设计稿 v3 Token：固定 v3 蓝（不随壁纸）、语义色、旧名别名、字号、圆角、尺寸、Compose 资源。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Android 12+ 才有动态取色，确认这里不用
class TrackrThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Captured {
        var primary = Color.Unspecified
        var background = Color.Unspecified
        var colors: TrackrColors? = null
        var bus: BusSemanticColors? = null
        var extended: ExtendedColors? = null
    }

    private fun capture(darkTheme: Boolean): Captured {
        val c = Captured()
        composeTestRule.setContent {
            TrackrTheme(darkTheme = darkTheme) {
                c.primary = MaterialTheme.colorScheme.primary
                c.background = MaterialTheme.colorScheme.background
                c.colors = TrackrTheme.colors
                c.bus = MaterialTheme.busColors
                c.extended = MaterialTheme.extendedColors
            }
        }
        composeTestRule.waitForIdle()
        return c
    }

    @Test
    fun light_usesV3BlueAndSemanticColors() {
        val c = capture(darkTheme = false)
        assertEquals(Color(0xFF1F5FD6), c.primary)
        assertEquals(Color(0xFFF5F6F8), c.background)
        assertEquals(LightTrackrColors, c.colors)
        assertEquals(Color(0xFF12924F), c.colors!!.go)
        assertEquals(Color(0xFFB45F00), c.colors!!.warn)
    }

    @Test
    fun dark_usesV3DarkPalette() {
        val c = capture(darkTheme = true)
        assertEquals(Color(0xFF8AB0FF), c.primary)
        assertEquals(Color(0xFF0F1115), c.background)
        assertEquals(DarkTrackrColors, c.colors)
    }

    @Test
    fun legacyAliases_pointToV3Colors() {
        val c = capture(darkTheme = false)
        val colors = c.colors!!
        val bus = c.bus!!
        val ext = c.extended!!
        assertEquals(colors.go, bus.busRunningGreen)
        assertEquals(colors.go, bus.busArrivingAmber)
        assertEquals(colors.walk, bus.busPassedGray)
        assertEquals(c.primary, bus.routeTrackActive)
        assertEquals(colors.goContainer, bus.busChipContainer)
        assertEquals(colors.go, ext.success)
        assertEquals(colors.warn, ext.warning)
        assertEquals(colors.warnContainer, ext.warningContainer)
        assertEquals(c.primary, ext.legSecondary)
        assertEquals(colors.walk, ext.walk)
    }

    @Test
    fun typographyShapesDimens_matchV3() {
        var etaSize = 0.sp
        var itemSize = 0.sp
        var dimens: TrackrDimens? = null
        var minTouch = 0.dp
        var mode: UiMode? = null
        composeTestRule.setContent {
            TrackrTheme {
                etaSize = MaterialTheme.typography.eta.fontSize
                itemSize = MaterialTheme.typography.item.fontSize
                dimens = TrackrTheme.dimens
                minTouch = LocalMinimumInteractiveComponentSize.current
                mode = TrackrTheme.uiMode
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(40.sp, etaSize)
        assertEquals(15.sp, itemSize)
        assertEquals("tnum", StandardTrackrTypography.eta.fontFeatureSettings)
        assertEquals(12.dp, dimens!!.screenPadding)
        assertEquals(48.dp, minTouch)
        assertEquals(UiMode.Standard, mode)
        assertEquals(StandardShapes.small, RoundedCornerShape(8.dp))
        assertEquals(StandardShapes.medium, RoundedCornerShape(16.dp))
        assertEquals(StandardShapes.extraLarge, RoundedCornerShape(28.dp))
    }

    @Test
    fun elder_largerTypeAnd64dpTouchTarget() {
        var etaSize = 0.sp
        var itemSize = 0.sp
        var bodySize = 0.sp
        var smallest = 100.sp
        var minTouch = 0.dp
        var dimens: TrackrDimens? = null
        var mode: UiMode? = null
        composeTestRule.setContent {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = false) {
                val t = MaterialTheme.typography
                etaSize = t.eta.fontSize
                itemSize = t.item.fontSize
                bodySize = t.bodyMedium.fontSize
                smallest = listOf(t.bodySmall, t.labelSmall, t.labelMedium, t.titleSmall)
                    .minOf { it.fontSize.value }.sp
                minTouch = LocalMinimumInteractiveComponentSize.current
                dimens = TrackrTheme.dimens
                mode = TrackrTheme.uiMode
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(56.sp, etaSize)
        assertEquals(22.sp, itemSize)
        assertEquals(20.sp, bodySize)
        assertEquals(18.sp, smallest) // 长辈版不出现 18sp 以下
        assertEquals(64.dp, minTouch)
        assertEquals(64.dp, dimens!!.minTouchTarget)
        assertEquals(16.dp, dimens!!.screenPadding)
        assertEquals(UiMode.Elder, mode)
    }

    @Test
    fun elder_highContrastPalette() {
        var light: Pair<Color, Color>? = null
        var colors: TrackrColors? = null
        composeTestRule.setContent {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = false) {
                light = MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surface
                colors = TrackrTheme.colors
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(Color.Black to Color.White, light)
        assertEquals(ElderLightTrackrColors, colors)
        // 正文（onSurfaceVariant 也算）对卡片底 ≥ 7:1
        assertTrue(contrast(ElderLightColorScheme.onSurfaceVariant, ElderLightColorScheme.surface) >= 7.0)
        assertTrue(contrast(ElderDarkColorScheme.onSurfaceVariant, ElderDarkColorScheme.surface) >= 7.0)
        assertTrue(contrast(ElderLightTrackrColors.walk, ElderLightColorScheme.surface) >= 7.0)
        assertTrue(contrast(ElderLightTrackrColors.go, ElderLightColorScheme.surface) >= 4.5)
        assertTrue(contrast(ElderLightTrackrColors.warn, ElderLightColorScheme.surface) >= 4.5)
    }

    @Test
    fun elder_darkUsesBlackBackground() {
        var bg = Color.Unspecified
        var text = Color.Unspecified
        composeTestRule.setContent {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = true) {
                bg = MaterialTheme.colorScheme.background
                text = MaterialTheme.colorScheme.onBackground
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(Color.Black, bg)
        assertEquals(Color.White, text)
    }

    /** WCAG 对比度 */
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    @Test
    fun composeResources_loadOnAndroid() {
        var retry = ""
        var iconWidth = 0.dp
        composeTestRule.setContent {
            retry = stringResource(Res.string.action_retry)
            iconWidth = vectorResource(Res.drawable.ic_warning).defaultWidth
        }
        composeTestRule.waitForIdle()
        assertEquals("重试", retry)
        assertTrue(iconWidth == 24.dp)
    }
}
