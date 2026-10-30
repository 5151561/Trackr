package com.trackr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.trackr.R
import com.trackr.TrackrApp
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.TrackingSessionState
import com.trackr.service.vendor.LiveUpdatePromotion
import com.trackr.service.vendor.OppoFluidCloudAdapter
import com.trackr.service.vendor.XiaomiFocusAdapter

/**
 * 负责构建常驻实时公交进度通知，深度适配 Android 16 / Core-KTX 1.19.0 的 NotificationCompat.ProgressStyle 实时活动
 */
object LiveProgressNotificationHelper {

    private const val TAG = "LiveProgressNotif"

    const val CHANNEL_ID_LIVE_PROGRESS = "bus_live_progress"
    private const val CHANNEL_NAME_LIVE_PROGRESS = "实时在途追踪与进度"

    const val NOTIFICATION_ID_LIVE_PROGRESS = 10001

    private val COLOR_COMPLETED = Color.parseColor("#FF1B8755") // 已行驶绿色段
    private val COLOR_REMAINING = Color.parseColor("#FF3B608C") // 剩余蓝色段

    /**
     * 是否开启常驻实时通知，以可注入 provider 的形式提供，
     * 避免直接耦合 TrackrApp 全局单例，同时保证单例未初始化时安全降级。
     * 兜底值为 true：读取失败时保持既有的实时活动参数注入行为，避免静默丢失流体云/胶囊能力。
     */
    internal var liveNotificationEnabledProvider: () -> Boolean = {
        runCatching { TrackrApp.instance.repository.isLiveNotificationEnabled.value }.getOrDefault(true)
    }

    internal fun resetLiveNotificationEnabledProvider() {
        liveNotificationEnabledProvider = {
            runCatching { TrackrApp.instance.repository.isLiveNotificationEnabled.value }.getOrDefault(true)
        }
    }

