package com.trackr.service

import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TrackingSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位跟车检测：[BoardingDetector] 与 [GroupLegTracker] 的定位分支、[LocationCadence] 档位。
 *
 * 路线语义：上车站 A = order 3（坐标 STOP_LAT/STOP_LNG），下车站 B = order 8。
 * 坐标用“A 以北 N 米”表示，纬度每 0.001° ≈ 111 米。
 */
class BoardingDetectionTest {

    private companion object {
        const val STOP_LAT = 31.2300
        const val STOP_LNG = 121.4700
        const val T0 = 1_000_000L
    }

    private fun northOfStop(meters: Int): Double = STOP_LAT + meters / 111_000.0

    private fun bus(
        id: String,
        order: Int,
        metersNorth: Int? = null,
        arrived: Boolean = false,
        progress: Float = 0.5f,
        eta: Int = 300
    ) = LiveBus(
        busId = id,
        plateOrFleetNo = id,
        order = order,
        segmentProgress = progress,
        isArrived = arrived,
        wgsLat = metersNorth?.let { northOfStop(it) } ?: 0.0,
        wgsLng = if (metersNorth != null) STOP_LNG else 0.0,
        etaSeconds = eta
    )

    private fun input(userMetersNorth: Int?, now: Long = T0, accuracy: Float = 10f) = BoardingDetector.Input(
        user = userMetersNorth?.let { BoardingDetector.UserPosition(northOfStop(it), STOP_LNG, accuracy) },
        boardStopLat = STOP_LAT,
        boardStopLng = STOP_LNG,
        nowMillis = now
    )

    private fun waitingSession() = TrackingSessionState(
        isActive = true,
        legPhase = LegPhase.WAITING_BOARD,
        lineName = "156路",
        boardStopOrder = 3,
        boardStopName = "上车站A",
        targetOrder = 8,
        targetStopName = "下车站B",
        totalLegs = 2
    )

    /** 车 b1 到站、用户就在站台：进入待确认 */
    private fun candidateSession(): TrackingSessionState {
        val update = GroupLegTracker.processPoll(
            waitingSession(),
            listOf(bus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 0)
        )
        return GroupLegTracker.planAlerts(waitingSession(), update).state
    }

    @Test
    fun busArrives_userAtStop_startsCandidateInsteadOfBoarding() {
        val update = GroupLegTracker.processPoll(
            waitingSession(),
            listOf(bus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 20)
        )
        assertEquals(LegPhase.WAITING_BOARD, update.state.legPhase)
        assertEquals("b1", update.state.boardingCandidateBusId)
        assertTrue(update.state.isAwaitingBoardingConfirmation)
        assertTrue(update.busArrivedAtBoard)
        assertFalse(update.reachedBoardStop)
        assertNull(update.state.lockedBusId)
    }

    @Test
    fun busArrives_withoutLocation_keepsOriginalBoardingBehavior() {
        val update = GroupLegTracker.processPoll(
            waitingSession(),
            listOf(bus("b1", order = 3, metersNorth = 0, arrived = true)),
            boarding = null
        )
        assertEquals(LegPhase.ON_BOARD, update.state.legPhase)
        assertEquals("b1", update.state.lockedBusId)
        assertTrue(update.reachedBoardStop)
    }

    @Test
    fun candidate_userRidesAwayWithBus_confirmsAfterTwoPolls() {
        val start = candidateSession()

        val first = GroupLegTracker.processPoll(
            start,
            listOf(bus("b1", order = 4, metersNorth = 150)),
            input(userMetersNorth = 150, now = T0 + 10_000)
        )
        assertEquals(LegPhase.WAITING_BOARD, first.state.legPhase)
        assertEquals(1, first.state.boardingConfirmStreak)

        val second = GroupLegTracker.processPoll(
            first.state,
            listOf(bus("b1", order = 4, metersNorth = 320)),
            input(userMetersNorth = 310, now = T0 + 20_000)
        )
        assertEquals(LegPhase.ON_BOARD, second.state.legPhase)
        assertEquals("b1", second.state.lockedBusId)
        assertTrue(second.reachedBoardStop)
        assertNull(second.state.boardingCandidateBusId)
        assertEquals(4, second.state.stopsRemaining)
    }

