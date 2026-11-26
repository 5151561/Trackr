package com.trackr.ui.screens.routedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v3 路线详情页（第 22 步）：喂手写状态，记下发出的事件 */
@RunWith(RobolectricTestRunner::class)
class RouteDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<RouteDetailEvent>()

    private val line325 = CandidateLine(
        cityId = "034",
        cityName = "上海",
        lineId = "L325",
        lineName = "325",
        source = DataSourceType.CUSTOM,
        fromStopOrder = 3,
        toStopOrder = 8,
        startStopName = "深大北门",
        endStopName = "高新园"
    )
    private val lineM194 = line325.copy(lineId = "LM194", lineName = "M194", fromStopOrder = 4, toStopOrder = 9)

    private val leg1 = TransitLeg(
        legIndex = 0,
        fromStopName = "深大北门",
        toStopName = "高新园",
        candidateLines = listOf(line325, lineM194),
        walkingDistanceMeters = 0
    )
    private val leg2 = TransitLeg(
        legIndex = 1,
        fromStopName = "高新园",
        toStopName = "腾讯大厦",
        candidateLines = listOf(line325.copy(lineId = "L19", lineName = "19", fromStopOrder = 1, toStopOrder = 4)),
        walkingDistanceMeters = 120
    )
    private val group = TransitGroup(
        id = "g1",
        name = "家 → 公司",
        category = GroupCategory.COMMUTE,
        legs = listOf(leg1, leg2)
    )

    private fun setContent(target: TransitGroup = group, selected: Map<String, String> = emptyMap()) {
        composeTestRule.setContent {
            TrackrTheme {
                RouteDetailScreen(
                    group = target,
                    activeSession = null,
                    realtimeSummaries = emptyMap(),
                    candidateRealtimeMap = emptyMap(),
                    selectedLineKeys = selected,
                    favoriteGroups = emptyList(),
                    message = null,
                    onMessageShown = {},
                    onEvent = { events += it }
                )
            }
        }
    }

    @Test
    fun showsBothLegsAndCandidateLines() {
        setContent()

        composeTestRule.onNodeWithText("家 → 公司").assertIsDisplayed()
        composeTestRule.onNodeWithText("深大北门 上车").assertIsDisplayed()
        composeTestRule.onNodeWithText("高新园 上车").assertIsDisplayed()
        composeTestRule.onNodeWithText("325").assertIsDisplayed()
        composeTestRule.onNodeWithText("19").assertExists()
    }

    @Test
    fun tappingCandidateLine_selectsIt() {
        setContent()

        composeTestRule.onNodeWithText("M194").performClick()
        assertTrue(events.contains(RouteDetailEvent.SelectLine(legIndex = 0, lineId = "LM194")))
    }

    @Test
    fun startTracking_sendsEvent() {
        setContent()

        composeTestRule.onNodeWithText("开始追踪").performClick()
        assertTrue(events.contains(RouteDetailEvent.StartTracking))
    }

    @Test
    fun temporaryGroup_canBeSavedWithName() {
        setContent(target = group.copy(isTemporary = true))

        composeTestRule.onNodeWithText("收藏").performClick()
        composeTestRule.onNodeWithText("收藏路线").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_confirm_save_route").performClick()

        val saved = events.filterIsInstance<RouteDetailEvent.SaveTemporary>().single()
        assertEquals("家 → 公司", saved.name)
    }

    @Test
    fun addCandidateLine_sendsTypedName() {
        setContent()

        composeTestRule.onAllNodesWithText("还可以坐别的线")[0].performClick()
        composeTestRule.onNodeWithText("第 1 程还能坐哪条线").assertIsDisplayed()
        composeTestRule.onNodeWithTag("input_add_line").performTextInput("71")
        composeTestRule.onNodeWithText("添加").performClick()

        assertTrue(events.contains(RouteDetailEvent.AddLine(legIndex = 0, lineName = "71")))
    }
}
