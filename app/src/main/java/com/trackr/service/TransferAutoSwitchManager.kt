package com.trackr.service

import android.content.Context
import android.util.Log
import com.trackr.TrackrApp
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 智能自动换乘追踪引擎与自适应调度器
 * 负责跨段换乘自动判定（公交事件 + 手机空间定位双保险）、多段状态流转以及防刷自适应轮询退避
 */
object TransferAutoSwitchManager {

    private const val TAG = "TransferAutoSwitch"

    private val _activeGroupSession = MutableStateFlow<GroupTrackingSessionState?>(null)
    val activeGroupSession: StateFlow<GroupTrackingSessionState?> = _activeGroupSession.asStateFlow()

    private var activeGroup: TransitGroup? = null
    private var lastUserLat: Double? = null
    private var lastUserLng: Double? = null

    /**
     * 更新手机用户当前位置坐标（用于双保险空间测距）
     */
    fun updateUserLocation(lat: Double, lng: Double) {
        lastUserLat = lat
        lastUserLng = lng
    }

    /**
     * 启动整组多程换乘追踪
     */
    fun startGroupTracking(context: Context, group: TransitGroup, startingLegIndex: Int = 0) {
        if (group.legs.isEmpty()) return

        val initialLegIndex = startingLegIndex.coerceIn(0, group.legs.lastIndex)
        val leg = group.legs[initialLegIndex]
        val candidateLine = leg.primaryLine

        activeGroup = group

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

    /**
     * 针对当前程段启动 BusTrackingService
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
            targetStopName = leg.toStopName,
            targetStopId = leg.toStopId,
            targetOrder = line.toStopOrder,
            startTrackingOrder = line.fromStopOrder,
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
     * 到达判定双保险算法：
     * 1. 公交到达/离站事件（stopsRemaining == 0 且到达/停靠/预计时间接近 0）
     * 2. 手机空间定位（低功耗 Haversine 距离判定，距目标站 < 150m）
     */
    fun isArrivalSatisfied(
        session: TrackingSessionState,
        leg: TransitLeg?,
        userLat: Double? = lastUserLat,
        userLng: Double? = lastUserLng
    ): Boolean {
        // 1. 公交车辆自身事件判定
        val busCondition = session.stopsRemaining == 0 &&
                (session.isBusArrivedAtStop || (session.etaSeconds in 0..45))

        // 2. 空间测距双保险判定
        val targetLat = leg?.toStopLat ?: 0.0
        val targetLng = leg?.toStopLng ?: 0.0
        val locationCondition = if (userLat != null && userLng != null && targetLat > 0.0 && targetLng > 0.0) {
            val dist = RouteProjectionMath.calculateDistanceMeters(userLat, userLng, targetLat, targetLng)
            dist != null && dist < 150
        } else false

        return busCondition || locationCondition
    }

    /**
     * 当 BusTrackingService 轮询得到当前单程最新状态时调用，进行换乘流转评估
     * @return 返回更新后的 GroupTrackingSessionState
     */
    fun onLegProgressUpdated(context: Context, session: TrackingSessionState): GroupTrackingSessionState? {
        val currentSession = _activeGroupSession.value ?: return null
        val group = activeGroup ?: return null
        val currentLegIndex = currentSession.activeLegIndex
        val currentLeg = group.legs.getOrNull(currentLegIndex)

        // 双保险检查是否到站
        val isArrived = isArrivalSatisfied(session, currentLeg)

        val updatedGroupState: GroupTrackingSessionState

        if (isArrived && !currentSession.isCompleted) {
            val nextLegIndex = currentLegIndex + 1
            if (nextLegIndex < group.legs.size) {
                // 触发换乘提醒并自动进入下一程
                val nextLeg = group.legs[nextLegIndex]
                val nextLine = nextLeg.primaryLine
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
                // 整组全部程段已完结
                Log.d(TAG, "全部换乘程段已完成到达终点！")
                updatedGroupState = currentSession.copy(
                    isCompleted = true,
                    trackingSessionState = session.copy(
                        statusSummary = "已顺利到达终点站 ${currentLeg?.toStopName}！"
                    )
                )
                _activeGroupSession.value = updatedGroupState
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
     * 手动切换至下一程段
     */
    fun switchToNextLeg(context: Context) {
        val currentSession = _activeGroupSession.value ?: return
        val group = activeGroup ?: return
        val nextIndex = currentSession.activeLegIndex + 1
        if (nextIndex < group.legs.size) {
            val nextLeg = group.legs[nextIndex]
            val nextLine = nextLeg.primaryLine
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
        BusTrackingService.stopTracking(context)
    }
}
