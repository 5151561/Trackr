@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.app.di

import com.trackr.app.platform.*
import com.trackr.core.data.local.*
import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.data.repository.*
import com.trackr.core.data.route.WebAmapRouteGateway
import com.trackr.core.database.createTrackrDatabase
import com.trackr.core.datastore.*
import com.trackr.core.domain.platform.*
import com.trackr.core.network.provider.chelaile.ChelaileTransitProvider
import com.trackr.core.network.provider.mygolbs.MygolbsTransitProvider
import com.trackr.core.network.remote.amap.AmapTransitApiClient
import com.trackr.core.network.remote.chelaile.ChelaileApiClient
import com.trackr.core.network.remote.mygolbs.MygolbsApiClient
import com.trackr.core.network.remote.common.createTrackrHttpClient
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.IScheduledReminderRepository
import kotlinx.coroutines.*
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.*
import kotlin.time.Clock

/** Owns the native application's stores and services, shared by every CMP destination. */
internal class IosAppRuntime(val module: Module, private val dispose: () -> Unit) {
    fun close() = dispose()

    companion object {
        suspend fun open(): IosAppRuntime = withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val db = createTrackrDatabase()
            val http = createTrackrHttpClient()
            try {
                val root = requireNotNull(NSFileManager.defaultManager.URLForDirectory(
                    NSApplicationSupportDirectory, NSUserDomainMask, null, true, null
                )?.path)
                val privateDir = "$root/private"
                check(NSFileManager.defaultManager.createDirectoryAtPath(privateDir, true, null, null))
                check(NSURL.fileURLWithPath(privateDir).setResourceValue(
                    NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null
                ))
                val store = RoomDataStoreTransitStore(db, createPreferencesDataStore("$root/datastore/trackr_settings.preferences_pb", scope))
                val writer = SerialWriter(scope)
                val settings = DefaultSettingsRepository.create(store, writer)
                val history = DefaultSearchHistoryRepository.create(store, writer)
                val favorites = DefaultFavoritesRepository.create(RoomFavoritesStore(db))
                val defaults = AmapKeyDefaults(hasAndroidMapSdk = false)
                val keys = DefaultAmapKeyRepository.create(
                    DataStoreAmapKeyStore(createPreferencesDataStore("$privateDir/trackr_amap_keys.preferences_pb", scope)), writer, defaults
                )
                val locationStore = IosUserLocationStore()
                val coordinator = TransitProviderCoordinator(
                    ChelaileTransitProvider(ChelaileApiClient(http, monotonicClock = IosMonotonicClock), IosMonotonicClock),
                    MygolbsTransitProvider(MygolbsApiClient(http, clock = IosMonotonicClock))
                )
                val tracking = DefaultTrackingRepository(
                    DataStoreTrackingSessionStore(createTrackingSessionDataStore("$privateDir/tracking_session.json", scope)),
                    scope, monotonicClock = IosMonotonicClock
                )
                IosAppRuntime(module {
                    single<SettingsRepository> { settings }
                    single<SearchHistoryRepository> { history }
                    single<FavoritesRepository> { favorites }
                    single<IRouteGroupRepository> { DefaultRouteGroupRepository(favorites) }
                    single { coordinator }
                    single<LocalMigrationFlags> { StoreMigrationFlags(store) }
                    single<AmapKeyRepository> { keys }
                    single { defaults }
                    single { AmapTransitApiClient(http) }
                    single<AmapRouteGateway> { WebAmapRouteGateway(get(), get()) }
                    single<UserLocationStore> { locationStore }
                    single<MonotonicClock> { IosMonotonicClock }
                    single { IosLocationSource() }
                    single<LocationSource> { get<IosLocationSource>() }
                    single<LocationRepository> { DefaultLocationRepository(get(), get()) }
                    single<RealtimeRepository> { DefaultRealtimeRepository(coordinator, settings, favorites, history,
                        userLocation = { locationStore.userLocation.value }, clock = IosMonotonicClock, fetchScope = scope) }
                    single<LineStopsCache> { RoomLineStopsCache(db.lineStopsCache()) { Clock.System.now().toEpochMilliseconds() } }
                    single<TrackingRepository> { tracking }
                    single<TrackingController> { IosUnavailableTrackingController }
                    single<IScheduledReminderRepository> { IosUnavailableReminders }
                    single<ReminderAlarms> { IosUnavailableReminders }
                    single<TransitCodeLauncher> { IosUnavailableTransitCode }
                    single<Announcer> { NoAnnouncer }
                    single<SystemSettingsLauncher> { IosSystemSettingsLauncher }
                    single<AppSigningInfo> { IosAppSigningInfo }
                    single { AppVersionInfo(
                        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 1,
                        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.1.0"
                    ) }
                }) { scope.cancel(); http.close(); db.close() }
            } catch (error: Throwable) {
                scope.cancel(); http.close(); db.close()
                throw error
            }
        }
    }
}
