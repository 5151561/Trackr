package com.trackr.core.domain.tracking

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState

/**
 * 单线路（非分组）候车追踪的纯函数核心：选车 + 提醒编排。
 *
 * 规则：
 * - 只考虑未开过目标站的车（order <= targetOrder），优先沿用正在追踪的车，否则取离目标站最近的一辆。
 * - 正在追踪的车短暂从数据里消失时，保持原车 [MAX_MISSING_POLLS] 轮，不立刻换车。
 * - 正在追踪的车已开过目标站或消失超过上限时换车；换车时重置提醒触发标志，下一辆车照常提醒。
 * - 开着「上车后自动结束追踪」时，车停到候车站就开始用定位确认人有没有跟着车走（见 [checkBoarding]），
 *   确认上了就发 `boarded` 让调用方结束这次追踪（issue #32）；一次定位看出人跟着车走了就算上了（issue #49），
 *   确认期间后面那班车不提醒。
 * - 指定了车（[TrackingSessionState.pinnedBusId]，issue #52「下车提醒」）时只追这一辆：不换车、不判上车，
 *   它暂时从数据里消失就一直保持上次位置；开过目标站后发 `finished` 让调用方结束追踪（没发过到站提醒的先补发）。
 */
internal object SingleLegTracker {

    /** 追踪车辆短暂消失时最多保持原车的轮询次数 */
    const val MAX_MISSING_POLLS = 2

    /** 只补回最近一轮漏掉的到站，避免用中断前的旧车辆启动检测。 */
    private const val DEPARTURE_RECOVERY_WINDOW_MILLIS = 60_000L

    /** 判出上车后写进状态的文案（会话马上就被清掉了，只有通知里会读到它） */
    const val BOARDED_SUMMARY = "已上车，本次追踪结束"

    /** 单线路提醒类型 */
    enum class AlertKind { APPROACHING, ARRIVED }

    /**
     * 单次轮询结果。
     * @param state 更新后的会话状态（已更新提醒触发标志）
     * @param alert 本轮要发的提醒，null 表示不发
     * @param alertState 发提醒时用的状态
     * @param boarded 定位判出已上车：调用方据此结束这次追踪
     * @param finished 指定的那辆车已开过目标站（issue #52）：调用方据此结束这次追踪
     */
    data class SingleUpdate(
        val state: TrackingSessionState,
        val alert: AlertKind? = null,
        val alertState: TrackingSessionState? = null,
        val boarded: Boolean = false,
        val finished: Boolean = false
    )

    /**
     * 处理一次单线路轮询。
     *
     * @param session 当前会话状态（legPhase == SINGLE）
     * @param buses 本次实时数据中该线路的在途车辆
     * @param operatingState 线路运营状态（无车时用于文案）
     * @param statusDescription 上游运营状态描述（无车时用于文案）
     * @param nowMillis 当前墙钟时刻（毫秒），写进 `updatedAtMillis`
     * @param boarding 判上车的定位上下文（候车站 = 目标站）；null 表示这次不判上车（开关关着 / 没定位），
     *   行为和原来一样
     */
    fun processPoll(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        operatingState: LineOperatingState = LineOperatingState.RUNNING,
        statusDescription: String = "",
        nowMillis: Long,
        boarding: BoardingDetector.Input? = null
    ): SingleUpdate {
        session.pinnedBusId?.let { pinnedId ->
            return processPinnedBus(session, pinnedId, buses, operatingState, statusDescription, nowMillis)
        }
        if (boarding == null) {
            // 不判上车了（开关关了 / 定位不能用了）：留着的待确认要清掉，否则一直压着提醒、一直开高频定位
            val cleaned = if (session.boardingCandidateBusId != null || session.recentDepartedBusId != null) {
                BoardingDetector.clearBoardingFields(session)
            } else {
                session
            }
            return processBus(cleaned, buses, operatingState, statusDescription, nowMillis)
        }

        val recovered = recoverDepartedCandidate(session, buses, boarding, nowMillis)
        val checked = checkBoarding(recovered, buses, boarding, nowMillis)
        if (checked.boarded) {
            return SingleUpdate(checked.state.copy(statusSummary = BOARDED_SUMMARY), boarded = true)
        }
        val update = processBus(checked.state, buses, operatingState, statusDescription, nowMillis)
        return update.copy(state = startCandidate(update.state, boarding, nowMillis))
    }

