package com.trackr.ui.screens.favoritesgroup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.TrackrApp
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.data.remote.amap.AmapKeyStore
import com.trackr.data.remote.amap.AmapLruCache
import com.trackr.data.remote.amap.AmapSdkManager
import com.trackr.data.remote.amap.AmapSdkTransitService
import com.trackr.data.remote.amap.AmapTipItem
import com.trackr.data.remote.amap.AmapTransitApiClient
import com.trackr.data.remote.amap.AmapTransitSchemeResult
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.FavoriteGroup
import com.trackr.domain.model.GeoPoint
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.service.TransferAutoSwitchManager
import com.trackr.ui.screens.routeplan.MAX_VISIBLE_SCHEMES
import com.trackr.util.CoordTransform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * 高德输入提示 / 公交算路通道。
 *
 * 默认实现（[FavoritesGroupViewModel] 内部）：SDK 优先，**只有 SDK 调用失败**才回落 Web 服务；
 * SDK 成功但结果为空时不再重复请求 Web。单元测试可注入替身，避免发起真实请求。
 */
interface AmapRouteGateway {
    /** 是否至少配置了一条可用通道（Android Key 或 Web 服务 Key） */
    fun isConfigured(): Boolean

    /** @param location 当前位置 "lng,lat"（GCJ-02），可为空 */
    suspend fun searchTips(keyword: String, cityName: String, location: String?): Result<List<AmapTipItem>>

    /** @param cityd 跨城时的终点城市 adcode，同城传 null */
    suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String,
        destName: String,
        cityName: String,
        cityd: String?
    ): Result<List<AmapTransitSchemeResult>>
}

/**
 * 路线组与换乘追踪 ViewModel
 */
