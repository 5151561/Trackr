package com.trackr.service

import android.content.Context
import android.util.Log
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.util.CoordTransform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 智能自动换乘追踪引擎与自适应调度器
 *
 * 分组换乘按“两阶段”追踪每一程：
 * - WAITING_BOARD（等车）：目标上车站 A，只考虑未过 A 的车，取离 A 最近的一辆。
 * - ON_BOARD（已上车）：锁定具体车辆，目标下车站 B，只有锁定车到 B 才切下一程 / 完成。
 *
 * 到站判定基于“阶段 + 锁定车辆”，定位仅作为 ON_BOARD 阶段到达下车站的辅助条件。
 */
object TransferAutoSwitchManager {

    private const val TAG = "TransferAutoSwitch"

    private val _activeGroupSession = MutableStateFlow<GroupTrackingSessionState?>(null)
    val activeGroupSession: StateFlow<GroupTrackingSessionState?> = _activeGroupSession.asStateFlow()

    private val _activeGroup = MutableStateFlow<TransitGroup?>(null)

    /** 当前正在追踪的整组路线（只读）；用于收藏页显示"临时追踪"卡片 */
    val activeGroupState: StateFlow<TransitGroup?> = _activeGroup.asStateFlow()

    private var activeGroup: TransitGroup?
        get() = _activeGroup.value
        set(value) {
            _activeGroup.value = value
        }
    /** 每程用户选中的候选线路（legIndex -> lineId） */
    private var selectedLineIds: Map<Int, String> = emptyMap()
    private var lastUserLat: Double? = null
    private var lastUserLng: Double? = null

    /**
     * 更新手机用户当前位置坐标（WGS-84，用于 ON_BOARD 阶段到站双保险空间测距）
     */
    fun updateUserLocation(lat: Double, lng: Double) {
        lastUserLat = lat
        lastUserLng = lng
    }

    /**
     * 启动整组多程换乘追踪
     *
     * @param selectedLineIds 每程用户在卡片上选中的线路（legIndex -> lineId），找不到回落 primaryLine
     */
    fun startGroupTracking(
        context: Context,
        group: TransitGroup,
        startingLegIndex: Int = 0,
        selectedLineIds: Map<Int, String> = emptyMap()
    ) {
        if (group.legs.isEmpty()) return

        val initialLegIndex = startingLegIndex.coerceIn(0, group.legs.lastIndex)
        val leg = group.legs[initialLegIndex]
        val candidateLine = resolveLineForLeg(leg, initialLegIndex, selectedLineIds)

        activeGroup = group
        this.selectedLineIds = selectedLineIds

        val groupState = GroupTrackingSessionState(
            groupId = group.id,
            groupName = group.name,
            activeLegIndex = initialLegIndex,
            totalLegs = group.legs.size,
            currentLeg = leg,
            isCompleted = false
        )
        _activeGroupSession.value = groupState

        if (candidateLine != null) {
            startTrackingLeg(context, group, leg, candidateLine, initialLegIndex)
        }
    }

    /** 选择当前程要追踪的线路：优先用户选中项，回落首个候选线路 */
    private fun resolveLineForLeg(
        leg: TransitLeg,
        legIndex: Int,
        selected: Map<Int, String>
    ): CandidateLine? {
        val chosenId = selected[legIndex]
        return leg.candidateLines.firstOrNull { it.lineId == chosenId } ?: leg.primaryLine
    }

    /**
     * 针对当前程段启动 BusTrackingService（分组换乘：WAITING_BOARD 阶段起步）
     */
    private fun startTrackingLeg(
        context: Context,
        group: TransitGroup,
        leg: TransitLeg,
        line: CandidateLine,
        legIndex: Int
    ) {
        BusTrackingService.startTrackingGroupLeg(
            context = context,
            groupId = group.id,
            groupName = group.name,
            legIndex = legIndex,
            totalLegs = group.legs.size,
            cityId = line.cityId,
            lineId = line.lineId,
            lineName = line.lineName,
            lineDirection = line.direction,
            source = line.source,
            mygolbsCityCode = line.mygolbsCityCode.orEmpty(),
            // 目标下车站 B
            targetStopName = leg.toStopName,
            targetStopId = leg.toStopId,
            targetOrder = line.toStopOrder,
            // 上车站 A
            boardStopName = leg.fromStopName,
            boardStopOrder = line.fromStopOrder,
            endStopName = line.endStopName
        )
    }

