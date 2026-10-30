package com.trackr.ui.screens.linedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LiveBus
import com.trackr.ui.screens.linedetail.components.BusFollowContent
import com.trackr.ui.theme.TrackrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BusFollowContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val lineDirection = BusLineDirection(
        lineId = "line_001",
        name = "49路",
        direction = 0,
        startStop = "汉口路",
        endStop = "上海体育馆",
        source = DataSourceType.CHELAILE
    )

    private val stops = listOf(
        BusStop(stopId = "s1", name = "汉口路", order = 1),
        BusStop(stopId = "s2", name = "人民广场", order = 2),
        BusStop(stopId = "s3", name = "上海体育馆", order = 3)
    )

    @Test
    fun testBusFollowContent_rendersFollowedBusAndStops() {
        val followedBus = LiveBus(
            busId = "b1",
            plateOrFleetNo = "沪A1234",
            order = 2,
            etaSeconds = 120,
            speedKmh = 35.0,
            isArrived = false
        )
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(followedBus)
        )
        var backClicked = false

        composeTestRule.setContent {
            TrackrTheme {
                BusFollowContent(
                    lineDetail = lineDetail,
                    followedBus = followedBus,
                    onBack = { backClicked = true }
                )
            }
        }

        // 验证标题与车牌
        composeTestRule.onNodeWithText("单车跟随 · 沪A1234").assertIsDisplayed()
        composeTestRule.onNodeWithText("开往  上海体育馆").assertIsDisplayed()

        // 验证统计概要卡
        composeTestRule.onNodeWithText("距终点").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("时速").assertIsDisplayed()
        composeTestRule.onNodeWithText("35 km/h").assertIsDisplayed()
        composeTestRule.onNodeWithText("到终点预测").assertIsDisplayed()

        // 验证站点与状态徽标
        composeTestRule.onNodeWithText("汉口路").assertIsDisplayed()
        composeTestRule.onNodeWithText("人民广场").assertIsDisplayed()
        composeTestRule.onNodeWithText("上海体育馆").assertIsDisplayed()
        composeTestRule.onNodeWithText("正在接近").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一站").assertIsDisplayed()
        composeTestRule.onNodeWithText("已过").assertIsDisplayed()
    }

    @Test
    fun testBusFollowContent_handlesNullBus() {
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList()
        )

        composeTestRule.setContent {
            TrackrTheme {
                BusFollowContent(
                    lineDetail = lineDetail,
                    followedBus = null,
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("这辆车已不在线上（可能已收班或已到达终点）。").assertIsDisplayed()
    }
}
