package com.trackr.ui.components.elder

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.core.model.UiMode
import com.trackr.ui.components.arrival.ArrivalUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 长辈版共用组件：按钮高度、「出示乘车码」、大字在 1.0 / 1.5 / 2.0 倍字体下不被裁。
 * 用 Robolectric 原生图形模式：默认模式下不真量字宽（中文整句只量出几 px），判不出裁切。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElderComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setElderContent(fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                TrackrTheme(uiMode = UiMode.Elder, darkTheme = false) { content() }
            }
        }
    }

    @Test
    fun showTransitCode_tallAndOneTap() {
        var clicks = 0
        setElderContent { ShowTransitCodeButton(onClick = { clicks++ }) }

        composeTestRule.onNodeWithText("出示乘车码").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG)
            .assertHeightIsAtLeast(ElderPrimaryButtonHeight)
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun allClickables_atLeast64dp() {
        setElderContent {
            Column {
                ElderTopBar(title = "325", onBack = {})
                ElderButton(text = "车快到时叫我", onClick = {}, primary = true)
                ElderButton(text = "换方向", onClick = {})
            }
        }
        composeTestRule.onNodeWithTag(ELDER_BACK_TAG).assertHeightIsAtLeast(64.dp)
        val nodes = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(3, nodes.size)
        nodes.forEach { node ->
            val height = with(composeTestRule.density) { node.size.height.toDp() }
            val width = with(composeTestRule.density) { node.size.width.toDp() }
            assertTrue("可点元素 $width x $height", height >= 64.dp && width >= 64.dp)
        }
    }

    @Test
    fun arrival_fits_fontScale1() = assertArrivalFits(1f)

    @Test
    fun arrival_fits_fontScale15() = assertArrivalFits(1.5f)

    @Test
    fun arrival_fits_fontScale2() = assertArrivalFits(2f)

    /** 最宽的一种大字「下一班 07:20」在 360dp 宽里不被裁（自动缩小） */
    private fun assertArrivalFits(scale: Float) {
        setElderContent(fontScale = scale) {
            ElderArrival(arrival = ArrivalUi(big = "下一班 07:20", small = "从 白石洲 发车", tone = ArrivalTone.Mute))
        }
        composeTestRule.onNodeWithText("下一班 07:20").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 白石洲 发车").assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText("下一班 07:20").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        // 原生图形模式下字宽是真量出来的：放不下时自动缩小，宽度不超出
        val result = results.single()
        assertFalse("大字在 $scale 倍字体下被裁", result.hasVisualOverflow)
        assertTrue(result.multiParagraph.intrinsics.maxIntrinsicWidth <= result.size.width)
    }
}