    @Test
    fun candidate_userStaysAtStopWhileBusLeaves_marksMissedAndWaitsForNextBus() {
        val start = candidateSession()
        assertTrue(start.hasTriggeredArrivedAlert)

        val first = GroupLegTracker.processPoll(
            start,
            listOf(bus("b1", order = 4, metersNorth = 350), bus("b2", order = 1, metersNorth = -2000)),
            input(userMetersNorth = 5, now = T0 + 10_000)
        )
        assertEquals(1, first.state.boardingMissStreak)
        assertEquals(LegPhase.WAITING_BOARD, first.state.legPhase)

        val second = GroupLegTracker.processPoll(
            first.state,
            listOf(bus("b1", order = 5, metersNorth = 700), bus("b2", order = 1, metersNorth = -1800)),
            input(userMetersNorth = 5, now = T0 + 20_000)
        )
        assertTrue(second.missedBoarding)
        assertEquals(LegPhase.WAITING_BOARD, second.state.legPhase)
        assertNull(second.state.boardingCandidateBusId)
        assertEquals("b2", second.state.currentBusId)
        // 新的一班车可以重新提醒
        assertFalse(second.state.hasTriggeredArrivedAlert)
        assertFalse(second.state.hasTriggeredApproachingAlert)
    }

    @Test
    fun busArrives_userFarFromStop_doesNotBoardAndNextBusCanAlertAgain() {
        val arrived = GroupLegTracker.processPoll(
            waitingSession(),
            listOf(bus("b1", order = 3, metersNorth = 0, arrived = true), bus("b2", order = 1, metersNorth = -2000)),
            input(userMetersNorth = 1200)
        )
        assertTrue(arrived.busArrivedAtBoard)
        assertNull(arrived.state.boardingCandidateBusId)
        assertEquals(LegPhase.WAITING_BOARD, arrived.state.legPhase)
        val afterAlert = GroupLegTracker.planAlerts(waitingSession(), arrived)
        assertEquals(GroupLegTracker.AlertKind.BOARD_ARRIVED, afterAlert.alert)

        // b1 开走了，b2 离 A 只剩 1 站：应重新发“即将到站”
        val next = GroupLegTracker.processPoll(
            afterAlert.state,
            listOf(bus("b1", order = 4, metersNorth = 400), bus("b2", order = 2, metersNorth = -300)),
            input(userMetersNorth = 1000, now = T0 + 30_000)
        )
        assertEquals("b2", next.state.currentBusId)
        val nextAlert = GroupLegTracker.planAlerts(afterAlert.state, next)
        assertEquals(GroupLegTracker.AlertKind.BOARD_APPROACHING, nextAlert.alert)
    }

    @Test
    fun candidate_timeoutWithoutFix_boardsOnlyAfterBusLeftStop() {
        val start = candidateSession()

        val stillAtStop = GroupLegTracker.processPoll(
            start,
            listOf(bus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = null, now = T0 + BoardingDetector.CANDIDATE_TIMEOUT_MILLIS + 1)
        )
        assertEquals(LegPhase.WAITING_BOARD, stillAtStop.state.legPhase)

        val left = GroupLegTracker.processPoll(
            stillAtStop.state,
            listOf(bus("b1", order = 5, metersNorth = 900)),
            input(userMetersNorth = null, now = T0 + BoardingDetector.CANDIDATE_TIMEOUT_MILLIS + 10_000)
        )
        assertEquals(LegPhase.ON_BOARD, left.state.legPhase)
        assertEquals("b1", left.state.lockedBusId)
    }

    @Test
    fun candidate_locationBecomesUnavailable_fallsBackToBoarding() {
        val update = GroupLegTracker.processPoll(
            candidateSession(),
            listOf(bus("b1", order = 4, metersNorth = 200)),
            boarding = null
        )
        assertEquals(LegPhase.ON_BOARD, update.state.legPhase)
        assertEquals("b1", update.state.lockedBusId)
    }

    @Test
    fun planAlerts_boardArrivedAlertFiresOnceAcrossCandidateAndConfirmation() {
        val waiting = waitingSession()
        val arrival = GroupLegTracker.processPoll(
            waiting,
            listOf(bus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 0)
        )
        val first = GroupLegTracker.planAlerts(waiting, arrival)
        assertEquals(GroupLegTracker.AlertKind.BOARD_ARRIVED, first.alert)

        val pending = GroupLegTracker.processPoll(
            first.state,
            listOf(bus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 0, now = T0 + 5_000)
        )
        assertNull(GroupLegTracker.planAlerts(first.state, pending).alert)

        val c1 = GroupLegTracker.processPoll(
            pending.state,
            listOf(bus("b1", order = 4, metersNorth = 150)),
            input(userMetersNorth = 150, now = T0 + 10_000)
        )
        val c2 = GroupLegTracker.processPoll(
            c1.state,
            listOf(bus("b1", order = 4, metersNorth = 300)),
            input(userMetersNorth = 300, now = T0 + 15_000)
        )
        val boarded = GroupLegTracker.planAlerts(c1.state, c2)
        assertNull(boarded.alert)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        // 进入 ON_BOARD 后下车站提醒标志重置
        assertFalse(boarded.state.hasTriggeredArrivedAlert)
        assertFalse(boarded.state.hasTriggeredApproachingAlert)
    }

