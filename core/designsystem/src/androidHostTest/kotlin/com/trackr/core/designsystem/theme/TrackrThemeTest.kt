package com.trackr.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
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