    /**
     * 确保常驻低干扰渠道创建
     */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID_LIVE_PROGRESS)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_LIVE_PROGRESS,
                    CHANNEL_NAME_LIVE_PROGRESS,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "常驻显示当前正在追踪公交的站间进度、剩余站数与预估时间"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 计算进度标尺：以「追踪起始站 → 目标候车站」的追踪区间为基准，
     * 保证进度条填充比例与「剩 N 站」文案始终一致
     */
    fun calculateSpanAndMaxProgress(startTrackingOrder: Int, targetOrder: Int): Pair<Int, Int> {
        val hops = (targetOrder - startTrackingOrder).coerceAtLeast(1)
        return hops to (hops * 100)
    }

    /**
     * 计算当前车辆相对于追踪区间的进度 [0, maxProgress]
     * 剩余站数是唯一基准：已完成站间数 = 区间站间数 - (剩余站数 + 未走完的当前站间比例)
     */
    fun calculateCurrentProgress(
        state: TrackingSessionState,
        isArrived: Boolean = state.stopsRemaining == 0 && (state.isBusArrivedAtStop || state.etaSeconds in 0..45)
    ): Int {
        val (hops, maxProgress) = calculateSpanAndMaxProgress(state.startTrackingOrder, state.targetOrder)
        if (isArrived) return maxProgress
        if (state.currentBusOrder <= 0 || state.stopsRemaining < 0) return 0

        // 用 Double 计算，避免 1 - 0.6f 之类的浮点误差把 260 截断成 259
        val segmentFraction = state.currentBusProgress.coerceIn(0f, 1f).toDouble()
        val remainingHops = state.stopsRemaining + (1.0 - segmentFraction)
        val completedHops = (hops - remainingHops).coerceIn(0.0, hops.toDouble())
        return (completedHops / hops * maxProgress).toInt().coerceIn(0, maxProgress)
    }

    /**
     * 构建站点标点：系统最多渲染 4 个，且首尾位置的标点会被丢弃，
     * 因此只在区间内部均匀取点，站距过密时抽稀
     */
    private fun buildStationPoints(
        span: Int,
        currentProgress: Int
    ): List<NotificationCompat.ProgressStyle.Point> {
        val maxPoints = 4
        if (span <= 1) return emptyList()
        val innerHops = (1 until span).toList()
        val step = ((innerHops.size + maxPoints - 1) / maxPoints).coerceAtLeast(1)
        return innerHops
            .filterIndexed { index, _ -> index % step == 0 }
            .take(maxPoints)
            .map { hopIndex ->
                val pointPos = hopIndex * 100
                val pointColor = if (pointPos <= currentProgress) COLOR_COMPLETED else COLOR_REMAINING
                NotificationCompat.ProgressStyle.Point(pointPos).setColor(pointColor)
            }
    }

    /**
     * 构建适合前台服务与常驻通知栏的 NotificationCompat 实例
     */
    fun buildNotification(context: Context, state: TrackingSessionState): Notification {
        ensureNotificationChannel(context)

        val (span, maxProgress) = calculateSpanAndMaxProgress(state.startTrackingOrder, state.targetOrder)
        val isArrived = state.stopsRemaining == 0 && (state.isBusArrivedAtStop || state.etaSeconds in 0..45)
        val currentProgress = calculateCurrentProgress(state, isArrived)

        // 3. 单段进度条：填充/未填充由 styledByProgress 统一着色，避免双段叠加导致填充比例失真
        val segments = listOf(
            NotificationCompat.ProgressStyle.Segment(maxProgress).setColor(COLOR_COMPLETED)
        )

        // 4. 站点标点：仅取区间内部站点，且受系统 4 个标点上限约束
        val points = buildStationPoints(span, currentProgress)

        val progressStyle = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(currentProgress)
            .setProgressSegments(segments)
            .setProgressPoints(points)
            .setProgressTrackerIcon(
                IconCompat.createWithResource(context, R.drawable.ic_stat_trackr)
            )

        // 5. Android 16 状态栏胶囊 Live Updates 短文本（<= 7 字符）
        val isMultiLeg = state.totalLegs > 1
        val currentLegNum = state.currentLegIndex + 1

        val shortCriticalText = when {
            isArrived && isMultiLeg && currentLegNum < state.totalLegs -> "换乘中"
            isArrived -> "已到站"
            state.stopsRemaining == 0 -> "即将进站"
            state.stopsRemaining > 0 -> "剩${state.stopsRemaining}站"
            else -> "追踪中"
        }

        // 6. 文案与展开大文本
        val title = if (isMultiLeg) {
            val destination = state.endStopName.ifEmpty { state.targetStopName }
            "[$currentLegNum/${state.totalLegs}程] ${state.lineName} 开往 $destination"
        } else if (state.endStopName.isNotEmpty()) {
            "${state.lineName} 开往 ${state.endStopName}"
        } else {
            "${state.lineName} 实时在途追踪"
        }

        val etaFormatted = RouteProjectionMath.formatEtaText(state.etaSeconds, state.isBusArrivedAtStop)
        val contentText = when {
            isArrived && isMultiLeg && currentLegNum < state.totalLegs ->
                "到达换乘站 ${state.targetStopName}，自动切换下一程"
            isArrived ->
                "目标站 ${state.targetStopName}：车辆已到达！"
            state.stopsRemaining == 0 && isMultiLeg ->
                "即将到达换乘站 ${state.targetStopName}（$etaFormatted）"
            state.stopsRemaining == 0 ->
                "目标站 ${state.targetStopName}：即将进站（$etaFormatted）"
            state.stopsRemaining > 0 && isMultiLeg ->
                "[$currentLegNum/${state.totalLegs}程 换乘] 距换乘站 ${state.targetStopName} 还有 ${state.stopsRemaining} 站"
            state.stopsRemaining > 0 ->
                "目标站 ${state.targetStopName}：剩 ${state.stopsRemaining} 站（$etaFormatted）"
            else ->
                "目标站 ${state.targetStopName}：${state.statusSummary}"
        }

        val subText = if (isMultiLeg) {
            "换乘追踪 · 第$currentLegNum/${state.totalLegs}程"
        } else {
            "候车追踪 · 第${state.targetOrder}站"
        }

        val bigSummaryText = buildString {
            appendLine("目标候车站：${state.targetStopName}（第 ${state.targetOrder} 站）")
            when {
                isArrived -> appendLine("车辆动态：车辆已到达！请准备上车")
                state.stopsRemaining == 0 -> appendLine("车辆动态：即将进站，预估时间 $etaFormatted")
                state.stopsRemaining > 0 -> appendLine("车辆动态：还有 ${state.stopsRemaining} 站到达，预估时间 $etaFormatted")
                else -> appendLine("状态：${state.statusSummary}")
            }
            if (!state.expectedArrivalTime.isNullOrEmpty()) {
                appendLine("预计到达时刻：${state.expectedArrivalTime}")
            }
            append("预警设置：提前 ${state.alertThresholdStops} 站强提醒（点击下方按钮切换）")
        }

        // 7. 通知点击与 Action Intent
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "com.trackr.MainActivity")
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            200,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cycleIntent = Intent(context, TrackingActionReceiver::class.java).apply {
            action = TrackingActionReceiver.ACTION_CYCLE_THRESHOLD
        }
        val cyclePendingIntent = PendingIntent.getBroadcast(
            context,
            201,
            cycleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, TrackingActionReceiver::class.java).apply {
            action = TrackingActionReceiver.ACTION_STOP_TRACKING
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isLiveEnabled = liveNotificationEnabledProvider()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LIVE_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_trackr)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(maxProgress, currentProgress, false)
            .setStyle(progressStyle)
            .setContentIntent(contentPendingIntent)
            .addAction(
                0,
                "提前${state.alertThresholdStops}站提醒(切换)",
                cyclePendingIntent
            )
            .addAction(
                0,
                "结束追踪",
                stopPendingIntent
            )

        if (isLiveEnabled) {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(shortCriticalText)
            if (XiaomiFocusAdapter.isXiaomiRom()) {
                XiaomiFocusAdapter.attachFocusParams(builder, state)
            }
        }

        val notification = builder.build()
        if (isLiveEnabled && LiveUpdatePromotion.isPromotionSupported()) {
            logPromotionState(context, notification)
        }
        return notification
    }

    private fun logPromotionState(context: Context, notification: Notification) {
        try {
            val canPost = LiveUpdatePromotion.canPromote(context)
            val promotable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                notification.hasPromotableCharacteristics()
            } else {
                false
            }
            Log.i(
                TAG,
                "promotion canPost=$canPost promotable=$promotable " +
                        "requested=${NotificationCompat.isRequestPromotedOngoing(notification)} " +
                        "fluidCloud=${OppoFluidCloudAdapter.isColorOs()}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect promotion state", e)
        }
    }

    /**
     * 刷新已有通知
     */
    fun updateNotification(context: Context, state: TrackingSessionState) {
        if (!state.isActive) {
            cancelNotification(context)
            return
        }
        val notification = buildNotification(context, state)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_LIVE_PROGRESS, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot update notification: missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update live progress notification", e)
        }
    }

    /**
     * 取消常驻通知
     */
    fun cancelNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_LIVE_PROGRESS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel live progress notification", e)
        }
    }
}
