package com.trackr.feature.line.elder

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.core.model.UiMode
import com.trackr.ui.components.elder.ELDER_BACK_TAG
import com.trackr.ui.components.elder.SHOW_TRANSIT_CODE_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 长辈版「换站」：一行一个站、点一下就选、车的位置用文字、当前站标出来；浅 / 深 × 字号 1.0 / 1.5 / 2.0 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElderPickStopScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val stops = listOf("白石洲", "大冲", "高新园", "深大", "科技园", "科技中一路", "海岸城", "后海", "深圳湾公园", "蛇口港")
        .mapIndexed { i, name -> BusStop(stopId = "s$i", name = name, order = i + 1) }

    private val buses = listOf(
        LiveBus(busId = "a", plateOrFleetNo = "", order = 3, isArrived = true),
        LiveBus(busId = "b", plateOrFleetNo = "", order = 2, isArrived = false)
    )

    private val picked = mutableListOf<BusStop>()
    private var backs = 0
    private var codes = 0

    private fun setContent(selectedOrder: Int? = 5, fontScale: Float = 1f, darkTheme: Boolean = false) {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                TrackrTheme(uiMode = UiMode.Elder, darkTheme = darkTheme) {
                    ElderPickStopScreen(
                        stops = stops,
                        buses = buses,
                        selectedOrder = selectedOrder,
                        onPick = { picked += it },
                        onBack = { backs++ },
                        onOpenTransitCode = { codes++ }
                    )
                }
            }
        }
    }

    @Test
    fun tapStop_picksIt() {
        setContent()
        composeTestRule.onNodeWithTag(ElderPickStopTags.stop(7)).performClick()
        assertEquals(listOf("海岸城"), picked.map { it.name })
    }

    @Test
    fun marksBusPositionAndCurrentStopInWords() {
        setContent()
        composeTestRule.onNodeWithTag(ElderPickStopTags.LIST).performScrollToNode(hasText("高新园"))
        composeTestRule.onNodeWithText("车在这").assertIsDisplayed()
        composeTestRule.onNodeWithText("车快到").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ElderPickStopTags.LIST).performScrollToNode(hasText("科技园"))
        composeTestRule.onNodeWithText("现在选的").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ElderPickStopTags.stop(5)).assertIsSelected()
    }

    @Test
    fun back_and_transitCode() {
        setContent()
        composeTestRule.onNodeWithTag(ELDER_BACK_TAG).performClick()
        assertEquals(1, backs)
        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).performClick()
        assertEquals(1, codes)
        assertEquals(emptyList<BusStop>(), picked)
    }

    @Test
    fun light_fontScale1() = assertFits(1f, false)

    @Test
    fun light_fontScale15() = assertFits(1.5f, false)

    @Test
    fun light_fontScale2() = assertFits(2f, false)

    @Test
    fun dark_fontScale1() = assertFits(1f, true)

    @Test
    fun dark_fontScale15() = assertFits(1.5f, true)

    @Test
    fun dark_fontScale2() = assertFits(2f, true)

    private fun assertFits(scale: Float, darkTheme: Boolean) {
        setContent(fontScale = scale, darkTheme = darkTheme)
        ElderScreenshots.capture(composeTestRule, "pick_stop", scale, darkTheme)
        listOf("白石洲", "科技园", "蛇口港").forEach { anchor ->
            composeTestRule.onNodeWithTag(ElderPickStopTags.LIST).performScrollToNode(hasText(anchor))
            ElderLayoutChecks.assertClickablesAtLeast64dp(composeTestRule)
            ElderLayoutChecks.assertNoTextClipped(composeTestRule, scale)
            composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertIsDisplayed()
        }
    }
}
