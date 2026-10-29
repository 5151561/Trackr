package com.trackr.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.LiveBus
import com.trackr.ui.theme.TrackrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressiveRouteRailUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testRouteRail_rendersStopsAndHandlesClick() {
        val stops = listOf(
            BusStop(stopId = "1", name = "汉口路", order = 1),
            BusStop(stopId = "2", name = "人民广场", order = 2),
            BusStop(stopId = "3", name = "上海体育馆", order = 3)
        )
        val buses = listOf(
            LiveBus(busId = "bus_101", plateOrFleetNo = "沪A1234", order = 2, etaSeconds = 60)
        )

        var selectedStop: BusStop? = null
        var selectedBus: LiveBus? = null

        composeTestRule.setContent {
            TrackrTheme {
                ExpressiveRouteRail(
                    stops = stops,
                    buses = buses,
                    selectedStop = stops[1],
                    onSelectStop = { selectedStop = it },
                    onSelectBus = { selectedBus = it }
                )
            }
        }

        // 验证竖排站名字元逐字渲染与显示
        composeTestRule.onNodeWithText("汉").assertIsDisplayed()
        composeTestRule.onNodeWithText("口").assertIsDisplayed()
        composeTestRule.onNodeWithText("路").assertIsDisplayed()

        // 点击第三站（上海体育馆）中的字元触发站台选择
        composeTestRule.onNodeWithText("体").performClick()

        assertNotNull(selectedStop)
        assertEquals("上海体育馆", selectedStop?.name)
        assertEquals(3, selectedStop?.order)
    }

    @Test
    fun testVerticalRouteRail_rendersStopsInVerticalMode() {
        val stops = listOf(
            BusStop(stopId = "1", name = "汉口路", order = 1),
            BusStop(stopId = "2", name = "人民广场", order = 2)
        )

        var clickedStop: BusStop? = null

        composeTestRule.setContent {
            TrackrTheme {
                ExpressiveRouteRail(
                    stops = stops,
                    buses = emptyList(),
                    selectedStop = stops[0],
                    layoutMode = RouteRailLayoutMode.Vertical,
                    onSelectStop = { clickedStop = it },
                    onSelectBus = {}
                )
            }
        }

        // 纵向模式下站名横排完整显示
        composeTestRule.onNodeWithText("汉口路").assertIsDisplayed()
        composeTestRule.onNodeWithText("人民广场").assertIsDisplayed()

        composeTestRule.onNodeWithText("人民广场").performClick()
        assertEquals("人民广场", clickedStop?.name)
    }
}
