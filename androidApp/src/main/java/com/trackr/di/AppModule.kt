package com.trackr.di

import android.content.Context
import com.trackr.TrackrApp
import com.trackr.data.location.AndroidDeviceLocationProvider
import com.trackr.data.location.DeviceLocationProvider
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.remote.amap.AmapKeyStore
import com.trackr.data.remote.amap.AmapSdkKeyApplier
import com.trackr.data.remote.amap.AmapSdkTransitService
import com.trackr.data.remote.amap.AndroidAmapSdkKeyApplier
import com.trackr.data.remote.update.GithubUpdateChecker
import com.trackr.data.remote.update.IUpdateChecker
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.service.AndroidTrackingController
import com.trackr.service.TrackingController
import com.trackr.ui.navigation.ScreenKey
import com.trackr.ui.screens.favoritesgroup.FavoritesGroupViewModel
import com.trackr.ui.screens.home.HomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.more.AboutViewModel
import com.trackr.ui.screens.more.MoreViewModel
import com.trackr.ui.screens.stopdetail.StopDetailViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 应用级依赖。
 *
 * 第 3 步：网络客户端、数据源和三个仓库仍由 [TrackrApp.onCreate] 构建（构建时机不变），这里只把现有实例交给 Koin。
 */
val appModule: Module = module {
    single<OkHttpClient> { (androidApplication() as TrackrApp).okHttpClient }
    single<TransitProviderCoordinator> { (androidApplication() as TrackrApp).coordinator }
    single<ITransitRepository> { (androidApplication() as TrackrApp).repository }
    single<IScheduledReminderRepository> { (androidApplication() as TrackrApp).reminderRepository }
    single<IRouteGroupRepository> { (androidApplication() as TrackrApp).routeGroupRepository }

    // 平台能力：ViewModel 不再直接拿 Context
    single<DeviceLocationProvider> { AndroidDeviceLocationProvider(androidContext()) }
    single<TrackingController> { AndroidTrackingController(androidContext()) }
    single<AmapSdkKeyApplier> { AndroidAmapSdkKeyApplier(androidContext()) }

    // 原来每个 AboutViewModel 都新建一个检查器，这里保持 factory
    factory<IUpdateChecker> { GithubUpdateChecker(get()) }
}

/** 6 个 ViewModel。带参数的页面用 `parametersOf(key)` 传入导航 key */
val viewModelModule: Module = module {
    viewModel {
        HomeViewModel(
            repository = get(),
            locationProvider = get(),
            trackingController = get()
        )
    }
    viewModel {
        val context = androidContext()
        FavoritesGroupViewModel(
            groupRepository = get(),
            transitRepository = get(),
            coordinator = get(),
            okHttpClient = get(),
            amapSdkService = runCatching { AmapSdkTransitService(context) }.getOrNull(),
            prefs = runCatching { context.getSharedPreferences(TRACKR_PREFS, Context.MODE_PRIVATE) }.getOrNull(),
            amapKeyPrefs = AmapKeyStore.prefs(context),
            amapSdkKeyApplier = get(),
            locationProvider = get()
        )
    }
    viewModel { (key: ScreenKey.LineDetail) ->
        LineDetailViewModel(
            cityId = key.cityId,
            initialLineId = key.lineId,
            initialTargetOrder = key.targetOrder.takeIf { it > 0 },
            initialStationId = key.stopId.takeIf { it.isNotBlank() },
            initialSource = key.source,
            lineNameHint = key.lineName,
            repository = get(),
            reminderRepository = get()
        )
    }
    viewModel { (key: ScreenKey.StopDetail) ->
        StopDetailViewModel(
            cityId = key.cityId,
            stopId = key.stopId,
            initialStopName = key.stopName,
            physicalStId = key.physicalStId,
            namesakeStId = key.namesakeStId,
            stationLat = key.lat,
            stationLng = key.lng,
            repository = get()
        )
    }
    viewModel { MoreViewModel(androidApplication(), get(), get()) }
    viewModel { AboutViewModel(androidApplication(), get()) }
}

/** 启动时装载的全部 Koin 模块；`KoinGraphVerifyTest` 逐个校验 */
val appModules: List<Module> = listOf(appModule, viewModelModule)

private const val TRACKR_PREFS = "trackr_prefs"