    /** 选车 + 提醒（原来的全部行为），不含上车判定 */
    private fun processBus(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        operatingState: LineOperatingState,
        statusDescription: String,
        nowMillis: Long
    ): SingleUpdate {
        val targetOrder = session.targetOrder
        val trackedId = session.currentBusId

        // 1. 正在追踪的车短暂消失：保持原车一两轮
        if (trackedId != null && buses.none { it.busId == trackedId } &&
            session.missingBusPolls < MAX_MISSING_POLLS
        ) {
            return SingleUpdate(
                session.copy(
                    missingBusPolls = session.missingBusPolls + 1,
                    statusSummary = "车辆信号短暂丢失，保持上次位置（距 ${session.targetStopName}）",
                    updatedAtMillis = nowMillis
                )
            )
        }

        // 2. 选车：只看未过站车辆，优先沿用正在追踪的车
        val candidates = buses.filter { it.order <= targetOrder }
        val tracked = trackedId?.let { id -> candidates.find { it.busId == id } }
        val best = tracked ?: candidates.maxByOrNull { it.order.toFloat() + it.segmentProgress }

        if (best == null) {
            val summary = when {
                operatingState != LineOperatingState.RUNNING ->
                    statusDescription.ifEmpty { operatingState.defaultLabel }
                buses.isNotEmpty() -> "车辆均已过站，等待下一班车调度"
                else -> "暂无在途车辆运营数据"
            }
            return SingleUpdate(
                session.copy(
                    currentBusId = null,
                    stopsRemaining = -1,
                    etaSeconds = -1,
                    isBusArrivedAtStop = false,
                    missingBusPolls = 0,
                    // 原车已过站 / 消失：下一辆车出现时重新提醒
                    hasTriggeredApproachingAlert = false,
                    hasTriggeredArrivedAlert = false,
                    statusSummary = summary,
                    updatedAtMillis = nowMillis
                )
            )
        }

        val isNewVehicle = trackedId == null || trackedId != best.busId
        val stopsRemaining = (targetOrder - best.order).coerceAtLeast(0)
        val isStoppedAtTarget = stopsRemaining == 0 && ArrivalRules.isBusStopped(best)
        val isArrivedCondition = ArrivalRules.isBusArrivedAtOrder(targetOrder, best)

        // 换车或初次锁定：重新确定起始追踪基准站
        val startOrder = if (isNewVehicle) {
            val base = if (ArrivalRules.isBusStopped(best)) best.order else best.order - 1
            base.coerceAtMost(targetOrder - 1).coerceAtLeast(1)
        } else {
            session.startTrackingOrder
        }

        // 换车时重置提醒触发标志
        var hasApproaching = if (isNewVehicle) false else session.hasTriggeredApproachingAlert
        var hasArrived = if (isNewVehicle) false else session.hasTriggeredArrivedAlert

        val etaFormatted = RouteProjectionMath.formatEtaText(best.etaSeconds, isStoppedAtTarget)
        val statusSummary = when {
            isStoppedAtTarget -> "车辆已到达 ${session.targetStopName}！"
            stopsRemaining == 0 -> "车辆即将进站 ${session.targetStopName}"
            best.etaSeconds > 0 -> "距 ${session.targetStopName} 还有 $stopsRemaining 站（$etaFormatted）"
            else -> "距 ${session.targetStopName} 还有 $stopsRemaining 站"
        }

        val baseState = session.copy(
            startTrackingOrder = startOrder,
            currentBusId = best.busId,
            currentBusOrder = best.order,
            currentBusProgress = best.segmentProgress,
            isBusArrivedAtStop = isStoppedAtTarget,
            stopsRemaining = stopsRemaining,
            etaSeconds = best.etaSeconds,
            expectedArrivalTime = best.expectedArrivalTimeText,
            missingBusPolls = 0,
            statusSummary = statusSummary,
            updatedAtMillis = nowMillis
        )

        // 正在确认是否上了前一班车：后面这班车的临近 / 到站先不提醒（issue #49：人已在车上，下一班到站又提醒）。
        // 判成没赶上会清掉待确认，后面这班车照常提醒（标志没被置上）
        val suppressAlerts = session.boardingCandidateBusId != null
        var alert: AlertKind? = null
        var alertState: TrackingSessionState? = null
        if (!suppressAlerts) {
            if (isArrivedCondition && !hasArrived) {
                alert = AlertKind.ARRIVED
                alertState = baseState.copy(stopsRemaining = 0, isBusArrivedAtStop = true)
                hasArrived = true
                hasApproaching = true
            } else if (!isArrivedCondition && stopsRemaining <= session.alertThresholdStops && !hasApproaching) {
                alert = AlertKind.APPROACHING
                alertState = baseState
                hasApproaching = true
            }
        }

        return SingleUpdate(
            baseState.copy(
                hasTriggeredApproachingAlert = hasApproaching,
                hasTriggeredArrivedAlert = hasArrived
            ),
            alert,
            alertState
        )
    }

