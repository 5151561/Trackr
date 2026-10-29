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
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.TransitCity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BusStopWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, BusStopWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        scope.launch {
            val views = RemoteViews(context.packageName, R.layout.widget_bus_stop)
            val app = context.applicationContext as? TrackrApp ?: TrackrApp.instance
            val repository = try { app.repository } catch (e: Exception) { null }
            val favorites = repository?.favorites?.value.orEmpty()
            val favorite = favorites.firstOrNull()

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
                // 没有收藏
                views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
                views.setViewVisibility(R.id.widget_content_view, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_empty_view, View.GONE)
                views.setViewVisibility(R.id.widget_content_view, View.VISIBLE)

                views.setTextViewText(R.id.widget_line_name, favorite.lineName)
                views.setTextViewText(R.id.widget_city_tag, "${favorite.cityName} · ${favorite.source.displayName}")
                views.setTextViewText(R.id.widget_direction, "开往 ${favorite.endStop}")
                views.setTextViewText(R.id.widget_stop_name, "候车站: ${favorite.targetStopName}")

                // 拉取实时线路详情
                var etaText = "查询中…"
                var progressPercent = 0
                try {
                    val city = TransitCity(cityId = favorite.cityId, cityName = favorite.cityName)
                    val result = repository?.getLineDetail(
                        city = city,
                        lineId = favorite.lineId,
                        targetOrder = favorite.targetOrder,
                        stationId = favorite.targetStopId,
                        source = favorite.source,
                        lineNameHint = favorite.lineName,
                        directionHint = favorite.direction
                    )
                    val detail = result?.getOrNull()
                    if (detail != null) {
                        // 寻找尚未通过候车站的最近在途车辆 (order <= targetOrder)
                        val approachingBuses = detail.buses
                            .filter { it.order <= favorite.targetOrder }
                            .sortedByDescending { it.order }

                        val nearestBus = approachingBuses.firstOrNull()
                        if (nearestBus != null) {
                            val stopsRemaining = favorite.targetOrder - nearestBus.order
                            if (stopsRemaining == 0 && nearestBus.isArrived) {
                                etaText = "已到达 ${favorite.targetStopName}"
                                progressPercent = 100
                            } else if (stopsRemaining == 0) {
                                etaText = "即将进站"
                                progressPercent = 92
                            } else {
                                val etaFormatted = RouteProjectionMath.formatEtaText(nearestBus.etaSeconds)
                                etaText = "还有 $stopsRemaining 站 · $etaFormatted"
                                val totalToTarget = favorite.targetOrder.coerceAtLeast(1)
                                val currentSegment = (nearestBus.order - 1).coerceAtLeast(0) + nearestBus.segmentProgress
                                progressPercent = ((currentSegment / totalToTarget) * 100).toInt().coerceIn(5, 95)
                            }
                        } else {
                            etaText = detail.statusDescription.ifBlank {
                                if (detail.operatingState.defaultLabel.isNotEmpty()) {
                                    detail.operatingState.defaultLabel
                                } else {
                                    "暂无在途车辆"
                                }
                            }
                            progressPercent = 0
                        }
                    } else {
                        etaText = "点击刷新同步数据"
                    }
                } catch (e: Exception) {
                    etaText = "网络连接异常，点击刷新"
                }

                views.setTextViewText(R.id.widget_eta_large, etaText)
                views.setProgressBar(R.id.widget_progress_bar, 100, progressPercent, false)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.trackr.action.WIDGET_REFRESH"

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
