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
import com.trackr.core.designsystem.theme.TrackrTheme
import kotlinx.datetime.LocalTime
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

    private fun setContent(detail: BusLineDetail, selected: BusStop?, now: LocalTime = LocalTime(12, 0)) {
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
                    onOpenScheduledReminder = {},
                    now = now
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

        // 车都过了：只给下一班始发发车时间，不估到站
        composeTestRule.onNodeWithText("下一班 21:05").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 延安东路外滩 发车").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("车都过了").assertIsDisplayed()
        composeTestRule.onNodeWithText("暂无下一班发车时间 · 末班 23:30").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("下一班 21:40").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 延安东路外滩 发车").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("今天没车了").assertIsDisplayed()
        composeTestRule.onNodeWithText("明天首班 05:30").assertIsDisplayed()
    }

    @Test
    fun allBusesPassed_afterLastBus_showsNoMoreBusToday() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = null
        )
        setContent(detail, stops[1], now = LocalTime(23, 31))

        composeTestRule.onNodeWithText("今天没车了").assertIsDisplayed()
        composeTestRule.onNodeWithText("明天首班 05:30").assertIsDisplayed()
    }

    @Test
    fun busOnTheWay_showsMinutesBigAndStopsSmall() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 2, etaSeconds = 480)),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("8").assertIsDisplayed()
        composeTestRule.onNodeWithText("分钟").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 3 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一辆还有 ", substring = true).assertDoesNotExist()
    }

    @Test
    fun busArrived_showsArrivedWithNextBus() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(
                LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 5, isArrived = true),
                LiveBus(busId = "b2", plateOrFleetNo = "沪A·D8867", order = 2, etaSeconds = 540)
            ),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("到站了").assertIsDisplayed()
        composeTestRule.onNodeWithText("快去上车 · 下一辆 9 分钟").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("首班 05:30").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 延安东路外滩 发车").assertIsDisplayed()
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
