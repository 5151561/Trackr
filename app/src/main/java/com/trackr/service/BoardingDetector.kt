package com.trackr.service

import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TrackingSessionState
import kotlin.math.max

/**
 * 定位跟车检测（纯函数，不依赖 Android）。
 *
 * 只用于分组换乘，并且只在有“新鲜且精确”的手机定位时启用；没有定位时由 [GroupLegTracker] 保持原有行为
 * （车到上车站 A 即视为上车）。坐标一律用 WGS-84：手机系统定位、车辆 wgsLat/wgsLng、站点 wgsLat/wgsLng。
 *
 * 状态流转（都在 WAITING_BOARD 内部用 [TrackingSessionState.boardingCandidateBusId] 表示“待确认”）：
 * 1. 车到 A：用户离 A ≤ [CANDIDATE_RADIUS_METERS] → 进入待确认；离得更远 → 不算上车，继续等下一班。
 * 2. 待确认 → 上车：连续 [CONFIRM_STREAK] 次满足“人车距离 ≤ max(80m, 2×精度) 且人已离开 A ≥ 100m”，
 *    或“人离 A ≥ 400m 且车也已离开 A”。
 * 3. 待确认 → 没赶上：连续 [MISS_STREAK] 次满足“人仍在 A 120m 内，车已离 A ≥ 300m（无车辆坐标时按站序已过 2 站）”。
 * 4. 待确认超时（[CANDIDATE_TIMEOUT_MILLIS]）仍无结论：车已离开 A 就按已上车处理（与无定位时一致），否则继续等。
 * 5. ON_BOARD 错锁自愈：锁定车离用户 > 500m（或已从数据消失），同时另一辆车离用户 ≤ 80m，连续 [RELOCK_STREAK] 次 → 改锁。
 */
object BoardingDetector {

    const val CANDIDATE_RADIUS_METERS = 300
    const val CONFIRM_STREAK = 2
    const val MISS_STREAK = 2
    const val RELOCK_STREAK = 3
    const val CANDIDATE_TIMEOUT_MILLIS = 5 * 60_000L

    private const val NEAR_BUS_MIN_METERS = 80
    private const val LEFT_BOARD_STOP_METERS = 100
    private const val FAR_FROM_BOARD_STOP_METERS = 400
    private const val STILL_AT_BOARD_STOP_METERS = 120
    private const val BUS_GONE_FROM_BOARD_STOP_METERS = 300
    private const val WRONG_LOCK_METERS = 500

    /** 一个已经通过新鲜度与精度过滤的用户位置 */
    data class UserPosition(val lat: Double, val lng: Double, val accuracyMeters: Float)

    /**
     * 一次轮询的定位上下文。
     * @param user 新鲜且精确的用户位置；null 表示本轮没有可用定位（不改变任何连续计数）
     * @param boardStopLat 上车站 A 坐标（WGS-84），无效时为 0
     */
    data class Input(
        val user: UserPosition?,
        val boardStopLat: Double,
        val boardStopLng: Double,
        val nowMillis: Long
    ) {
        val hasBoardStop: Boolean get() = boardStopLat > 0.0 && boardStopLng > 0.0
    }

    /** 待确认阶段的判定结果 */
    enum class Verdict {
        /** 继续观察 */
        PENDING,
        /** 已上车：锁定待确认车辆进入 ON_BOARD */
        BOARDED,
        /** 没赶上：放弃待确认车辆，回到正常等车 */
        MISSED
    }

    data class CandidateResult(val state: TrackingSessionState, val verdict: Verdict)

