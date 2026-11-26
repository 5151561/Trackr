package com.trackr.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

/**
 * 刷新全部小组件实例：一次取数（[BusStopWidgetUpdater]），结果写给每个实例。
 * 周期刷新（原 `updatePeriodMillis` 的 30 分钟）和点刷新按钮、收藏变化、添加小组件都走这里。
 */
class BusStopWidgetWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val updater = get<BusStopWidgetUpdater>()
        val ids = BusStopWidget.glanceIds(context)
        if (ids.isEmpty()) return Result.success()
        if (updater.currentFavorite() == null) {
            // 没收藏：不发请求，重画成空状态
            BusStopWidget().updateAll(context)
            return Result.success()
        }
        updater.refresh(ids) { instances, eta -> BusStopWidget.publishEta(context, instances, eta) }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "bus_stop_widget_periodic"
        private const val REFRESH_WORK = "bus_stop_widget_refresh"

        /** 和原来 `bus_stop_widget_info.xml` 的 `updatePeriodMillis="1800000"` 一样 30 分钟 */
        private const val PERIOD_MINUTES = 30L

        /** 周期刷新；已排上就不动（KEEP），不会每次 onUpdate 重置周期 */
        fun schedulePeriodic(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<BusStopWidgetWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
                )
            }.onFailure { it.printStackTrace() }
        }

        /**
         * 马上刷新一次。REPLACE：收藏刚换时旧的刷新作废，免得把旧收藏的结果写上去。
         * 一直有 [PERIODIC_WORK] 在排队，一次性任务跑完不会触发 WorkManager 的 RescheduleReceiver 循环。
         */
        fun refreshNow(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    REFRESH_WORK,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<BusStopWidgetWorker>().build()
                )
            }.onFailure { it.printStackTrace() }
        }

        /** 最后一个实例移除后取消刷新 */
        fun cancel(context: Context) {
            runCatching {
                val workManager = WorkManager.getInstance(context)
                workManager.cancelUniqueWork(PERIODIC_WORK)
                workManager.cancelUniqueWork(REFRESH_WORK)
            }.onFailure { it.printStackTrace() }
        }
    }
}