    /**
     * 自适应防刷轮询退避算法：
     * - 即将到站（<= 1 站 或 ETA <= 180 秒）：10 秒高效轮询
     * - 中距离（2-4 站 或 ETA <= 600 秒）：20 秒平稳轮询
     * - 远距离（> 4 站 或 暂无实时数据）：45 秒节能防刷轮询
     */
    fun calculateAdaptivePollingInterval(session: TrackingSessionState): Long {
        val stopsRemaining = session.stopsRemaining
        val etaSeconds = session.etaSeconds

        return when {
            stopsRemaining in 0..1 || (etaSeconds in 1..180) -> 10_000L
            stopsRemaining in 2..4 || (etaSeconds in 181..600) -> 20_000L
            else -> 45_000L
        }
    }

    /**
     * ON_BOARD 阶段到达下车站判定（基于阶段 + 锁定车辆）：
     * 1. 公交到达事件（stopsRemaining == 0 且已停靠/ETA<=45s）
     * 2. 手机空间定位辅助（距下车站 < 150m）——仅 ON_BOARD 阶段有效
     *
     * WAITING_BOARD 阶段恒返回 false：等车阶段不能用定位触发切程。
     */
    fun isArrivalSatisfied(
        session: TrackingSessionState,
        leg: TransitLeg?,
        userLat: Double? = lastUserLat,
        userLng: Double? = lastUserLng
    ): Boolean {
        // WAITING_BOARD（或 SINGLE）阶段绝不在此触发下车站到达
        if (session.legPhase == LegPhase.WAITING_BOARD) return false

        // 1. 公交车辆自身事件判定（锁定车到下车站）
        val busCondition = session.stopsRemaining == 0 &&
                (session.isBusArrivedAtStop || (session.etaSeconds in 0..45))

        // 2. 空间测距辅助判定，仅在 ON_BOARD 阶段生效；leg 坐标为 GCJ-02，用户坐标为 WGS-84，需转换对齐
        val locationCondition = if (session.legPhase == LegPhase.ON_BOARD) {
            val targetLat = leg?.toStopLat ?: 0.0
            val targetLng = leg?.toStopLng ?: 0.0
            if (userLat != null && userLng != null && targetLat > 0.0 && targetLng > 0.0) {
                val (userGcjLat, userGcjLng) = CoordTransform.wgs84ToGcj02(userLat, userLng)
                val dist = RouteProjectionMath.calculateDistanceMeters(userGcjLat, userGcjLng, targetLat, targetLng)
                dist != null && dist < 150
            } else false
        } else false

        return busCondition || locationCondition
    }

