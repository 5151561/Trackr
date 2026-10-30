package com.trackr.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.TrackingSessionState

/**
 * 负责管理高优先级到站/进站预警通知渠道及震动声音提醒
 */
object ArrivalAlertManager {

    private const val TAG = "ArrivalAlertManager"

    const val CHANNEL_ID_ALERT = "bus_arrival_alert"
    private const val CHANNEL_NAME_ALERT = "公交到站与进站强提醒"

    const val NOTIFICATION_ID_APPROACHING = 10002
    const val NOTIFICATION_ID_ARRIVED = 10003
    const val NOTIFICATION_ID_TRANSFER = 10004

    private val APPROACHING_VIBRATION_PATTERN = longArrayOf(0, 280, 120, 280, 120, 400)
    private val TRANSFER_VIBRATION_PATTERN = longArrayOf(0, 300, 100, 300, 100, 300)
    private const val ARRIVED_VIBRATION_DURATION_MS = 800L

    /**
     * 确保高优先级预警渠道创建完毕
     */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID_ALERT)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_ALERT,
                    CHANNEL_NAME_ALERT,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "当追踪的公交车即将到站或已经进站时发送高优先级浮动与震动强提醒"
                    enableVibration(true)
                    vibrationPattern = APPROACHING_VIBRATION_PATTERN
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 当车辆距离目标候车站 stopsRemaining <= state.alertThresholdStops 时触发强提醒通知及三段式节奏震动
     */
    fun triggerApproachingAlert(context: Context, state: TrackingSessionState) {
        ensureNotificationChannel(context)

        val etaText = if (state.etaSeconds > 0) {
            "（${RouteProjectionMath.formatEtaText(state.etaSeconds)}）"
        } else {
            ""
        }
        val title = "【即将到站】${state.lineName}"
        val content = "距 ${state.targetStopName} 还有 ${state.stopsRemaining} 站$etaText，请准备上车/下车！"

        val contentPendingIntent = createMainActivityPendingIntent(context, NOTIFICATION_ID_APPROACHING)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_APPROACHING, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post approaching alert: missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post approaching alert notification", e)
        }

        performWaveformVibration(context, APPROACHING_VIBRATION_PATTERN)
    }

    /**
     * 当车辆到达目标候车站（stopsRemaining == 0 且 isBusArrivedAtStop 或 etaSeconds <= 45）时触发到站提醒及长震动
     */
    fun triggerArrivedAlert(context: Context, state: TrackingSessionState) {
        ensureNotificationChannel(context)

        val title = "【车辆已到站】${state.lineName}"
        val content = "${state.lineName} 已到达 ${state.targetStopName}！请注意上车/下车。"

        val contentPendingIntent = createMainActivityPendingIntent(context, NOTIFICATION_ID_ARRIVED)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_ARRIVED, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post arrived alert: missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post arrived alert notification", e)
        }

        performOneShotVibration(context, ARRIVED_VIBRATION_DURATION_MS)
    }

    /**
     * 当换乘行程第一程到达换乘站时触发换乘提醒及节拍震动，通知用户准备下车换乘
     */
    fun triggerTransferAlert(context: Context, transferStationName: String, nextLineName: String) {
        ensureNotificationChannel(context)

        val title = "【换乘提醒】到达 $transferStationName"
        val content = "已到达换乘站 $transferStationName，已自动切换追踪下一程：$nextLineName"

        val contentPendingIntent = createMainActivityPendingIntent(context, NOTIFICATION_ID_TRANSFER)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_TRANSFER, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post transfer alert: missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post transfer alert notification", e)
        }

        performWaveformVibration(context, TRANSFER_VIBRATION_PATTERN)
    }

    private fun createMainActivityPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "com.trackr.MainActivity")
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun performWaveformVibration(context: Context, pattern: LongArray) {
        try {
            val vibrator = getVibrator(context)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform waveform vibration", e)
        }
    }

    private fun performOneShotVibration(context: Context, durationMs: Long) {
        try {
            val vibrator = getVibrator(context)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform one shot vibration", e)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