    // ---------------------------------------------------------------- 只追指定的车（issue #52）

    /**
     * 只追 [pinnedId] 这一辆：提醒规则和不指定时一样（还剩阈值站数提醒一次、到站提醒一次）。
     * - 车不在数据里：保持上次位置接着等，不换车（指定的车可能只是一会儿没信号）；
     * - 车开过目标站：没发过到站提醒的先补发，然后 `finished`。
     */
    private fun processPinnedBus(
        session: TrackingSessionState,
        pinnedId: String,
        buses: List<LiveBus>,
        operatingState: LineOperatingState,
        statusDescription: String,
        nowMillis: Long
    ): SingleUpdate {
        val targetOrder = session.targetOrder
        val plate = session.pinnedPlate.ifBlank { "这辆车" }
        val bus = buses.firstOrNull { it.busId == pinnedId }
        if (bus == null) {
            val summary = when {
                session.currentBusId == null && operatingState != LineOperatingState.RUNNING ->
                    statusDescription.ifEmpty { operatingState.defaultLabel }
                session.currentBusId == null -> "正在查找 $plate 的实时位置…"
                else -> "$plate 信号暂时丢失，保持上次位置（距 ${session.targetStopName}）"
            }
            return SingleUpdate(
                session.copy(
                    missingBusPolls = session.missingBusPolls + 1,
                    statusSummary = summary,
                    updatedAtMillis = nowMillis
                )
            )
        }

        val isFirstSight = session.currentBusId != pinnedId
        val passed = bus.order > targetOrder
        val stopsRemaining = (targetOrder - bus.order).coerceAtLeast(0)
        val isStoppedAtTarget = stopsRemaining == 0 && !passed && ArrivalRules.isBusStopped(bus)
        val isArrivedCondition = ArrivalRules.isBusArrivedAtOrder(targetOrder, bus)
        val startOrder = if (isFirstSight) {
            val base = if (ArrivalRules.isBusStopped(bus)) bus.order else bus.order - 1
            base.coerceAtMost(targetOrder - 1).coerceAtLeast(1)
        } else {
            session.startTrackingOrder
        }
        val etaFormatted = RouteProjectionMath.formatEtaText(bus.etaSeconds, isStoppedAtTarget)
        val statusSummary = when {
            passed || isStoppedAtTarget -> "$plate 已到达 ${session.targetStopName}！"
            stopsRemaining == 0 -> "$plate 即将进站 ${session.targetStopName}"
            bus.etaSeconds > 0 -> "$plate 距 ${session.targetStopName} 还有 $stopsRemaining 站（$etaFormatted）"
            else -> "$plate 距 ${session.targetStopName} 还有 $stopsRemaining 站"
        }
        val baseState = session.copy(
            startTrackingOrder = startOrder,
            currentBusId = pinnedId,
            currentBusOrder = bus.order.coerceAtMost(targetOrder),
            currentBusProgress = if (passed) 1f else bus.segmentProgress,
            isBusArrivedAtStop = isStoppedAtTarget || passed,
            stopsRemaining = stopsRemaining,
            etaSeconds = if (passed) 0 else bus.etaSeconds,
            expectedArrivalTime = bus.expectedArrivalTimeText,
            missingBusPolls = 0,
            statusSummary = statusSummary,
            updatedAtMillis = nowMillis
        )

        var hasApproaching = session.hasTriggeredApproachingAlert
        var hasArrived = session.hasTriggeredArrivedAlert
        var alert: AlertKind? = null
        var alertState: TrackingSessionState? = null
        if (isArrivedCondition && !hasArrived) {
            alert = AlertKind.ARRIVED
            alertState = baseState.copy(stopsRemaining = 0, isBusArrivedAtStop = true)
            hasArrived = true
            hasApproaching = true
        } else if (!isArrivedCondition && stopsRemaining <= session.alertThresholdStops && !hasApproaching) {
            alert = AlertKind.APPROACHING
            alertState = baseState
            hasApproaching = true
        }
        return SingleUpdate(
            baseState.copy(
                hasTriggeredApproachingAlert = hasApproaching,
                hasTriggeredArrivedAlert = hasArrived
            ),
            alert,
            alertState,
            finished = passed
        )
    }

