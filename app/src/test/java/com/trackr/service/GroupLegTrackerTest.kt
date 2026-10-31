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
 * 分组换乘两阶段选车 + 流转核心（纯函数）单元测试
 *
 * 路线语义：上车站 A = order 3，下车站 B = order 8。
 */
class GroupLegTrackerTest {

    private fun bus(id: String, order: Int, arrived: Boolean = false, progress: Float = 0.5f, eta: Int = 300) =
        LiveBus(
            busId = id,
            plateOrFleetNo = id,
            order = order,
            segmentProgress = progress,
            isArrived = arrived,
            etaSeconds = eta
        )

    private fun waitingSession() = TrackingSessionState(
        isActive = true,
        legPhase = LegPhase.WAITING_BOARD,
        lineName = "156路",
        boardStopOrder = 3,
        boardStopName = "上车站A",
        targetOrder = 8,
        targetStopName = "下车站B"
    )

    @Test
    fun waitingBoard_ignoresBusThatAlreadyPassedBoardStop() {
        // 人在 A（order 3）等车，前方一辆车已过 A（order 5），不应被选中
        val session = waitingSession()
        val buses = listOf(
            bus("passed", order = 5, progress = 0.3f), // 已过 A
            bus("coming", order = 2, progress = 0.4f)  // 还没到 A
        )
        val update = GroupLegTracker.processPoll(session, buses)
        // 选中的应是还没到 A 的那辆
        assertEquals("coming", update.state.currentBusId)
        assertFalse(update.reachedBoardStop)
        assertEquals(LegPhase.WAITING_BOARD, update.state.legPhase)
    }

    @Test
    fun waitingBoard_recordsWaitStartOrderForNotificationProgress() {
        // 选中车辆时记录它所在的站作为等车区间起点；同一辆车后续轮询保持不变
        val first = GroupLegTracker.processPoll(waitingSession(), listOf(bus("coming", order = 2, progress = 0.4f)))
        assertEquals(1, first.state.startTrackingOrder)
        val second = GroupLegTracker.processPoll(first.state, listOf(bus("coming", order = 3, progress = 0.2f)))
        assertEquals(1, second.state.startTrackingOrder)
    }

    @Test
    fun waitingBoard_noEligibleBus_whenAllPassedBoardStop() {
        val session = waitingSession()
        val buses = listOf(bus("passed", order = 6))
        val update = GroupLegTracker.processPoll(session, buses)
        assertNull(update.state.currentBusId)
        assertFalse(update.reachedBoardStop)
    }

    @Test
    fun waitingBoard_lockBusWhenArriveAtBoardStop() {
        // 车到达 A（order==3 且已停靠）-> 锁定该车，进入 ON_BOARD
        val session = waitingSession()
        val buses = listOf(bus("busX", order = 3, arrived = true, progress = 1.0f, eta = 0))
        val update = GroupLegTracker.processPoll(session, buses)
        assertTrue(update.reachedBoardStop)
        assertEquals(LegPhase.ON_BOARD, update.state.legPhase)
        assertEquals("busX", update.state.lockedBusId)
    }

    @Test
    fun onBoard_tracksOnlyLockedBus_andReachesDropStop() {
        val onBoard = waitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "busX"
        )
        // 另一辆非锁定车已到 B，不应触发切程
        val otherAtB = listOf(
            bus("other", order = 8, arrived = true, progress = 1.0f, eta = 0),
            bus("busX", order = 5, progress = 0.5f, eta = 300)
        )
        val update1 = GroupLegTracker.processPoll(onBoard, otherAtB)
        assertFalse(update1.reachedDropStop)
        assertEquals("busX", update1.state.currentBusId)

