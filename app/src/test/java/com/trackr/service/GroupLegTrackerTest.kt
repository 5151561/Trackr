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
}
