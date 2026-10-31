package com.trackr.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.util.CoordTransform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.mp.KoinPlatform

/**
 * 智能自动换乘追踪引擎与自适应调度器
 *
 * 分组换乘按“两阶段”追踪每一程：
 * - WAITING_BOARD（等车）：目标上车站 A，只考虑未过 A 的车，取离 A 最近的一辆。
 * - ON_BOARD（已上车）：锁定具体车辆，目标下车站 B，只有锁定车到 B 才切下一程 / 完成。
 *
 * 到站判定基于“阶段 + 锁定车辆”，定位仅作为 ON_BOARD 阶段到达下车站的辅助条件。
 *
 * 第 4 步从 object 改成类，由 Koin 提供单例（换乘组状态只有这一份）；
 * 旧的 [TransferAutoSwitchManager] 暂时转发到这里（第 12 步删）。
 *
 * @param arrivalAlerts 到换乘站时发换乘提醒
 */
class TransferAutoSwitcher(
    private val arrivalAlerts: ArrivalAlertNotifier
) {

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

    @Volatile
    private var lastUserFix: UserFix? = null

    /** 最近一次手机定位快照（给转发对象的默认参数用） */
    internal val currentUserFix: UserFix?
        get() = lastUserFix

    /** 当前开机时长时钟，可在测试中替换 */
    internal var elapsedRealtimeNanosProvider: () -> Long = { SystemClock.elapsedRealtimeNanos() }

    /**
     * 服务存活时由 [BusTrackingService] 注册：在服务内部直接切换到新一程，
     * 不再 startForegroundService 绕一圈。返回 false 表示未处理，回落为发 Intent 启动服务。
     */
    @Volatile
    internal var legSwitcher: ((TrackingStartParams) -> Boolean)? = null

    /**
     * 更新手机用户当前定位（WGS-84，用于 ON_BOARD 阶段到达下车站的辅助测距）。
     * 只有 age <= 60s 且 accuracy <= 50m 的定位才参与判定。
     */
    fun updateUserFix(lat: Double, lng: Double, accuracyMeters: Float?, elapsedRealtimeNanos: Long) {
        lastUserFix = UserFix(lat, lng, accuracyMeters, elapsedRealtimeNanos)
    }

    /**
     * 旧接口：只有经纬度，没有精度和时间，无法判断是否过期。
     * 保留签名供旧调用方编译通过，但不再参与到站判定。
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateUserLocation(lat: Double, lng: Double) {
        // 不再记录：无精度/时间的坐标可能是几小时前的，会误判到站
    }

    /** 清空定位快照（停止追踪、切程时调用） */
    fun clearUserFix() {
        lastUserFix = null
    }

    /** 定位是否足够新且足够准，可用于下车站辅助判定 */
    fun isUserFixUsable(fix: UserFix?, nowElapsedNanos: Long = elapsedRealtimeNanosProvider()): Boolean {
        if (fix == null) return false
        val accuracy = fix.accuracyMeters ?: return false
        if (accuracy <= 0f || accuracy > USER_FIX_MAX_ACCURACY_METERS) return false
        val age = nowElapsedNanos - fix.elapsedRealtimeNanos
        return age in 0..USER_FIX_MAX_AGE_NANOS
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
        clearUserFix()

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
        val params = BusTrackingService.groupLegParams(
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
        // 服务已在运行：内部直接切换会话；否则发 Intent 启动前台服务
        val handled = legSwitcher?.invoke(params) == true
        if (!handled) {
            ContextCompat.startForegroundService(context, params.toIntent(context))
        }
    }

    /**
     * 自适应防刷轮询退避算法：
     * - 连续失败 n 次：在正常间隔基础上 ×2^n 指数退避，最多 120 秒；成功后（n=0）恢复
     * - 线路非运营：5 分钟一次
     * - 即将到站（<= 1 站 或 ETA <= 180 秒）：10 秒高效轮询
     * - 中距离（2-4 站 或 ETA <= 600 秒）：20 秒平稳轮询
     * - 远距离（> 4 站 或 暂无实时数据）：45 秒节能防刷轮询
     * - 锁定车辆信号丢失期间：至少 30 秒
     *
     * @param consecutiveFailures 连续刷新失败次数
     * @param operatingState 最近一次成功刷新拿到的线路运营状态
     */
    fun calculateAdaptivePollingInterval(
        session: TrackingSessionState,
        consecutiveFailures: Int = 0,
        operatingState: LineOperatingState = LineOperatingState.RUNNING
    ): Long {
        val stopsRemaining = session.stopsRemaining
        val etaSeconds = session.etaSeconds

        val base = when {
            stopsRemaining in 0..1 || (etaSeconds in 1..180) -> 10_000L
            stopsRemaining in 2..4 || (etaSeconds in 181..600) -> 20_000L
            else -> 45_000L
        }

        if (consecutiveFailures > 0) {
            val shift = consecutiveFailures.coerceAtMost(4)
            return (base shl shift).coerceAtMost(MAX_FAILURE_BACKOFF_MS)
        }
        if (operatingState != LineOperatingState.RUNNING) return NOT_RUNNING_POLL_INTERVAL_MS
        if (session.isSignalLost) return base.coerceAtLeast(SIGNAL_LOST_MIN_INTERVAL_MS)
        return base
    }

    /**
     * ON_BOARD 阶段到达下车站判定（基于阶段 + 锁定车辆）：
     * 1. 公交到达事件（stopsRemaining == 0 且已停靠/ETA 在 1..45s）
     * 2. 手机空间定位辅助（距下车站 < 150m）——仅 ON_BOARD 阶段、且定位 age<=60s、accuracy<=50m 时有效
     *
     * WAITING_BOARD 阶段恒返回 false：等车阶段不能用定位触发切程。
     */
    fun isArrivalSatisfied(
        session: TrackingSessionState,
        leg: TransitLeg?,
        userFix: UserFix? = lastUserFix,
        nowElapsedNanos: Long = elapsedRealtimeNanosProvider()
    ): Boolean {
        // WAITING_BOARD（或 SINGLE）阶段绝不在此触发下车站到达
        if (session.legPhase == LegPhase.WAITING_BOARD) return false

        // 1. 公交车辆自身事件判定（锁定车到下车站）
        val busCondition = ArrivalRules.isSessionArrived(session)

        // 2. 空间测距辅助判定，仅在 ON_BOARD 阶段生效；leg 坐标为 GCJ-02，用户坐标为 WGS-84，需转换对齐
        val locationCondition = if (session.legPhase == LegPhase.ON_BOARD && isUserFixUsable(userFix, nowElapsedNanos)) {
            val fix = requireNotNull(userFix)
            val targetLat = leg?.toStopLat ?: 0.0
            val targetLng = leg?.toStopLng ?: 0.0
            if (targetLat > 0.0 && targetLng > 0.0) {
                val (userGcjLat, userGcjLng) = CoordTransform.wgs84ToGcj02(fix.lat, fix.lng)
                val dist = RouteProjectionMath.calculateDistanceMeters(userGcjLat, userGcjLng, targetLat, targetLng)
                dist != null && dist < DROP_STOP_RADIUS_METERS
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
                // 非最后一程只发换乘提醒（内部会先取消到站/临近通知）
                arrivalAlerts.triggerTransferAlert(context, currentLeg?.toStopName.orEmpty(), nextLineName)
                clearUserFix()

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
                clearUserFix()
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
            clearUserFix()
            if (nextLine != null) {
                startTrackingLeg(context, group, nextLeg, nextLine, nextIndex)
            }
        }
    }

    /**
     * 只清空换乘组内存状态，不碰服务（服务自行停止时调用）
     */
    fun clearGroupState() {
        _activeGroupSession.value = null
        activeGroup = null
        selectedLineIds = emptyMap()
        clearUserFix()
    }

    /**
     * 终止换乘追踪会话（同时停止追踪服务）。
     * 单线路追踪时调用也安全：换乘组状态本来就是空的，只会停止服务。
     */
    fun stopGroupTracking(context: Context) {
        clearGroupState()
        BusTrackingService.stopTracking(context)
    }

    companion object {
        private const val TAG = "TransferAutoSwitch"

        /** 下车站定位辅助判定：定位最长有效期 */
        const val USER_FIX_MAX_AGE_NANOS = 60_000_000_000L

        /** 下车站定位辅助判定：定位精度上限（米） */
        const val USER_FIX_MAX_ACCURACY_METERS = 50f

        /** 下车站定位辅助判定：距下车站小于该距离（米）视为到达 */
        private const val DROP_STOP_RADIUS_METERS = 150

        /** 失败退避的最长间隔 */
        const val MAX_FAILURE_BACKOFF_MS = 120_000L

        /** 线路非运营（等待发车 / 停运 / 末班已过 / 无实时）时的轮询间隔 */
        const val NOT_RUNNING_POLL_INTERVAL_MS = 300_000L

        /** 锁定车辆信号丢失期间的最短轮询间隔 */
        const val SIGNAL_LOST_MIN_INTERVAL_MS = 30_000L
    }
}

/** 手机定位快照，第 7 步挪进 `:core:model`；这里保留旧名 `TransferAutoSwitchManager.UserFix` 的写法 */
typealias UserFix = TransferAutoSwitchManager.UserFix

/**
 * 旧入口：第 4 步起实现在 [TransferAutoSwitcher]（Koin 单例），这里只转发，第 12 步删。
 * 服务、通知动作接收器已改为注入 [TransferAutoSwitcher]；ViewModel 等其余调用方暂时还走这里。
 */
object TransferAutoSwitchManager {

    private val impl: TransferAutoSwitcher get() = KoinPlatform.getKoin().get()

    const val USER_FIX_MAX_AGE_NANOS = TransferAutoSwitcher.USER_FIX_MAX_AGE_NANOS
    const val USER_FIX_MAX_ACCURACY_METERS = TransferAutoSwitcher.USER_FIX_MAX_ACCURACY_METERS
    const val MAX_FAILURE_BACKOFF_MS = TransferAutoSwitcher.MAX_FAILURE_BACKOFF_MS
    const val NOT_RUNNING_POLL_INTERVAL_MS = TransferAutoSwitcher.NOT_RUNNING_POLL_INTERVAL_MS
    const val SIGNAL_LOST_MIN_INTERVAL_MS = TransferAutoSwitcher.SIGNAL_LOST_MIN_INTERVAL_MS

    /**
     * 手机定位快照（WGS-84）。
     * @param accuracyMeters 水平精度（米），未知为 null（未知时不参与判定）
     * @param elapsedRealtimeNanos 定位时刻（[SystemClock.elapsedRealtimeNanos] 时基，对应 Location.elapsedRealtimeNanos）
     */
    data class UserFix(
        val lat: Double,
        val lng: Double,
        val accuracyMeters: Float?,
        val elapsedRealtimeNanos: Long
    )

    val activeGroupSession: StateFlow<GroupTrackingSessionState?> get() = impl.activeGroupSession

    val activeGroupState: StateFlow<TransitGroup?> get() = impl.activeGroupState

    internal var elapsedRealtimeNanosProvider: () -> Long
        get() = impl.elapsedRealtimeNanosProvider
        set(value) {
            impl.elapsedRealtimeNanosProvider = value
        }

    internal var legSwitcher: ((TrackingStartParams) -> Boolean)?
        get() = impl.legSwitcher
        set(value) {
            impl.legSwitcher = value
        }

    fun updateUserFix(lat: Double, lng: Double, accuracyMeters: Float?, elapsedRealtimeNanos: Long) =
        impl.updateUserFix(lat, lng, accuracyMeters, elapsedRealtimeNanos)

    fun updateUserLocation(lat: Double, lng: Double) = impl.updateUserLocation(lat, lng)

    fun clearUserFix() = impl.clearUserFix()

    fun isUserFixUsable(fix: UserFix?, nowElapsedNanos: Long = impl.elapsedRealtimeNanosProvider()): Boolean =
        impl.isUserFixUsable(fix, nowElapsedNanos)

    fun startGroupTracking(
        context: Context,
        group: TransitGroup,
        startingLegIndex: Int = 0,
        selectedLineIds: Map<Int, String> = emptyMap()
    ) = impl.startGroupTracking(context, group, startingLegIndex, selectedLineIds)

    fun calculateAdaptivePollingInterval(
        session: TrackingSessionState,
        consecutiveFailures: Int = 0,
        operatingState: LineOperatingState = LineOperatingState.RUNNING
    ): Long = impl.calculateAdaptivePollingInterval(session, consecutiveFailures, operatingState)

    fun isArrivalSatisfied(
        session: TrackingSessionState,
        leg: TransitLeg?,
        userFix: UserFix? = impl.currentUserFix,
        nowElapsedNanos: Long = impl.elapsedRealtimeNanosProvider()
    ): Boolean = impl.isArrivalSatisfied(session, leg, userFix, nowElapsedNanos)

    fun onLegProgressUpdated(context: Context, session: TrackingSessionState): GroupTrackingSessionState? =
        impl.onLegProgressUpdated(context, session)

    fun confirmBoarded(context: Context) = impl.confirmBoarded(context)

    fun nextLegLineName(legIndex: Int): String? = impl.nextLegLineName(legIndex)

    fun switchToNextLeg(context: Context) = impl.switchToNextLeg(context)

    fun clearGroupState() = impl.clearGroupState()

    fun stopGroupTracking(context: Context) = impl.stopGroupTracking(context)
}
