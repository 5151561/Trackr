package com.trackr.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransferAutoSwitchManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        TransferAutoSwitchManager.stopGroupTracking(context)
    }

    @Test
    fun testAdaptivePollingIntervalBackoff() {
        // 即将进站（<= 1 站 或 ETA <= 180 秒）：10s
        val approachingSession1 = TrackingSessionState(stopsRemaining = 1, etaSeconds = 120)
        assertEquals(10_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(approachingSession1))

        val approachingSession0 = TrackingSessionState(stopsRemaining = 0, etaSeconds = 30)
        assertEquals(10_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(approachingSession0))

        // 中距离（2-4 站 或 ETA <= 600 秒）：20s
        val mediumSession = TrackingSessionState(stopsRemaining = 3, etaSeconds = 450)
        assertEquals(20_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(mediumSession))

        // 远距离（> 4 站 或 暂无实时数据）：45s
        val farSession = TrackingSessionState(stopsRemaining = 7, etaSeconds = 1200)
        assertEquals(45_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(farSession))

        val unknownSession = TrackingSessionState(stopsRemaining = -1, etaSeconds = -1)
        assertEquals(45_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(unknownSession))
    }

    @Test
    fun testDualInsuranceArrivalDetection() {
        val targetLat = 30.2740
        val targetLng = 120.1550
        val leg = TransitLeg(
            legIndex = 0,
            fromStopName = "A",
            toStopName = "B",
            toStopLat = targetLat,
            toStopLng = targetLng
        )

        // 1. 公交自身事件判定到达
        val busArrivedSession = TrackingSessionState(
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        assertTrue(TransferAutoSwitchManager.isArrivalSatisfied(busArrivedSession, leg))

        // 2. 手机空间定位双保险判定：距离目标站 < 150 米
        val busNotArrivedSession = TrackingSessionState(
            stopsRemaining = 2,
            isBusArrivedAtStop = false
        )
        // 用户坐标距目标站仅约 50 米
        val nearUserLat = 30.2744
        val nearUserLng = 120.1550
        assertTrue(
            TransferAutoSwitchManager.isArrivalSatisfied(
                session = busNotArrivedSession,
                leg = leg,
                userLat = nearUserLat,
                userLng = nearUserLng
            )
        )

        // 3. 距离远且公交未到站 -> false
        val farUserLat = 30.2900
        val farUserLng = 120.1700
        assertFalse(
            TransferAutoSwitchManager.isArrivalSatisfied(
                session = busNotArrivedSession,
                leg = leg,
                userLat = farUserLat,
                userLng = farUserLng
            )
        )
    }

    @Test
    fun testIntelligentTransferTransition() {
        val testGroup = TransitGroup(
            id = "test_commute_group",
            name = "两段通勤测试",
            category = GroupCategory.COMMUTE,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "起点站A",
                    toStopName = "换乘站B",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "033",
                            cityName = "杭州",
                            lineId = "line_1",
                            lineName = "156路",
                            fromStopOrder = 1,
                            toStopOrder = 5
                        )
                    )
                ),
                TransitLeg(
                    legIndex = 1,
                    fromStopName = "换乘站B",
                    toStopName = "终点站C",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "033",
                            cityName = "杭州",
                            lineId = "line_2",
                            lineName = "地铁4号线",
                            fromStopOrder = 1,
                            toStopOrder = 8
                        )
                    )
                )
            )
        )

        // 启动整组追踪
        TransferAutoSwitchManager.startGroupTracking(context, testGroup, 0)
        val sessionInit = TransferAutoSwitchManager.activeGroupSession.value
        assertNotNull(sessionInit)
        assertEquals(0, sessionInit?.activeLegIndex)
        assertEquals(2, sessionInit?.totalLegs)
        assertFalse(sessionInit?.isCompleted ?: true)

        // 第一程正常行驶中
        val leg1Running = TrackingSessionState(
            isActive = true,
            stopsRemaining = 2,
            isBusArrivedAtStop = false
        )
        TransferAutoSwitchManager.onLegProgressUpdated(context, leg1Running)
        assertEquals(0, TransferAutoSwitchManager.activeGroupSession.value?.activeLegIndex)

        // 第一程到达换乘站 -> 自动流转激活第二程
        val leg1Arrived = TrackingSessionState(
            isActive = true,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        val transitionResult = TransferAutoSwitchManager.onLegProgressUpdated(context, leg1Arrived)
        assertNotNull(transitionResult)
        assertEquals(1, transitionResult?.activeLegIndex)
        assertFalse(transitionResult?.isCompleted ?: true)
        assertEquals("终点站C", transitionResult?.currentLeg?.toStopName)

        // 第二程到达终点站 -> 标记为已完成
        val leg2Arrived = TrackingSessionState(
            isActive = true,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        val finalResult = TransferAutoSwitchManager.onLegProgressUpdated(context, leg2Arrived)
        assertNotNull(finalResult)
        assertTrue(finalResult?.isCompleted ?: false)
    }
}
