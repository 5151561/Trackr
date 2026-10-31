package com.trackr.service

import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TrackingSessionState

/**
 * 分组换乘单程两阶段追踪的纯函数核心（选车 + 阶段流转）。
 *
 * 该对象不依赖 Android 环境，便于单元测试：
 * - WAITING_BOARD（等车）：目标是上车站 A（boardStopOrder）。只考虑 order<=A 的车，
 *   取离 A 最近的一辆；该车到 A 后锁定其 busId 并进入 ON_BOARD。
 * - ON_BOARD（已上车）：只跟踪锁定的 busId，目标下车站 B（targetOrder）；
 *   锁定车辆从数据里消失时保持上次状态并标记信号丢失，绝不换成别的车。
 */
object GroupLegTracker {

    /**
     * 单次轮询结果。
     * @param state 更新后的追踪状态
     * @param reachedBoardStop WAITING_BOARD 阶段锁定车刚到达上车站（本次刚切入 ON_BOARD）
     * @param reachedDropStop ON_BOARD 阶段锁定车到达下车站（应切下一程 / 完成）
     */
    data class LegUpdate(
        val state: TrackingSessionState,
        val reachedBoardStop: Boolean = false,
        val reachedDropStop: Boolean = false
    )

    /** 到站判定：stopsRemaining==0 且（已停靠 或 ETA<=45s） */
    fun isArrivedAtOrder(targetOrder: Int, bus: LiveBus): Boolean {
        val stopsRemaining = (targetOrder - bus.order).coerceAtLeast(0)
        if (stopsRemaining != 0) return false
        val stopped = bus.isArrived || bus.segmentProgress >= 0.98f
        return stopped || (bus.etaSeconds in 0..45)
    }

    /**
     * 处理一次分组换乘单程的轮询。
     *
     * @param session 当前会话状态（legPhase 必须为 WAITING_BOARD 或 ON_BOARD）
     * @param buses 本次实时数据中该线路的在途车辆
     */
    fun processPoll(session: TrackingSessionState, buses: List<LiveBus>): LegUpdate {
        return when (session.legPhase) {
            LegPhase.WAITING_BOARD -> processWaitingBoard(session, buses)
            LegPhase.ON_BOARD -> processOnBoard(session, buses)
            LegPhase.SINGLE -> LegUpdate(session) // 不应走到这里
        }
    }

    private fun processWaitingBoard(session: TrackingSessionState, buses: List<LiveBus>): LegUpdate {
        val boardOrder = session.boardStopOrder
        // 只考虑尚未开过上车站 A 的车（order <= A），取离 A 最近的一辆
        val candidates = buses.filter { it.order <= boardOrder }
        val best = candidates.maxByOrNull { it.order.toFloat() + it.segmentProgress }

        if (best == null) {
            return LegUpdate(
                session.copy(
                    currentBusId = null,
                    stopsRemaining = -1,
                    etaSeconds = -1,
                    statusSummary = "暂无开往上车站的在途车辆，等待下一班",
                    isSignalLost = false,
                    updatedAtMillis = now()
                )
            )
        }

        val stopsRemaining = (boardOrder - best.order).coerceAtLeast(0)
        val arrivedBoard = isArrivedAtOrder(boardOrder, best)

        if (arrivedBoard) {
            // 车到上车站 A，锁定该车并进入 ON_BOARD
            val locked = session.copy(
                legPhase = LegPhase.ON_BOARD,
                lockedBusId = best.busId,
                currentBusId = best.busId,
                currentBusOrder = best.order,
                currentBusProgress = best.segmentProgress,
                startTrackingOrder = boardOrder,
                stopsRemaining = (session.targetOrder - best.order).coerceAtLeast(0),
                etaSeconds = best.etaSeconds,
                isBusArrivedAtStop = false,
                isSignalLost = false,
                statusSummary = "已上车，距下车站 ${session.targetStopName} 还有 ${(session.targetOrder - best.order).coerceAtLeast(0)} 站",
                updatedAtMillis = now()
            )
            return LegUpdate(locked, reachedBoardStop = true)
        }

        val etaText = formatEtaSeconds(best.etaSeconds)
        val summary = if (etaText.isNotEmpty()) {
            "${session.lineName} 距上车站 ${session.boardStopName} 还有 $stopsRemaining 站 · $etaText"
        } else {
            "${session.lineName} 距上车站 ${session.boardStopName} 还有 $stopsRemaining 站"
        }
        // 等车区间起点：选中这辆车时它所在的站（只供通知进度条使用，换车时重新记录）
        val waitStartOrder = if (session.currentBusId != best.busId) {
            (best.order - 1).coerceIn(1, (boardOrder - 1).coerceAtLeast(1))
        } else {
            session.startTrackingOrder
        }
        return LegUpdate(
            session.copy(
                startTrackingOrder = waitStartOrder,
                currentBusId = best.busId,
                currentBusOrder = best.order,
                currentBusProgress = best.segmentProgress,
                stopsRemaining = stopsRemaining,
                etaSeconds = best.etaSeconds,
                isBusArrivedAtStop = false,
                isSignalLost = false,
                statusSummary = summary,
                updatedAtMillis = now()
            )
        )
    }

    private fun processOnBoard(session: TrackingSessionState, buses: List<LiveBus>): LegUpdate {
        val lockedId = session.lockedBusId
        val locked = buses.find { it.busId == lockedId }

        if (locked == null) {
            // 锁定车辆从数据里消失：保持上次状态，提示信号丢失，绝不换车
            return LegUpdate(
                session.copy(
                    isSignalLost = true,
                    statusSummary = "锁定车辆信号丢失，保持上次位置（距 ${session.targetStopName}）",
                    updatedAtMillis = now()
                )
            )
        }

        val targetOrder = session.targetOrder
        val stopsRemaining = (targetOrder - locked.order).coerceAtLeast(0)
        val arrivedDrop = isArrivedAtOrder(targetOrder, locked)

        val etaText = formatEtaSeconds(locked.etaSeconds)
        val summary = when {
            arrivedDrop -> "已到达下车站 ${session.targetStopName}"
            etaText.isNotEmpty() -> "距下车站 ${session.targetStopName} 还有 $stopsRemaining 站 · $etaText"
            else -> "距下车站 ${session.targetStopName} 还有 $stopsRemaining 站"
        }

        return LegUpdate(
            session.copy(
                currentBusId = locked.busId,
                currentBusOrder = locked.order,
                currentBusProgress = locked.segmentProgress,
                stopsRemaining = stopsRemaining,
                etaSeconds = locked.etaSeconds,
                isBusArrivedAtStop = arrivedDrop,
                isSignalLost = false,
                statusSummary = summary,
                updatedAtMillis = now()
            ),
            reachedDropStop = arrivedDrop
        )
    }

    private fun formatEtaSeconds(etaSeconds: Int): String {
        if (etaSeconds <= 0) return ""
        val minutes = (etaSeconds / 60.0).let { Math.round(it).toInt() }.coerceAtLeast(1)
        return "约 $minutes 分钟"
    }

    private fun now(): Long = System.currentTimeMillis()
}
