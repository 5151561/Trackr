package com.trackr.service

import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TrackingSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单线路候车追踪核心（纯函数）单元测试
 *
 * 路线语义：目标候车站 = order 10，提前 2 站提醒。
 */
class SingleLegTrackerTest {

    private fun bus(id: String, order: Int, arrived: Boolean = false, progress: Float = 0.5f, eta: Int = 300) =
        LiveBus(
            busId = id,
            plateOrFleetNo = id,
            order = order,
            segmentProgress = progress,
            isArrived = arrived,
            etaSeconds = eta
        )

    private fun session() = TrackingSessionState(
        isActive = true,
        lineName = "49路",
        targetStopName = "上海体育馆",
        targetOrder = 10,
        startTrackingOrder = 9,
        alertThresholdStops = 2
    )

    @Test
    fun picksNearestBusNotPassedTarget() {
        val update = SingleLegTracker.processPoll(
            session(),
            listOf(bus("passed", order = 11), bus("far", order = 3), bus("near", order = 6))
        )
        assertEquals("near", update.state.currentBusId)
        assertEquals(4, update.state.stopsRemaining)
        assertNull(update.alert)
    }

    @Test
    fun approachingThenArrived_eachAlertOnce() {
        val first = SingleLegTracker.processPoll(session(), listOf(bus("b1", order = 8)))
        assertEquals(SingleLegTracker.AlertKind.APPROACHING, first.alert)
        assertTrue(first.state.hasTriggeredApproachingAlert)

        val second = SingleLegTracker.processPoll(first.state, listOf(bus("b1", order = 9)))
        assertNull(second.alert)

        val arrived = SingleLegTracker.processPoll(second.state, listOf(bus("b1", order = 10, arrived = true, progress = 1f)))
        assertEquals(SingleLegTracker.AlertKind.ARRIVED, arrived.alert)
        assertTrue(arrived.state.isBusArrivedAtStop)
        assertTrue(arrived.state.hasTriggeredArrivedAlert)

        val again = SingleLegTracker.processPoll(arrived.state, listOf(bus("b1", order = 10, arrived = true, progress = 1f)))
        assertNull(again.alert)
    }

    @Test
    fun etaZeroAndDefaultProgress_isNotArrival() {
        // 坐标缺失时 progress 默认 1.0、eta=0 无预测：只能算“即将进站”，不能发到站提醒
        val update = SingleLegTracker.processPoll(session(), listOf(bus("b1", order = 10, progress = 1f, eta = 0)))
        assertFalse(update.state.isBusArrivedAtStop)
        assertEquals(SingleLegTracker.AlertKind.APPROACHING, update.alert)
    }

    @Test
    fun switchingBusAfterPassingTarget_resetsAlertFlags() {
        val arrived = SingleLegTracker.processPoll(session(), listOf(bus("b1", order = 10, arrived = true, progress = 1f)))
        assertTrue(arrived.state.hasTriggeredArrivedAlert)

        // b1 开过目标站，b2 在 order 9：换车并重新提醒
        val switched = SingleLegTracker.processPoll(arrived.state, listOf(bus("b1", order = 11), bus("b2", order = 9)))
        assertEquals("b2", switched.state.currentBusId)
        assertEquals(SingleLegTracker.AlertKind.APPROACHING, switched.alert)
        assertTrue(switched.state.hasTriggeredApproachingAlert)
        assertFalse(switched.state.hasTriggeredArrivedAlert)
    }

    @Test
    fun trackedBusBrieflyMissing_keepsSameBusForTwoPolls() {
        val tracking = SingleLegTracker.processPoll(session(), listOf(bus("b1", order = 5))).state
        assertEquals("b1", tracking.currentBusId)

        val miss1 = SingleLegTracker.processPoll(tracking, listOf(bus("b2", order = 7)))
        assertEquals("b1", miss1.state.currentBusId)
        assertEquals(1, miss1.state.missingBusPolls)
        assertNull(miss1.alert)

        val miss2 = SingleLegTracker.processPoll(miss1.state, listOf(bus("b2", order = 7)))
        assertEquals("b1", miss2.state.currentBusId)
        assertEquals(2, miss2.state.missingBusPolls)

        // 第三轮仍不见：换车
        val miss3 = SingleLegTracker.processPoll(miss2.state, listOf(bus("b2", order = 7)))
        assertEquals("b2", miss3.state.currentBusId)
        assertEquals(0, miss3.state.missingBusPolls)
    }

    @Test
    fun trackedBusReappears_resetsMissingCounter() {
        val tracking = SingleLegTracker.processPoll(session(), listOf(bus("b1", order = 5))).state
        val miss1 = SingleLegTracker.processPoll(tracking, emptyList())
        val back = SingleLegTracker.processPoll(miss1.state, listOf(bus("b1", order = 6)))
        assertEquals("b1", back.state.currentBusId)
        assertEquals(0, back.state.missingBusPolls)
    }

    @Test
    fun noBus_notRunningLine_showsOperatingState() {
        val update = SingleLegTracker.processPoll(
            session(),
            emptyList(),
            operatingState = LineOperatingState.ENDED,
            statusDescription = ""
        )
        assertNull(update.state.currentBusId)
        assertEquals(-1, update.state.stopsRemaining)
        assertEquals(LineOperatingState.ENDED.defaultLabel, update.state.statusSummary)
    }
}
