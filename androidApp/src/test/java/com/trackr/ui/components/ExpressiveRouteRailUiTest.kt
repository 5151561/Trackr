package com.trackr.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.LiveBus
import com.trackr.core.designsystem.theme.TrackrTheme
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

    @Test
    fun testVerticalRouteRail_rendersBusChipWithoutEnteringText() {
        val stops = listOf(
            BusStop(stopId = "1", name = "汉口路", order = 1),
            BusStop(stopId = "2", name = "人民广场", order = 2)
        )
        val buses = listOf(
            LiveBus(busId = "bus_101", plateOrFleetNo = "沪A1234", order = 2, isArrived = false),
            LiveBus(busId = "bus_102", plateOrFleetNo = "沪A5678", order = 1, isArrived = true)
        )

        composeTestRule.setContent {
            TrackrTheme {
                ExpressiveRouteRail(
                    stops = stops,
                    buses = buses,
                    selectedStop = stops[0],
                    layoutMode = RouteRailLayoutMode.Vertical,
                    onSelectStop = {},
                    onSelectBus = {}
                )
            }
        }

        // 未到站的车辆只展示简短车牌号，不再包含“进站中”后缀
        composeTestRule.onNodeWithText("1234").assertIsDisplayed()
        // 已到站的车辆保持展示“已到站”
        composeTestRule.onNodeWithText("已到站").assertIsDisplayed()
    }

    @Test
    fun testGetRouteSegmentColor_consistentLogic() {
        val lightColors = com.trackr.core.designsystem.theme.LightBusSemanticColors

        // 1. 首站或全景模式 (currentSelectedOrder <= 1)：所有路段均为活跃绿
        assertEquals(
            lightColors.routeTrackActive,
            getRouteSegmentColor(fromOrder = 1, toOrder = 2, currentSelectedOrder = 1, busColors = lightColors)
        )
        assertEquals(
            lightColors.routeTrackActive,
            getRouteSegmentColor(fromOrder = 2, toOrder = 3, currentSelectedOrder = 1, busColors = lightColors)
        )

        // 2. 候车中模式 (currentSelectedOrder = 3)：候车站之前的各路段为已过站灰，候车站及之后路段为活跃绿
        // 路段 1->2 (已过站)
        assertEquals(
            lightColors.busPassedGray,
            getRouteSegmentColor(fromOrder = 1, toOrder = 2, currentSelectedOrder = 3, busColors = lightColors)
        )
        // 路段 2->3 (到达候车站之前的进站路段，亦属于用户无法乘坐的历史区间 -> 灰)
        assertEquals(
            lightColors.busPassedGray,
            getRouteSegmentColor(fromOrder = 2, toOrder = 3, currentSelectedOrder = 3, busColors = lightColors)
        )
        // 路段 3->4 (候车站出发路段，属于乘车可达区间 -> 绿)
        assertEquals(
            lightColors.routeTrackActive,
            getRouteSegmentColor(fromOrder = 3, toOrder = 4, currentSelectedOrder = 3, busColors = lightColors)
        )
        // 路段 4->5 (后续乘车区间 -> 绿)
        assertEquals(
            lightColors.routeTrackActive,
            getRouteSegmentColor(fromOrder = 4, toOrder = 5, currentSelectedOrder = 3, busColors = lightColors)
        )
    }
}
