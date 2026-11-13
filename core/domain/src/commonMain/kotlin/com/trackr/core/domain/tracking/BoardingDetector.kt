package com.trackr.core.domain.tracking

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * 定位上车检测（纯函数，不依赖 Android）。
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
internal object BoardingDetector {

    const val CANDIDATE_RADIUS_METERS = 300
    const val CONFIRM_STREAK = 2
    const val MISS_STREAK = 2
    const val RELOCK_STREAK = 3
    const val CANDIDATE_TIMEOUT_MILLIS = 5 * 60_000L

    private const val NEAR_BUS_MIN_METERS = 80
    private const val NEAR_BUS_MOVING_METERS = 160
    private const val LEFT_BOARD_STOP_METERS = 100
    private const val FAR_FROM_BOARD_STOP_METERS = 400
    private const val STILL_AT_BOARD_STOP_METERS = 120
    private const val BUS_GONE_FROM_BOARD_STOP_METERS = 300
    private const val WRONG_LOCK_METERS = 500

    /** 补判上车：定位离线路折线不超过这么远才算「在线路上」 */
    private const val ON_LINE_METERS = 150

    /** 补判上车：沿线离候车站至少这么远才算「已经过了站」 */
    const val PAST_STOP_ALONG_METERS = 500

    private const val METERS_PER_DEGREE = 111_320.0

    /** 一个已经通过新鲜度与精度过滤的用户位置 */
    data class UserPosition(
        val lat: Double,
        val lng: Double,
        val accuracyMeters: Float,
        /** 定位单调时刻，用于避免同一快照重复计数。 */
        val elapsedMillis: Long = -1L
    )

