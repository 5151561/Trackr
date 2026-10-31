package com.trackr.ui.screens.linedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.LiveBus
import com.trackr.ui.screens.linedetail.components.StationArrivalContent
import com.trackr.ui.theme.TrackrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StationArrivalContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val lineDirection = BusLineDirection(
        lineId = "line_001",
        name = "71路",
        direction = 0,
        startStop = "延安东路外滩",
        endStop = "申昆路枢纽站",
        firstTime = "05:30",
        lastTime = "23:30",
        source = DataSourceType.CHELAILE
    )

    private val stops = listOf(
        BusStop(stopId = "s1", name = "延安东路外滩", order = 1),
        BusStop(stopId = "s2", name = "陕西北路", order = 5)
    )

    private fun setContent(detail: BusLineDetail, selected: BusStop?) {
        composeTestRule.setContent {
            TrackrTheme {
                StationArrivalContent(
                    lineDetail = detail,
                    selectedStop = selected,
                    isTrackingActive = false,
                    alertThreshold = 2,
                    onThresholdChange = {},
                    onOpenBusList = {},
                    onToggleTracking = {},
                    onOpenScheduledReminder = {}
                )
            }
        }
    }

    @Test
    fun allBusesPassed_withNextDeparture_showsStartDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = "21:05"
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("车辆均已过该站").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一班约 21:05 从始发站（延安东路外滩）发车").assertIsDisplayed()
        composeTestRule.onNodeWithText("全线 1 辆车在途 · 查看").assertIsDisplayed()
    }

    @Test
    fun allBusesPassed_withoutNextDeparture_showsFallbackHint() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = null
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("车辆均已过该站").assertIsDisplayed()
        composeTestRule.onNodeWithText("暂无下一班发车信息 · 末班 23:30").assertIsDisplayed()
    }

    @Test
    fun allBusesPassed_withTimetableFallback_showsStartDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = null
        )
        composeTestRule.setContent {
            TrackrTheme {
                StationArrivalContent(
                    lineDetail = detail,
                    selectedStop = stops[1],
                    isTrackingActive = false,
                    alertThreshold = 2,
                    onThresholdChange = {},
                    onOpenBusList = {},
                    onToggleTracking = {},
                    onOpenScheduledReminder = {},
                    fallbackNextDeparture = "21:40"
                )
            }
        }

        composeTestRule.onNodeWithText("下一班约 21:40 从始发站（延安东路外滩）发车").assertIsDisplayed()
    }

    @Test
    fun lastBusEnded_showsLastDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            operatingState = LineOperatingState.ENDED
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("今日末班已过").assertIsDisplayed()
        composeTestRule.onNodeWithText("末班 23:30").assertIsDisplayed()
    }

    @Test
    fun waiting_showsFirstDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            operatingState = LineOperatingState.WAITING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("等待发车").assertIsDisplayed()
        composeTestRule.onNodeWithText("首班 05:30").assertIsDisplayed()
    }

    @Test
    fun reverseSwitchButtonRemoved_notDisplayed() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("切换反向同名站").assertDoesNotExist()
        composeTestRule.onNodeWithText("全线暂无在途车辆").assertIsDisplayed()
    }
}
