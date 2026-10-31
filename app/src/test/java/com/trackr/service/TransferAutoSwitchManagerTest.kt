package com.trackr.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitLeg
import com.trackr.util.CoordTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
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

    @After
    fun tearDown() {
        TransferAutoSwitchManager.clearUserFix()
        TransferAutoSwitchManager.elapsedRealtimeNanosProvider = { android.os.SystemClock.elapsedRealtimeNanos() }
    }

    private val now = 1_000_000_000_000L

    private fun fix(lat: Double, lng: Double, accuracy: Float? = 10f, ageSeconds: Long = 5) =
        TransferAutoSwitchManager.UserFix(lat, lng, accuracy, now - ageSeconds * 1_000_000_000L)

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
                userFix = fix(30.2740, 120.1550),
                nowElapsedNanos = now
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
                userFix = fix(userWgsLat, userWgsLng),
                nowElapsedNanos = now
            )
        )

        // 距离很远 -> false
        assertFalse(
            TransferAutoSwitchManager.isArrivalSatisfied(
                session = onBoard,
                leg = leg,
                userFix = fix(30.3900, 120.2700),
                nowElapsedNanos = now
            )
        )
    }

    private fun legAtUser(): Pair<TransitLeg, Pair<Double, Double>> {
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
        return leg to (userWgsLat to userWgsLng)
    }

    @Test
    fun onBoardPhase_staleOrInaccurateFix_doesNotTriggerDropArrival() {
        val (leg, user) = legAtUser()
        val onBoard = TrackingSessionState(legPhase = LegPhase.ON_BOARD, stopsRemaining = 2)

        // 定位超过 60 秒
        assertFalse(
            TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg, fix(user.first, user.second, ageSeconds = 61), now)
        )
        // 精度差于 50m
        assertFalse(
            TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg, fix(user.first, user.second, accuracy = 80f), now)
        )
        // 精度未知
        assertFalse(
            TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg, fix(user.first, user.second, accuracy = null), now)
        )
        // 边界：60 秒、50m 仍可用
        assertTrue(
            TransferAutoSwitchManager.isArrivalSatisfied(
                onBoard, leg, fix(user.first, user.second, accuracy = 50f, ageSeconds = 60), now
            )
        )
    }

    @Test
    fun legacyUpdateUserLocation_noLongerParticipates_andFixIsClearedOnStop() {
        val (leg, user) = legAtUser()
        val onBoard = TrackingSessionState(legPhase = LegPhase.ON_BOARD, stopsRemaining = 2)
        TransferAutoSwitchManager.elapsedRealtimeNanosProvider = { now }

        // 旧接口只有经纬度：不参与判定
        TransferAutoSwitchManager.updateUserLocation(user.first, user.second)
        assertFalse(TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg))

        // 新接口带精度和时刻：参与判定
        TransferAutoSwitchManager.updateUserFix(user.first, user.second, 10f, now - 1_000_000_000L)
        assertTrue(TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg))

        // 停止追踪后清空
        TransferAutoSwitchManager.stopGroupTracking(context)
        assertFalse(TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg))
    }

    @Test
    fun onBoardPhase_etaZeroIsNotArrival() {
        // etaSeconds<=0 表示无预测，不能当成到站
        val leg = TransitLeg(legIndex = 0, fromStopName = "A", toStopName = "B")
        val onBoard = TrackingSessionState(legPhase = LegPhase.ON_BOARD, stopsRemaining = 0, etaSeconds = 0)
        assertFalse(TransferAutoSwitchManager.isArrivalSatisfied(onBoard, leg, userFix = null))
        assertTrue(TransferAutoSwitchManager.isArrivalSatisfied(onBoard.copy(etaSeconds = 30), leg, userFix = null))
    }

    @Test
    fun pollingInterval_backsOffOnFailures_capsAt120s_andResetsOnSuccess() {
        val far = TrackingSessionState(stopsRemaining = 7, etaSeconds = 1200)
        assertEquals(90_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(far, consecutiveFailures = 1))
        assertEquals(120_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(far, consecutiveFailures = 2))
        assertEquals(120_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(far, consecutiveFailures = 30))

        val near = TrackingSessionState(stopsRemaining = 1, etaSeconds = 120)
        assertEquals(20_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(near, consecutiveFailures = 1))
        assertEquals(40_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(near, consecutiveFailures = 2))
        // 成功后（失败次数清零）恢复正常间隔
        assertEquals(10_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(near, consecutiveFailures = 0))
    }

    @Test
    fun pollingInterval_notRunningLine_pollsEveryFiveMinutes() {
        val session = TrackingSessionState(stopsRemaining = -1, etaSeconds = -1)
        assertEquals(
            300_000L,
            TransferAutoSwitchManager.calculateAdaptivePollingInterval(session, operatingState = LineOperatingState.ENDED)
        )
        assertEquals(
            300_000L,
            TransferAutoSwitchManager.calculateAdaptivePollingInterval(session, operatingState = LineOperatingState.WAITING)
        )
    }

    @Test
    fun pollingInterval_signalLost_atLeast30s() {
        val lost = TrackingSessionState(stopsRemaining = 1, etaSeconds = 60, isSignalLost = true)
        assertEquals(30_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(lost))
        val lostFar = TrackingSessionState(stopsRemaining = 7, isSignalLost = true)
        assertEquals(45_000L, TransferAutoSwitchManager.calculateAdaptivePollingInterval(lostFar))
    }
}