    /**
     * 一次轮询的定位上下文。
     * @param user 新鲜且精确的用户位置；null 表示本轮没有可用定位（不改变任何连续计数）
     * @param boardStopLat 上车站 A 坐标（WGS-84），无效时为 0
     * @param coarseUser 精度放宽（≤100 m）的用户位置；默认同 [user]。issue #41：街上、车里定位常常只有 60–100 m 精度，
     *   只认 ≤50 m 时车到站那一轮经常拿不到定位，待确认根本建不起来。粗定位只用在容得下误差的判断上：
     *   「人在站附近（300 m 内）」和「人离站已经很远（≥400 m + 精度）」；「贴着车」「人还在站上」仍只认精定位
     */
    data class Input(
        val user: UserPosition?,
        val boardStopLat: Double,
        val boardStopLng: Double,
        val nowMillis: Long,
        val coarseUser: UserPosition? = user
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
        // 没有可用定位时不能创建待确认状态。否则候选车离站后的超时
        // 会把用户没有上车误判成已上车。
        val user = input.user ?: input.coarseUser ?: return false
        if (!input.hasBoardStop) return true
        val toStop = distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng) ?: return true
        return toStop <= CANDIDATE_RADIUS_METERS
    }

    /**
     * 单线路：车刚从候车站开走（跳过了到站那轮、或到站那轮没定位）时，判断是否要补建“待确认”。
     * 人在站附近（[shouldStartCandidate]），或人已经跟着这辆车走了（[isRidingAwayWithBus]，和确认上车同一条规则；
     * issue #49：以前这里要求「贴车 80 m」，车载 GPS 一滞后就不补，整段判定直接漏掉）。
     */
    fun shouldRecoverDepartedCandidate(input: Input, departed: LiveBus, boardOrder: Int): Boolean {
        if (shouldStartCandidate(input)) return true
        return isRidingAwayWithBus(input, departed, boardOrder)
    }

    /** 这一轮的定位（精 / 粗都行）是不是在候车站附近（≤ [CANDIDATE_RADIUS_METERS]），用来记「站旁记忆」 */
    fun isNearBoardStop(input: Input): Boolean {
        val fix = input.user ?: input.coarseUser ?: return false
        if (!input.hasBoardStop) return false
        val toStop = distance(fix.lat, fix.lng, input.boardStopLat, input.boardStopLng) ?: return false
        return toStop <= CANDIDATE_RADIUS_METERS
    }

    /** 线路上的一个站点（WGS-84），供 [isPastStopAlongLine] 把定位投到线路上 */
    data class LinePoint(val order: Int, val lat: Double, val lng: Double)

    /**
     * 补判上车（issue #49）：这一刻的定位是不是「在这条线路上、已经过了候车站」——不看车的上报位置，
     * 车从数据里消失、车载 GPS 滞后、站很密时都管用。
     *
     * 把站点按站序连成折线，定位投到最近的一段上：
     * - 离线路 ≤ max([ON_LINE_METERS], 1.5×精度)，而且离候车站下游那段比离上游那段近（排除 U 形线路、对向的路）；
     * - 投影点已越过候车站的下一站，并且沿线离候车站 ≥ [PAST_STOP_ALONG_METERS]（粗定位再多一个精度半径）——
     *   走到下一站去等车的人不会被当成上了车。
     */
    fun isPastStopAlongLine(input: Input, line: List<LinePoint>, boardOrder: Int): Boolean {
        val fix = input.user ?: input.coarseUser ?: return false
        val points = line.filter { it.lat > 0.0 && it.lng > 0.0 }.sortedBy { it.order }
        val boardIndex = points.indexOfFirst { it.order == boardOrder }
        // 要有候车站和它的下一站，才知道「过了站」在哪
        if (boardIndex < 0 || boardIndex + 1 > points.lastIndex) return false
        val origin = points[boardIndex]
        fun xy(lat: Double, lng: Double): Pair<Double, Double> {
            val x = (lng - origin.lng) * METERS_PER_DEGREE * cos(origin.lat * PI / 180.0)
            val y = (lat - origin.lat) * METERS_PER_DEGREE
            return x to y
        }
        val user = xy(fix.lat, fix.lng)
        val xs = points.map { xy(it.lat, it.lng) }

        // 下游：从候车站往后，记每段起点的沿线里程
        var along = 0.0
        var bestDown = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in boardIndex until xs.lastIndex) {
            val (d, t) = projectToSegment(user, xs[i], xs[i + 1])
            val segLen = hypot(xs[i + 1].first - xs[i].first, xs[i + 1].second - xs[i].second)
            if (d < bestDown) {
                bestDown = d
                bestAlong = along + t * segLen
            }
            along += segLen
        }
        // 上游：候车站之前的线路
        var bestUp = Double.MAX_VALUE
        for (i in 0 until boardIndex) {
            bestUp = minOf(bestUp, projectToSegment(user, xs[i], xs[i + 1]).first)
        }
        val accuracy = fix.accuracyMeters.toDouble()
        val onLineLimit = max(ON_LINE_METERS.toDouble(), accuracy * 1.5)
        if (bestDown > onLineLimit || bestDown >= bestUp) return false
        val nextStopAlong = hypot(xs[boardIndex + 1].first, xs[boardIndex + 1].second)
        val margin = if (input.user != null) 0.0 else accuracy
        return bestAlong > nextStopAlong && bestAlong >= PAST_STOP_ALONG_METERS + margin
    }

    /** 点到线段的距离与投影位置（0..1） */
    private fun projectToSegment(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>
    ): Pair<Double, Double> {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / lenSq).coerceIn(0.0, 1.0)
        val px = a.first + t * dx
        val py = a.second + t * dy
        return hypot(p.first - px, p.second - py) to t
    }

    /** 用户是否已离开候车站且贴着 [bus]（用于在多辆车之间识别用户实际坐上的那辆） */
    fun isUserRidingBus(input: Input, bus: LiveBus): Boolean {
        val user = input.user ?: return false
        if (!input.hasBoardStop) return false
        val toStop = distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng) ?: return false
        val toBus = distance(user.lat, user.lng, bus.wgsLat, bus.wgsLng) ?: return false
        val nearBusLimit = max(NEAR_BUS_MIN_METERS, (user.accuracyMeters * 2).toInt())
        return toStop >= LEFT_BOARD_STOP_METERS && toBus <= nearBusLimit
    }

    /**
     * 单线路一次定位就判上车（issue #49）：车已离站，人也离开了候车站，而且是跟着车的方向走的。
     *
     * 单线路判出上车就结束追踪，拖着不判的代价是下一班车到站又提醒、追踪一直不结束；所以不像路线那样要连续两次、
     * 也不要求「贴着车 80 m」（车载 GPS 常常比手机慢 10–30 秒，人在车上也离车的上报位置好几百米）。
     *
     * 条件（这一刻的定位，精 / 粗都行；粗定位离站要再多一个精度半径）：
     * - 待确认的车已离站：站序过了，或离站 ≥ [LEFT_BOARD_STOP_METERS]；
     * - 人离站 ≥ [LEFT_BOARD_STOP_METERS]；
     * - 人在车那一侧：人离车比人离站近。车载 GPS 落后时车的上报位置在站和人之间，满足；
     *   人从反方向、侧街往站上赶的，离车比离站远，不满足（这种人多半是没赶上，交给「没赶上」判定）；
     * - 车从数据里消失了（判不了方向）：退回人离站 ≥ [FAR_FROM_BOARD_STOP_METERS]。
     */
    fun isRidingAwayWithBus(input: Input, candidate: LiveBus?, boardOrder: Int): Boolean {
        val fix = input.user ?: input.coarseUser ?: return false
        if (!input.hasBoardStop) return false
        val userToStop = distance(fix.lat, fix.lng, input.boardStopLat, input.boardStopLng) ?: return false
        val margin = if (input.user != null) 0 else fix.accuracyMeters.toInt()
        if (userToStop < LEFT_BOARD_STOP_METERS + margin) return false
        if (candidate == null) return userToStop >= FAR_FROM_BOARD_STOP_METERS + margin
        val busToStop = distance(candidate.wgsLat, candidate.wgsLng, input.boardStopLat, input.boardStopLng)
        val busLeft = candidate.order > boardOrder || (busToStop != null && busToStop >= LEFT_BOARD_STOP_METERS)
        if (!busLeft) return false
        val userToBus = distance(fix.lat, fix.lng, candidate.wgsLat, candidate.wgsLng)
            // 车没有坐标：判不了方向，同样退回「人已经走远」
            ?: return userToStop >= FAR_FROM_BOARD_STOP_METERS + margin
        return userToBus < userToStop
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
     * @param missNeedsBus true = 「没赶上」要有车的位置作证，车从数据里消失不算（单线路用）
     */
    fun evaluateCandidate(
        session: TrackingSessionState,
        candidate: LiveBus?,
        input: Input,
        missNeedsBus: Boolean = false
    ): CandidateResult {
        val boardOrder = session.boardStopOrder
        val user = input.user
        // 没有精定位时退而用粗定位，但只认「人离站已经很远」这一条
        val probe = user ?: input.coarseUser
        val precise = user != null
        var confirm = session.boardingConfirmStreak
        var miss = session.boardingMissStreak

        // 定位服务可能在同一轮回调同一个快照多次。连续计数只消费新定位，
        // 否则一条定位会把“连续两次”误算成两次。
        // 测试/旧调用方可能没有单调时刻（-1）；这种定位无法去重，按新快照处理。
        val isNewFix = probe != null &&
            (probe.elapsedMillis < 0L || probe.elapsedMillis != session.boardingLastFixElapsedMillis)
        if (isNewFix && input.hasBoardStop) {
            val fix = probe!!
            val userToStop = distance(fix.lat, fix.lng, input.boardStopLat, input.boardStopLng)
            val userToBus = candidate?.let { distance(fix.lat, fix.lng, it.wgsLat, it.wgsLng) }
            val busToStop = candidate?.let { distance(it.wgsLat, it.wgsLng, input.boardStopLat, input.boardStopLng) }
            val busLeftStop = candidate == null ||
                candidate.order > boardOrder ||
                (busToStop != null && busToStop >= LEFT_BOARD_STOP_METERS)

            val baseNearLimit = max(NEAR_BUS_MIN_METERS, (fix.accuracyMeters * 2).toInt())
            // 车已离站、人也已离站时，车载 GPS 上报往往比手机实时定位慢 10–15 秒，放宽人车距离上限
            val nearBusLimit = if (busLeftStop && (busToStop == null || busToStop >= LEFT_BOARD_STOP_METERS)) {
                max(NEAR_BUS_MOVING_METERS, baseNearLimit)
            } else {
                baseNearLimit
            }
            // 粗定位离站要再多出一个精度半径，才算真的走远了
            val farLimit = if (precise) FAR_FROM_BOARD_STOP_METERS else FAR_FROM_BOARD_STOP_METERS + fix.accuracyMeters.toInt()
            val boardedSignal = userToStop != null && (
                (precise && userToBus != null && userToBus <= nearBusLimit && userToStop >= LEFT_BOARD_STOP_METERS) ||
                    (userToStop >= farLimit && busLeftStop)
                )
            val missedSignal = precise && userToStop != null && userToStop <= STILL_AT_BOARD_STOP_METERS && (
                // 单线路（[missNeedsBus]）：车只是这一轮不在数据里不算证据——人可能就在那辆车上、刚离站不远（issue #49）
                (candidate == null && !missNeedsBus) ||
                    (busToStop != null && busToStop >= BUS_GONE_FROM_BOARD_STOP_METERS) ||
                    (busToStop == null && candidate != null && candidate.order >= boardOrder + 2)
                )

            confirm = when {
                boardedSignal -> confirm + 1
                confirm > 0 && !missedSignal && userToStop != null && userToStop >= LEFT_BOARD_STOP_METERS -> confirm
                else -> 0
            }
            miss = when {
                missedSignal -> miss + 1
                // 粗定位判不了「人还在站上」，不打断没赶上的连续计数
                precise -> 0
                else -> miss
            }
        }

        val updated = session.copy(
            boardingConfirmStreak = confirm,
            boardingMissStreak = miss,
            boardingLastFixElapsedMillis = if (isNewFix) probe!!.elapsedMillis else session.boardingLastFixElapsedMillis
        )
        val verdict = when {
            confirm >= CONFIRM_STREAK -> Verdict.BOARDED
            miss >= MISS_STREAK -> Verdict.MISSED
            input.nowMillis - session.boardingCandidateSinceMillis >= CANDIDATE_TIMEOUT_MILLIS &&
                user != null && (candidate == null || candidate.order > boardOrder) -> Verdict.BOARDED
            input.nowMillis - session.boardingCandidateSinceMillis >= CANDIDATE_TIMEOUT_MILLIS &&
                user == null -> Verdict.MISSED
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
        boardingLastFixElapsedMillis = -1L,
        boardingLockAttempts = 0,
        relockCandidateBusId = null,
        relockStreak = 0,
        recentDepartedBusId = null,
        recentDepartedAtMillis = 0L
    )

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int? =
        RouteProjectionMath.calculateDistanceMeters(lat1, lng1, lat2, lng2)
}
