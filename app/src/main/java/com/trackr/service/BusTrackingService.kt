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
import com.trackr.domain.model.LineOperatingState
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
            startTrackingOrder = (targetOrder - 1).coerceAtLeast(1),
            alertThresholdStops = alertThreshold,
            statusSummary = "正在连接实时公交数据…",
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
                delay(10_000L) // 每 10 秒刷新一次
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
