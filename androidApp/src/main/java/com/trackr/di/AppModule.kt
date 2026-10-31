package com.trackr.di

import android.content.Context
import com.trackr.data.location.AndroidDeviceLocationProvider
import com.trackr.data.location.DeviceLocationProvider
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.chelaile.ChelaileTransitProvider
import com.trackr.data.provider.mygolbs.MygolbsTransitProvider
import com.trackr.data.remote.amap.AmapKeyStorage
import com.trackr.data.remote.amap.AmapSdkKeyApplier
import com.trackr.data.remote.amap.AmapSdkTransitService
import com.trackr.data.remote.amap.AndroidAmapSdkKeyApplier
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.data.remote.mygolbs.MygolbsApiClient
import com.trackr.data.remote.update.GithubUpdateChecker
import com.trackr.data.remote.update.IUpdateChecker
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.data.repository.ScheduledReminderRepository
import com.trackr.data.repository.TransitRepository
import com.trackr.service.AndroidTrackingController
import com.trackr.service.ArrivalAlertNotifier
import com.trackr.service.LiveProgressNotifier
import com.trackr.service.TrackingController
import com.trackr.service.TransferAutoSwitcher
import com.trackr.ui.navigation.ScreenKey
import com.trackr.ui.screens.favoritesgroup.FavoritesGroupViewModel
import com.trackr.ui.screens.home.HomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.more.AboutViewModel
import com.trackr.ui.screens.more.MoreViewModel
import com.trackr.ui.screens.stopdetail.StopDetailViewModel
import com.trackr.util.ReminderAlarmScheduler
import com.trackr.widget.FavoritesShortcutWidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.util.concurrent.TimeUnit

/** 应用级协程作用域的限定名：活得和进程一样久，接收器、小组件、收藏同步都用它 */
val AppScopeQualifier = named("app")

/**
 * 应用级依赖。
 *
 * 网络客户端、数据源和三个仓库原来在 TrackrApp.onCreate 里直接 new，现在由 Koin 构建。
 * 三个仓库仍由 TrackrApp.onCreate 按原顺序取一次（startKoin 之后），读盘时机和顺序都和原来一样。
 */
val appModule: Module = module {
    single<OkHttpClient> {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            // 整个调用（含重定向与重试）的总时限，防止弱网下单次请求无限挂起
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    single { ChelaileApiClient(get()) }
    single { MygolbsApiClient(get()) }
    single { ChelaileTransitProvider(get()) }
    single { MygolbsTransitProvider(get()) }
    single {
        TransitProviderCoordinator(
            chelaileProvider = get<ChelaileTransitProvider>(),
            mygolbsProvider = get<MygolbsTransitProvider>()
        )
    }
    single<ITransitRepository> {
        TransitRepository(context = androidContext(), coordinator = get())
    }
    single<IScheduledReminderRepository> {
        ScheduledReminderRepository(context = androidContext())
    }
    single<IRouteGroupRepository> {
        RouteGroupRepository(context = androidContext())
    }

    // 应用级作用域：SupervisorJob，一个任务失败不影响别的；Koin 关闭时取消
    single<CoroutineScope>(AppScopeQualifier) { CoroutineScope(SupervisorJob() + Dispatchers.Default) } onClose { it?.cancel() }

    // 收藏真变了才同步桌面快捷方式和小组件（原来挂在 MainActivity 的组合里）
    single { FavoritesShortcutWidgetSync(androidContext(), get(), get(AppScopeQualifier)) }

    // 原来是 object 的有状态组件，改成类后由 Koin 提供唯一实例；旧 object 转发到这里
    single {
        LiveProgressNotifier(
            liveNotificationSetting = { get<ITransitRepository>().isLiveNotificationEnabled.value },
            nextLegLineNameOf = { legIndex -> get<TransferAutoSwitcher>().nextLegLineName(legIndex) }
        )
    }
    single { ArrivalAlertNotifier(get()) }
    single { TransferAutoSwitcher(get()) }
    single { ReminderAlarmScheduler(androidContext()) }
    single { AmapKeyStorage(androidContext()) }

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
            amapKeyPrefs = get<AmapKeyStorage>().prefs(),
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