        // 锁定车 busX 到达 B -> 触发切程 / 完成
        val lockedAtB = listOf(bus("busX", order = 8, arrived = true, progress = 1.0f, eta = 0))
        val update2 = GroupLegTracker.processPoll(onBoard, lockedAtB)
        assertTrue(update2.reachedDropStop)
    }

    @Test
    fun onBoard_lockedBusDisappears_keepsStateAndSignalsLost() {
        val onBoard = waitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "busX",
            stopsRemaining = 2,
            statusSummary = "距下车站B 还有 2 站"
        )
        // 锁定车从数据消失，只有别的车
        val update = GroupLegTracker.processPoll(onBoard, listOf(bus("other", order = 7)))
        assertFalse(update.reachedDropStop)
        assertTrue(update.state.isSignalLost)
        // 不换成别的车
        assertEquals("busX", update.state.lockedBusId)
    }

    // ---------------- 到站口径统一 ----------------

    @Test
    fun arrival_etaZeroMeansNoPrediction_notArrived() {
        // eta=0 表示无预测；坐标缺失时 progress 默认 1.0 也不可信
        val noPrediction = bus("b", order = 3, progress = 1.0f, eta = 0)
        assertFalse(GroupLegTracker.isArrivedAtOrder(3, noPrediction))
        assertTrue(GroupLegTracker.isArrivedAtOrder(3, noPrediction.copy(etaSeconds = 30)))
    }

    @Test
    fun arrival_progressTrustedOnlyWithValidCoordinates() {
        val noCoords = bus("b", order = 3, progress = 1.0f, eta = 300)
        assertFalse(noCoords.hasValidProgress)
        assertFalse(GroupLegTracker.isArrivedAtOrder(3, noCoords))

        val withCoords = noCoords.copy(wgsLat = 30.27, wgsLng = 120.15)
        assertTrue(withCoords.hasValidProgress)
        assertTrue(GroupLegTracker.isArrivedAtOrder(3, withCoords))
    }

    @Test
    fun waitingBoard_missingCoordinatesDefaultProgress_doesNotLockBus() {
        // 车在 A 前一段、坐标缺失（progress 默认 1.0），不应误判到站并锁车
        val update = GroupLegTracker.processPoll(waitingSession(), listOf(bus("b", order = 3, progress = 1.0f, eta = 200)))
        assertFalse(update.reachedBoardStop)
        assertEquals(LegPhase.WAITING_BOARD, update.state.legPhase)
    }

    @Test
    fun onBoard_lockedBusPassedDropStop_countsAsArrived() {
        val onBoard = waitingSession().copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "busX")
        val update = GroupLegTracker.processPoll(onBoard, listOf(bus("busX", order = 9, progress = 0.2f, eta = -1)))
        assertTrue(update.reachedDropStop)
    }

    // ---------------- 手动“我已上车”选车 ----------------

    @Test
    fun pickBoardedBus_prefersBusWithinAToAPlusOneNearestToA() {
        val buses = listOf(
            bus("next", order = 2, progress = 0.5f),           // 下一班，还没到 A
            bus("justLeft", order = 4, progress = 0.1f).copy(wgsLat = 30.0, wgsLng = 120.0), // 刚离开 A
            bus("farAhead", order = 6, progress = 0.5f)        // 早就开走
        )
        assertEquals("justLeft", GroupLegTracker.pickBoardedBus(3, buses)?.busId)
    }

    @Test
    fun pickBoardedBus_busStoppedAtA_wins() {
        val buses = listOf(
            bus("atA", order = 3, arrived = true, progress = 1.0f),
            bus("justLeft", order = 4, progress = 0.3f).copy(wgsLat = 30.0, wgsLng = 120.0)
        )
        assertEquals("atA", GroupLegTracker.pickBoardedBus(3, buses)?.busId)
    }

    @Test
    fun pickBoardedBus_noneInRange_returnsNull() {
        val buses = listOf(bus("next", order = 2), bus("farAhead", order = 6))
        assertNull(GroupLegTracker.pickBoardedBus(3, buses))
    }

    @Test
    fun confirmBoarded_locksPickedBus_insteadOfWaitingCandidate() {
        // 等车阶段 currentBusId 已经变成下一班（order 2），用户实际坐的是刚离开 A 的车
        val waiting = waitingSession().copy(currentBusId = "next")
        val buses = listOf(
            bus("next", order = 2, progress = 0.5f),
            bus("mine", order = 4, progress = 0.2f).copy(wgsLat = 30.0, wgsLng = 120.0)
        )
        val confirmed = GroupLegTracker.confirmBoarded(waiting, buses)
        assertEquals(LegPhase.ON_BOARD, confirmed.legPhase)
        assertEquals("mine", confirmed.lockedBusId)
        assertEquals(3, confirmed.startTrackingOrder)
        assertFalse(confirmed.hasTriggeredApproachingAlert)
        assertFalse(confirmed.hasTriggeredArrivedAlert)
    }

    @Test
    fun confirmBoarded_withoutBus_relocksOnNextPoll() {
        val confirmed = GroupLegTracker.confirmBoarded(waitingSession(), emptyList())
        assertEquals(LegPhase.ON_BOARD, confirmed.legPhase)
        assertNull(confirmed.lockedBusId)

        // 下一次轮询：按同样规则补锁
        val update = GroupLegTracker.processPoll(
            confirmed,
            listOf(bus("next", order = 2), bus("mine", order = 4, progress = 0.4f))
        )
        assertEquals("mine", update.state.lockedBusId)
        assertEquals("mine", update.state.currentBusId)
        assertFalse(update.state.isSignalLost)
        assertEquals(4, update.state.stopsRemaining)
    }

    @Test
    fun onBoard_nullLockedBus_noCandidate_keepsWaitingForLock() {
        val confirmed = GroupLegTracker.confirmBoarded(waitingSession(), emptyList())
        val update = GroupLegTracker.processPoll(confirmed, listOf(bus("farAhead", order = 7)))
        assertNull(update.state.lockedBusId)
        assertFalse(update.reachedDropStop)
    }

    // ---------------- 提醒编排 ----------------

    @Test
    fun planAlerts_boardArrival_firesBoardAlertThenResetsFlags_andSkipsDropApproaching() {
        // 这一程只有 2 站（A=3, B=5），阈值 2：旧逻辑会在上车同一轮立刻发“即将到下车站”
        val waiting = waitingSession().copy(targetOrder = 5, alertThresholdStops = 2, hasTriggeredApproachingAlert = true)
        val update = GroupLegTracker.processPoll(waiting, listOf(bus("busX", order = 3, arrived = true, progress = 1.0f)))
        assertTrue(update.reachedBoardStop)

        val decision = GroupLegTracker.planAlerts(waiting, update)
        assertEquals(GroupLegTracker.AlertKind.BOARD_ARRIVED, decision.alert)
        assertEquals("上车站A", decision.alertState?.targetStopName)
        assertEquals(LegPhase.ON_BOARD, decision.state.legPhase)
        assertFalse(decision.state.hasTriggeredApproachingAlert)
        assertFalse(decision.state.hasTriggeredArrivedAlert)

        // 下一轮才做下车站临近判定
        val next = GroupLegTracker.processPoll(decision.state, listOf(bus("busX", order = 4, progress = 0.3f)))
        val nextDecision = GroupLegTracker.planAlerts(decision.state, next)
        assertEquals(GroupLegTracker.AlertKind.DROP_APPROACHING, nextDecision.alert)
        assertTrue(nextDecision.state.hasTriggeredApproachingAlert)
    }

    @Test
    fun planAlerts_boardApproaching_usesBoardStopName_once() {
        val waiting = waitingSession()
        val update = GroupLegTracker.processPoll(waiting, listOf(bus("coming", order = 2, progress = 0.4f)))
        val decision = GroupLegTracker.planAlerts(waiting, update)
        assertEquals(GroupLegTracker.AlertKind.BOARD_APPROACHING, decision.alert)
        assertEquals("上车站A", decision.alertState?.targetStopName)

        val again = GroupLegTracker.processPoll(decision.state, listOf(bus("coming", order = 2, progress = 0.6f)))
        assertNull(GroupLegTracker.planAlerts(decision.state, again).alert)
    }

    @Test
    fun planAlerts_dropArrival_notLastLeg_noArrivedAlert_lastLeg_arrivedAlert() {
        val onBoard = waitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "busX",
            currentLegIndex = 0,
            totalLegs = 2
        )
        val atB = listOf(bus("busX", order = 8, arrived = true, progress = 1.0f))
        val transfer = GroupLegTracker.planAlerts(onBoard, GroupLegTracker.processPoll(onBoard, atB))
        // 非最后一程：只由换乘引擎发换乘提醒
        assertNull(transfer.alert)
        assertTrue(transfer.state.hasTriggeredArrivedAlert)

        val lastLeg = onBoard.copy(currentLegIndex = 1)
        val finish = GroupLegTracker.planAlerts(lastLeg, GroupLegTracker.processPoll(lastLeg, atB))
        assertEquals(GroupLegTracker.AlertKind.DROP_ARRIVED, finish.alert)
    }

    @Test
    fun planAlerts_signalLost_noDropApproaching() {
        val onBoard = waitingSession().copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "busX", stopsRemaining = 1)
        val update = GroupLegTracker.processPoll(onBoard, emptyList())
        assertTrue(update.state.isSignalLost)
        assertNull(GroupLegTracker.planAlerts(onBoard, update).alert)
    }
}