    /**
     * 当 BusTrackingService 轮询得到当前单程最新状态时调用，进行换乘流转评估。
     *
     * 仅当锁定车到达下车站（ON_BOARD 阶段的到站）才切下一程 / 完成；
     * WAITING_BOARD 阶段、或非锁定车到站都不会触发切程。
     */
    fun onLegProgressUpdated(context: Context, session: TrackingSessionState): GroupTrackingSessionState? {
        val currentSession = _activeGroupSession.value ?: return null
        val group = activeGroup ?: return null
        val currentLegIndex = currentSession.activeLegIndex
        val currentLeg = group.legs.getOrNull(currentLegIndex)

        // 基于阶段 + 锁定车辆判定下车站到达
        val isArrived = isArrivalSatisfied(session, currentLeg)

        val updatedGroupState: GroupTrackingSessionState

        if (isArrived && !currentSession.isCompleted) {
            val nextLegIndex = currentLegIndex + 1
            if (nextLegIndex < group.legs.size) {
                val nextLeg = group.legs[nextLegIndex]
                val nextLine = resolveLineForLeg(nextLeg, nextLegIndex, selectedLineIds)
                val nextLineName = nextLine?.lineName ?: "下一程公交"

                Log.d(TAG, "第 ${currentLegIndex + 1} 程到站，自动切换到第 ${nextLegIndex + 1} 程: $nextLineName")
                ArrivalAlertManager.triggerTransferAlert(context, currentLeg?.toStopName.orEmpty(), nextLineName)

                updatedGroupState = currentSession.copy(
                    activeLegIndex = nextLegIndex,
                    currentLeg = nextLeg,
                    trackingSessionState = session.copy(
                        currentLegIndex = nextLegIndex,
                        statusSummary = "已到达换乘站 ${currentLeg?.toStopName}，正在连接第 ${nextLegIndex + 1} 程…"
                    ),
                    transferPromptMessage = "到达换乘站 ${currentLeg?.toStopName}，已自动切换追踪下一程"
                )
                _activeGroupSession.value = updatedGroupState

                if (nextLine != null) {
                    startTrackingLeg(context, group, nextLeg, nextLine, nextLegIndex)
                }
            } else {
                // 整组全部程段已完结：停止服务
                Log.d(TAG, "全部换乘程段已完成到达终点！")
                updatedGroupState = currentSession.copy(
                    isCompleted = true,
                    trackingSessionState = session.copy(
                        statusSummary = "已顺利到达终点站 ${currentLeg?.toStopName}！"
                    )
                )
                _activeGroupSession.value = updatedGroupState
                BusTrackingService.stopTracking(context)
            }
        } else {
            updatedGroupState = currentSession.copy(
                trackingSessionState = session.copy(
                    groupId = group.id,
                    groupName = group.name,
                    currentLegIndex = currentLegIndex,
                    totalLegs = group.legs.size
                )
            )
            _activeGroupSession.value = updatedGroupState
        }

        return updatedGroupState
    }

    /**
     * 手动确认“我已上车”：跳过等车阶段，直接进入 ON_BOARD。
     * 转发给服务锁定当前最接近的车并切换到已上车阶段。
     */
    fun confirmBoarded(context: Context) {
        val currentSession = _activeGroupSession.value ?: return
        if (currentSession.isCompleted) return
        BusTrackingService.confirmBoarded(context)
    }

    /**
     * 第 [legIndex] 程之后那一程要坐的线路名（只读，供通知文案"下车后换乘 X"使用）；
     * 没有下一程或不在分组追踪中时返回 null。
     */
    fun nextLegLineName(legIndex: Int): String? {
        val group = activeGroup ?: return null
        val nextIndex = legIndex + 1
        val nextLeg = group.legs.getOrNull(nextIndex) ?: return null
        return resolveLineForLeg(nextLeg, nextIndex, selectedLineIds)?.lineName
    }

    /**
     * 手动切换至下一程段
     */
    fun switchToNextLeg(context: Context) {
        val currentSession = _activeGroupSession.value ?: return
        val group = activeGroup ?: return
        val nextIndex = currentSession.activeLegIndex + 1
        if (nextIndex < group.legs.size) {
            val nextLeg = group.legs[nextIndex]
            val nextLine = resolveLineForLeg(nextLeg, nextIndex, selectedLineIds)
            _activeGroupSession.value = currentSession.copy(
                activeLegIndex = nextIndex,
                currentLeg = nextLeg
            )
            if (nextLine != null) {
                startTrackingLeg(context, group, nextLeg, nextLine, nextIndex)
            }
        }
    }

    /**
     * 终止换乘追踪会话
     */
    fun stopGroupTracking(context: Context) {
        _activeGroupSession.value = null
        activeGroup = null
        selectedLineIds = emptyMap()
        BusTrackingService.stopTracking(context)
    }
}
