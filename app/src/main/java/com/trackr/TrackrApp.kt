package com.trackr

import android.app.Application
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.chelaile.ChelaileTransitProvider
import com.trackr.data.provider.mygolbs.MygolbsTransitProvider
import com.trackr.data.remote.amap.AmapKeyStore
import com.trackr.data.remote.amap.AmapSdkManager
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.data.remote.mygolbs.MygolbsApiClient
import com.trackr.data.repository.ITransitRepository
import com.trackr.data.repository.TransitRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TrackrApp : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set

    lateinit var chelaileClient: ChelaileApiClient
        private set

    lateinit var mygolbsClient: MygolbsApiClient
        private set

    lateinit var chelaileProvider: ChelaileTransitProvider
        private set

    lateinit var mygolbsProvider: MygolbsTransitProvider
        private set

    lateinit var coordinator: TransitProviderCoordinator
        private set

    lateinit var repository: ITransitRepository
        private set

    lateinit var reminderRepository: com.trackr.data.repository.IScheduledReminderRepository
        private set

    lateinit var routeGroupRepository: com.trackr.data.repository.IRouteGroupRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 高德 SDK 必须在任何地图/搜索/定位实例创建之前完成隐私合规与 Key 注入，
        // 否则首次进入路线规划页会因未鉴权而只显示空白底图。
        runCatching {
            AmapSdkManager.initialize(applicationContext, AmapKeyStore.readAndroidKey(applicationContext))
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            // 整个调用（含重定向与重试）的总时限，防止弱网下单次请求无限挂起
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        chelaileClient = ChelaileApiClient(okHttpClient)
        mygolbsClient = MygolbsApiClient(okHttpClient)

        chelaileProvider = ChelaileTransitProvider(chelaileClient)
        mygolbsProvider = MygolbsTransitProvider(mygolbsClient)

        coordinator = TransitProviderCoordinator(
            chelaileProvider = chelaileProvider,
            mygolbsProvider = mygolbsProvider
        )

        repository = TransitRepository(
            context = applicationContext,
            coordinator = coordinator
        )

        reminderRepository = com.trackr.data.repository.ScheduledReminderRepository(
            context = applicationContext
        )

        routeGroupRepository = com.trackr.data.repository.RouteGroupRepository(
            context = applicationContext
        )
    }

    companion object {
        @Volatile
        lateinit var instance: TrackrApp
            private set
    }
}