    @Test
    fun onBoard_wrongLockedBus_relocksAfterThreeConsistentPolls() {
        var session = waitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "wrong",
            currentBusId = "wrong"
        )
        val buses = listOf(
            bus("wrong", order = 5, metersNorth = 3000),
            bus("right", order = 5, metersNorth = 1520)
        )
        repeat(2) { i ->
            val update = GroupLegTracker.processPoll(session, buses, input(userMetersNorth = 1500, now = T0 + i * 10_000L))
            assertFalse(update.relocked)
            assertEquals("wrong", update.state.lockedBusId)
            session = update.state
        }
        val third = GroupLegTracker.processPoll(session, buses, input(userMetersNorth = 1500, now = T0 + 30_000))
        assertTrue(third.relocked)
        assertEquals("right", third.state.lockedBusId)
        assertEquals("right", third.state.currentBusId)
    }

    @Test
    fun onBoard_lockedBusNearUser_neverRelocks() {
        var session = waitingSession().copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "mine")
        val buses = listOf(bus("mine", order = 5, metersNorth = 1500), bus("other", order = 5, metersNorth = 1510))
        repeat(5) { i ->
            val update = GroupLegTracker.processPoll(session, buses, input(userMetersNorth = 1505, now = T0 + i * 10_000L))
            assertFalse(update.relocked)
            session = update.state
        }
        assertEquals("mine", session.lockedBusId)
    }

    @Test
    fun confirmBoarded_prefersBusAwaitingConfirmation() {
        val candidate = candidateSession()
        val buses = listOf(
            bus("b1", order = 4, metersNorth = 200),
            bus("closer", order = 3, metersNorth = -10, progress = 0.99f)
        )
        val confirmed = GroupLegTracker.confirmBoarded(candidate, buses)
        assertEquals(LegPhase.ON_BOARD, confirmed.legPhase)
        assertEquals("b1", confirmed.lockedBusId)
        assertNull(confirmed.boardingCandidateBusId)
    }

    @Test
    fun shouldStartCandidate_respectsRadius() {
        assertTrue(BoardingDetector.shouldStartCandidate(input(userMetersNorth = BoardingDetector.CANDIDATE_RADIUS_METERS - 20)))
        assertFalse(BoardingDetector.shouldStartCandidate(input(userMetersNorth = BoardingDetector.CANDIDATE_RADIUS_METERS + 50)))
        // 暂无定位：先进入待确认
        assertTrue(BoardingDetector.shouldStartCandidate(input(userMetersNorth = null)))
    }

    @Test
    fun locationCadence_followsTrackingPhase() {
        val waiting = waitingSession()
        assertEquals(LocationCadence.Tier.OFF, LocationCadence.tierFor(waiting.copy(stopsRemaining = 6, etaSeconds = 900)))
        assertEquals(LocationCadence.Tier.WAITING_NEAR, LocationCadence.tierFor(waiting.copy(stopsRemaining = 2)))
        assertEquals(LocationCadence.Tier.WAITING_NEAR, LocationCadence.tierFor(waiting.copy(stopsRemaining = 5, etaSeconds = 120)))
        assertEquals(LocationCadence.Tier.BOARDING_CHECK, LocationCadence.tierFor(waiting.copy(boardingCandidateBusId = "b1")))
        val riding = waiting.copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "b1")
        assertEquals(LocationCadence.Tier.RIDING, LocationCadence.tierFor(riding.copy(stopsRemaining = 5)))
        assertEquals(LocationCadence.Tier.NEAR_DROP, LocationCadence.tierFor(riding.copy(stopsRemaining = 1)))
        assertEquals(LocationCadence.Tier.OFF, LocationCadence.tierFor(waiting.copy(legPhase = LegPhase.SINGLE)))
        assertEquals(LocationCadence.Tier.OFF, LocationCadence.tierFor(waiting.copy(isActive = false)))
    }
}
