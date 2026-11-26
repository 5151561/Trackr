package com.trackr.data.local

import android.content.Context
import android.util.Log
import com.trackr.BuildConfig
import com.trackr.core.data.legacy.LegacyAmapKeyMigration
import com.trackr.core.data.legacy.LegacyLibraryMigration
import com.trackr.core.data.legacy.LegacySettingsMigration
import com.trackr.core.data.local.DataStoreAmapKeyStore
import com.trackr.core.data.local.FavoritesStore
import com.trackr.core.data.local.ReminderStore
import com.trackr.core.data.local.RoomDataStoreTransitStore
import com.trackr.core.data.local.RoomFavoritesStore
import com.trackr.core.data.local.RoomReminderScheduleStateStore
import com.trackr.core.data.local.RoomReminderStore
import com.trackr.core.data.local.SerialWriter
import com.trackr.core.data.local.TransitLocalStore
import com.trackr.core.data.repository.AmapKeyDefaults
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.DataStoreTrackingSessionStore
import com.trackr.core.data.repository.DefaultAmapKeyRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.data.repository.TrackingSessionStore
import com.trackr.core.data.local.LineStopsCache
import com.trackr.core.data.local.NoLineStopsCache
import com.trackr.core.data.local.RoomLineStopsCache
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.database.createTrackrDatabase
import com.trackr.core.datastore.TrackrDataStoreFiles
import com.trackr.core.datastore.createPreferencesDataStore
import com.trackr.core.datastore.createTrackingSessionDataStore
import com.trackr.core.datastore.trackrDataStorePath
import com.trackr.util.CachedReminderScheduleState
import com.trackr.util.ReminderScheduleState
import com.trackr.util.SharedPrefsReminderScheduleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 这次启动用的整套本地存储（第 9 步）。按 [StoreSwitch] 选新旧：
 *
 * - 新：Room `trackr.db` + DataStore `trackr_settings` / `trackr_amap_keys` / `tracking_session`。打开时先跑 [LegacyLibraryMigration]
 *   （旧 SharedPreferences -> 新存储，只读不删）。**迁移出任何错，这次启动就退回旧存储**，用户看到的还是原来的数据，
 *   下次启动再试。
 * - 旧：原来的 SharedPreferences（`SharedPrefs*Store`）。
 *
 * 读盘都在 IO 线程上做，调用线程（Application.onCreate，主线程）等它做完，和旧版构造仓库时同步读 SharedPreferences 一样。
 */
