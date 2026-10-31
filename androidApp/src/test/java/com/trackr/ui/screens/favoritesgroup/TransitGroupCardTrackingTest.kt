package com.trackr.ui.screens.favoritesgroup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.ui.screens.favoritesgroup.components.TransitGroupCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TransitGroupCardTrackingTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val b612 = CandidateLine(cityId = "0755", lineId = "b612", lineName = "B612", fromStopOrder = 5, toStopOrder = 9)
    private val m492 = CandidateLine(cityId = "0755", lineId = "m492", lineName = "M492", fromStopOrder = 3, toStopOrder = 8)

    private val group = TransitGroup(
        id = "g_transfer",
        name = "通勤",
        legs = listOf(
            TransitLeg(legIndex = 0, fromStopName = "科技园", toStopName = "深大北门", candidateLines = listOf(b612)),
            TransitLeg(legIndex = 1, fromStopName = "深大北门", toStopName = "车公庙", candidateLines = listOf(m492))
        )
    )

    private fun session(phase: LegPhase, stopsRemaining: Int, etaSeconds: Int, signalLost: Boolean = false) =
        GroupTrackingSessionState(
            groupId = group.id,
            groupName = group.name,
            activeLegIndex = 0,
            totalLegs = 2,
            currentLeg = group.legs[0],
            trackingSessionState = TrackingSessionState(
                isActive = true,
                lineId = "b612",
                lineName = "B612",
                targetStopName = "深大北门",
                targetOrder = 9,
                boardStopOrder = 5,
                boardStopName = "科技园",
                currentBusOrder = if (phase == LegPhase.ON_BOARD) 7 else 3,
                stopsRemaining = stopsRemaining,
                etaSeconds = etaSeconds,
                legPhase = phase,
                currentLegIndex = 0,
                totalLegs = 2,
                isSignalLost = signalLost
            )
        )

    @Test
    fun untracked_showsFirstLegHeadlineAndEstimatedBoardTimeForSecondLeg() {
        val realtime = mapOf(
            "g_transfer_0_b612" to CandidateLineRealtimeInfo(
                lineId = "b612", lineName = "B612", shortChipEta = "3站",
                stopsAway = 3, estimatedMinutes = 6, vehiclePlate = "粤B·58921", isFastest = true
            ),
            "g_transfer_1_m492" to CandidateLineRealtimeInfo(
                lineId = "m492", lineName = "M492", shortChipEta = "1站", stopsAway = 1, estimatedMinutes = 2
            )
        )
        var clickedOrder = -1
        composeTestRule.setContent {
            TransitGroupCard(
                group = group,
                activeSession = null,
                realtimeSummaries = emptyMap(),
                candidateRealtimeMap = realtime,
                onStartTracking = {},
                onStopTracking = {},
                onSaveTemporary = {},
                onEdit = {},
                onDelete = {},
                onLineClick = { clickedOrder = it.boardStopOrderForDetail() }
            )
        }

        composeTestRule.onNodeWithTag("group_title_g_transfer").assertTextEquals("科技园 → 车公庙")
        composeTestRule.onNodeWithText("6 分钟后到 科技园").assertExists()
        composeTestRule.onNodeWithText("还有 3 站 · 粤B·58921").assertExists()
        composeTestRule.onNodeWithTag("leg_ride_note_0").assertTextEquals("坐 4 站 · 在 深大北门 下车")
        // 第 2 程不显示"车离换乘站 1 站"，改为预计上车时间
        val note = composeTestRule.onNodeWithTag("leg_ride_note_1").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertTrue(note, note.matches(Regex("坐 5 站 · 预计 \\d{2}:\\d{2} 上车")))

        // 点线路跳详情传上车站站序
        composeTestRule.onNodeWithTag("btn_track_trajectory_0").performClick()
        assertEquals(5, clickedOrder)
    }

    @Test
    fun waitingBoard_showsPhaseBadgeBoardedButtonAndHereBadge() {
        var confirmed = false
        composeTestRule.setContent {
            TransitGroupCard(
                group = group,
                activeSession = session(LegPhase.WAITING_BOARD, stopsRemaining = 2, etaSeconds = 240),
                realtimeSummaries = emptyMap(),
                onStartTracking = {},
                onStopTracking = {},
                onConfirmBoarded = { confirmed = true },
                onSaveTemporary = {},
                onEdit = {},
                onDelete = {},
                onLineClick = {}
            )
        }

        composeTestRule.onNodeWithTag("phase_badge_g_transfer").assertTextEquals("第 1/2 程 · 等车")
        composeTestRule.onNodeWithTag("leg_stepper_g_transfer").assertExists()
        composeTestRule.onNodeWithText("4 分钟后到 科技园").assertExists()
        composeTestRule.onNodeWithTag("badge_you_are_here").assertExists()
        composeTestRule.onNodeWithText("结束").assertExists()
        composeTestRule.onNodeWithTag("btn_confirm_boarded_g_transfer").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun onBoard_nearTransfer_showsDropHeadlineNextLegLiveAndSignalLost() {
        val realtime = mapOf(
            "g_transfer_1_m492" to CandidateLineRealtimeInfo(
                lineId = "m492", lineName = "M492", stopsAway = 6, estimatedMinutes = 12
            )
        )
        var switched = false
        composeTestRule.setContent {
            TransitGroupCard(
                group = group,
                activeSession = session(LegPhase.ON_BOARD, stopsRemaining = 2, etaSeconds = 300, signalLost = true),
                realtimeSummaries = emptyMap(),
                candidateRealtimeMap = realtime,
                onStartTracking = {},
                onStopTracking = {},
                onSwitchToNextLeg = { switched = true },
                onSaveTemporary = {},
                onEdit = {},
                onDelete = {},
                onLineClick = {}
            )
        }

        composeTestRule.onNodeWithTag("phase_badge_g_transfer").assertTextEquals("第 1/2 程 · 乘车")
        composeTestRule.onNodeWithText("2 站后在 深大北门 下车").assertExists()
        composeTestRule.onNodeWithText("约 5 分钟 · 下车后换乘 M492").assertExists()
        composeTestRule.onNodeWithTag("signal_lost_g_transfer").assertTextEquals("信号丢失，显示的是上次位置")
        composeTestRule.onNodeWithTag("leg_ride_note_0").assertTextEquals("已坐 2 / 4 站 · 车上")
        composeTestRule.onNodeWithTag("leg_ride_note_1").assertTextEquals("坐 5 站 · 现在离 深大北门 6 站 · 约 12 分钟")
        composeTestRule.onNodeWithTag("btn_next_leg_g_transfer").performClick()
        assertTrue(switched)
    }
}