@OptIn(FlowPreview::class)
class FavoritesGroupViewModel(
    private val groupRepository: IRouteGroupRepository = TrackrApp.instance.routeGroupRepository,
    private val transitRepository: ITransitRepository = TrackrApp.instance.repository,
    private val coordinator: TransitProviderCoordinator? = runCatching { TrackrApp.instance.coordinator }.getOrNull(),
    private val okHttpClient: OkHttpClient? = runCatching { TrackrApp.instance.okHttpClient }.getOrNull(),
    private val amapApiClient: AmapTransitApiClient = AmapTransitApiClient(okHttpClient ?: OkHttpClient()),
    private val amapSdkService: AmapSdkTransitService? = runCatching { AmapSdkTransitService(TrackrApp.instance) }.getOrNull(),
    private val prefs: SharedPreferences? = runCatching { TrackrApp.instance.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE) }.getOrNull(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    enableAutoRefresh: Boolean = true,
    private val enableAutoMigration: Boolean = true,
    /** 高德通道替身（测试用）；为 null 时使用 SDK 优先、失败才走 Web 的默认实现 */
    amapGatewayOverride: AmapRouteGateway? = null,
    /** 墙钟（毫秒），用于算路缓存时间桶与对齐缓存过期；测试可替换 */
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    /** 高德 Key 专用存储（与普通设置分文件，备份时排除）；测试可注入 */
    private val amapKeyPrefs: SharedPreferences? = runCatching { AmapKeyStore.prefs(TrackrApp.instance) }.getOrNull()
) : ViewModel() {

    companion object {
        @Deprecated("历史槽位，等同于 web 服务 Key")
        const val PREF_KEY_AMAP_KEY = AmapKeyStore.KEY_WEB

        /** 高德 Android 平台 Key（地图 / 搜索 / 定位 SDK） */
        const val PREF_KEY_AMAP_ANDROID_KEY = AmapKeyStore.KEY_ANDROID

        /** 一次性数据迁移版本号，避免每次 ViewModel 重建都全量重跑迁移逻辑 */
        const val PREF_KEY_MIGRATION_VERSION = "favorites_group_migration_version"
        const val CURRENT_MIGRATION_VERSION = 2

        /**
         * 早期版本写死的默认城市 ID（当时误标为"杭州"，实际是车来了的哈尔滨）。
         * 存量候选线路上的这个值视为"未指定城市"，回落到当前所选城市。
         */
        const val LEGACY_PLACEHOLDER_CITY_ID = "033"

        /** 起点为"我的位置"时的占位名称 */
        const val MY_LOCATION_NAME = "我的位置"

        /** 输入提示防抖 */
        const val TIPS_DEBOUNCE_MS = 300L
        private const val TIPS_CACHE_SIZE = 64

        /** 算路结果缓存：最多 16 条，按 15 分钟时间桶失效 */
        private const val PLAN_CACHE_SIZE = 16
        const val PLAN_CACHE_BUCKET_MS = 15 * 60 * 1000L

        /** 路线组变化触发实时刷新的防抖 */
        const val ETA_REFRESH_DEBOUNCE_MS = 500L

        /** 合成候选线路对齐结果缓存：命中保留 24 小时，未命中 10 分钟后重试 */
        private const val ALIGN_HIT_TTL_MS = 24 * 60 * 60 * 1000L
        private const val ALIGN_MISS_TTL_MS = 10 * 60 * 1000L

        /** 方案与实时源对齐的最大并发 */
        private const val ALIGN_CONCURRENCY = 4

        /** 系统 lastKnown 定位兜底的最大年龄 */
        const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L

        /** 输入提示是否值得发请求：至少 2 个字符，或包含 1 个汉字 */
        fun isTipsKeywordEligible(keyword: String): Boolean {
            val kw = keyword.trim()
            if (kw.isEmpty()) return false
            return kw.length >= 2 || kw.any { it in '\u4e00'..'\u9fff' }
        }
    }

    private val _selectedCategory = MutableStateFlow<GroupCategory?>(null)
    val selectedCategory: StateFlow<GroupCategory?> = _selectedCategory.asStateFlow()

    private val _selectedFavoriteGroupId = MutableStateFlow<String?>(null)
    val selectedFavoriteGroupId: StateFlow<String?> = _selectedFavoriteGroupId.asStateFlow()

    val groups: StateFlow<List<TransitGroup>> = groupRepository.groups
    val favoriteGroups: StateFlow<List<FavoriteGroup>> = groupRepository.favoriteGroups

    val currentCity: StateFlow<TransitCity> = transitRepository.selectedCity

    /** 当前设备定位（WGS-84，由首页定位链路写入），供地图初始视角使用 */
    val userLocation: StateFlow<Pair<Double, Double>?> = transitRepository.userLocation

    fun isGroupInFavoriteGroup(group: TransitGroup, favGroupId: String): Boolean {
        return when (favGroupId) {
            "commute" -> group.customGroupId == "commute" || (group.customGroupId == null && group.category == GroupCategory.COMMUTE)
            "home" -> group.customGroupId == "home" || (group.customGroupId == null && group.name.contains("返程"))
            else -> group.customGroupId == favGroupId
        }
    }

    val filteredGroups: StateFlow<List<TransitGroup>> = combine(
        groups,
        selectedCategory,
        selectedFavoriteGroupId
    ) { allGroups, category, favGroupId ->
        when {
            favGroupId != null -> allGroups.filter { isGroupInFavoriteGroup(it, favGroupId) }
            category != null -> allGroups.filter { it.category == category }
            else -> allGroups
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, groups.value)

    val activeGroupSession: StateFlow<GroupTrackingSessionState?> =
        TransferAutoSwitchManager.activeGroupSession

    /**
     * 正在追踪、但不在已保存列表里的路线（如规划页"开始追踪"的临时方案）。
     * 收藏页在列表最上方显示为"临时追踪"卡片。
     */
    val temporaryTrackingGroup: StateFlow<TransitGroup?> = combine(
        TransferAutoSwitchManager.activeGroupState,
        TransferAutoSwitchManager.activeGroupSession,
        groups
    ) { activeGroup, session, savedGroups ->
        activeGroup?.takeIf { group ->
            session != null && !session.isCompleted && session.groupId == group.id &&
                savedGroups.none { it.id == group.id }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _legRealtimeSummary = MutableStateFlow<Map<String, String>>(emptyMap())
    val legRealtimeSummary: StateFlow<Map<String, String>> = _legRealtimeSummary.asStateFlow()

    private val _candidateRealtimeMap = MutableStateFlow<Map<String, CandidateLineRealtimeInfo>>(emptyMap())
    val candidateRealtimeMap: StateFlow<Map<String, CandidateLineRealtimeInfo>> = _candidateRealtimeMap.asStateFlow()

    private val _userSelectedLines = MutableStateFlow<Map<String, String>>(emptyMap())

    val selectedLineKeys: StateFlow<Map<String, String>> = combine(
        groups,
        _candidateRealtimeMap,
        _userSelectedLines
    ) { groupList, realtimeMap, userSelected ->
        val result = mutableMapOf<String, String>()
        for (group in groupList) {
            for (leg in group.legs) {
                val legKey = "${group.id}_${leg.legIndex}"
                val userChoice = userSelected[legKey]
                if (userChoice != null && leg.candidateLines.any { it.lineId == userChoice }) {
                    result[legKey] = userChoice
                } else {
                    val fastestLine = leg.candidateLines
                        .mapNotNull { line ->
                            val info = realtimeMap["${group.id}_${leg.legIndex}_${line.lineId}"]
                                ?: realtimeMap["${line.lineId}_${line.toStopOrder}"]
                            if (info != null && info.stopsAway < Int.MAX_VALUE) line to info.stopsAway else null
                        }
                        .minByOrNull { it.second }?.first
                    result[legKey] = fastestLine?.lineId ?: leg.primaryLine?.lineId.orEmpty()
                }
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _amapOriginTips = MutableStateFlow<List<AmapTipItem>>(emptyList())
    val amapOriginTips: StateFlow<List<AmapTipItem>> = _amapOriginTips.asStateFlow()

    private val _amapDestTips = MutableStateFlow<List<AmapTipItem>>(emptyList())
    val amapDestTips: StateFlow<List<AmapTipItem>> = _amapDestTips.asStateFlow()

    /** 输入框原始关键字（已 trim），经防抖后驱动输入提示请求 */
    private val originTipsQuery = MutableStateFlow("")
    private val destTipsQuery = MutableStateFlow("")

    /** 输入提示 LRU 缓存，键 = 城市 + 关键字 */
    private val tipsCache = AmapLruCache<String, List<AmapTipItem>>(TIPS_CACHE_SIZE)

    /** 算路 LRU 缓存，键 = 城市 + 6 位小数坐标 + 15 分钟时间桶 */
    private val planCache = AmapLruCache<String, List<AmapTransitSchemeResult>>(PLAN_CACHE_SIZE)
    private var planJob: Job? = null

    /** 实时刷新任务：新一轮开始时取消旧的一轮，避免旧结果覆盖新结果 */
    private var etaRefreshJob: Job? = null

    private data class AlignmentCacheEntry(val match: CandidateLine?, val savedAtMs: Long)

    /** 合成候选线路的对齐结果（内存缓存，避免每次 groups 变化都重新 search + getLineDetail） */
    private val syntheticAlignmentCache = ConcurrentHashMap<String, AlignmentCacheEntry>()

    private val _plannedSchemes = MutableStateFlow<List<AmapTransitSchemeResult>>(emptyList())
    val plannedSchemes: StateFlow<List<AmapTransitSchemeResult>> = _plannedSchemes.asStateFlow()

    private val _isPlanningRoute = MutableStateFlow(false)
    val isPlanningRoute: StateFlow<Boolean> = _isPlanningRoute.asStateFlow()

    private val _planErrorMessage = MutableStateFlow<String?>(null)
    val planErrorMessage: StateFlow<String?> = _planErrorMessage.asStateFlow()

    /** 追踪相关的一次性提示（如未能对齐真实站序时） */
    private val _trackingMessage = MutableStateFlow<String?>(null)
    val trackingMessage: StateFlow<String?> = _trackingMessage.asStateFlow()

    fun clearTrackingMessage() {
        _trackingMessage.value = null
    }

    /** 高德 Android 平台 Key（地图渲染 / 搜索 / 定位 SDK），与包名 + SHA-1 绑定；严格分槽，不回落 Web 槽位 */
    private val _customAmapApiKey = MutableStateFlow(AmapKeyStore.readAndroidKey(amapKeyPrefs))
    val customAmapApiKey: StateFlow<String?> = _customAmapApiKey.asStateFlow()

    /** 高德 Web 服务 Key（restapi.amap.com 公交算路 / 输入提示），不绑定包名 */
    private val _customAmapWebApiKey = MutableStateFlow(AmapKeyStore.readWebKey(amapKeyPrefs))
    val customAmapWebApiKey: StateFlow<String?> = _customAmapWebApiKey.asStateFlow()

    /** 当前生效的 Web 服务 Key：判断可用与实际发请求统一用它（用户配置 > BuildConfig） */
    private fun effectiveWebServiceKey(): String =
        AmapSdkManager.getEffectiveWebServiceKey(_customAmapWebApiKey.value)

    private val amapGateway: AmapRouteGateway = amapGatewayOverride ?: DefaultAmapRouteGateway()

    init {
        // SDK Key 已在 TrackrApp 启动阶段完成注入，这里只在运行期配置变更后重新对齐一次
        runCatching {
            AmapSdkManager.applyApiKey(TrackrApp.instance, _customAmapApiKey.value)
        }
        if (enableAutoMigration) {
            runDataMigrationIfNeeded()
        }
        observeTipsQuery(originTipsQuery, _amapOriginTips)
        observeTipsQuery(destTipsQuery, _amapDestTips)
        if (enableAutoRefresh) {
            viewModelScope.launch {
                // 防抖合并连续变更；新一轮开始时取消旧一轮（refreshRealtimeEta 内部也会取消上一个任务）
                groups.debounce(ETA_REFRESH_DEBOUNCE_MS).collectLatest { groupList ->
                    refreshRealtimeEta(groupList).join()
                }
            }
            // 换乘引擎的下车站定位辅助判定需要精度与定位时刻，由拿到 Location 的地方直接调用
            // TransferAutoSwitchManager.updateUserFix；仓库里的纯经纬度不再转发（无法判断是否过期）
        }
    }

    /**
     * 一次性数据迁移：仅做不依赖网络的本地修复，并通过 [PREF_KEY_MIGRATION_VERSION] 保证只执行一次。
     * 合成线路的实时对齐不在此处持久化，交由 [refreshRealtimeEta] 在运行时完成。
     */
    fun runDataMigrationIfNeeded(): Job = viewModelScope.launch(ioDispatcher) {
        // prefs 为空（例如测试环境）时退化为每次都执行，不做版本短路
        val migratedVersion = prefs?.getInt(PREF_KEY_MIGRATION_VERSION, 0) ?: 0
        if (migratedVersion >= CURRENT_MIGRATION_VERSION) return@launch

        // 1. 补齐遗留通勤分组的 customGroupId（纯本地判定，无网络请求）
        val currentGroups = groupRepository.groups.value
        for (g in currentGroups) {
            if (g.customGroupId == null && g.category == GroupCategory.COMMUTE) {
                groupRepository.updateGroup(g.copy(customGroupId = "commute"))
            }
        }

        // 2. 为通勤路线补全缺失的返程（回家）路线
        val latestGroups = groupRepository.groups.value
        val homeRoutes = latestGroups.filter { isGroupInFavoriteGroup(it, "home") }
        val commuteRoutes = latestGroups.filter { isGroupInFavoriteGroup(it, "commute") }
        for (commute in commuteRoutes) {
            val newOrigin = commute.destinationStopName
            val newDest = commute.originStopName
            val hasReverse = homeRoutes.any {
                it.originStopName == newOrigin && it.destinationStopName == newDest
            }
            if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                val reversed = buildReverseTransitGroup(commute, targetGroupId = "home")
                groupRepository.addGroup(reversed)
            }
        }

        prefs?.edit()?.putInt(PREF_KEY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION)?.apply()
    }

    fun selectCategory(category: GroupCategory?) {
        _selectedCategory.value = category
        _selectedFavoriteGroupId.value = null
    }

    fun selectFavoriteGroup(groupId: String?) {
        _selectedFavoriteGroupId.value = groupId
        _selectedCategory.value = null
    }

    fun selectCandidateLine(groupId: String, legIndex: Int, lineId: String) {
        _userSelectedLines.value = _userSelectedLines.value + ("${groupId}_${legIndex}" to lineId)
    }

    fun createFavoriteGroup(name: String) {
        viewModelScope.launch {
            groupRepository.createFavoriteGroup(name)
        }
    }

    fun renameFavoriteGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            groupRepository.renameFavoriteGroup(groupId, newName)
        }
    }

    fun deleteFavoriteGroup(groupId: String) {
        viewModelScope.launch {
            groupRepository.deleteFavoriteGroup(groupId)
            if (_selectedFavoriteGroupId.value == groupId) {
                _selectedFavoriteGroupId.value = null
            }
        }
    }

    fun reorderFavoriteGroups(groupIds: List<String>) {
        viewModelScope.launch {
            groupRepository.reorderFavoriteGroups(groupIds)
        }
    }

    fun addCandidateLineToLeg(groupId: String, legIndex: Int, candidateLine: CandidateLine) {
        viewModelScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val leg = group?.legs?.getOrNull(legIndex)
            val alignedLine = if (leg != null) {
                val city = resolveCandidateCity(candidateLine, transitRepository.selectedCity.value)
                val matched = findRealtimeLineMatch(
                    city = city,
                    lineName = candidateLine.lineName,
                    fromStopName = leg.fromStopName,
                    toStopName = leg.toStopName,
                    preferredDirection = candidateLine.direction
                )
                matched ?: candidateLine
            } else candidateLine

            groupRepository.addCandidateLineToLeg(groupId, legIndex, alignedLine)
            refreshRealtimeEta(groupRepository.groups.value)
        }
    }

    /**
     * 保存高德 Android 平台 Key（地图 / 搜索 / 定位 SDK 使用）
     */
    fun saveCustomAmapApiKey(key: String) {
        val trimmed = key.trim().ifBlank { null }
        AmapKeyStore.writeAndroidKey(amapKeyPrefs, trimmed)
        _customAmapApiKey.value = trimmed
        runCatching {
            AmapSdkManager.applyApiKey(TrackrApp.instance, trimmed)
        }
    }

    /**
     * 保存高德 Web 服务 Key（restapi.amap.com 公交算路 / 输入提示）
     */
    fun saveCustomAmapWebApiKey(key: String) {
        val trimmed = key.trim().ifBlank { null }
        AmapKeyStore.writeWebKey(amapKeyPrefs, trimmed)
        _customAmapWebApiKey.value = trimmed
    }

    /**
     * 搜索起点关键字候选项（优先高德，失败则智能降级至本地站点搜索）。
     * 只记录关键字；真正的请求经 300ms 防抖、去重后在 [observeTipsQuery] 里发出，旧请求会被取消。
     */
    fun searchOriginTips(keyword: String) {
        val kw = keyword.trim()
        originTipsQuery.value = kw
        if (!isTipsKeywordEligible(kw)) {
            _amapOriginTips.value = emptyList()
        }
    }

    /**
     * 搜索终点关键字候选项（优先高德 SDK，失败则降级至 Web API 或本地站点搜索）
     */
    fun searchDestTips(keyword: String) {
        val kw = keyword.trim()
        destTipsQuery.value = kw
        if (!isTipsKeywordEligible(kw)) {
            _amapDestTips.value = emptyList()
        }
    }

    /**
     * 输入提示管道：debounce → distinctUntilChanged → collectLatest（新关键字到来时取消旧请求）。
     * 结果回来时若输入框已变化则丢弃，避免旧前缀的结果覆盖新结果。
     */
    private fun observeTipsQuery(
        query: MutableStateFlow<String>,
        target: MutableStateFlow<List<AmapTipItem>>
    ) {
        viewModelScope.launch {
            query
                .debounce(TIPS_DEBOUNCE_MS)
                // 同一关键字不重复请求；但若提示已被清空（如关闭后重新输入同一个词），允许再查一次（命中缓存）
                .distinctUntilChanged { old, new -> old == new && target.value.isNotEmpty() }
                .collectLatest { keyword ->
                    if (!isTipsKeywordEligible(keyword)) {
                        target.value = emptyList()
                        return@collectLatest
                    }
                    val tips = loadPoiTips(keyword, transitRepository.selectedCity.value)
                    if (query.value == keyword) {
                        target.value = tips
                    }
                }
        }
    }

    /** 查询输入提示：先查 LRU 缓存，再走高德（SDK 失败才走 Web），都没有结果时降级为本地站点搜索 */
    private suspend fun loadPoiTips(keyword: String, city: TransitCity): List<AmapTipItem> {
        val cacheKey = "${city.cityId}|${city.cityName}|$keyword"
        tipsCache.get(cacheKey)?.let { return it }

        val amapTips = if (amapGateway.isConfigured()) {
            amapGateway.searchTips(keyword, city.cityName, currentGcjLngLat()).getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val tips = amapTips.ifEmpty { searchLocalStopTips(keyword, city) }
        if (tips.isNotEmpty()) {
            tipsCache.put(cacheKey, tips)
        }
        return tips
    }

    /** 当前设备定位转成高德需要的 GCJ-02 "lng,lat"；无定位时返回 null */
    private fun currentGcjLngLat(): String? {
        val (lat, lng) = transitRepository.userLocation.value ?: return null
        val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(lat, lng)
        return "$gcjLng,$gcjLat"
    }

    /**
     * 默认高德通道：SDK 优先；只有 SDK 调用失败（未配置 Key / 网络 / 鉴权等）才回落 Web 服务。
     * SDK 成功但结果为空时直接返回空结果，不再用 Web 重复请求。
     */
    private inner class DefaultAmapRouteGateway : AmapRouteGateway {
        private fun sdkUsable(): Boolean =
            amapSdkService != null && AmapSdkManager.isAndroidKeyConfigured(_customAmapApiKey.value)

        override fun isConfigured(): Boolean = sdkUsable() || effectiveWebServiceKey().isNotEmpty()

        override suspend fun searchTips(
            keyword: String,
            cityName: String,
            location: String?
        ): Result<List<AmapTipItem>> {
            var sdkFailure: Throwable? = null
            val sdk = amapSdkService
            if (sdk != null && sdkUsable()) {
                val result = sdk.searchPoiTips(keyword, cityName, _customAmapApiKey.value, location)
                currentCoroutineContext().ensureActive()
                if (result.isSuccess) return result
                sdkFailure = result.exceptionOrNull()
            }
            val webKey = effectiveWebServiceKey()
            if (webKey.isNotEmpty()) {
                return amapApiClient.searchPoiTips(keyword, cityName, webKey, location)
            }
            return Result.failure(sdkFailure ?: IllegalStateException("未配置高德 Key"))
        }

        override suspend fun planTransit(
            originLngLat: String,
            destinationLngLat: String,
            originName: String,
            destName: String,
            cityName: String,
            cityd: String?
        ): Result<List<AmapTransitSchemeResult>> {
            var sdkFailure: Throwable? = null
            val sdk = amapSdkService
            if (sdk != null && sdkUsable()) {
                val result = sdk.planTransit(
                    originLngLat = originLngLat,
                    destinationLngLat = destinationLngLat,
                    originName = originName,
                    destName = destName,
                    city = cityName,
                    customApiKey = _customAmapApiKey.value,
                    cityd = cityd
                )
                currentCoroutineContext().ensureActive()
                if (result.isSuccess) return result
                sdkFailure = result.exceptionOrNull()
            }
            val webKey = effectiveWebServiceKey()
            if (webKey.isNotEmpty()) {
                return amapApiClient.planTransit(
                    originLngLat = originLngLat,
                    destinationLngLat = destinationLngLat,
                    originName = originName,
                    destName = destName,
                    city = cityName,
                    apiKey = webKey,
                    cityd = cityd
                )
            }
            return Result.failure(sdkFailure ?: IllegalStateException("未配置高德 Key"))
        }
    }

    private suspend fun searchLocalStopTips(keyword: String, city: TransitCity): List<AmapTipItem> {
        val coord = coordinator ?: return emptyList()
        return try {
            val searchRes = coord.search(city, keyword, EngineMode.CHELAILE)
            val stopTips = searchRes.stops.map { stop ->
                // 车来了站点坐标是 WGS-84，传给高德算路前转成 GCJ-02
                val loc = if (stop.wgsLat != 0.0 && stop.wgsLng != 0.0) {
                    val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(stop.wgsLat, stop.wgsLng)
                    "$gcjLng,$gcjLat"
                } else ""
                AmapTipItem(
                    name = stop.name,
                    district = city.cityName,
                    address = stop.name,
                    location = loc
                )
            }
            if (stopTips.isNotEmpty()) {
                stopTips
            } else {
                searchRes.lines.mapNotNull { line ->
                    val dir = line.directions.firstOrNull() ?: return@mapNotNull null
                    AmapTipItem(
                        name = dir.name,
                        district = city.cityName,
                        address = "${dir.startStop} ➔ ${dir.endStop}",
                        location = ""
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearPlanError() {
        _planErrorMessage.value = null
    }

    /**
     * 执行公交路线规划（高德云端规划 + 实时公交站点直达/换乘双引擎）。
     *
     * - 新的规划会取消上一个仍在进行的规划；
     * - 结果按「城市 + 6 位小数坐标 + 15 分钟时间桶」缓存（LRU 16 条）；
     * - 高德 SDK 成功但无方案时不再用 Web 重复算路，只有 SDK 调用失败才回落 Web；
     * - 起点为「我的位置」但定位不可用时直接报错，不拿这四个字去搜 POI。
     */
    fun planTransitRoute(
        origin: AmapTipItem,
        destination: AmapTipItem,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _isPlanningRoute.value = true
            _planErrorMessage.value = null
            _plannedSchemes.value = emptyList()

            val city = transitRepository.selectedCity.value
            val hasAmap = amapGateway.isConfigured()

            var effectiveOrigin = origin
            var effectiveDest = destination

            // 1. 解析"我的位置"：定位不到就直接提示，不再拿"我的位置"四个字去搜 POI
            if (isMyLocationTip(effectiveOrigin)) {
                val resolved = resolveMyLocationTip(city)
                if (resolved == null) {
                    _planErrorMessage.value = "定位不可用，无法以【$MY_LOCATION_NAME】作为出发地。请开启定位后重试，或手动输入出发地。"
                    _isPlanningRoute.value = false
                    onComplete?.invoke(false)
                    return@launch
                }
                effectiveOrigin = resolved
            }

            // 2. 补全缺少经纬度的起终点坐标（高德 SDK 成功但无结果时同样不再走 Web）
            suspend fun resolveLocation(tip: AmapTipItem): AmapTipItem {
                if (tip.location.contains(",")) return tip
                if (hasAmap) {
                    val amapFound = amapGateway.searchTips(tip.name, city.cityName, currentGcjLngLat())
                        .getOrNull()?.firstOrNull { it.location.contains(",") }
                    if (amapFound != null) return amapFound
                }
                val localFound = searchLocalStopTips(tip.name, city).firstOrNull { it.location.contains(",") }
                if (localFound != null) return localFound
                return tip
            }

            effectiveOrigin = resolveLocation(effectiveOrigin)
            effectiveDest = resolveLocation(effectiveDest)

            val cacheKey = buildPlanCacheKey(city, effectiveOrigin.location, effectiveDest.location)
            val cached = cacheKey?.let { planCache.get(it) }

            var schemes: List<AmapTransitSchemeResult> = emptyList()

            if (cached != null) {
                schemes = cached
            } else {
                if (hasAmap && effectiveOrigin.location.contains(",") && effectiveDest.location.contains(",")) {
                    schemes = amapGateway.planTransit(
                        originLngLat = effectiveOrigin.location,
                        destinationLngLat = effectiveDest.location,
                        originName = effectiveOrigin.name,
                        destName = effectiveDest.name,
                        cityName = city.cityName,
                        cityd = resolveCrossCityCode(effectiveOrigin, effectiveDest, city)
                    ).getOrNull().orEmpty()

                    if (schemes.isNotEmpty()) {
                        schemes = alignAmapSchemesWithRealtimeSource(schemes, city)
                    }
                }

                if (schemes.isEmpty()) {
                    schemes = planLocalTransitRoute(effectiveOrigin, effectiveDest, city)
                }

                // 将用户选择的起终点 POI 坐标（GCJ-02 "lng,lat"）注入方案，供地图起终点 marker 使用
                val originPoint = parseLngLatToGeoPoint(effectiveOrigin.location)
                val destPoint = parseLngLatToGeoPoint(effectiveDest.location)
                if (originPoint != null || destPoint != null) {
                    schemes = schemes.map { it.copy(originPoint = originPoint, destinationPoint = destPoint) }
                }

                if (schemes.isNotEmpty() && cacheKey != null) {
                    planCache.put(cacheKey, schemes)
                }
            }

            if (schemes.isNotEmpty()) {
                _plannedSchemes.value = schemes
                onComplete?.invoke(true)
            } else {
                _planErrorMessage.value = if (!hasAmap) {
                    "未查到经过【${effectiveOrigin.name}】与【${effectiveDest.name}】的直达或换乘公交。\n建议输入具体公交站名，或配置高德 Key 启用全城地标与地铁算路。"
                } else {
                    "在当前城市未找到合适的公交换乘方案，请尝试更换更精准的站名或地名。"
                }
                onComplete?.invoke(false)
            }

            _isPlanningRoute.value = false
        }
    }

    private fun isMyLocationTip(tip: AmapTipItem): Boolean =
        tip.name == MY_LOCATION_NAME || tip.name.isBlank()

    /**
     * 把"我的位置"解析为带 GCJ-02 坐标的起点；定位不可用时返回 null。
     * 优先用首页定位链路写入的坐标，其次是系统 lastKnown（需有权限且不超过 10 分钟）。
     */
    private suspend fun resolveMyLocationTip(city: TransitCity): AmapTipItem? {
        val (lat, lng) = transitRepository.userLocation.value ?: lastKnownWgsLocation() ?: return null
        val nearby = runCatching {
            transitRepository.getNearbyStops(city, lat, lng, 1).getOrNull()?.firstOrNull()
        }.getOrNull()
        // 系统定位是 WGS-84，高德算路要求 GCJ-02，必须显式转换后再拼参数
        val (originGcjLat, originGcjLng) = CoordTransform.wgs84ToGcj02(lat, lng)
        return if (nearby != null) {
            AmapTipItem(
                name = "$MY_LOCATION_NAME(${nearby.name})",
                district = city.cityName,
                address = nearby.name,
                location = "$originGcjLng,$originGcjLat"
            )
        } else {
            AmapTipItem(
                name = MY_LOCATION_NAME,
                district = city.cityName,
                address = "当前定位",
                location = "$originGcjLng,$originGcjLat"
            )
        }
    }

    /**
     * 系统 lastKnown 定位兜底（WGS-84）：先检查定位权限，只接受 10 分钟内的定位，多个来源取精度最高的。
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownWgsLocation(): Pair<Double, Double>? {
        val app = runCatching { TrackrApp.instance }.getOrNull() ?: return null
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            try {
                lm.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }.filter { locationAgeMillis(it) in 0..MAX_LAST_KNOWN_AGE_MS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        return best?.let { it.latitude to it.longitude }
    }

    /** 定位年龄：优先用单调时钟 elapsedRealtimeNanos（不受改系统时间影响），缺失时回落墙钟 */
    private fun locationAgeMillis(loc: Location): Long =
        if (loc.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - loc.time
        }

    /** 算路缓存键：城市 + 6 位小数坐标 + 15 分钟时间桶；坐标非法时不缓存 */
    private fun buildPlanCacheKey(city: TransitCity, originLngLat: String, destLngLat: String): String? {
        val o = AmapTransitApiClient.formatLngLat(originLngLat) ?: return null
        val d = AmapTransitApiClient.formatLngLat(destLngLat) ?: return null
        val bucket = wallClockMillis() / PLAN_CACHE_BUCKET_MS
        return "${city.cityId}|${city.cityName}|$o|$d|$bucket"
    }

    /**
     * 起终点跨城时返回终点城市 adcode（传给高德 cityd），同城或拿不到终点城市时返回 null。
     * 起点有 adcode 时按地级市 adcode 比较；没有时（如"我的位置"）看终点行政区文本是否包含当前城市名。
     */
    private fun resolveCrossCityCode(origin: AmapTipItem, dest: AmapTipItem, city: TransitCity): String? {
        val destCityCode = AmapTransitApiClient.cityAdcodeOf(dest.adcode) ?: return null
        val originCityCode = AmapTransitApiClient.cityAdcodeOf(origin.adcode)
        val isCrossCity = if (originCityCode != null) {
            originCityCode != destCityCode
        } else {
            val shortName = city.cityName.trim().removeSuffix("市")
            dest.district.isNotBlank() && shortName.isNotBlank() && !dest.district.contains(shortName)
        }
        return if (isCrossCity) destCityCode else null
    }

    /** 解析高德 "lng,lat" 字符串为 GeoPoint（GCJ-02） */
    private fun parseLngLatToGeoPoint(lngLat: String): GeoPoint? {
        val parts = lngLat.split(",")
        if (parts.size != 2) return null
        val lng = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        return GeoPoint(lat = lat, lng = lng)
    }

    /** 方案线路与实时源的匹配结果：[fromStop]/[toStop] 为空表示只对齐了线路，没对齐站序 */
    private data class SchemeLineMatch(
        val direction: BusLineDirection,
        val fromStop: BusStop?,
        val toStop: BusStop?
    )

    /**
     * 把高德方案里的候选线路对齐到实时公交源（真实 lineId / 方向 / 站序）。
     * 只处理界面可见的前 [MAX_VISIBLE_SCHEMES] 个方案；同一「线路名 + 上车站 + 下车站」只查一次；
     * 并发不超过 [ALIGN_CONCURRENCY]。
     */
    private suspend fun alignAmapSchemesWithRealtimeSource(
        schemes: List<AmapTransitSchemeResult>,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return schemes
        val visible = schemes.take(MAX_VISIBLE_SCHEMES)
        val rest = schemes.drop(MAX_VISIBLE_SCHEMES)
        return withContext(ioDispatcher) {
            val semaphore = Semaphore(ALIGN_CONCURRENCY)
            val memo = LinkedHashMap<String, Deferred<SchemeLineMatch?>>()

            fun memoKey(lineName: String, fromCore: String, toCore: String) = "$lineName|$fromCore|$toCore"

            // 1. 去重后并发查询
            for (scheme in visible) {
                for (leg in scheme.group.legs) {
                    val fromCore = RouteProjectionMath.coreStopName(leg.fromStopName)
                    val toCore = RouteProjectionMath.coreStopName(leg.toStopName)
                    for (candidate in leg.candidateLines) {
                        val key = memoKey(candidate.lineName, fromCore, toCore)
                        if (!memo.containsKey(key)) {
                            val lineName = candidate.lineName
                            memo[key] = async {
                                semaphore.withPermit { matchSchemeLine(coord, city, lineName, fromCore, toCore) }
                            }
                        }
                    }
                }
            }

            // 2. 套用匹配结果
            val aligned = visible.map { scheme ->
                val newLegs = scheme.group.legs.map { leg ->
                    val fromCore = RouteProjectionMath.coreStopName(leg.fromStopName)
                    val toCore = RouteProjectionMath.coreStopName(leg.toStopName)
                    val updatedCandidates = leg.candidateLines.map { candidate ->
                        val match = memo[memoKey(candidate.lineName, fromCore, toCore)]?.await()
                        if (match != null) applySchemeLineMatch(candidate, match, city) else candidate
                    }
                    leg.copy(candidateLines = updatedCandidates)
                }
                scheme.copy(group = scheme.group.copy(legs = newLegs))
            }
            aligned + rest
        }
    }

    /** 在实时源里查找线路：站序能对上的方向优先，否则退而取第一个同名方向 */
    private suspend fun matchSchemeLine(
        coord: TransitProviderCoordinator,
        city: TransitCity,
        lineName: String,
        fromCore: String,
        toCore: String
    ): SchemeLineMatch? = runCatching {
        val searchRes = coord.search(city, lineName, EngineMode.CHELAILE)
        val matchedDirs = searchRes.lines.flatMap { it.directions }.filter { dir ->
            dir.name.contains(lineName) || lineName.contains(dir.name)
        }
        var fallback: SchemeLineMatch? = null
        for (dir in matchedDirs) {
            // 线路详情只走 transitRepository 一条路径，失败不再对同一上游重复请求
            val detail = transitRepository.getLineDetail(
                city = city,
                lineId = dir.lineId,
                source = dir.source,
                lineNameHint = dir.name,
                directionHint = dir.direction
            ).getOrNull()
            if (detail != null) {
                val fromStop = detail.stops.find {
                    it.name.contains(fromCore) || fromCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                val toStop = detail.stops.find {
                    it.name.contains(toCore) || toCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                if (fromStop != null && toStop != null && fromStop.order < toStop.order) {
                    return@runCatching SchemeLineMatch(dir, fromStop, toStop)
                }
            }
            if (fallback == null) {
                fallback = SchemeLineMatch(dir, null, null)
            }
        }
        fallback
    }.getOrNull()

    private fun applySchemeLineMatch(
        candidate: CandidateLine,
        match: SchemeLineMatch,
        city: TransitCity
    ): CandidateLine {
        val dir = match.direction
        val fromStop = match.fromStop
        val toStop = match.toStop
        return if (fromStop != null && toStop != null) {
            candidate.copy(
                cityId = city.cityId,
                cityName = city.cityName,
                lineId = dir.lineId,
                direction = dir.direction,
                source = dir.source,
                fromStopOrder = fromStop.order,
                toStopOrder = toStop.order,
                startStopName = fromStop.name,
                endStopName = toStop.name,
                mygolbsCityCode = city.mygolbsCityCode
            )
        } else {
            candidate.copy(
                cityId = city.cityId,
                cityName = city.cityName,
                lineId = dir.lineId,
                direction = dir.direction,
                source = dir.source,
                mygolbsCityCode = city.mygolbsCityCode
            )
        }
    }

    private suspend fun planLocalTransitRoute(
        origin: AmapTipItem,
        destination: AmapTipItem,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return emptyList()
        return withContext(ioDispatcher) {
            try {
                val originCore = RouteProjectionMath.coreStopName(origin.name)
                val destCore = RouteProjectionMath.coreStopName(destination.name)

                val originSearch = coord.search(city, origin.name, EngineMode.CHELAILE)
                val destSearch = coord.search(city, destination.name, EngineMode.CHELAILE)

                val originStops = originSearch.stops.filter {
                    it.name.contains(originCore) || originCore.contains(RouteProjectionMath.coreStopName(it.name))
                }.ifEmpty { originSearch.stops.take(2) }

                val destStops = destSearch.stops.filter {
                    it.name.contains(destCore) || destCore.contains(RouteProjectionMath.coreStopName(it.name))
                }.ifEmpty { destSearch.stops.take(2) }

                if (originStops.isEmpty() || destStops.isEmpty()) {
                    return@withContext emptyList()
                }

                val originNearby = mutableListOf<NearbyStop>()
                for (stop in originStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CHELAILE)
                    }.getOrNull().orEmpty()
                    originNearby.addAll(details)
                }
                val originLines = originNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                val destNearby = mutableListOf<NearbyStop>()
                for (stop in destStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CHELAILE)
                    }.getOrNull().orEmpty()
                    destNearby.addAll(details)
                }
                val destLines = destNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                val schemes = mutableListOf<AmapTransitSchemeResult>()

                for (origLine in originLines) {
                    val matchingDest = destLines.find {
                        (it.line.name == origLine.line.name || it.line.lineId == origLine.line.lineId) &&
                        it.line.direction == origLine.line.direction &&
                        origLine.targetOrder < it.targetOrder
                    }

                    if (matchingDest != null) {
                        val stopCount = (matchingDest.targetOrder - origLine.targetOrder).coerceAtLeast(1)
                        val durationMinutes = (stopCount * 3 + 2).coerceAtLeast(5)
                        val primaryLine = CandidateLine(
                            cityId = city.cityId,
                            cityName = city.cityName,
                            lineId = origLine.line.lineId,
                            lineName = origLine.line.name,
                            source = origLine.line.source,
                            direction = origLine.line.direction,
                            fromStopOrder = origLine.targetOrder,
                            toStopOrder = matchingDest.targetOrder,
                            startStopName = origLine.targetStopName,
                            endStopName = matchingDest.targetStopName,
                            mygolbsCityCode = city.mygolbsCityCode
                        )
                        val leg = TransitLeg(
                            legIndex = 0,
                            fromStopName = origLine.targetStopName,
                            toStopName = matchingDest.targetStopName,
                            candidateLines = listOf(primaryLine),
                            walkingDistanceMeters = 0
                        )
                        val title = "${origLine.line.name} 直达 · 共${stopCount}站"
                        val group = TransitGroup(
                            id = UUID.randomUUID().toString(),
                            name = "${origin.name} ➔ ${destination.name} ($title)",
                            category = GroupCategory.TEMPORARY,
                            legs = listOf(leg),
                            isTemporary = true,
                            sortOrder = 0
                        )
                        schemes.add(
                            AmapTransitSchemeResult(
                                title = title,
                                durationMinutes = durationMinutes,
                                walkingMeters = 50,
                                costYuan = "2.0",
                                transferCount = 0,
                                group = group
                            )
                        )
                    }
                }

                if (schemes.size < 3) {
                    val origDetails = originLines.take(3).mapNotNull { l ->
                        runCatching {
                            coord.getLineDetail(city, LineDetailRequest(lineId = l.line.lineId, source = l.line.source, lineNameHint = l.line.name, directionHint = l.line.direction))
                        }.getOrNull()
                    }
                    val destDetails = destLines.take(3).mapNotNull { l ->
                        runCatching {
                            coord.getLineDetail(city, LineDetailRequest(lineId = l.line.lineId, source = l.line.source, lineNameHint = l.line.name, directionHint = l.line.direction))
                        }.getOrNull()
                    }

                    transferLoop@ for (d1 in origDetails) {
                        for (d2 in destDetails) {
                            if (d1.line.lineId == d2.line.lineId) continue
                            val d1Stops = d1.stops
                            val d2Stops = d2.stops

                            val fromStopInD1 = d1Stops.find { it.name.contains(originCore) || originCore.contains(RouteProjectionMath.coreStopName(it.name)) } ?: continue
                            val toStopInD2 = d2Stops.find { it.name.contains(destCore) || destCore.contains(RouteProjectionMath.coreStopName(it.name)) } ?: continue

                            for (s1 in d1Stops) {
                                if (s1.order <= fromStopInD1.order) continue
                                val s1Core = RouteProjectionMath.coreStopName(s1.name)
                                val s2 = d2Stops.find { RouteProjectionMath.coreStopName(it.name) == s1Core }
                                if (s2 != null && s2.order < toStopInD2.order) {
                                    val leg1 = TransitLeg(
                                        legIndex = 0,
                                        fromStopName = fromStopInD1.name,
                                        toStopName = s1.name,
                                        candidateLines = listOf(
                                            CandidateLine(
                                                cityId = city.cityId,
                                                cityName = city.cityName,
                                                lineId = d1.line.lineId,
                                                lineName = d1.line.name,
                                                source = d1.line.source,
                                                direction = d1.line.direction,
                                                fromStopOrder = fromStopInD1.order,
                                                toStopOrder = s1.order,
                                                startStopName = fromStopInD1.name,
                                                endStopName = s1.name
                                            )
                                        )
                                    )
                                    val leg2 = TransitLeg(
                                        legIndex = 1,
                                        fromStopName = s2.name,
                                        toStopName = toStopInD2.name,
                                        candidateLines = listOf(
                                            CandidateLine(
                                                cityId = city.cityId,
                                                cityName = city.cityName,
                                                lineId = d2.line.lineId,
                                                lineName = d2.line.name,
                                                source = d2.line.source,
                                                direction = d2.line.direction,
                                                fromStopOrder = s2.order,
                                                toStopOrder = toStopInD2.order,
                                                startStopName = s2.name,
                                                endStopName = toStopInD2.name
                                            )
                                        )
                                    )
                                    val stopsTotal = (s1.order - fromStopInD1.order) + (toStopInD2.order - s2.order)
                                    val durationMinutes = (stopsTotal * 3 + 6).coerceAtLeast(15)
                                    val title = "${d1.line.name} ➔ ${d2.line.name} · 1次换乘(${s1.name})"
                                    val group = TransitGroup(
                                        id = UUID.randomUUID().toString(),
                                        name = "${origin.name} ➔ ${destination.name} ($title)",
                                        category = GroupCategory.TEMPORARY,
                                        legs = listOf(leg1, leg2),
                                        isTemporary = true,
                                        sortOrder = 0
                                    )
                                    schemes.add(
                                        AmapTransitSchemeResult(
                                            title = title,
                                            durationMinutes = durationMinutes,
                                            walkingMeters = 80,
                                            costYuan = "4.0",
                                            transferCount = 1,
                                            group = group
                                        )
                                    )
                                    if (schemes.size >= 5) break@transferLoop
                                }
                            }
                        }
                    }
                }

                schemes.distinctBy { it.title }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 将规划方案直接加入到我的路线组
     */
    fun addPlannedSchemeToGroups(scheme: AmapTransitSchemeResult) {
        viewModelScope.launch {
            groupRepository.addGroup(scheme.group)
        }
    }

    /**
     * 将规划方案转存为正式收藏分组
     */
    fun addPlannedSchemeAsFavorite(
        group: TransitGroup,
        customGroupId: String? = null,
        category: GroupCategory = GroupCategory.CUSTOM,
        customName: String? = null
    ) {
        viewModelScope.launch {
            val targetGroupId = customGroupId
                ?: _selectedFavoriteGroupId.value
                ?: if (group.category == GroupCategory.COMMUTE) "commute" else null
            val targetCategory = if (targetGroupId == "commute") GroupCategory.COMMUTE else category
            val preparedGroup = group.copy(
                isTemporary = false,
                customGroupId = targetGroupId,
                category = targetCategory,
                name = customName?.takeIf { it.isNotBlank() } ?: group.name
            )
            val alignedGroup = alignTransitGroupWithRealtimeSource(preparedGroup)
            groupRepository.addGroup(alignedGroup)

            if (targetGroupId == "commute") {
                val newOrigin = alignedGroup.destinationStopName
                val newDest = alignedGroup.originStopName
                val existingHome = groupRepository.groups.value.filter { isGroupInFavoriteGroup(it, "home") }
                val hasReverse = existingHome.any { it.originStopName == newOrigin && it.destinationStopName == newDest }
                if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                    val reverseGroup = buildReverseTransitGroup(alignedGroup, targetGroupId = "home")
                    groupRepository.addGroup(reverseGroup)
                }
            }
        }
    }

    fun addPlannedSchemeAsFavorite(
        scheme: AmapTransitSchemeResult,
        customGroupId: String? = null,
        category: GroupCategory = GroupCategory.CUSTOM,
        customName: String? = null
    ) {
        addPlannedSchemeAsFavorite(
            group = scheme.group,
            customGroupId = customGroupId,
            category = category,
            customName = customName
        )
    }

    fun clearPlanning() {
        planJob?.cancel()
        _isPlanningRoute.value = false
        originTipsQuery.value = ""
        destTipsQuery.value = ""
        _plannedSchemes.value = emptyList()
        _amapOriginTips.value = emptyList()
        _amapDestTips.value = emptyList()
        _planErrorMessage.value = null
    }

    /**
     * 启动整组全程自动换乘追踪。
     *
     * 启动前：
     * 1. 若候选线路为合成的（lineId 以 line_ 开头 / fromStopOrder>=toStopOrder / fromStopOrder<=0），
     *    先调用 [findRealtimeLineMatch] 对齐并把结果写回仓库（[groupRepository.updateGroup]）；
     *    匹配不到则保持未对齐并通过 [trackingMessage] 提示，不使用假站序启动追踪。
     * 2. 追踪使用用户在卡片上选中的候选线路（[selectedLineKeys]）。
     */
    fun startGroupTracking(context: Context, group: TransitGroup, startingLegIndex: Int = 0): Job =
        viewModelScope.launch {
            val alignedGroup = ensureGroupAligned(group)

            // 若对齐后仍存在合成/无效站序的要追踪线路，则不启动，给出提示
            val selectedIds = resolveSelectedLineIds(alignedGroup)
            val startLeg = alignedGroup.legs.getOrNull(startingLegIndex.coerceIn(0, alignedGroup.legs.lastIndex))
            val startLine = startLeg?.let { leg ->
                leg.candidateLines.firstOrNull { it.lineId == selectedIds[leg.legIndex] } ?: leg.primaryLine
            }
            if (startLine == null || isSyntheticLine(startLine)) {
                _trackingMessage.value = "未能对齐【${group.name}】的真实线路站序，暂无法开启追踪，请稍后重试或检查候选线路。"
                return@launch
            }

            TransferAutoSwitchManager.startGroupTracking(
                context = context,
                group = alignedGroup,
                startingLegIndex = startingLegIndex,
                selectedLineIds = selectedIds
            )
        }

    /** 手动确认“我已上车”，跳过等车阶段 */
    fun confirmBoarded(context: Context) {
        TransferAutoSwitchManager.confirmBoarded(context)
    }

    /** 手动跳到下一程（乘车阶段使用） */
    fun switchToNextLeg(context: Context) {
        TransferAutoSwitchManager.switchToNextLeg(context)
    }

    /** 判断候选线路是否为合成/未对齐（合成 id / 非法站序） */
    private fun isSyntheticLine(line: CandidateLine): Boolean =
        line.lineId.startsWith("line_") || line.fromStopOrder <= 0 || line.fromStopOrder >= line.toStopOrder

    /**
     * 对齐该组内所有合成线路；仅当该组已保存在仓库时才写回（临时追踪的方案只在内存里，不能借此写进收藏）。
     * 若无需对齐则原样返回。
     */
    private suspend fun ensureGroupAligned(group: TransitGroup): TransitGroup {
        val needsAlign = group.legs.any { leg -> leg.candidateLines.any { isSyntheticLine(it) } }
        if (!needsAlign) return group
        val aligned = alignTransitGroupWithRealtimeSource(group)
        val isSaved = groupRepository.groups.value.any { it.id == group.id }
        if (aligned != group && isSaved) {
            runCatching { groupRepository.updateGroup(aligned) }
        }
        return aligned
    }

    /** 从 selectedLineKeys 解析每程选中的 lineId（legIndex -> lineId） */
    private fun resolveSelectedLineIds(group: TransitGroup): Map<Int, String> {
        val keys = selectedLineKeys.value
        val result = mutableMapOf<Int, String>()
        for (leg in group.legs) {
            val chosen = keys["${group.id}_${leg.legIndex}"]
            if (chosen != null && leg.candidateLines.any { it.lineId == chosen }) {
                result[leg.legIndex] = chosen
            }
        }
        return result
    }

    /**
     * 停止换乘追踪
     */
    fun stopGroupTracking(context: Context) {
        TransferAutoSwitchManager.stopGroupTracking(context)
    }

    /**
     * 删除分组
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            groupRepository.deleteGroup(groupId)
        }
    }

    /**
     * 转存临时分组为正式常用分组
     */
    fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String? = null,
        newCategory: GroupCategory? = null,
        customGroupId: String? = null
    ) {
        viewModelScope.launch {
            groupRepository.saveOrConvertTemporaryGroup(groupId, newName, newCategory, customGroupId)
        }
    }

    /**
     * 创建或更新自定义分组
     */
    fun saveGroup(group: TransitGroup) {
        viewModelScope.launch {
            val targetGroupId = group.customGroupId
                ?: _selectedFavoriteGroupId.value
                ?: if (group.category == GroupCategory.COMMUTE) "commute" else null
            val targetCategory = if (targetGroupId == "commute") GroupCategory.COMMUTE else group.category
            val preparedGroup = group.copy(
                customGroupId = targetGroupId,
                category = targetCategory
            )
            val alignedGroup = alignTransitGroupWithRealtimeSource(preparedGroup)
            groupRepository.addGroup(alignedGroup)

            if (targetGroupId == "commute") {
                val newOrigin = alignedGroup.destinationStopName
                val newDest = alignedGroup.originStopName
                val existingHome = groupRepository.groups.value.filter { isGroupInFavoriteGroup(it, "home") }
                val hasReverse = existingHome.any { it.originStopName == newOrigin && it.destinationStopName == newDest }
                if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                    val reverseGroup = buildReverseTransitGroup(alignedGroup, targetGroupId = "home")
                    groupRepository.addGroup(reverseGroup)
                }
            }
        }
    }

    /**
     * 为指定路线生成反向路线分组
     */
    fun createReverseGroup(group: TransitGroup) {
        viewModelScope.launch {
            val targetGroupId = when (group.customGroupId) {
                "commute" -> "home"
                "home" -> "commute"
                else -> group.customGroupId ?: if (group.category == GroupCategory.COMMUTE) "home" else null
            }
            val reversed = buildReverseTransitGroup(group, targetGroupId)
            groupRepository.addGroup(reversed)
        }
    }

    /**
     * 在实时公交数据源中查找精准匹配的线路走向与起止站序
     */
    suspend fun findRealtimeLineMatch(
        city: TransitCity,
        lineName: String,
        fromStopName: String,
        toStopName: String,
        preferredDirection: Int? = null
    ): CandidateLine? {
        val fromCore = RouteProjectionMath.coreStopName(fromStopName)
        val toCore = RouteProjectionMath.coreStopName(toStopName)

        // 只走一条路径：有 coordinator 时直接按车来了搜索（不写搜索历史），否则走仓库；失败不再对同一上游重复请求
        val coord = coordinator
        val searchRes = if (coord != null) {
            runCatching { coord.search(city, lineName, EngineMode.CHELAILE) }.getOrNull()
        } else {
            runCatching { transitRepository.search(city, lineName).getOrNull() }.getOrNull()
        }

        val matchedDirs = searchRes?.lines?.flatMap { it.directions }?.filter { dir ->
            dir.name.contains(lineName) || lineName.contains(dir.name)
        }.orEmpty()

        val sortedDirs = if (preferredDirection != null) {
            matchedDirs.sortedByDescending { it.direction == preferredDirection }
        } else {
            matchedDirs
        }

        for (dir in sortedDirs) {
            val detail = fetchLineDetailOnce(city, dir)

            if (detail != null) {
                val fromStop = detail.stops.find {
                    it.name.contains(fromCore) || fromCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                val toStop = detail.stops.find {
                    it.name.contains(toCore) || toCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                if (fromStop != null && toStop != null && fromStop.order < toStop.order) {
                    return CandidateLine(
                        cityId = city.cityId,
                        cityName = city.cityName,
                        lineId = dir.lineId,
                        lineName = dir.name.ifBlank { lineName },
                        source = dir.source,
                        direction = dir.direction,
                        fromStopOrder = fromStop.order,
                        toStopOrder = toStop.order,
                        startStopName = fromStop.name,
                        endStopName = toStop.name,
                        mygolbsCityCode = city.mygolbsCityCode
                    )
                }

                val rev = detail.reverseDirection
                if (rev != null) {
                    val revDetail = fetchLineDetailOnce(city, rev)

                    if (revDetail != null) {
                        val revFromStop = revDetail.stops.find {
                            it.name.contains(fromCore) || fromCore.contains(RouteProjectionMath.coreStopName(it.name))
                        }
                        val revToStop = revDetail.stops.find {
                            it.name.contains(toCore) || toCore.contains(RouteProjectionMath.coreStopName(it.name))
                        }
                        if (revFromStop != null && revToStop != null && revFromStop.order < revToStop.order) {
                            return CandidateLine(
                                cityId = city.cityId,
                                cityName = city.cityName,
                                lineId = rev.lineId,
                                lineName = rev.name.ifBlank { lineName },
                                source = rev.source,
                                direction = rev.direction,
                                fromStopOrder = revFromStop.order,
                                toStopOrder = revToStop.order,
                                startStopName = revFromStop.name,
                                endStopName = revToStop.name,
                                mygolbsCityCode = city.mygolbsCityCode
                            )
                        }
                    }
                }
            }
        }

        // 匹配不到真实站序：返回 null，保持未对齐，绝不使用写死的假站序（原先的 1->5）
        return null
    }

    /** 线路详情统一只走 transitRepository（仓库层负责去重 / 缓存），失败不再换通道重复请求同一上游 */
    private suspend fun fetchLineDetailOnce(city: TransitCity, dir: BusLineDirection): BusLineDetail? =
        runCatching {
            transitRepository.getLineDetail(
                city = city,
                lineId = dir.lineId,
                source = dir.source,
                lineNameHint = dir.name,
                directionHint = dir.direction
            ).getOrNull()
        }.getOrNull()

    /** 候选线路所属城市；未填或为历史占位 ID 时回落到 [defaultCity] */
    private fun resolveCandidateCity(candidate: CandidateLine, defaultCity: TransitCity): TransitCity =
        if (candidate.cityId.isNotBlank() && candidate.cityId != LEGACY_PLACEHOLDER_CITY_ID) {
            TransitCity(candidate.cityId, candidate.cityName)
        } else {
            defaultCity
        }

    /**
     * 将路线组中各段候选线路与实时公交数据源进行站序和真实 lineId 对齐
     */
    suspend fun alignTransitGroupWithRealtimeSource(group: TransitGroup): TransitGroup = withContext(ioDispatcher) {
        val defaultCity = transitRepository.selectedCity.value
        val newLegs = group.legs.map { leg ->
            val newCandidates = leg.candidateLines.map { candidate ->
                val isSynthetic = candidate.lineId.startsWith("line_") || candidate.fromStopOrder >= candidate.toStopOrder
                if (isSynthetic) {
                    val lineCity = resolveCandidateCity(candidate, defaultCity)
                    val matched = findRealtimeLineMatch(
                        city = lineCity,
                        lineName = candidate.lineName,
                        fromStopName = leg.fromStopName,
                        toStopName = leg.toStopName,
                        preferredDirection = candidate.direction
                    )
                    // 匹配不到则保持原候选（仍是合成/未对齐），由启动追踪处拦截并提示，不编造假站序
                    matched ?: candidate
                } else {
                    candidate
                }
            }
            leg.copy(candidateLines = newCandidates)
        }
        group.copy(legs = newLegs)
    }

    /**
     * 构建返程路线分组（自动反转行程段、调换起终点与候选线路上下行并对齐真实源数据）
     */
    suspend fun buildReverseTransitGroup(
        forwardGroup: TransitGroup,
        targetGroupId: String? = "home"
    ): TransitGroup = withContext(ioDispatcher) {
        val defaultCity = transitRepository.selectedCity.value
        val reversedOriginalLegs = forwardGroup.legs.reversed()
        val newLegs = reversedOriginalLegs.mapIndexed { reversedIdx, originalLeg ->
            val reversedFromStop = originalLeg.toStopName
            val reversedToStop = originalLeg.fromStopName
            val walkingMeters = if (reversedIdx == 0) 0 else {
                forwardGroup.legs[forwardGroup.legs.size - reversedIdx].walkingDistanceMeters
            }
            val reversedCandidates = originalLeg.candidateLines.map { candidate ->
                val flippedDir = if (candidate.direction == 0) 1 else 0
                val lineCity = resolveCandidateCity(candidate, defaultCity)
                val matched = findRealtimeLineMatch(
                    city = lineCity,
                    lineName = candidate.lineName,
                    fromStopName = reversedFromStop,
                    toStopName = reversedToStop,
                    preferredDirection = flippedDir
                )
                matched ?: candidate.copy(
                    cityId = lineCity.cityId,
                    cityName = lineCity.cityName,
                    direction = flippedDir,
                    startStopName = candidate.endStopName.ifEmpty { reversedFromStop },
                    endStopName = candidate.startStopName.ifEmpty { reversedToStop },
                    fromStopOrder = 1,
                    toStopOrder = maxOf(2, (candidate.toStopOrder - candidate.fromStopOrder) + 1)
                )
            }
            TransitLeg(
                legIndex = reversedIdx,
                fromStopName = reversedFromStop,
                fromStopId = originalLeg.toStopId,
                toStopName = reversedToStop,
                toStopId = originalLeg.fromStopId,
                fromStopLat = originalLeg.toStopLat,
                fromStopLng = originalLeg.toStopLng,
                toStopLat = originalLeg.fromStopLat,
                toStopLng = originalLeg.fromStopLng,
                candidateLines = reversedCandidates,
                walkingDistanceMeters = walkingMeters
            )
        }

        val newOrigin = newLegs.firstOrNull()?.fromStopName.orEmpty()
        val newDest = newLegs.lastOrNull()?.toStopName.orEmpty()
        val newName = when {
            forwardGroup.name.contains("➔") || forwardGroup.name.contains("->") -> {
                if (newOrigin.isNotBlank() && newDest.isNotBlank()) {
                    "$newOrigin ➔ $newDest"
                } else {
                    "${forwardGroup.name} (返程)"
                }
            }
            newOrigin.isNotBlank() && newDest.isNotBlank() -> "$newOrigin ➔ $newDest (${forwardGroup.name}·返程)"
            else -> "${forwardGroup.name} (返程)"
        }

        TransitGroup(
            id = UUID.randomUUID().toString(),
            name = newName,
            category = forwardGroup.category,
            customGroupId = targetGroupId,
            legs = newLegs,
            isTemporary = false,
            sortOrder = forwardGroup.sortOrder + 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 刷新路线组中所有候选线路在途车辆实时状态并聚合计算最先到站线路。
     * 新一轮刷新会取消上一轮尚未完成的刷新，避免旧结果覆盖新结果。
     */
    fun refreshRealtimeEta(groupList: List<TransitGroup> = groups.value): Job {
        etaRefreshJob?.cancel()
        val job = viewModelScope.launch(ioDispatcher) {
            refreshRealtimeEtaInternal(groupList)
        }
        etaRefreshJob = job
        return job
    }

    /** 合成候选线路的对齐（带内存缓存：命中 24 小时，未命中 10 分钟内不重查） */
    private suspend fun alignSyntheticCandidateCached(leg: TransitLeg, candidate: CandidateLine): CandidateLine? {
        val city = resolveCandidateCity(candidate, transitRepository.selectedCity.value)
        val cacheKey = listOf(
            city.cityId, city.cityName, candidate.lineName, leg.fromStopName, leg.toStopName, candidate.direction
        ).joinToString("|")
        val now = wallClockMillis()
        syntheticAlignmentCache[cacheKey]?.let { entry ->
            val ttl = if (entry.match != null) ALIGN_HIT_TTL_MS else ALIGN_MISS_TTL_MS
            if (now - entry.savedAtMs in 0 until ttl) return entry.match
        }
        val matched = findRealtimeLineMatch(
            city = city,
            lineName = candidate.lineName,
            fromStopName = leg.fromStopName,
            toStopName = leg.toStopName,
            preferredDirection = candidate.direction
        )
        currentCoroutineContext().ensureActive()
        syntheticAlignmentCache[cacheKey] = AlignmentCacheEntry(matched, now)
        return matched
    }

    private suspend fun refreshRealtimeEtaInternal(groupList: List<TransitGroup>): Unit = coroutineScope {
            val candidateAlignmentMap = mutableMapOf<String, CandidateLine>()
            for (group in groupList) {
                for (leg in group.legs) {
                    for (candidate in leg.candidateLines) {
                        val key = "${group.id}_${leg.legIndex}_${candidate.lineId}"
                        if (candidate.lineId.startsWith("line_") || candidate.fromStopOrder >= candidate.toStopOrder) {
                            val matched = alignSyntheticCandidateCached(leg, candidate)
                            if (matched != null) {
                                candidateAlignmentMap[key] = matched
                            }
                        }
                    }
                }
            }

            val allCandidates = groupList
                .flatMap { g ->
                    g.legs.flatMap { leg ->
                        leg.candidateLines.map { c ->
                            candidateAlignmentMap["${g.id}_${leg.legIndex}_${c.lineId}"] ?: c
                        }
                    }
                }

            if (allCandidates.isEmpty()) return@coroutineScope

            data class QueryKey(
                val cityId: String,
                val cityName: String,
                val lineId: String,
                val targetOrder: Int,
                val source: DataSourceType,
                val lineName: String,
                val direction: Int
            )

            val uniqueQueries = allCandidates.map { candidate ->
                val targetOrder = if (candidate.fromStopOrder > 0) candidate.fromStopOrder else candidate.toStopOrder
                QueryKey(
                    cityId = candidate.cityId,
                    cityName = candidate.cityName,
                    lineId = candidate.lineId,
                    targetOrder = targetOrder,
                    source = candidate.source,
                    lineName = candidate.lineName,
                    direction = candidate.direction
                )
            }.distinctBy { "${it.lineId}_${it.targetOrder}" }

            val detailResults = uniqueQueries.map { query ->
                async {
                    val city = TransitCity(cityId = query.cityId, cityName = query.cityName)
                    // 只走 transitRepository 一条路径，失败不再对同一上游重复请求
                    val detail: BusLineDetail? = runCatching {
                        transitRepository.getLineDetail(
                            city = city,
                            lineId = query.lineId,
                            targetOrder = query.targetOrder,
                            source = query.source,
                            lineNameHint = query.lineName,
                            directionHint = query.direction
                        ).getOrNull()
                    }.getOrNull()
                    "${query.lineId}_${query.targetOrder}" to detail
                }
            }.awaitAll().toMap()

            val updatedCandidateMap = _candidateRealtimeMap.value.toMutableMap()
            val updatedSummaryMap = _legRealtimeSummary.value.toMutableMap()

            for (group in groupList) {
                for (leg in group.legs) {
                    val legCandidates = leg.candidateLines
                    if (legCandidates.isEmpty()) continue

                    val parsedInfos = legCandidates.map { candidate ->
                        val effectiveCandidate = candidateAlignmentMap["${group.id}_${leg.legIndex}_${candidate.lineId}"] ?: candidate
                        val targetOrder = if (effectiveCandidate.fromStopOrder > 0) effectiveCandidate.fromStopOrder else effectiveCandidate.toStopOrder
                        val detail = detailResults["${effectiveCandidate.lineId}_${targetOrder}"]

                        val rideStops = (effectiveCandidate.toStopOrder - effectiveCandidate.fromStopOrder).coerceAtLeast(0)
                        val directionText = detail?.line?.endStop?.takeIf { it.isNotBlank() }?.let { "${it}方向" }
                            ?: effectiveCandidate.endStopName.takeIf { it.isNotBlank() }?.let { "${it}方向" }
                            ?: ""

                        if (detail != null) {
                            val candidateBuses = detail.buses.filter { it.order <= targetOrder }
                            val nearest = candidateBuses.maxByOrNull { it.order.toFloat() + it.segmentProgress }
                            if (nearest != null) {
                                val stopsLeft = (targetOrder - nearest.order).coerceAtLeast(0)
                                val isArrived = stopsLeft == 0 && (nearest.isArrived || nearest.segmentProgress >= 0.98f)
                                val etaMinutes = if (nearest.etaSeconds > 0) {
                                    (nearest.etaSeconds / 60.0).roundToInt().coerceAtLeast(1)
                                } else {
                                    (stopsLeft * 2).coerceAtLeast(1)
                                }
                                val etaText = if (isArrived) {
                                    "车辆已到站"
                                } else {
                                    val formattedEta = RouteProjectionMath.formatEtaText(nearest.etaSeconds, isArrived)
                                    "还有 $stopsLeft 站 · $formattedEta"
                                }
                                val shortChipEta = if (isArrived) "已到站" else "${stopsLeft}站"

                                CandidateLineRealtimeInfo(
                                    lineId = candidate.lineId,
                                    lineName = candidate.lineName,
                                    etaText = etaText,
                                    shortChipEta = shortChipEta,
                                    stopsAway = stopsLeft,
                                    estimatedMinutes = etaMinutes,
                                    directionText = directionText,
                                    vehiclePlate = nearest.friendlyPlate,
                                    rideStopsCount = rideStops,
                                    isFastest = false
                                )
                            } else {
                                CandidateLineRealtimeInfo(
                                    lineId = candidate.lineId,
                                    lineName = candidate.lineName,
                                    etaText = "暂无在途车辆",
                                    shortChipEta = "等待发车",
                                    stopsAway = Int.MAX_VALUE,
                                    estimatedMinutes = Int.MAX_VALUE,
                                    directionText = directionText,
                                    vehiclePlate = "",
                                    rideStopsCount = rideStops,
                                    isFastest = false
                                )
                            }
                        } else {
                            CandidateLineRealtimeInfo(
                                lineId = candidate.lineId,
                                lineName = candidate.lineName,
                                etaText = "等待调度发车",
                                shortChipEta = "等待发车",
                                stopsAway = Int.MAX_VALUE,
                                estimatedMinutes = Int.MAX_VALUE,
                                directionText = directionText,
                                vehiclePlate = "",
                                rideStopsCount = rideStops,
                                isFastest = false
                            )
                        }
                    }

                    val minStops = parsedInfos
                        .filter { it.stopsAway < Int.MAX_VALUE }
                        .minOfOrNull { it.stopsAway }

                    val fastestLineId = if (minStops != null) {
                        parsedInfos.find { it.stopsAway == minStops }?.lineId
                    } else null

                    parsedInfos.forEachIndexed { idx, info ->
                        val candidate = legCandidates[idx]
                        val effective = candidateAlignmentMap["${group.id}_${leg.legIndex}_${candidate.lineId}"] ?: candidate
                        val isFastest = (info.lineId == fastestLineId)
                        val finalInfo = info.copy(isFastest = isFastest)

                        val candidateLegKey = "${group.id}_${leg.legIndex}_${candidate.lineId}"
                        val candidateLineKey = "${candidate.lineId}_${candidate.toStopOrder}"
                        val sameLine = candidate.lineId == effective.lineId
                        val sameOrder = candidate.toStopOrder == effective.toStopOrder

                        // 始终保留原始 key，UI 侧会按原始候选项查询
                        updatedCandidateMap[candidateLegKey] = finalInfo
                        updatedCandidateMap[candidateLineKey] = finalInfo
                        updatedSummaryMap[candidateLineKey] = finalInfo.etaText

                        // 对齐后的 key 仅在真正不同时才额外写入，避免同 key 重复赋值
                        if (!sameLine) {
                            updatedCandidateMap["${group.id}_${leg.legIndex}_${effective.lineId}"] = finalInfo
                        }
                        if (!sameLine || !sameOrder) {
                            updatedCandidateMap["${effective.lineId}_${effective.toStopOrder}"] = finalInfo
                            updatedSummaryMap["${effective.lineId}_${effective.toStopOrder}"] = finalInfo.etaText
                        }
                    }
                }
            }

            _candidateRealtimeMap.value = updatedCandidateMap
            _legRealtimeSummary.value = updatedSummaryMap
        }
}