    /**
     * 车刚到 A 时，判断是否要进入“待确认”。
     * @return true 进入待确认；false 用户明显不在站附近，不算上车
     */
    fun shouldStartCandidate(input: Input): Boolean {
        val user = input.user ?: return true // 暂时没有定位：先进入待确认，等后续定位或超时兜底
        if (!input.hasBoardStop) return true
        val toStop = distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng) ?: return true
        return toStop <= CANDIDATE_RADIUS_METERS
    }

    /** 用户离上车站 A 的距离（米），算不出时为 null */
    fun userDistanceToBoardStop(input: Input): Int? {
        val user = input.user ?: return null
        if (!input.hasBoardStop) return null
        return distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng)
    }

    /**
     * 待确认阶段的一次评估。
     * @param session 当前会话（[TrackingSessionState.boardingCandidateBusId] 非空）
     * @param candidate 本轮数据中的待确认车辆；从数据里消失时为 null
     */
    fun evaluateCandidate(
        session: TrackingSessionState,
        candidate: LiveBus?,
        input: Input
    ): CandidateResult {
        val boardOrder = session.boardStopOrder
        val user = input.user
        var confirm = session.boardingConfirmStreak
        var miss = session.boardingMissStreak

        if (user != null && input.hasBoardStop) {
            val userToStop = distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng)
            val userToBus = candidate?.let { distance(user.lat, user.lng, it.wgsLat, it.wgsLng) }
            val busToStop = candidate?.let { distance(it.wgsLat, it.wgsLng, input.boardStopLat, input.boardStopLng) }
            val busLeftStop = candidate == null || candidate.order > boardOrder

            val nearBusLimit = max(NEAR_BUS_MIN_METERS, (user.accuracyMeters * 2).toInt())
            val boardedSignal = userToStop != null && (
                (userToBus != null && userToBus <= nearBusLimit && userToStop >= LEFT_BOARD_STOP_METERS) ||
                    (userToStop >= FAR_FROM_BOARD_STOP_METERS && busLeftStop)
                )
            val missedSignal = userToStop != null && userToStop <= STILL_AT_BOARD_STOP_METERS && candidate != null && (
                (busToStop != null && busToStop >= BUS_GONE_FROM_BOARD_STOP_METERS) ||
                    (busToStop == null && candidate.order >= boardOrder + 2)
                )

            confirm = if (boardedSignal) confirm + 1 else 0
            miss = if (missedSignal) miss + 1 else 0
        }

        val updated = session.copy(boardingConfirmStreak = confirm, boardingMissStreak = miss)
        val verdict = when {
            confirm >= CONFIRM_STREAK -> Verdict.BOARDED
            miss >= MISS_STREAK -> Verdict.MISSED
            input.nowMillis - session.boardingCandidateSinceMillis >= CANDIDATE_TIMEOUT_MILLIS &&
                (candidate == null || candidate.order > boardOrder) -> Verdict.BOARDED
            else -> Verdict.PENDING
        }
        return CandidateResult(updated, verdict)
    }

    /**
     * ON_BOARD 阶段的错锁自愈。
     * @param locked 本轮数据中的锁定车辆；消失时为 null
     * @return 需要改锁时返回新车辆 busId 与更新后的会话；否则返回 null 与（可能更新了计数的）会话
     */
    fun evaluateRelock(
        session: TrackingSessionState,
        locked: LiveBus?,
        buses: List<LiveBus>,
        input: Input
    ): Pair<String?, TrackingSessionState> {
        val user = input.user ?: return null to session
        if (session.legPhase != LegPhase.ON_BOARD || session.lockedBusId == null) return null to session

        val lockedDistance = locked?.let { distance(user.lat, user.lng, it.wgsLat, it.wgsLng) }
        // 锁定车坐标未知但仍在数据里：无法判断，不动
        if (locked != null && lockedDistance == null) {
            return null to session.copy(relockCandidateBusId = null, relockStreak = 0)
        }
        val lockedLooksWrong = locked == null || lockedDistance!! > WRONG_LOCK_METERS

        val nearest = buses
            .asSequence()
            .filter { it.busId != session.lockedBusId }
            .filter { it.order >= session.boardStopOrder && it.order <= session.targetOrder }
            .mapNotNull { bus -> distance(user.lat, user.lng, bus.wgsLat, bus.wgsLng)?.let { bus to it } }
            .minByOrNull { it.second }
        val nearLimit = max(NEAR_BUS_MIN_METERS, (user.accuracyMeters * 2).toInt())

        if (!lockedLooksWrong || nearest == null || nearest.second > nearLimit) {
            return null to session.copy(relockCandidateBusId = null, relockStreak = 0)
        }
        val otherId = nearest.first.busId
        val streak = if (session.relockCandidateBusId == otherId) session.relockStreak + 1 else 1
        if (streak >= RELOCK_STREAK) {
            return otherId to session.copy(relockCandidateBusId = null, relockStreak = 0)
        }
        return null to session.copy(relockCandidateBusId = otherId, relockStreak = streak)
    }

    /** 清空所有待确认 / 改锁相关字段 */
    fun clearBoardingFields(session: TrackingSessionState): TrackingSessionState = session.copy(
        boardingCandidateBusId = null,
        boardingCandidateSinceMillis = 0L,
        boardingConfirmStreak = 0,
        boardingMissStreak = 0,
        relockCandidateBusId = null,
        relockStreak = 0
    )

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int? =
        RouteProjectionMath.calculateDistanceMeters(lat1, lng1, lat2, lng2)
}
