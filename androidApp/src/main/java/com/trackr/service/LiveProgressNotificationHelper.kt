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
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import com.trackr.service.vendor.LiveUpdatePromotion
import com.trackr.service.vendor.OppoFluidCloudAdapter
import com.trackr.service.vendor.XiaomiFocusAdapter
import org.koin.mp.KoinPlatform

/**
 * 负责构建常驻实时公交进度通知，深度适配 Android 16 / Core-KTX 1.19.0 的 NotificationCompat.ProgressStyle 实时活动
 *
 * 第 4 步从 object 改成类，由 Koin 提供单例；旧的 [LiveProgressNotificationHelper] 暂时转发到这里（第 12 步删）。
 *
 * @param liveNotificationSetting 读「实时通知」设置开关（Koin 里接仓库）
 * @param nextLegLineNameOf 第 N 程之后那一程的线路名（Koin 里接 [TransferAutoSwitcher]）
 */
class LiveProgressNotifier(
    private val liveNotificationSetting: () -> Boolean,
    private val nextLegLineNameOf: (legIndex: Int) -> String?
) {

    /**
     * 是否开启常驻实时通知，以可注入 provider 的形式提供，
     * 避免直接耦合全局单例，同时保证读取失败时安全降级。
     * 兜底值见 [readLiveNotificationEnabled]。
     */
    internal var liveNotificationEnabledProvider: () -> Boolean = { readLiveNotificationEnabled() }

    internal fun resetLiveNotificationEnabledProvider() {
        liveNotificationEnabledProvider = { readLiveNotificationEnabled() }
    }

    /**
     * 读取“实时通知”开关；读取失败时返回 [LIVE_NOTIFICATION_ENABLED_FALLBACK]。
     * 常驻通知与 [ArrivalAlertNotifier] 共用此函数，保证两处兜底值一致。
     */
    internal fun readLiveNotificationEnabled(): Boolean =
        runCatching { liveNotificationSetting() }
            .getOrDefault(LIVE_NOTIFICATION_ENABLED_FALLBACK)

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
     * 按阶段确定进度条区间（起始站序 → 目标站序）：
     * - WAITING_BOARD：选中车辆时它所在的站 → 上车站
     * - ON_BOARD：上车站 → 下车站
     * - SINGLE：追踪起始站 → 目标候车站（原有语义）
     */
    fun progressRangeOf(state: TrackingSessionState): Pair<Int, Int> = when (state.legPhase) {
        LegPhase.WAITING_BOARD -> {
            val board = if (state.boardStopOrder > 0) state.boardStopOrder else state.targetOrder
            state.startTrackingOrder.coerceAtMost(board - 1).coerceAtLeast(1) to board
        }
        LegPhase.ON_BOARD -> {
            val board = if (state.boardStopOrder > 0) state.boardStopOrder else state.startTrackingOrder
            board to state.targetOrder
        }
        LegPhase.SINGLE -> state.startTrackingOrder to state.targetOrder
    }

    /**
     * 计算当前车辆相对于追踪区间的进度 [0, maxProgress]
     * 剩余站数是唯一基准：已完成站间数 = 区间站间数 - (剩余站数 + 未走完的当前站间比例)
     */
    fun calculateCurrentProgress(
        state: TrackingSessionState,
        isArrived: Boolean = ArrivalRules.isSessionArrived(state)
    ): Int {
        val (startOrder, endOrder) = progressRangeOf(state)
        val (hops, maxProgress) = calculateSpanAndMaxProgress(startOrder, endOrder)
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
    fun buildNotification(
        context: Context,
        state: TrackingSessionState,
        nextLineName: String? = nextLegLineNameOf(state.currentLegIndex)
    ): Notification {
        ensureNotificationChannel(context)

        val (startOrder, endOrder) = progressRangeOf(state)
        val (span, maxProgress) = calculateSpanAndMaxProgress(startOrder, endOrder)
        val isArrived = isArrivedState(state)
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

        // 5~6. 胶囊短文本与标题 / 正文 / 展开文本（按阶段）
        val texts = buildTexts(state, nextLineName)

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

        val isLiveEnabled = liveNotificationEnabledProvider()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LIVE_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_trackr)
            .setContentTitle(texts.title)
            .setContentText(texts.content)
            .setSubText(texts.subText)
            .setStyle(progressStyle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(maxProgress, currentProgress, false)
            .setContentIntent(contentPendingIntent)

        // 按钮（最多 3 个）：等车 = 我已上车 / 提前N站提醒 / 结束；乘车 = 跳到下一程（非最后一程）/ 结束
        notificationActions(state).forEach { action ->
            builder.addAction(0, action.label, receiverPendingIntent(context, action.intentAction, action.requestCode))
        }

        if (isLiveEnabled) {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(texts.shortCriticalText)
            if (XiaomiFocusAdapter.isXiaomiRom()) {
                XiaomiFocusAdapter.attachFocusParams(builder, state, nextLineName)
            }
        }

        val notification = builder.build()
        if (isLiveEnabled && LiveUpdatePromotion.isPromotionSupported()) {
            logPromotionState(context, notification)
        }
        return notification
    }

    /** 通知上的文字 */
    data class NotificationTexts(
        val title: String,
        val content: String,
        val subText: String,
        val shortCriticalText: String
    )

    /** 通知按钮 */
    data class NotificationAction(val label: String, val intentAction: String, val requestCode: Int)

    internal fun isArrivedState(state: TrackingSessionState): Boolean =
        ArrivalRules.isSessionArrived(state)

    private fun isLastLeg(state: TrackingSessionState): Boolean =
        state.currentLegIndex >= state.totalLegs - 1

    private fun etaMinutesText(etaSeconds: Int): String? {
        if (etaSeconds <= 0) return null
        val minutes = Math.round(etaSeconds / 60.0).toInt().coerceAtLeast(1)
        return "约 $minutes 分钟"
    }

    /**
     * 状态栏胶囊短文本（Android 16 shortCriticalText，<= 7 字符）。
     * - 等车："B612 2站"，放不下时"等车2站"
     * - 乘车："2站下车"；到换乘站"换乘"
     */
    fun shortCriticalTextFor(state: TrackingSessionState): String {
        val isArrived = isArrivedState(state)
        return when (state.legPhase) {
            LegPhase.WAITING_BOARD -> when {
                state.stopsRemaining < 0 -> "等车中"
                isArrived -> "车已到"
                state.stopsRemaining == 0 -> "即将进站"
                else -> {
                    val withLine = "${state.lineName} ${state.stopsRemaining}站"
                    if (withLine.length <= MAX_SHORT_TEXT_LENGTH) withLine else "等车${state.stopsRemaining}站"
                }
            }
            LegPhase.ON_BOARD -> when {
                isArrived && !isLastLeg(state) -> "换乘"
                isArrived -> "已到站"
                state.stopsRemaining == 0 -> "准备下车"
                state.stopsRemaining > 0 -> "${state.stopsRemaining}站下车"
                else -> "乘车中"
            }
            LegPhase.SINGLE -> {
                val isMultiLeg = state.totalLegs > 1
                when {
                    isArrived && isMultiLeg && !isLastLeg(state) -> "换乘中"
                    isArrived -> "已到站"
                    state.stopsRemaining == 0 -> "即将进站"
                    state.stopsRemaining > 0 -> "剩${state.stopsRemaining}站"
                    else -> "追踪中"
                }
            }
        }.take(MAX_SHORT_TEXT_LENGTH)
    }

    /**
     * 按阶段生成通知文字。
     * @param nextLineName 下一程线路名（乘车阶段写"下车后换乘 X"；最后一程传 null）
     */
    fun buildTexts(state: TrackingSessionState, nextLineName: String? = null): NotificationTexts {
        val shortText = shortCriticalTextFor(state)
        val isArrived = isArrivedState(state)
        val legLabel = "第 ${state.currentLegIndex + 1}/${state.totalLegs.coerceAtLeast(1)} 程"
        val boardName = state.boardStopName.ifBlank { "上车站" }
        val dropName = state.targetStopName
        val eta = etaMinutesText(state.etaSeconds)

        return when (state.legPhase) {
            LegPhase.WAITING_BOARD -> {
                val title = when {
                    state.stopsRemaining < 0 -> "${state.lineName} 等车 · $boardName"
                    isArrived -> "${state.lineName} 已到 $boardName"
                    state.stopsRemaining == 0 -> "${state.lineName} 即将到 $boardName"
                    else -> "${state.lineName} 还有 ${state.stopsRemaining} 站到 $boardName"
                }
                val content = when {
                    state.stopsRemaining < 0 -> state.statusSummary
                    isArrived -> "车已进站，上车后点「我已上车」"
                    else -> eta ?: "等车中"
                }
                // 等车区间起点站名拿不到，只写目标站（已在标题中）
                NotificationTexts(title, content, "$legLabel · 等车", shortText)
            }

            LegPhase.ON_BOARD -> {
                val title = when {
                    isArrived -> "已到 $dropName，请下车"
                    state.stopsRemaining == 0 -> "即将到 $dropName，准备下车"
                    else -> "还有 ${state.stopsRemaining} 站在 $dropName 下车"
                }
                val transfer = nextLineName?.takeIf { !isLastLeg(state) && it.isNotBlank() }?.let { "下车后换乘 $it" }
                val range = state.boardStopName.takeIf { it.isNotBlank() }?.let { "$it → $dropName" }
                val content = if (state.isSignalLost) {
                    listOfNotNull("信号丢失，显示的是上次位置", transfer).joinToString(" · ")
                } else {
                    // ProgressStyle 没有展开大文本，区间两头站名放在内容末尾
                    listOfNotNull(eta, transfer, range).joinToString(" · ").ifEmpty { "乘车中" }
                }
                NotificationTexts(title, content, "$legLabel · 乘车", shortText)
            }

            LegPhase.SINGLE -> buildSingleTexts(state, shortText, isArrived)
        }
    }

    /** 单线路（非分组）追踪：保持原有文案 */
    private fun buildSingleTexts(state: TrackingSessionState, shortText: String, isArrived: Boolean): NotificationTexts {
        val isMultiLeg = state.totalLegs > 1
        val currentLegNum = state.currentLegIndex + 1
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

        return NotificationTexts(title, contentText, subText, shortText)
    }

    /** 按阶段决定通知按钮（最多 3 个） */
    fun notificationActions(state: TrackingSessionState): List<NotificationAction> {
        val cycle = NotificationAction(
            "提前${state.alertThresholdStops}站提醒",
            TrackingActionReceiver.ACTION_CYCLE_THRESHOLD,
            201
        )
        val stop = NotificationAction("结束", TrackingActionReceiver.ACTION_STOP_TRACKING, 202)
        return when (state.legPhase) {
            LegPhase.WAITING_BOARD -> listOf(
                NotificationAction("我已上车", TrackingActionReceiver.ACTION_CONFIRM_BOARDED, 203),
                cycle,
                stop
            )
            LegPhase.ON_BOARD -> buildList {
                if (!isLastLeg(state)) {
                    add(NotificationAction("跳到下一程", TrackingActionReceiver.ACTION_NEXT_LEG, 204))
                }
                add(stop)
            }
            LegPhase.SINGLE -> listOf(
                cycle.copy(label = "提前${state.alertThresholdStops}站提醒(切换)"),
                stop.copy(label = "结束追踪")
            )
        }
    }

    private fun receiverPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, TrackingActionReceiver::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    companion object {
        private const val TAG = "LiveProgressNotif"

        const val CHANNEL_ID_LIVE_PROGRESS = "bus_live_progress"
        private const val CHANNEL_NAME_LIVE_PROGRESS = "实时在途追踪与进度"

        const val NOTIFICATION_ID_LIVE_PROGRESS = 10001

        /** Android 16 胶囊短文本的建议长度上限 */
        private const val MAX_SHORT_TEXT_LENGTH = 7

        private val COLOR_COMPLETED = Color.parseColor("#FF1B8755") // 已行驶绿色段
        private val COLOR_REMAINING = Color.parseColor("#FF3B608C") // 剩余蓝色段

        /** 读取失败时的兜底值，与设置项默认值（开启）一致；常驻通知和强提醒共用 */
        internal const val LIVE_NOTIFICATION_ENABLED_FALLBACK = true
    }
}

/**
 * 旧入口：第 4 步起实现在 [LiveProgressNotifier]（Koin 单例），这里只转发，第 12 步删。
 * 服务、接收器已改为注入 [LiveProgressNotifier]；其余调用方暂时还走这里。
 */
object LiveProgressNotificationHelper {

    private val impl: LiveProgressNotifier get() = KoinPlatform.getKoin().get()

    const val CHANNEL_ID_LIVE_PROGRESS = LiveProgressNotifier.CHANNEL_ID_LIVE_PROGRESS
    const val NOTIFICATION_ID_LIVE_PROGRESS = LiveProgressNotifier.NOTIFICATION_ID_LIVE_PROGRESS
    internal const val LIVE_NOTIFICATION_ENABLED_FALLBACK = LiveProgressNotifier.LIVE_NOTIFICATION_ENABLED_FALLBACK

    internal var liveNotificationEnabledProvider: () -> Boolean
        get() = impl.liveNotificationEnabledProvider
        set(value) {
            impl.liveNotificationEnabledProvider = value
        }

    internal fun resetLiveNotificationEnabledProvider() = impl.resetLiveNotificationEnabledProvider()

    internal fun readLiveNotificationEnabled(): Boolean = impl.readLiveNotificationEnabled()

    fun ensureNotificationChannel(context: Context) = impl.ensureNotificationChannel(context)

    fun calculateSpanAndMaxProgress(startTrackingOrder: Int, targetOrder: Int): Pair<Int, Int> =
        impl.calculateSpanAndMaxProgress(startTrackingOrder, targetOrder)

    fun progressRangeOf(state: TrackingSessionState): Pair<Int, Int> = impl.progressRangeOf(state)

    fun calculateCurrentProgress(
        state: TrackingSessionState,
        isArrived: Boolean = ArrivalRules.isSessionArrived(state)
    ): Int = impl.calculateCurrentProgress(state, isArrived)

    fun buildNotification(context: Context, state: TrackingSessionState): Notification =
        impl.buildNotification(context, state)

    fun buildNotification(context: Context, state: TrackingSessionState, nextLineName: String?): Notification =
        impl.buildNotification(context, state, nextLineName)

    internal fun isArrivedState(state: TrackingSessionState): Boolean = impl.isArrivedState(state)

    fun shortCriticalTextFor(state: TrackingSessionState): String = impl.shortCriticalTextFor(state)

    fun buildTexts(state: TrackingSessionState, nextLineName: String? = null): LiveProgressNotifier.NotificationTexts =
        impl.buildTexts(state, nextLineName)

    fun notificationActions(state: TrackingSessionState): List<LiveProgressNotifier.NotificationAction> =
        impl.notificationActions(state)

    fun updateNotification(context: Context, state: TrackingSessionState) = impl.updateNotification(context, state)

    fun cancelNotification(context: Context) = impl.cancelNotification(context)
}
