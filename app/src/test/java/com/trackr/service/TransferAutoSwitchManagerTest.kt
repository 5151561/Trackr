package com.trackr.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitLeg
import com.trackr.util.CoordTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val approachingSession1 = TrackingSessionState(stopsRemaining = 1, etaSeconds = 120)
        assertEquals(10_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(approachingSession1))

        val approachingSession0 = TrackingSessionState(stopsRemaining = 0, etaSeconds = 30)
        assertEquals(10_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(approachingSession0))

        val mediumSession = TrackingSessionState(stopsRemaining = 3, etaSeconds = 450)
        assertEquals(20_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(mediumSession))

        val farSession = TrackingSessionState(stopsRemaining = 7, etaSeconds = 1200)
        assertEquals(45_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(farSession))

        val unknownSession = TrackingSessionState(stopsRemaining = -1, etaSeconds = -1)
        assertEquals(45_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(unknownSession))
    }

    @Test
    fun waitingBoardPhase_neverTriggersDropArrival() {
        // 等车阶段，即便 stopsRemaining==0 且公交到站，也绝不触发下车站到达 / 切程
        val leg = TransitLeg(
            legIndex = 0,
            fromStopName = "A",
            toStopName = "B",
            toStopLat = 30.2740,
            toStopLng = 120.1550
        )
        val waiting = TrackingSessionState(
            legPhase = LegPhase.WAITING_BOARD,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        assertFalse(
            TransferAutoSwitchManager.isArrivalSatisfied(
                session = waiting,
                leg = leg,
                userLat = 30.2740,
                userLng = 120.1550
            )
        )
    }

    @Test
    fun onBoardPhase_busEventTriggersDropArrival() {
        val leg = TransitLeg(legIndex = 0, fromStopName = "A", toStopName = "B")
        val onBoard = TrackingSessionState(
            legPhase = LegPhase.ON_BOARD,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        assertTrue(TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg))
    }

    @Test
    fun onBoardPhase_locationAssistTriggersDropArrival() {
        // ON_BOARD 阶段：公交未到站，但设备定位（WGS-84）距下车站（GCJ-02）< 150m，辅助判定到达。
        // leg 坐标是 GCJ-02；用户坐标是 WGS-84，判定时会 wgs84ToGcj02 转换对齐。
        val userWgsLat = 30.2740
        val userWgsLng = 120.1550
        val (targetGcjLat, targetGcjLng) = CoordTransform.wgs84ToGcj02(userWgsLat, userWgsLng)
        val leg = TransitLeg(
            legIndex = 0,
            fromStopName = "A",
            toStopName = "B",
            toStopLat = targetGcjLat,
            toStopLng = targetGcjLng
        )
        val onBoard = TrackingSessionState(
            legPhase = LegPhase.ON_BOARD,
            stopsRemaining = 2,
            isBusArrivedAtStop = false
        )
        // 用户定位转换后恰好落在目标站附近，< 150m
        assertTrue(
            TransferAutoSwitchManager.isArrivalSatisfied(
                session = onBoard,
                leg = leg,
                userLat = userWgsLat,
                userLng = userWgsLng
            )
        )

        // 距离很远 -> false
        assertFalse(
            TransferAutoSwitchManager.isArrivalSatisfied(
                session = onBoard,
                leg = leg,
                userLat = 30.3900,
                userLng = 120.2700
            )
        )
    }
}