    // ---------------------------------------------------------------- 判上车（issue #32）

    private class BoardingCheck(val state: TrackingSessionState, val boarded: Boolean)

    /**
     * 待确认阶段的一轮判定。
     *
     * 上车（issue #49）：一次定位满足 [BoardingDetector.isRidingAwayWithBus]（车走了、人也跟着车的方向离站 ≥100 m）
     * 就判上车、马上结束——不再等连续两次、不要求贴车。以前要凑齐两次「贴车 80 m」，车载 GPS 一滞后就凑不齐，
     * 等着等着下一班车到站又提醒，追踪一直不结束。路线、一起等的判定不变。
     * 没赶上仍按 [BoardingDetector] 的规则（人还在站上、车开远了，连续两次）；
     * 待确认满 [BoardingDetector.CANDIDATE_TIMEOUT_MILLIS] 还没结论就放掉（人可能正往站上走），不按超时判上车。
     */
    private fun checkBoarding(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): BoardingCheck {
        val candidateId = session.boardingCandidateBusId ?: return BoardingCheck(session, false)
        // 车开过候车站之后就不再是「未过站的车」了，所以按 id 在全部在途车里找它
        val candidate = buses.firstOrNull { it.busId == candidateId }
        if (BoardingDetector.isRidingAwayWithBus(input, candidate, session.targetOrder)) {
            return BoardingCheck(BoardingDetector.clearBoardingFields(session), true)
        }
        if (nowMillis - session.boardingCandidateSinceMillis >= BoardingDetector.CANDIDATE_TIMEOUT_MILLIS) {
            return BoardingCheck(BoardingDetector.clearBoardingFields(session), false)
        }
        // 候车站就是目标站：用 boardStopOrder = targetOrder 借一趟路线的规矩（车过站 = candidate.order > boardStopOrder）
        val probe = session.copy(boardStopOrder = session.targetOrder)
        val evaluated = BoardingDetector.evaluateCandidate(probe, candidate, input)
        // 如果原候选车还没确认过（confirm == 0）且人没在它上面，而同线路紧跟的另一辆车已到/刚过目标站且人正贴着那辆车走，
        // 改追那辆实际坐上的车（前后车串车或前车停靠未上、上了后车的情形）
        if (evaluated.verdict == BoardingDetector.Verdict.PENDING &&
            evaluated.state.boardingConfirmStreak == 0 &&
            (candidate == null || !BoardingDetector.isUserRidingBus(input, candidate))
        ) {
            val otherRiding = buses.firstOrNull {
                it.busId != candidateId &&
                    it.order in session.targetOrder..(session.targetOrder + 1) &&
                    BoardingDetector.isUserRidingBus(input, it)
            }
            if (otherRiding != null) {
                val switched = withCandidate(session, otherRiding.busId, nowMillis)
                return checkBoarding(switched, buses, input, nowMillis)
            }
        }
        val state = session.copy(
            boardingConfirmStreak = evaluated.state.boardingConfirmStreak,
            boardingMissStreak = evaluated.state.boardingMissStreak,
            boardingLastFixElapsedMillis = evaluated.state.boardingLastFixElapsedMillis
        )
        return when (evaluated.verdict) {
            BoardingDetector.Verdict.BOARDED -> BoardingCheck(BoardingDetector.clearBoardingFields(state), true)
            BoardingDetector.Verdict.MISSED -> BoardingCheck(BoardingDetector.clearBoardingFields(state), false)
            BoardingDetector.Verdict.PENDING -> BoardingCheck(state, false)
        }
    }

