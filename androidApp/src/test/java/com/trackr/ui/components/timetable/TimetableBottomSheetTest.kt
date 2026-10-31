package com.trackr.ui.components.timetable

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.TimetableSegment
import com.trackr.core.designsystem.theme.TrackrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimetableBottomSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testTimetableBottomSheet_rendersTimetableInfo() {
        val timetable = LineTimetable(
            lineId = "line_001",
            lineName = "49路",
            nextDeparture = "08:15",
            nextDepartureSource = "今日预测",
            segments = listOf(
                TimetableSegment(fromTime = "06:00", toTime = "09:00", intervalMinutes = 8)
            ),
            todayDepartures = listOf("08:15", "08:23", "08:31"),
            depDesc = "发车时刻仅供参考，请提前候车"
        )
        val line = BusLineDirection(
            lineId = "line_001",
            name = "49路",
            direction = 0,
            startStop = "汉口路",
            endStop = "上海体育馆",
            source = DataSourceType.CHELAILE
        )
        var dismissed = false

        composeTestRule.setContent {
            TrackrTheme {
                TimetableBottomSheet(
                    timetable = timetable,
                    line = line,
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("49路 发车时刻表").assertIsDisplayed()
        composeTestRule.onNodeWithText("始发站：汉口路 → 终点：上海体育馆").assertIsDisplayed()
        composeTestRule.onNodeWithText("预计下一班发车：08:15 （今日预测）").assertIsDisplayed()
        composeTestRule.onNodeWithText("06:00 – 09:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("8 分钟/班").assertIsDisplayed()
        composeTestRule.onNodeWithText("08:15").assertIsDisplayed()
        composeTestRule.onNodeWithText("08:23").assertIsDisplayed()
        composeTestRule.onNodeWithText("08:31").assertIsDisplayed()
        composeTestRule.onNodeWithText("发车时刻仅供参考，请提前候车").assertIsDisplayed()
    }
}
