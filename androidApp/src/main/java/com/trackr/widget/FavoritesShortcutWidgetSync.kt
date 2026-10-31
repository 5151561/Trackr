package com.trackr.widget

import android.content.Context
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.model.FavoriteStationLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收藏内容真正变化时才同步桌面快捷方式 (App Shortcuts) 与桌面小组件 (AppWidget)，切离主线程调度。
 *
 * 原来挂在 MainActivity 的组合里（`LaunchedEffect`），App 不打开就不同步；第 4 步挪到应用级作用域，
 * 由 Application 启动时调用一次 [start]。
 * StateFlow 只在内容不同（equals）时才发新值；跳过订阅时的当前值，冷启动不会多刷一次。
 */
class FavoritesShortcutWidgetSync(
    context: Context,
    private val repository: ITransitRepository,
    private val appScope: CoroutineScope,
    /** 刷新快捷方式和小组件用的线程；测试可替换 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 收藏变了以后做什么；默认刷新快捷方式和小组件，测试可替换 */
    private val onFavoritesChanged: (Context, List<FavoriteStationLine>) -> Unit = { ctx, favorites ->
        BusStopWidgetProvider.updateDynamicShortcuts(ctx, favorites)
        BusStopWidgetProvider.triggerRefresh(ctx)
    }
) {

    private val appContext: Context = context.applicationContext ?: context

    private var job: Job? = null

    /** 开始收集；重复调用不会开第二个收集 */
    fun start() {
        if (job?.isActive == true) return
        job = appScope.launch {
            repository.favorites
                .drop(1)
                .collect { favorites ->
                    withContext(ioDispatcher) {
                        onFavoritesChanged(appContext, favorites)
                    }
                }
        }
    }
}
