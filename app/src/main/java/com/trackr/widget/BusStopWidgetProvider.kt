package com.trackr.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.trackr.TrackrApp
import com.trackr.MainActivity
import com.trackr.R
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.TransitCity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BusStopWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        updateAppWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, BusStopWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            updateAppWidgets(context, appWidgetManager, appWidgetIds)
        }
    }

    /**
     * 刷新全部小组件实例：所有实例展示同一条收藏，只请求一次实时数据再分发给各实例。
     * 使用 goAsync() 让广播在后台请求完成前保持存活，并以 [FETCH_TIMEOUT_MS] 兜底，确保在系统 10 秒限制内结束。
     */
    private fun updateAppWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray?
    ) {
        if (appWidgetIds == null || appWidgetIds.isEmpty()) return
        try {
            val app = context.applicationContext as? TrackrApp ?: TrackrApp.instance
            val repository = try { app.repository } catch (e: Exception) { null }
            val favorites = repository?.favorites?.value.orEmpty()
            val favorite = favorites.firstOrNull()

            // 1. 同步构建并立即推送初始视图，确保启动器在添加微件瞬间获得有效布局，避免出现超时或空白
            for (appWidgetId in appWidgetIds) {
                appWidgetManager.updateAppWidget(appWidgetId, buildBaseRemoteViews(context, appWidgetId, favorite))
            }

            // 2. 如果存在收藏，后台拉取一次实时到站并更新所有实例
            if (favorite == null || repository == null) return
            val pendingResult = goAsync()
            widgetScope.launch {
                try {
                    val (etaText, progressPercent) = try {
                        val city = TransitCity(
                            cityId = favorite.cityId,
                            cityName = favorite.cityName,
                            mygolbsCityCode = favorite.mygolbsCityCode.takeIf { it.isNotBlank() }
                        )
                        val detail = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                            repository.getLineDetail(
                                city = city,
                                lineId = favorite.lineId,
                                targetOrder = favorite.targetOrder,
                                stationId = favorite.targetStopId,
                                source = favorite.source,
                                lineNameHint = favorite.lineName,
                                directionHint = favorite.direction
                            ).getOrNull()
                        }
                        buildEta(favorite, detail)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "网络连接异常，点击刷新" to null
                    }

                    for (appWidgetId in appWidgetIds) {
                        val updatedViews = buildBaseRemoteViews(context, appWidgetId, favorite)
                        updatedViews.setTextViewText(R.id.widget_eta_large, etaText)
                        if (progressPercent != null) {
                            updatedViews.setProgressBar(R.id.widget_progress_bar, 100, progressPercent, false)
                        }
                        appWidgetManager.updateAppWidget(appWidgetId, updatedViews)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 根据线路详情计算小组件主文案与进度（进度为 null 表示保持默认）。
     * 最近车按 order + segmentProgress 选取，与其他页面口径一致。
     */
    private fun buildEta(favorite: FavoriteStationLine, detail: BusLineDetail?): Pair<String, Int?> {
        if (detail == null) return "点击刷新同步数据" to 0

        val nearestBus = detail.buses
            .filter { it.order <= favorite.targetOrder }
            .maxByOrNull { it.order + it.segmentProgress }
            ?: return detail.statusDescription.ifBlank {
                if (detail.operatingState.defaultLabel.isNotEmpty()) {
                    detail.operatingState.defaultLabel
                } else {
                    "暂无在途车辆"
                }
            } to 0

        val stopsRemaining = favorite.targetOrder - nearestBus.order
        return if (stopsRemaining == 0 && nearestBus.isArrived) {
            "已到达 ${favorite.targetStopName}" to 100
        } else if (stopsRemaining == 0) {
            "即将进站" to 92
        } else {
            val etaFormatted = RouteProjectionMath.formatEtaText(nearestBus.etaSeconds)
            val totalToTarget = favorite.targetOrder.coerceAtLeast(1)
            val currentSegment = (nearestBus.order - 1).coerceAtLeast(0) + nearestBus.segmentProgress
            "还有 $stopsRemaining 站 · $etaFormatted" to ((currentSegment / totalToTarget) * 100).toInt().coerceIn(5, 95)
        }
    }

    private fun buildBaseRemoteViews(
        context: Context,
        appWidgetId: Int,
        favorite: FavoriteStationLine?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_bus_stop)

        // 点击小组件主视图打开应用
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (favorite != null) {
                putExtra("extra_city_id", favorite.cityId)
                putExtra("extra_line_id", favorite.lineId)
                putExtra("extra_target_order", favorite.targetOrder)
                putExtra("extra_stop_id", favorite.targetStopId)
                putExtra("extra_source", favorite.source.name)
                putExtra("extra_line_name", favorite.lineName)
            }
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)

        // 绑定右上角刷新按钮
        val refreshIntent = Intent(context, BusStopWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        views.setTextViewText(R.id.widget_updated_time, timeFormat.format(Date()))

        if (favorite == null) {
            views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
            views.setViewVisibility(R.id.widget_content_view, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_empty_view, View.GONE)
            views.setViewVisibility(R.id.widget_content_view, View.VISIBLE)

            views.setTextViewText(R.id.widget_line_name, favorite.lineName)
            views.setTextViewText(R.id.widget_city_tag, "${favorite.cityName} · ${favorite.source.displayName}")
            views.setTextViewText(R.id.widget_direction, "开往 ${favorite.endStop}")
            views.setTextViewText(R.id.widget_stop_name, "候车站: ${favorite.targetStopName}")
            views.setTextViewText(R.id.widget_eta_large, "查询中…")
            views.setProgressBar(R.id.widget_progress_bar, 100, 0, false)
        }

        return views
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.trackr.action.WIDGET_REFRESH"

        /** 实时请求超时：goAsync 的广播需在 10 秒内 finish，留出更新视图的余量 */
        private const val FETCH_TIMEOUT_MS = 7_000L

        /** 进程级作用域：BroadcastReceiver 实例每次广播都会重建，不能把作用域放在实例字段里 */
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 触发所有桌面小组件刷新
         */
        fun triggerRefresh(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, BusStopWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                    val intent = Intent(context, BusStopWidgetProvider::class.java).apply {
                        action = ACTION_WIDGET_REFRESH
                    }
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 使用 ShortcutManagerCompat 将前 3 个常用候车站注册为桌面长按图标快捷方式
         */
        fun updateDynamicShortcuts(context: Context, favorites: List<FavoriteStationLine>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
            try {
                val shortcuts = favorites.take(3).mapIndexed { index, fav ->
                    val intent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("extra_city_id", fav.cityId)
                        putExtra("extra_line_id", fav.lineId)
                        putExtra("extra_target_order", fav.targetOrder)
                        putExtra("extra_stop_id", fav.targetStopId)
                        putExtra("extra_source", fav.source.name)
                        putExtra("extra_line_name", fav.lineName)
                    }
                    ShortcutInfoCompat.Builder(context, "shortcut_${fav.id}")
                        .setShortLabel("${fav.lineName}·${fav.targetStopName}")
                        .setLongLabel("${fav.lineName} 开往 ${fav.endStop} (${fav.targetStopName})")
                        .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_dialog_map))
                        .setIntent(intent)
                        .setRank(index)
                        .build()
                }
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
