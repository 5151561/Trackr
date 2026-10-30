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
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.TrackingSessionState
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
     * 构建适合前台服务与常驻通知栏的 NotificationCompat 实例
     */
    fun buildNotification(context: Context, state: TrackingSessionState): Notification {
        ensureNotificationChannel(context)

        // 1. 计算站间跨度与最大进度
        val span = (state.targetOrder - state.startTrackingOrder).coerceAtLeast(1)
        val maxProgress = span * 100

        // 2. 将当前车辆位置映射到 [0, maxProgress]
        val currentProgress = if (state.currentBusOrder <= 0) {
            0
        } else if (state.stopsRemaining == 0 && (state.isBusArrivedAtStop || state.etaSeconds in 0..45)) {
            maxProgress
        } else {
            val busContinuous = (state.currentBusOrder - 1) + state.currentBusProgress.coerceIn(0f, 1f)
            val startContinuous = (state.startTrackingOrder - 1).toFloat()
            val completedHops = (busContinuous - startContinuous).coerceIn(0f, span.toFloat())
            (completedHops * 100).toInt().coerceIn(0, maxProgress)
        }

        // 3. 构建 Android 16 ProgressStyle 分段（已行驶绿色段与剩余蓝色段）
        val completedLength = currentProgress.coerceIn(1, maxProgress)
        val remainingLength = (maxProgress - completedLength).coerceAtLeast(0)

        val segments = if (remainingLength > 0) {
            listOf(
                NotificationCompat.ProgressStyle.Segment(completedLength).setColor(COLOR_COMPLETED),
                NotificationCompat.ProgressStyle.Segment(remainingLength).setColor(COLOR_REMAINING)
            )
        } else {
            listOf(
                NotificationCompat.ProgressStyle.Segment(maxProgress).setColor(COLOR_COMPLETED)
            )
        }

        // 4. 构建沿途各个站点的 Point 标点
        val points = (1..span).map { hopIndex ->
            val pointPos = hopIndex * 100
            val pointColor = if (pointPos <= currentProgress) COLOR_COMPLETED else COLOR_REMAINING
            NotificationCompat.ProgressStyle.Point(pointPos).setColor(pointColor)
        }

        val progressStyle = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(currentProgress)
            .setProgressSegments(segments)
            .setProgressPoints(points)

        // 5. Android 16 状态栏胶囊 Live Updates 短文本（<= 7 字符）
        val shortCriticalText = when {
            state.stopsRemaining == 0 && (state.isBusArrivedAtStop || state.etaSeconds in 0..45) -> "已到站"
            state.stopsRemaining >= 0 -> "剩${state.stopsRemaining}站"
            else -> "追踪中"
        }

        // 6. 文案与展开大文本
        val title = if (state.endStopName.isNotEmpty()) {
            "${state.lineName} 开往 ${state.endStopName}"
        } else {
            "${state.lineName} 实时在途追踪"
        }

        val etaFormatted = RouteProjectionMath.formatEtaText(state.etaSeconds, state.isBusArrivedAtStop)
        val contentText = if (state.stopsRemaining >= 0) {
            "目标站 ${state.targetStopName}：剩 ${state.stopsRemaining} 站（$etaFormatted）"
        } else {
            "目标站 ${state.targetStopName}：${state.statusSummary}"
        }

        val bigSummaryText = buildString {
            appendLine("目标候车站：${state.targetStopName}（第 ${state.targetOrder} 站）")
            if (state.stopsRemaining >= 0) {
                appendLine("车辆动态：还有 ${state.stopsRemaining} 站到达，预估时间 $etaFormatted")
            } else {
                appendLine("状态：${state.statusSummary}")
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

        val isLiveEnabled = try {
            com.trackr.TrackrApp.instance.repository.isLiveNotificationEnabled.value
        } catch (_: Exception) {
            true
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LIVE_PROGRESS)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText("候车追踪 · 第${state.targetOrder}站")
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
            // 注入小米 HyperOS 焦点通知 / 超级岛协议参数
            XiaomiFocusAdapter.attachFocusParams(builder, state)
        }

        return builder.build()
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
