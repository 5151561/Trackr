package com.trackr.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.trackr.TrackrApp
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实时公交前台追踪服务，负责定时轮询在途车辆并维持实时活动通知栏与到站预警
 */
class BusTrackingService : LifecycleService() {

    private var pollingJob: Job? = null
    private var currentSession: TrackingSessionState = TrackingSessionState()
    private var lineDirection: Int = 0
    private var mygolbsCityCode: String = ""
    private var dataSourceType: DataSourceType = DataSourceType.CHELAILE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START_TRACKING -> handleStartTracking(intent)
            ACTION_STOP_TRACKING -> handleStopTracking()
            ACTION_CYCLE_THRESHOLD -> handleCycleThreshold()
            ACTION_CONFIRM_BOARDED -> handleConfirmBoarded()
        }

        return START_NOT_STICKY
    }

    private fun handleStartTracking(intent: Intent) {
        val cityId = intent.getStringExtra(EXTRA_CITY_ID).orEmpty()
        val lineId = intent.getStringExtra(EXTRA_LINE_ID).orEmpty()
        val lineName = intent.getStringExtra(EXTRA_LINE_NAME).orEmpty()
        val endStop = intent.getStringExtra(EXTRA_END_STOP).orEmpty()
        val targetStopId = intent.getStringExtra(EXTRA_TARGET_STOP_ID).orEmpty()
        val targetStopName = intent.getStringExtra(EXTRA_TARGET_STOP_NAME).orEmpty()
        val targetOrder = intent.getIntExtra(EXTRA_TARGET_ORDER, 1)
        val totalStops = intent.getIntExtra(EXTRA_TOTAL_STOPS, targetOrder)
        val alertThreshold = intent.getIntExtra(EXTRA_ALERT_THRESHOLD, 2)
        val sourceName = intent.getStringExtra(EXTRA_LINE_SOURCE)
        dataSourceType = if (sourceName == DataSourceType.MYGOLBS.name) {
            DataSourceType.MYGOLBS
        } else {
            DataSourceType.CHELAILE
        }
        mygolbsCityCode = intent.getStringExtra(EXTRA_MYGOLBS_CITY_CODE).orEmpty()
        lineDirection = intent.getIntExtra(EXTRA_DIRECTION, 0)

        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME)
        val legIndex = intent.getIntExtra(EXTRA_LEG_INDEX, 0)
        val totalLegs = intent.getIntExtra(EXTRA_TOTAL_LEGS, 1)

        val isGroup = !groupId.isNullOrEmpty()
        // 分组换乘：上车站 A（boardStopOrder）作为 WAITING_BOARD 阶段目标
        val boardOrder = intent.getIntExtra(EXTRA_BOARD_ORDER, -1)
        val boardStopName = intent.getStringExtra(EXTRA_BOARD_STOP_NAME).orEmpty()
        // 单线路保持旧语义：从 (targetOrder-1) 作为起始基准
        val startTrackingOrder = if (isGroup && boardOrder > 0) {
            boardOrder
        } else {
            (targetOrder - 1).coerceAtLeast(1)
        }
        val legPhase = if (isGroup) LegPhase.WAITING_BOARD else LegPhase.SINGLE

        currentSession = TrackingSessionState(
            isActive = true,
            cityId = cityId,
            lineId = lineId,
            lineName = lineName,
            endStopName = endStop,
            targetStopId = targetStopId,
            targetStopName = targetStopName,
            targetOrder = targetOrder,
            totalStops = totalStops,
            startTrackingOrder = startTrackingOrder,
            alertThresholdStops = alertThreshold,
            groupId = groupId,
            groupName = groupName,
            currentLegIndex = legIndex,
            totalLegs = totalLegs,
            legPhase = legPhase,
            boardStopOrder = if (boardOrder > 0) boardOrder else 0,
            boardStopName = boardStopName,
            statusSummary = if (isGroup) "正在连接实时公交数据，等待上车站车辆…" else "正在连接实时公交数据…",
            updatedAtMillis = System.currentTimeMillis()
        )

        // 1. 同步初始状态至 Repository
        TrackrApp.instance.repository.updateTrackingSession(currentSession)

        // 2. 构建通知并进入前台服务
        val notification = LiveProgressNotificationHelper.buildNotification(this, currentSession)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            LiveProgressNotificationHelper.NOTIFICATION_ID_LIVE_PROGRESS,
            notification,
            fgsType
        )

        // 3. 启动定时轮询协程
        startPolling()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            // 首次立即刷新
            pollLineDetail()
            while (isActive) {
                val intervalMs = TransferAutoSwitchManager.calculateAdaptivePollingInterval(currentSession)
                delay(intervalMs) // 自适应防刷轮询退避
                pollLineDetail()
            }
        }
    }

    private suspend fun pollLineDetail() {
        if (!currentSession.isActive) return

        val repository = TrackrApp.instance.repository
        val selectedCity = repository.selectedCity.value
        val targetCity = if (selectedCity.cityId == currentSession.cityId) {
            selectedCity
        } else {
            TransitCity(
                cityId = currentSession.cityId,
                cityName = "",
                mygolbsCityCode = mygolbsCityCode.takeIf { it.isNotEmpty() }
            )
        }

        val result = repository.getLineDetail(
            city = targetCity,
            lineId = currentSession.lineId,
            targetOrder = currentSession.targetOrder,
            stationId = currentSession.targetStopId,
            source = dataSourceType,
            lineNameHint = currentSession.lineName,
            directionHint = lineDirection
        )

        result.onSuccess { detail ->
            // 分组换乘使用两阶段追踪核心（等车站 A / 锁定车 -> 下车站 B）
            if (currentSession.legPhase != LegPhase.SINGLE) {
                handleGroupPollSuccess(detail.buses)
                return@onSuccess
            }

            val targetOrder = currentSession.targetOrder
            // 筛选未过站车辆（bus.order <= targetOrder）
            val candidateBuses = detail.buses.filter { it.order <= targetOrder }
            // 优先沿用当前正在追踪的车辆，若不存在或初次则选离目标站点最近的一辆车
            val trackedBus = if (currentSession.currentBusId != null) {
                candidateBuses.find { it.busId == currentSession.currentBusId }
            } else null
            val bestBus = trackedBus ?: candidateBuses.maxByOrNull { it.order.toFloat() + it.segmentProgress }

            val updatedState: TrackingSessionState

            if (bestBus != null) {
                val stopsRemaining = (targetOrder - bestBus.order).coerceAtLeast(0)
                val isArrivedAtStop = stopsRemaining == 0 && (bestBus.isArrived || bestBus.segmentProgress >= 0.98f)

                // 换车或初次锁定判定：当未锁定车辆或追踪的车辆发生变更时，重新确定起始追踪基准站
                val isNewVehicle = currentSession.currentBusId == null || currentSession.currentBusId != bestBus.busId

                val startOrder = if (isNewVehicle) {
                    if (bestBus.isArrived || bestBus.segmentProgress >= 0.95f) {
                        bestBus.order.coerceAtMost(targetOrder - 1).coerceAtLeast(1)
                    } else {
                        (bestBus.order - 1).coerceAtMost(targetOrder - 1).coerceAtLeast(1)
                    }
                } else {
                    currentSession.startTrackingOrder
                }

                val etaFormatted = RouteProjectionMath.formatEtaText(bestBus.etaSeconds, isArrivedAtStop)
                val statusSummary = when {
                    isArrivedAtStop -> "车辆已到达 ${currentSession.targetStopName}！"
                    stopsRemaining == 0 -> "车辆即将进站 ${currentSession.targetStopName}"
                    bestBus.etaSeconds > 0 -> "距 ${currentSession.targetStopName} 还有 $stopsRemaining 站（$etaFormatted）"
                    else -> "距 ${currentSession.targetStopName} 还有 $stopsRemaining 站"
                }

                var hasTriggeredApproaching = currentSession.hasTriggeredApproachingAlert
                var hasTriggeredArrived = currentSession.hasTriggeredArrivedAlert

                // 到站判定：stopsRemaining == 0 且 (已停靠 或 eta <= 45秒)
                val isArrivedCondition = stopsRemaining == 0 && (isArrivedAtStop || (bestBus.etaSeconds in 1..45))
                if (isArrivedCondition && !hasTriggeredArrived) {
                    ArrivalAlertManager.triggerArrivedAlert(
                        this@BusTrackingService,
                        currentSession.copy(
                            stopsRemaining = 0,
                            isBusArrivedAtStop = true,
                            etaSeconds = bestBus.etaSeconds
                        )
                    )
                    hasTriggeredArrived = true
                    hasTriggeredApproaching = true
                } else if (stopsRemaining <= currentSession.alertThresholdStops && !hasTriggeredApproaching && !isArrivedCondition) {
                    ArrivalAlertManager.triggerApproachingAlert(
                        this@BusTrackingService,
                        currentSession.copy(
                            stopsRemaining = stopsRemaining,
                            isBusArrivedAtStop = isArrivedAtStop,
                            etaSeconds = bestBus.etaSeconds
                        )
                    )
                    hasTriggeredApproaching = true
                }

                updatedState = currentSession.copy(
                    startTrackingOrder = startOrder,
                    currentBusId = bestBus.busId,
                    currentBusOrder = bestBus.order,
                    currentBusProgress = bestBus.segmentProgress,
                    isBusArrivedAtStop = isArrivedAtStop,
                    stopsRemaining = stopsRemaining,
                    etaSeconds = bestBus.etaSeconds,
                    expectedArrivalTime = bestBus.expectedArrivalTimeText,
                    hasTriggeredApproachingAlert = hasTriggeredApproaching,
                    hasTriggeredArrivedAlert = hasTriggeredArrived,
                    statusSummary = statusSummary,
                    updatedAtMillis = System.currentTimeMillis()
                )
            } else {
                val summary = when {
                    detail.operatingState != LineOperatingState.RUNNING ->
                        detail.statusDescription.ifEmpty { detail.operatingState.defaultLabel }
                    detail.buses.isNotEmpty() ->
                        "车辆均已过站，等待下一班车调度"
                    else ->
                        "暂无在途车辆运营数据"
                }

                updatedState = currentSession.copy(
                    currentBusId = null,
                    stopsRemaining = -1,
                    etaSeconds = -1,
                    statusSummary = summary,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }

            currentSession = updatedState
            repository.updateTrackingSession(updatedState)
            LiveProgressNotificationHelper.updateNotification(this@BusTrackingService, updatedState)

            if (!updatedState.groupId.isNullOrEmpty()) {
                TransferAutoSwitchManager.onLegProgressUpdated(this@BusTrackingService, updatedState)
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to refresh line detail", e)
            val updated = currentSession.copy(
                statusSummary = "实时数据刷新失败，正在重试…",
                updatedAtMillis = System.currentTimeMillis()
            )
            currentSession = updated
            repository.updateTrackingSession(updated)
            LiveProgressNotificationHelper.updateNotification(this@BusTrackingService, updated)
        }
    }

    /**
     * 分组换乘两阶段轮询处理：委托纯函数 [GroupLegTracker]，再处理提醒与换乘流转。
     */
    private fun handleGroupPollSuccess(buses: List<LiveBus>) {
        val repository = TrackrApp.instance.repository
        val update = GroupLegTracker.processPoll(currentSession, buses)
        var state = update.state

        // 到站/临近提醒：WAITING_BOARD 针对上车站；ON_BOARD 针对下车站
        if (state.legPhase == LegPhase.WAITING_BOARD) {
            if (update.reachedBoardStop && !state.hasTriggeredArrivedAlert) {
                ArrivalAlertManager.triggerArrivedAlert(
                    this,
                    state.copy(targetStopName = currentSession.boardStopName, stopsRemaining = 0, isBusArrivedAtStop = true)
                )
                state = state.copy(hasTriggeredArrivedAlert = true, hasTriggeredApproachingAlert = true)
            } else if (!update.reachedBoardStop && state.stopsRemaining in 0..state.alertThresholdStops &&
                !state.hasTriggeredApproachingAlert
            ) {
                ArrivalAlertManager.triggerApproachingAlert(
                    this,
                    state.copy(targetStopName = currentSession.boardStopName)
                )
                state = state.copy(hasTriggeredApproachingAlert = true)
            }
        }

        if (update.reachedBoardStop) {
            // 刚进入 ON_BOARD，重置下车站提醒触发标志
            state = state.copy(hasTriggeredApproachingAlert = false, hasTriggeredArrivedAlert = false)
        }

        if (state.legPhase == LegPhase.ON_BOARD) {
            val isDropArrived = update.reachedDropStop
            if (isDropArrived && !state.hasTriggeredArrivedAlert) {
                ArrivalAlertManager.triggerArrivedAlert(this, state.copy(stopsRemaining = 0, isBusArrivedAtStop = true))
                state = state.copy(hasTriggeredArrivedAlert = true, hasTriggeredApproachingAlert = true)
            } else if (!isDropArrived && state.stopsRemaining in 0..state.alertThresholdStops &&
                !state.hasTriggeredApproachingAlert && !state.isSignalLost
            ) {
                ArrivalAlertManager.triggerApproachingAlert(this, state)
                state = state.copy(hasTriggeredApproachingAlert = true)
            }
        }

        currentSession = state
        repository.updateTrackingSession(state)
        LiveProgressNotificationHelper.updateNotification(this, state)

        // 换乘流转评估（仅锁定车到下车站才会切程 / 完成）
        TransferAutoSwitchManager.onLegProgressUpdated(this, state)
    }

    /**
     * 手动“我已上车”：跳过等车阶段，锁定当前离上车站最近的车并切入 ON_BOARD。
     */
    private fun handleConfirmBoarded() {
        if (currentSession.legPhase != LegPhase.WAITING_BOARD) return
        val lockedId = currentSession.currentBusId
        val updated = currentSession.copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = lockedId,
            // 乘车区间从上车站算起
            startTrackingOrder = if (currentSession.boardStopOrder > 0) currentSession.boardStopOrder else currentSession.startTrackingOrder,
            hasTriggeredApproachingAlert = false,
            hasTriggeredArrivedAlert = false,
            statusSummary = "已手动确认上车，开始追踪下车站 ${currentSession.targetStopName}",
            updatedAtMillis = System.currentTimeMillis()
        )
        currentSession = updated
        TrackrApp.instance.repository.updateTrackingSession(updated)
        LiveProgressNotificationHelper.updateNotification(this, updated)
    }

    private fun handleCycleThreshold() {
        val nextThreshold = when (currentSession.alertThresholdStops) {
            1 -> 2
            2 -> 3
            else -> 1
        }
        val resetApproaching = if (currentSession.stopsRemaining > nextThreshold) false else currentSession.hasTriggeredApproachingAlert
        val updated = currentSession.copy(
            alertThresholdStops = nextThreshold,
            hasTriggeredApproachingAlert = resetApproaching,
            updatedAtMillis = System.currentTimeMillis()
        )
        currentSession = updated
        TrackrApp.instance.repository.updateTrackingSession(updated)
        LiveProgressNotificationHelper.updateNotification(this, updated)
    }

    private fun handleStopTracking() {
        pollingJob?.cancel()
        pollingJob = null

        val stoppedState = TrackingSessionState(isActive = false)
        currentSession = stoppedState
        TrackrApp.instance.repository.updateTrackingSession(stoppedState)

        LiveProgressNotificationHelper.cancelNotification(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        pollingJob = null
        LiveProgressNotificationHelper.cancelNotification(this)
    }

    companion object {
        private const val TAG = "BusTrackingService"

        const val ACTION_START_TRACKING = "com.trackr.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.trackr.action.STOP_TRACKING"
        const val ACTION_CYCLE_THRESHOLD = "com.trackr.action.CYCLE_THRESHOLD"
        const val ACTION_CONFIRM_BOARDED = "com.trackr.action.CONFIRM_BOARDED"

        private const val EXTRA_CITY_ID = "extra_city_id"
        private const val EXTRA_LINE_ID = "extra_line_id"
        private const val EXTRA_LINE_NAME = "extra_line_name"
        private const val EXTRA_LINE_NO = "extra_line_no"
        private const val EXTRA_END_STOP = "extra_end_stop"
        private const val EXTRA_TARGET_STOP_ID = "extra_target_stop_id"
        private const val EXTRA_TARGET_STOP_NAME = "extra_target_stop_name"
        private const val EXTRA_TARGET_ORDER = "extra_target_order"
        private const val EXTRA_TOTAL_STOPS = "extra_total_stops"
        private const val EXTRA_ALERT_THRESHOLD = "extra_alert_threshold"
        private const val EXTRA_LINE_SOURCE = "extra_line_source"
        private const val EXTRA_MYGOLBS_CITY_CODE = "extra_mygolbs_city_code"
        private const val EXTRA_DIRECTION = "extra_direction"
        private const val EXTRA_GROUP_ID = "extra_group_id"
        private const val EXTRA_GROUP_NAME = "extra_group_name"
        private const val EXTRA_LEG_INDEX = "extra_leg_index"
        private const val EXTRA_TOTAL_LEGS = "extra_total_legs"
        private const val EXTRA_BOARD_ORDER = "extra_board_order"
        private const val EXTRA_BOARD_STOP_NAME = "extra_board_stop_name"

        /**
         * 启动多程换乘组中的特定程段追踪（WAITING_BOARD 阶段起步）
         *
         * @param boardStopName 上车站 A 名称
         * @param boardStopOrder 上车站 A 站序（WAITING_BOARD 阶段目标）
         * @param targetStopName 下车站 B 名称
         * @param targetOrder 下车站 B 站序（ON_BOARD 阶段目标）
         */
        fun startTrackingGroupLeg(
            context: Context,
            groupId: String,
            groupName: String,
            legIndex: Int,
            totalLegs: Int,
            cityId: String,
            lineId: String,
            lineName: String,
            lineDirection: Int,
            source: DataSourceType,
            mygolbsCityCode: String = "",
            targetStopName: String,
            targetStopId: String = "",
            targetOrder: Int,
            boardStopName: String,
            boardStopOrder: Int,
            endStopName: String = "",
            alertThresholdStops: Int = 2
        ) {
            val intent = Intent(context, BusTrackingService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_GROUP_ID, groupId)
                putExtra(EXTRA_GROUP_NAME, groupName)
                putExtra(EXTRA_LEG_INDEX, legIndex)
                putExtra(EXTRA_TOTAL_LEGS, totalLegs)
                putExtra(EXTRA_CITY_ID, cityId)
                putExtra(EXTRA_LINE_ID, lineId)
                putExtra(EXTRA_LINE_NAME, lineName)
                putExtra(EXTRA_DIRECTION, lineDirection)
                putExtra(EXTRA_LINE_SOURCE, source.name)
                putExtra(EXTRA_MYGOLBS_CITY_CODE, mygolbsCityCode)
                putExtra(EXTRA_TARGET_STOP_NAME, targetStopName)
                putExtra(EXTRA_TARGET_STOP_ID, targetStopId)
                putExtra(EXTRA_TARGET_ORDER, targetOrder)
                putExtra(EXTRA_BOARD_ORDER, boardStopOrder)
                putExtra(EXTRA_BOARD_STOP_NAME, boardStopName)
                putExtra(EXTRA_TOTAL_STOPS, targetOrder.coerceAtLeast(boardStopOrder))
                putExtra(EXTRA_END_STOP, endStopName)
                putExtra(EXTRA_ALERT_THRESHOLD, alertThresholdStops)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 手动确认“我已上车”，跳过等车阶段
         */
        fun confirmBoarded(context: Context) {
            val intent = Intent(context, BusTrackingService::class.java).apply {
                action = ACTION_CONFIRM_BOARDED
            }
            context.startService(intent)
        }

        /**
         * 启动实时公交在途追踪前台服务
         */
        fun startTracking(
            context: Context,
            cityId: String,
            line: BusLineDirection,
            targetStop: BusStop,
            totalStops: Int,
            alertThresholdStops: Int = 2
        ) {
            val intent = Intent(context, BusTrackingService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_CITY_ID, cityId)
                putExtra(EXTRA_LINE_ID, line.lineId)
                putExtra(EXTRA_LINE_NAME, line.name)
                putExtra(EXTRA_LINE_NO, line.lineNo)
                putExtra(EXTRA_END_STOP, line.endStop)
                putExtra(EXTRA_TARGET_STOP_ID, targetStop.stopId)
                putExtra(EXTRA_TARGET_STOP_NAME, targetStop.name)
                putExtra(EXTRA_TARGET_ORDER, targetStop.order)
                putExtra(EXTRA_TOTAL_STOPS, totalStops)
                putExtra(EXTRA_ALERT_THRESHOLD, alertThresholdStops)
                putExtra(EXTRA_LINE_SOURCE, line.source.name)
                putExtra(EXTRA_MYGOLBS_CITY_CODE, line.mygolbsCityCode)
                putExtra(EXTRA_DIRECTION, line.direction)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 停止实时公交追踪服务
         */
        fun stopTracking(context: Context) {
            val intent = Intent(context, BusTrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }

        /**
         * 切换提前站数提醒阈值 (1 -> 2 -> 3 -> 1)
         */
        fun cycleThreshold(context: Context) {
            val intent = Intent(context, BusTrackingService::class.java).apply {
                action = ACTION_CYCLE_THRESHOLD
            }
            context.startService(intent)
        }
    }
}