class StorageBackend private constructor(
    /** true = 新存储 */
    val usesNewStore: Boolean,
    val transitStore: TransitLocalStore,
    /** 收藏合一（车、路线、收藏分组、合一索引；第 14 步） */
    val favoritesStore: FavoritesStore,
    val reminderStore: ReminderStore,
    val reminderScheduleState: ReminderScheduleState,
    val amapKeys: AmapKeyRepository,
    /** 追踪会话（第 12 步）：新存储落 DataStore `tracking_session.json`；旧存储只在内存里（和之前一样，进程没了就没了） */
    val trackingSessionStore: TrackingSessionStore,
    /** 线路站表磁盘缓存（第 20 步）：新存储落 Room `line_stops_cache`；旧存储没有 */
    val lineStopsCache: LineStopsCache,
    /** 新存储这次启动的迁移结果；旧存储为 null */
    val migrationReport: LegacyLibraryMigration.Report?,
    private val onClose: () -> Unit
) {

    fun close() = onClose()

    companion object {
        private const val TAG = "StorageBackend"

        /**
         * @param writeScope 写盘排队用的应用级作用域
         * @param forceLegacy 测试用：直接用旧存储
         */
        fun open(context: Context, writeScope: CoroutineScope, forceLegacy: Boolean = false): StorageBackend {
            val appContext = context.applicationContext ?: context
            if (!forceLegacy && StoreSwitch.useNewStore(appContext)) {
                // Room/DataStore may fail transiently (for example while the process is
                // being restored). Retry once before falling back. The fallback is an
                // automatic safety net and must not advance the legacy generation: doing
                // so would make a later successful open overwrite new-store data from a
                // legacy snapshot that was never intentionally used.
                repeat(2) { attempt ->
                    try {
                        return openNew(appContext, writeScope)
                    } catch (e: Exception) {
                        Log.e(TAG, "新存储打开或迁移失败（第 ${attempt + 1} 次）", e)
                    }
                }
                Log.e(TAG, "新存储重试仍失败，这次启动退回旧存储（旧数据未改动）")
                return openLegacy(appContext, writeScope, recordSession = false)
            }
            return openLegacy(appContext, writeScope)
        }

        /** 构建时注入的高德 Key（local.properties / 环境变量），两槽各自兜底（§2.5） */
        private fun amapKeyDefaults() = AmapKeyDefaults(
            androidKey = BuildConfig.AMAP_ANDROID_KEY,
            webKey = BuildConfig.AMAP_WEB_KEY,
            hasAndroidMapSdk = true
        )

        private fun newWriter(scope: CoroutineScope) = SerialWriter(scope).apply {
            onError = { Log.e(TAG, "写盘失败", it) }
        }

        private fun openLegacy(
            context: Context,
            writeScope: CoroutineScope,
            recordSession: Boolean = true
        ): StorageBackend {
            if (recordSession) StoreSwitch.recordOldStoreSession(context)
            val amapKeys = runBlocking(Dispatchers.IO) {
                DefaultAmapKeyRepository.create(SharedPrefsAmapKeyStore(context), newWriter(writeScope), amapKeyDefaults())
            }
            return StorageBackend(
                usesNewStore = false,
                transitStore = SharedPrefsTransitStore(context),
                favoritesStore = SharedPrefsFavoritesStore(context),
                reminderStore = SharedPrefsReminderStore(context),
                reminderScheduleState = SharedPrefsReminderScheduleState(context),
                amapKeys = amapKeys,
                trackingSessionStore = InMemoryTrackingSessionStore(),
                lineStopsCache = NoLineStopsCache,
                migrationReport = null,
                onClose = {}
            )
        }

        private fun openNew(context: Context, writeScope: CoroutineScope): StorageBackend {
            val generation = StoreSwitch.generation(context)
            // DataStore 的作用域：关掉它才能在同一进程里重新打开同一个文件
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var db: TrackrDatabase? = null
            try {
                val settings = createPreferencesDataStore(
                    path = context.trackrDataStorePath(TrackrDataStoreFiles.SETTINGS),
                    scope = dataStoreScope,
                    migrations = listOf(LegacySettingsMigration(context, generation))
                )
                val keys = createPreferencesDataStore(
                    path = context.trackrDataStorePath(TrackrDataStoreFiles.AMAP_KEYS),
                    scope = dataStoreScope,
                    migrations = listOf(LegacyAmapKeyMigration(context, generation))
                )
                val trackingSession = createTrackingSessionDataStore(
                    path = context.trackrDataStorePath(TrackrDataStoreFiles.TRACKING_SESSION),
                    scope = dataStoreScope
                )
                val database = createTrackrDatabase(context).also { db = it }

                return runBlocking(Dispatchers.IO) {
                    val report = LegacyLibraryMigration(
                        context = context,
                        db = database,
                        settings = settings,
                        amapKeys = keys,
                        generation = generation,
                        log = { Log.i(TAG, it) }
                    ).runIfNeeded()
                    StorageBackend(
                        usesNewStore = true,
                        transitStore = RoomDataStoreTransitStore(database, settings),
                        favoritesStore = RoomFavoritesStore(database),
                        reminderStore = RoomReminderStore(database),
                        reminderScheduleState = CachedReminderScheduleState.create(
                            RoomReminderScheduleStateStore(database),
                            newWriter(writeScope)
                        ),
                        amapKeys = DefaultAmapKeyRepository.create(DataStoreAmapKeyStore(keys), newWriter(writeScope), amapKeyDefaults()),
                        trackingSessionStore = DataStoreTrackingSessionStore(trackingSession),
                        lineStopsCache = RoomLineStopsCache(database.lineStopsCache()) { System.currentTimeMillis() },
                        migrationReport = report,
                        onClose = {
                            dataStoreScope.cancel()
                            database.close()
                        }
                    )
                }
            } catch (e: Exception) {
                dataStoreScope.cancel()
                runCatching { db?.close() }
                throw e
            }
        }
    }
}
