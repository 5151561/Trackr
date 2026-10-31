package com.trackr.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.chelaile.ChelaileTransitProvider
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.data.provider.mygolbs.MygolbsTransitProvider
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.data.remote.common.SingleFlightCache
import com.trackr.data.remote.mygolbs.MygolbsApiClient
import com.trackr.domain.model.AggregatedSourcePriority
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.domain.model.TransitSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一实时公交数据仓库实现
 * 融合车来了与掌上公交双数据源，支持纯本地直连、混合检索、静态站表缓存及收藏持久化
 */
class TransitRepository(
    private val context: Context,
    val coordinator: TransitProviderCoordinator,
    /** 单调时钟（毫秒），用于线路详情短缓存；测试可注入 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** 线路详情合并请求的执行作用域；测试可注入 */
    fetchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : ITransitRepository {

    /**
     * 兼容既有调用方与测试的次构造函数
     */
    constructor(
        context: Context,
        chelaileClient: ChelaileApiClient,
        mygolbsClient: MygolbsApiClient
    ) : this(
        context = context,
        coordinator = TransitProviderCoordinator(
            chelaileProvider = ChelaileTransitProvider(chelaileClient),
            mygolbsProvider = MygolbsTransitProvider(mygolbsClient)
        )
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE)

    private val _selectedCity = MutableStateFlow(loadInitialCity())
    override val selectedCity: StateFlow<TransitCity> = _selectedCity.asStateFlow()
    override val hasSavedCity: Boolean
        get() = prefs.contains("selected_city_json")

    private val _engineMode = MutableStateFlow(loadInitialEngineMode())
    override val engineMode: StateFlow<EngineMode> = _engineMode.asStateFlow()

    private val _aggregatedPriority = MutableStateFlow(loadInitialAggregatedPriority())
    override val aggregatedPriority: StateFlow<AggregatedSourcePriority> = _aggregatedPriority.asStateFlow()

    private val _favorites = MutableStateFlow(loadInitialFavorites())
    override val favorites: StateFlow<List<FavoriteStationLine>> = _favorites.asStateFlow()

    private val _searchHistory = MutableStateFlow(loadInitialSearchHistory())
    override val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _preferredTransitCodeProvider = MutableStateFlow(loadInitialTransitCodeProvider())
    override val preferredTransitCodeProvider: StateFlow<TransitCodeProvider> = _preferredTransitCodeProvider.asStateFlow()

    private val _nearbyStopsCount = MutableStateFlow(loadInitialNearbyStopsCount())
    override val nearbyStopsCount: StateFlow<Int> = _nearbyStopsCount.asStateFlow()

    private val _isLiveNotificationEnabled = MutableStateFlow(loadInitialLiveNotificationEnabled())
    override val isLiveNotificationEnabled: StateFlow<Boolean> = _isLiveNotificationEnabled.asStateFlow()

    private val _activeTrackingSession = MutableStateFlow(TrackingSessionState())
    override val activeTrackingSession: StateFlow<TrackingSessionState> = _activeTrackingSession.asStateFlow()

    private val _userLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    override fun updateUserLocation(lat: Double, lng: Double) {
        if (lat > 0.0 && lng > 0.0) {
            _userLocation.value = Pair(lat, lng)
        }
    }

    // 内存中的静态站表缓存（lineId -> List<BusStop>）
    private val stopsCache = ConcurrentHashMap<String, List<BusStop>>()

    /**
     * 线路详情 key：同一数据源 / 城市 / 线路 / 候车站的请求视为同一份数据
     */
    private data class LineDetailKey(
        val source: DataSourceType,
        val cityId: String,
        val mygolbsCityCode: String,
        val lineId: String,
        val targetOrder: Int?,
        val stationId: String?
    )

    /**
     * 线路详情 in-flight 去重 + 短 TTL 缓存：
     * 首页收藏、详情页、跟车服务、小组件等同时请求同一线路时只发一次上游请求。
     */
    private val lineDetailFlights = SingleFlightCache<LineDetailKey, BusLineDetail>(
        ttlMillis = LINE_DETAIL_CACHE_TTL_MS,
        maxEntries = LINE_DETAIL_CACHE_MAX_ENTRIES,
        scope = fetchScope,
        nowMillis = nowMillis
    )
    private var cachedCities: List<TransitCity> = emptyList()

    private fun loadInitialCity(): TransitCity {
        val cityJson = prefs.getString("selected_city_json", null)
        if (!cityJson.isNullOrBlank()) {
            try {
                val city = json.decodeFromString<TransitCity>(cityJson)
                val prov = if (city.province.isNotBlank()) city.province else ProvinceCityCatalog.inferProvince(city.cityName)
                return city.copy(province = prov)
            } catch (_: Exception) {}
        }
        return TransitCity(cityId = "034", cityName = "上海", pinyin = "ShangHai", supportSubway = true, isHot = true, province = "直辖市")
    }

    private fun loadInitialFavorites(): List<FavoriteStationLine> {
        val favJson = prefs.getString("favorites_json", null)
        if (!favJson.isNullOrBlank()) {
            try {
                return json.decodeFromString<List<FavoriteStationLine>>(favJson)
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun loadInitialSearchHistory(): List<String> {
        val historyJson = prefs.getString("search_history_json", null)
        if (!historyJson.isNullOrBlank()) {
            try {
                return json.decodeFromString<List<String>>(historyJson)
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun loadInitialTransitCodeProvider(): TransitCodeProvider {
        val providerId = prefs.getString("preferred_transit_code_provider", TransitCodeProvider.ASK_ALWAYS.id)
        return TransitCodeProvider.fromId(providerId)
    }

    private fun loadInitialNearbyStopsCount(): Int {
        return prefs.getInt("nearby_stops_count", 4).coerceIn(1, 16)
    }

    override fun setNearbyStopsCount(count: Int) {
        val clamped = count.coerceIn(1, 16)
        _nearbyStopsCount.value = clamped
        prefs.edit().putInt("nearby_stops_count", clamped).apply()
    }

    private fun loadInitialLiveNotificationEnabled(): Boolean {
        return prefs.getBoolean("is_live_notification_enabled", true)
    }

    override fun setLiveNotificationEnabled(enabled: Boolean) {
        _isLiveNotificationEnabled.value = enabled
        prefs.edit().putBoolean("is_live_notification_enabled", enabled).apply()
    }

    private fun loadInitialEngineMode(): EngineMode {
        val modeStr = prefs.getString("engine_mode", EngineMode.CHELAILE.name)
        return runCatching { EngineMode.valueOf(modeStr ?: EngineMode.CHELAILE.name) }.getOrDefault(EngineMode.CHELAILE)
    }

    private fun loadInitialAggregatedPriority(): AggregatedSourcePriority {
        val name = prefs.getString("aggregated_source_priority", AggregatedSourcePriority.CHELAILE_FIRST.name)
        return runCatching { AggregatedSourcePriority.valueOf(name ?: AggregatedSourcePriority.CHELAILE_FIRST.name) }
            .getOrDefault(AggregatedSourcePriority.CHELAILE_FIRST)
    }

    override fun setAggregatedPriority(priority: AggregatedSourcePriority) {
        _aggregatedPriority.value = priority
        prefs.edit().putString("aggregated_source_priority", priority.name).apply()
    }

    override fun setEngineMode(mode: EngineMode) {
        _engineMode.value = mode
        prefs.edit().putString("engine_mode", mode.name).apply()
        // 若当前城市缺少掌上公交编码，尝试从缓存城市列表中补齐
        val currCity = _selectedCity.value
        if (currCity.mygolbsCityCode.isNullOrBlank() && cachedCities.isNotEmpty()) {
            val matched = cachedCities.firstOrNull {
                ProvinceCityCatalog.cleanCityName(it.cityName) == ProvinceCityCatalog.cleanCityName(currCity.cityName)
            }
            if (matched?.mygolbsCityCode?.isNotBlank() == true) {
                _selectedCity.value = currCity.copy(mygolbsCityCode = matched.mygolbsCityCode)
            }
        }
    }

    private fun saveFavorites(favs: List<FavoriteStationLine>) {
        val str = json.encodeToString(favs)
        prefs.edit().putString("favorites_json", str).apply()
    }

    private fun saveSearchHistory(history: List<String>) {
        val str = json.encodeToString(history)
        prefs.edit().putString("search_history_json", str).apply()
    }

    override suspend fun selectCity(city: TransitCity) {
        val effectiveCity = if (city.province.isBlank()) {
            city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName))
        } else {
            city
        }
        _selectedCity.value = effectiveCity
        withContext(Dispatchers.IO) {
            val str = json.encodeToString(effectiveCity)
            prefs.edit().putString("selected_city_json", str).apply()
        }
    }

    override suspend fun getSupportedCities(forceRefresh: Boolean): Result<List<TransitCity>> = runCatching {
        if (!forceRefresh && cachedCities.isNotEmpty()) {
            return@runCatching cachedCities
        }
        val cities = coordinator.getSupportedCities(forceRefresh)
        cachedCities = cities
        cities
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?> = runCatching {
        withContext(Dispatchers.IO) {
            val priority = _aggregatedPriority.value

            // 1) 优先尝试雷达 Provider GPS 逆地理服务（地级市 + 区县）
            var resolvedName: String? = coordinator.resolveCityByLocation(wgsLat, wgsLng, priority)
            var rawDistrict: String? = coordinator.resolveDistrictByLocation(wgsLat, wgsLng, priority)

            // 2) 若为空，尝试 Android 系统原生 Geocoder
            if (resolvedName.isNullOrBlank()) {
                try {
                    if (android.location.Geocoder.isPresent()) {
                        @Suppress("DEPRECATION")
                        android.location.Geocoder(context, java.util.Locale.CHINA)
                            .getFromLocation(wgsLat, wgsLng, 1)
                            ?.firstOrNull()
                            ?.let { address ->
                                resolvedName = address.locality ?: address.subAdminArea ?: address.adminArea
                                if (rawDistrict.isNullOrBlank()) rawDistrict = address.subLocality
                            }
                    }
                } catch (_: Exception) {
                }
            }

            val supportedCities = getSupportedCities().getOrDefault(emptyList())

            // 2.5) 定位在县 / 县级市时，优先匹配支持列表里的同名县城（Issue #6）
            matchCountyLevelCity(rawDistrict, resolvedName, supportedCities)?.let { county ->
                return@withContext county.copy(
                    province = county.province.ifBlank { ProvinceCityCatalog.inferProvince(county.cityName) }
                )
            }

            // 3) 若仍为空，使用内置离线核心城市 Haversine 距离（80km 以内）兜底
            if (resolvedName.isNullOrBlank()) {
                resolvedName = ProvinceCityCatalog.findNearestCityName(wgsLat, wgsLng)
            }

            val cityName = resolvedName
            if (cityName.isNullOrBlank()) {
                return@withContext null
            }

            val cleanTarget = ProvinceCityCatalog.cleanCityName(cityName)

            // 4) 在支持的城市列表中匹配（无后缀精准与包含匹配）
            val matched = supportedCities.firstOrNull { city ->
                val cleanItem = ProvinceCityCatalog.cleanCityName(city.cityName)
                cleanItem == cleanTarget ||
                        cleanItem.contains(cleanTarget) ||
                        cleanTarget.contains(cleanItem)
            }

            if (matched != null) {
                val prov = if (matched.province.isNotBlank()) {
                    matched.province
                } else {
                    ProvinceCityCatalog.inferProvince(matched.cityName)
                }
                return@withContext matched.copy(province = prov)
            }

            // 若 getSupportedCities() 中未包含，但为 ProvinceCityCatalog 中已知城市，构造返回以确保定位切换城市永不失败
            if (cleanTarget.isNotBlank() && ProvinceCityCatalog.isKnownCity(cleanTarget)) {
                val inferredProvince = ProvinceCityCatalog.inferProvince(cleanTarget)
                return@withContext TransitCity(
                    cityId = "",
                    cityName = cleanTarget,
                    province = inferredProvince
                )
            }

            null
        }
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): Result<List<NearbyStop>> = runCatching {
        coordinator.getNearbyStops(city, wgsLat, wgsLng, limit, _engineMode.value, _aggregatedPriority.value)
    }

    override suspend fun getStopDetail(
        city: TransitCity,
        stopId: String?,
        stopName: String?,
        physicalStId: String?,
        namesakeStId: String?,
        stationLat: Double?,
        stationLng: Double?,
        userLat: Double?,
        userLng: Double?
    ): Result<List<NearbyStop>> = resultCatchingNonCancellation {
        val resolvedUserLat = userLat ?: _userLocation.value?.first
        val resolvedUserLng = userLng ?: _userLocation.value?.second
        val request = StopDetailRequest(
            stopId = stopId,
            stopName = stopName,
            physicalStId = physicalStId,
            namesakeStId = namesakeStId,
            stationLat = stationLat,
            stationLng = stationLng,
            userLat = resolvedUserLat,
            userLng = resolvedUserLng
        )
        coordinator.getStopDetail(city, request, _engineMode.value, _aggregatedPriority.value)
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): Result<TransitSearchResult> = runCatching {
        val resolvedUserLat = userLat ?: _userLocation.value?.first
        val resolvedUserLng = userLng ?: _userLocation.value?.second
        val result = coordinator.search(
            city = city,
            keyword = keyword,
            mode = _engineMode.value,
            userLat = resolvedUserLat,
            userLng = resolvedUserLng,
            priority = _aggregatedPriority.value
        )
        if (result.lines.isNotEmpty() || result.stops.isNotEmpty()) {
            addSearchHistory(keyword)
        }
        result
    }

    override suspend fun getLineDetail(
        city: TransitCity,
        lineId: String,
        targetOrder: Int?,
        stationId: String?,
        wgsLat: Double?,
        wgsLng: Double?,
        source: DataSourceType,
        lineNameHint: String,
        directionHint: Int
    ): Result<BusLineDetail> {
        val request = LineDetailRequest(
            lineId = lineId,
            targetOrder = targetOrder,
            stationId = stationId,
            wgsLat = wgsLat,
            wgsLng = wgsLng,
            source = source,
            lineNameHint = lineNameHint,
            directionHint = directionHint
        )
        val key = LineDetailKey(
            source = source,
            cityId = city.cityId,
            mygolbsCityCode = city.mygolbsCityCode.orEmpty(),
            lineId = lineId,
            targetOrder = targetOrder,
            stationId = stationId
        )
        return resultCatchingNonCancellation {
            lineDetailFlights.get(key) { fetchLineDetail(city, request) }
        }
    }

    private suspend fun fetchLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail {
        val lineId = request.lineId
        val detail = coordinator.getLineDetail(city, request, _aggregatedPriority.value)

        // 静态站表内存缓存维护
        val effectiveStops = if (detail.stops.isNotEmpty()) {
            stopsCache[lineId] = detail.stops
            detail.stops
        } else {
            stopsCache[lineId] ?: detail.stops
        }

        return if (effectiveStops !== detail.stops) {
            detail.copy(stops = effectiveStops)
        } else {
            detail
        }
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): Result<LineTimetable> = runCatching {
        coordinator.getTimetable(city, line, atTimeHHMM, _aggregatedPriority.value)
    }

    override suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>> = runCatching {
        val favs = _favorites.value
        if (favs.isEmpty()) return@runCatching emptyMap()

        coroutineScope {
            val tasks = favs.map { fav ->
                async {
                    val city = TransitCity(
                        cityId = fav.cityId,
                        cityName = fav.cityName,
                        mygolbsCityCode = fav.mygolbsCityCode.takeIf { it.isNotBlank() }
                    )
                    val lineRes = runCatching {
                        getLineDetail(
                            city = city,
                            lineId = fav.lineId,
                            targetOrder = fav.targetOrder,
                            stationId = fav.targetStopId,
                            source = fav.source
                        ).getOrNull()
                    }.getOrNull()

                    if (lineRes != null) {
                        val approaching = lineRes.buses.filter { it.order <= fav.targetOrder }
                        val lineStatus = NearbyStopLineStatus(
                            line = lineRes.line,
                            targetOrder = fav.targetOrder,
                            targetStopId = fav.targetStopId,
                            targetStopName = fav.targetStopName,
                            statusText = lineRes.statusDescription,
                            preArrivalTime = lineRes.nextDepartureFromStart,
                            approachingBuses = approaching
                        )
                        val stop = NearbyStop(
                            stopId = fav.targetStopId,
                            name = fav.targetStopName,
                            wgsLat = fav.wgsLat,
                            wgsLng = fav.wgsLng,
                            lines = listOf(lineStatus)
                        )
                        fav.id to stop
                    } else null
                }
            }
            tasks.awaitAll().filterNotNull().toMap()
        }
    }

    override suspend fun toggleFavorite(item: FavoriteStationLine): Boolean {
        val current = _favorites.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        val isAdded: Boolean
        if (index >= 0) {
            current.removeAt(index)
            isAdded = false
        } else {
            current.add(0, item)
            isAdded = true
        }
        _favorites.value = current
        withContext(Dispatchers.IO) {
            saveFavorites(current)
        }
        return isAdded
    }

    override suspend fun removeFavorite(id: String) {
        val current = _favorites.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _favorites.value = current
            withContext(Dispatchers.IO) {
                saveFavorites(current)
            }
        }
    }

    override suspend fun addSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = _searchHistory.value.toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val limited = if (current.size > MAX_SEARCH_HISTORY) current.take(MAX_SEARCH_HISTORY) else current
        _searchHistory.value = limited
        withContext(Dispatchers.IO) {
            saveSearchHistory(limited)
        }
    }

    override suspend fun removeSearchHistory(query: String) {
        val trimmed = query.trim()
        val current = _searchHistory.value.toMutableList()
        val removed = current.removeAll { it.equals(trimmed, ignoreCase = true) }
        if (removed) {
            _searchHistory.value = current
            withContext(Dispatchers.IO) {
                saveSearchHistory(current)
            }
        }
    }

    override suspend fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        withContext(Dispatchers.IO) {
            prefs.edit().remove("search_history_json").apply()
        }
    }

    override suspend fun setPreferredTransitCodeProvider(provider: TransitCodeProvider) {
        _preferredTransitCodeProvider.value = provider
        withContext(Dispatchers.IO) {
            prefs.edit().putString("preferred_transit_code_provider", provider.id).apply()
        }
    }

    override fun updateTrackingSession(state: TrackingSessionState) {
        _activeTrackingSession.value = state
    }

    /**
     * 与 runCatching 相同，但协程取消（如轮询暂停）向上抛出，不包装成失败结果
     */
    private inline fun <T> resultCatchingNonCancellation(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    companion object {
        const val MAX_SEARCH_HISTORY = 20

        /** 线路详情短缓存时长：小于详情页 10 秒轮询周期，保证每轮轮询仍拿到新数据 */
        const val LINE_DETAIL_CACHE_TTL_MS = 6_000L
        const val LINE_DETAIL_CACHE_MAX_ENTRIES = 64
    }
}

/**
 * 把逆地理得到的区县名匹配为支持列表里的县城（Issue #6）。
 *
 * - 只认 "县"/"市"/"旗" 结尾的县级单位；"区" 是地级市的市辖区，交给地级市匹配，
 *   避免北京 "朝阳区" 被认成辽宁 "朝阳"。
 * - 清洗后名字必须完全相同，不用包含匹配。
 * - 能推断出省份时，县城所在省须与定位地级市所在省一致。
 */
internal fun matchCountyLevelCity(
    rawDistrict: String?,
    prefectureName: String?,
    supportedCities: List<TransitCity>
): TransitCity? {
    val district = rawDistrict?.trim().orEmpty()
    if (district.isBlank()) return null
    if (!(district.endsWith("县") || district.endsWith("市") || district.endsWith("旗"))) return null

    val cleanDistrict = ProvinceCityCatalog.cleanCityName(district)
    if (cleanDistrict.isBlank()) return null
    if (!prefectureName.isNullOrBlank() && ProvinceCityCatalog.cleanCityName(prefectureName) == cleanDistrict) {
        return null
    }

    val expectedProvince = prefectureName
        ?.takeIf { it.isNotBlank() }
        ?.let { ProvinceCityCatalog.cleanCityName(ProvinceCityCatalog.inferProvince(it)) }
        ?.takeIf { it.isNotBlank() && it != "其他" }

    return supportedCities.firstOrNull { city ->
        if (ProvinceCityCatalog.cleanCityName(city.cityName) != cleanDistrict) return@firstOrNull false
        if (expectedProvince == null) return@firstOrNull true
        val cityProvince = ProvinceCityCatalog.cleanCityName(
            city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
        )
        cityProvince.isBlank() || cityProvince == "其他" || cityProvince == expectedProvince
    }
}