    /**
     * 轮询可能直接从进站前跳到离站后，或车到站那轮还没取得定位。
     * 必须在选下一班车之前补回原车；只认最近追过的同一辆车、刚过一两站且人在站旁或已随车离开。
     * 此处只创建待确认，是否上车仍由两次不同定位判断，不能因车过站直接结束。
     */
    private fun recoverDepartedCandidate(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): TrackingSessionState {
        if (session.boardingCandidateBusId != null) return session

        // 1. 先检查上一轮刚记录为“刚过站但当时尚无定位”的车
        val recentId = session.recentDepartedBusId
        val cleanedSession = if (recentId != null &&
            nowMillis - session.recentDepartedAtMillis !in 0..DEPARTURE_RECOVERY_WINDOW_MILLIS
        ) {
            session.copy(recentDepartedBusId = null, recentDepartedAtMillis = 0L)
        } else {
            session
        }
        val activeRecentId = cleanedSession.recentDepartedBusId
        if (activeRecentId != null) {
            val recentBus = buses.firstOrNull { it.busId == activeRecentId }
            if (recentBus == null || recentBus.order !in (session.targetOrder + 1)..(session.targetOrder + 2)) {
                return cleanedSession.copy(recentDepartedBusId = null, recentDepartedAtMillis = 0L)
            }
            if (input.user == null && input.coarseUser == null) {
                return cleanedSession
            }
            return if (BoardingDetector.shouldRecoverDepartedCandidate(input, recentBus)) {
                withCandidate(cleanedSession, activeRecentId, nowMillis)
            } else {
                cleanedSession.copy(recentDepartedBusId = null, recentDepartedAtMillis = 0L)
            }
        }

        // 2. 检查上一轮还在候车站（或前两站内）的当前追踪车辆是否本轮刚过站
        if (nowMillis - cleanedSession.updatedAtMillis !in 0..DEPARTURE_RECOVERY_WINDOW_MILLIS) return cleanedSession
        if (cleanedSession.currentBusOrder !in (cleanedSession.targetOrder - 2)..cleanedSession.targetOrder) return cleanedSession
        val previousId = cleanedSession.currentBusId ?: return cleanedSession
        val departed = buses.firstOrNull { it.busId == previousId } ?: return cleanedSession
        // 上游数据常常一跳两站（车不停站、或这一轮轮询晚了），只认刚过一站会整段漏掉判定（issue #41）
        if (departed.order !in (cleanedSession.targetOrder + 1)..(cleanedSession.targetOrder + 2)) return cleanedSession
        if (input.user == null && input.coarseUser == null) {
            // 车刚过站但定位还没拿到：暂存 busId 保持高频定位与轮询，等下一轮拿到定位再补回
            return cleanedSession.copy(
                recentDepartedBusId = previousId,
                recentDepartedAtMillis = nowMillis
            )
        }
        if (!BoardingDetector.shouldRecoverDepartedCandidate(input, departed)) return cleanedSession
        return withCandidate(cleanedSession, previousId, nowMillis)
    }

    /**
     * 车到候车站、人也在站附近：开始用定位确认（没有定位、人明显不在站附近时不创建）。
     *
     * 用 `stopsRemaining == 0` 而不是「已停靠」：公交往往不停稳就过站，数据里也常常只有站序没有停靠状态，
     * 只看停靠会漏掉一整轮判定。
     */
    private fun startCandidate(
        state: TrackingSessionState,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): TrackingSessionState {
        if (state.boardingCandidateBusId != null || state.stopsRemaining != 0) return state
        val busId = state.currentBusId ?: return state
        if (!BoardingDetector.shouldStartCandidate(input)) return state
        return withCandidate(state, busId, nowMillis)
    }

    private fun withCandidate(
        state: TrackingSessionState,
        busId: String,
        nowMillis: Long
    ): TrackingSessionState = state.copy(
        boardingCandidateBusId = busId,
        boardingCandidateSinceMillis = nowMillis,
        boardingConfirmStreak = 0,
        boardingMissStreak = 0,
        boardingLastFixElapsedMillis = -1L,
        recentDepartedBusId = null,
        recentDepartedAtMillis = 0L
    )
}
