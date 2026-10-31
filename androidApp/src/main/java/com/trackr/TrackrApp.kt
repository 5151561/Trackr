package com.trackr

import android.app.Application
import com.trackr.data.remote.amap.AmapKeyStore
import com.trackr.data.remote.amap.AmapSdkManager
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.di.appModules
import com.trackr.widget.FavoritesShortcutWidgetSync
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class TrackrApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 高德 SDK 必须在任何地图/搜索/定位实例创建之前完成隐私合规与 Key 注入，
        // 否则首次进入路线规划页会因未鉴权而只显示空白底图。
        runCatching {
            AmapSdkManager.initialize(applicationContext, AmapKeyStore.readAndroidKey(applicationContext))
        }

        // 网络客户端、数据源和三个仓库由 Koin 构建。startKoin 只登记定义；
        // 三个仓库按原来的顺序在这里取一次，构建（含读盘）的时机和顺序都和改造前一样
        startKoinOnce()
        get<ITransitRepository>()
        get<IScheduledReminderRepository>()
        get<IRouteGroupRepository>()

        // 收藏真变了才同步桌面快捷方式和小组件（原来在 MainActivity 里，App 不打开就不同步）
        get<FavoritesShortcutWidgetSync>().start()
    }

    private fun startKoinOnce() {
        // 生产环境每个进程只会走一次；Robolectric 每个用例都新建 Application，而 Koin 的全局容器跨用例保留，先停掉旧的
        if (GlobalContext.getOrNull() != null) stopKoin()
        startKoin {
            androidContext(this@TrackrApp)
            modules(appModules)
        }
    }
}
