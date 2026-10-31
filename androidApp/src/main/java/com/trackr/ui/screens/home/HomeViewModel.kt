package com.trackr.ui.screens.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.data.location.AndroidDeviceLocationProvider
import com.trackr.data.location.DeviceLocationProvider
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.model.AggregatedSourcePriority
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import com.trackr.service.TrackingController
import com.trackr.ui.util.FixedDelayPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeUiState(
    val currentCity: TransitCity = TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", supportSubway = true, isHot = true, province = "直辖市"),
    val selectedProvince: String = "直辖市",
    val locatedCity: TransitCity? = null,
    val cities: List<TransitCity> = emptyList(),
    val citySearchQuery: String = "",
    val isCityPickerOpen: Boolean = false,
    val currentLat: Double = 31.2304,
    val currentLng: Double = 121.4737,
    val isLocating: Boolean = false,
    val locationError: String? = null,
    val nearbyStops: List<NearbyStop> = emptyList(),
    val isLoadingNearby: Boolean = false,
    val favorites: List<FavoriteStationLine> = emptyList(),
    val favoritesRealtime: Map<String, NearbyStop> = emptyMap(),
    val activeTracking: TrackingSessionState = TrackingSessionState(),
    val searchQuery: String = "",
    val searchResult: TransitSearchResult? = null,
    val isSearching: Boolean = false,
    val searchHistory: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val engineMode: EngineMode = EngineMode.CHELAILE,
    val aggregatedPriority: AggregatedSourcePriority = AggregatedSourcePriority.CHELAILE_FIRST
) {
    val filteredCities: List<TransitCity>
        get() {
            val baseList = when (engineMode) {
                EngineMode.MYGOLBS -> cities.filter { !it.mygolbsCityCode.isNullOrBlank() }
                EngineMode.CHELAILE -> cities.filter { it.cityId.isNotBlank() }
            }
            if (citySearchQuery.isBlank()) return baseList
            val q = citySearchQuery.trim().lowercase()
            return baseList.filter {
                it.cityName.lowercase().contains(q) ||
                it.pinyin.lowercase().contains(q) ||
                (it.province.ifBlank { ProvinceCityCatalog.inferProvince(it.cityName) }).lowercase().contains(q)
            }
        }
}

class HomeViewModel(
    private val repository: ITransitRepository,
    /** 设备定位（首页刷新时取一次当前位置） */
    private val locationProvider: DeviceLocationProvider,
    /** 追踪服务控制（「结束追踪」、把定位喂给换乘引擎） */
    private val trackingController: TrackingController,
    private val enableAutoPolling: Boolean = true,
    /** 单调时钟（毫秒），用于判断恢复轮询时距上次刷新是否已超过一个周期；测试可替换 */
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 上一次实时数据（附近站 + 收藏）刷新完成的时间，基于 [elapsedRealtimeMillis]；0 表示还没刷新过 */
    @Volatile
    private var lastRealtimeRefreshAtMs: Long = 0L

    /** 恢复轮询时补发的一次立即刷新 */
    private var catchUpPollJob: Job? = null

    internal val pollingCoordinator = PollingCoordinator(
        scope = viewModelScope,
        strategy = FixedDelayPollingStrategy(POLLING_INTERVAL_MS),
        initialEnabled = enableAutoPolling
    ) {
        pollRealtimeOnce()
    }

    /** 轮询一次附近站点与收藏线路的实时到站（定位 / 下拉刷新进行中时跳过，避免并发重复请求） */
    private suspend fun pollRealtimeOnce() {
        val state = _uiState.value
        if (!state.isRefreshing && !state.isLoadingNearby && !state.isLocating) {
            val nearbyResult = repository.getNearbyStops(
                state.currentCity,
                state.currentLat,
                state.currentLng,
                limit = repository.nearbyStopsCount.value
            )
            val favResult = repository.refreshFavoritesRealtime()
            _uiState.update {
                it.copy(
                    nearbyStops = nearbyResult.getOrNull() ?: it.nearbyStops,
                    favoritesRealtime = favResult.getOrNull() ?: it.favoritesRealtime
                )
            }
            lastRealtimeRefreshAtMs = elapsedRealtimeMillis()
        }
    }

    val isPollingActive: Boolean
        get() = pollingCoordinator.isPollingActive

    private var searchJob: Job? = null

    init {
        // 观察数据仓库中选中的城市
        viewModelScope.launch {
            repository.selectedCity.collect { city ->
                val state = _uiState.value
                if (state.currentCity.cityId == city.cityId && state.currentCity.cityName == city.cityName) {
                    return@collect
                }
                val isSameAsLocated = city.matchesCity(state.locatedCity)
                if (isSameAsLocated) {
                    _uiState.update {
                        it.copy(
                            currentCity = city,
                            selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
                        )
                    }
                } else {
                    val coords = ProvinceCityCatalog.getCityCenterCoordinates(city.cityName)
                    _uiState.update {
                        it.copy(
                            currentCity = city,
                            selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) },
                            currentLat = coords.first,
                            currentLng = coords.second
                        )
                    }
                    loadNearbyStops(coords.first, coords.second, cityOverride = city)
                }
            }
        }

        // 观察数据仓库收藏列表（桌面快捷方式与微件的同步统一在 MainActivity 里做，且只在收藏内容真变时触发）
        viewModelScope.launch {
            repository.favorites.collect { favs ->
                _uiState.update { it.copy(favorites = favs) }
            }
        }

        // 观察实时通知栏追踪会话
        viewModelScope.launch {
            repository.activeTrackingSession.collect { session ->
                _uiState.update { it.copy(activeTracking = session) }
            }
        }

        // 观察搜索历史
        viewModelScope.launch {
            repository.searchHistory.collect { history ->
                _uiState.update { it.copy(searchHistory = history) }
            }
        }

        // 观察当前引擎运行模式
        viewModelScope.launch {
            repository.engineMode.collect { mode ->
                val prevMode = _uiState.value.engineMode
                _uiState.update { it.copy(engineMode = mode) }
                val currentQuery = _uiState.value.searchQuery
                if (currentQuery.isNotBlank()) {
                    updateSearchQuery(currentQuery)
                } else if (prevMode != mode) {
                    val state = _uiState.value
                    loadNearbyStops(state.currentLat, state.currentLng)
                    refreshFavoritesRealtime()
                }
            }
        }

        // 观察聚合优先级设置变化
        viewModelScope.launch {
            repository.aggregatedPriority.collect { priority ->
                val prevPriority = _uiState.value.aggregatedPriority
                _uiState.update { it.copy(aggregatedPriority = priority) }
                if (prevPriority != priority) {
                    val currentQuery = _uiState.value.searchQuery
                    if (currentQuery.isNotBlank()) {
                        updateSearchQuery(currentQuery)
                    } else {
                        val state = _uiState.value
                        loadNearbyStops(state.currentLat, state.currentLng)
                        refreshFavoritesRealtime()
                    }
                }
            }
        }

        // 观察附近站点展示数量设置变化
        viewModelScope.launch {
            repository.nearbyStopsCount.collect { count ->
                val state = _uiState.value
                if (state.currentLat > 0.0 && state.currentLng > 0.0) {
                    loadNearbyStops(state.currentLat, state.currentLng, showLoading = false)
                }
            }
        }

        // 初始化加载支持的城市
        loadSupportedCities()

        // 刷新 GPS 定位并加载附近站点和收藏实时状态
        refreshLocationAndNearby()
    }

    private fun fallbackCities(): List<TransitCity> = listOf(
        TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "027", cityName = "北京", pinyin = "beijing", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "007", cityName = "广州", pinyin = "guangzhou", supportSubway = true, isHot = true, province = "广东"),
        TransitCity(cityId = "051", cityName = "深圳", pinyin = "shenzhen", supportSubway = true, isHot = true, province = "广东"),
        TransitCity(cityId = "015", cityName = "杭州", pinyin = "hangzhou", supportSubway = true, isHot = true, province = "浙江"),
        TransitCity(cityId = "055", cityName = "南京", pinyin = "nanjing", supportSubway = true, isHot = true, province = "江苏"),
        TransitCity(cityId = "028", cityName = "武汉", pinyin = "wuhan", supportSubway = true, isHot = true, province = "湖北"),
        TransitCity(cityId = "050", cityName = "成都", pinyin = "chengdu", supportSubway = true, isHot = true, province = "四川"),
        TransitCity(cityId = "036", cityName = "重庆", pinyin = "chongqing", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "440", cityName = "玉林", pinyin = "yulin", supportSubway = false, isHot = true, mygolbsCityCode = "450900", province = "广西")
    )

    private fun loadSupportedCities() {
        viewModelScope.launch {
            val result = repository.getSupportedCities()
            val list = result.getOrNull()
            if (!list.isNullOrEmpty()) {
                _uiState.update { it.copy(cities = list) }
            } else {
                _uiState.update { it.copy(cities = fallbackCities()) }
            }
        }
    }

    /**
     * 刷新定位并加载附近站点
     */
    fun refreshLocationAndNearby(switchCity: Boolean = false) {
        refresh(switchCity)
    }

    private fun loadNearbyStops(
        lat: Double,
        lng: Double,
        cityOverride: TransitCity? = null,
        showLoading: Boolean = true
    ) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoadingNearby = true) }
            }
            val targetCity = cityOverride ?: _uiState.value.currentCity
            val result = repository.getNearbyStops(targetCity, lat, lng, limit = repository.nearbyStopsCount.value)
            val stops = result.getOrNull().orEmpty()
            _uiState.update {
                it.copy(
                    nearbyStops = stops,
                    isLoadingNearby = false
                )
            }
        }
    }

    private fun refreshFavoritesRealtime() {
        viewModelScope.launch {
            val result = repository.refreshFavoritesRealtime()
            val map = result.getOrNull().orEmpty()
            if (map.isNotEmpty()) {
                _uiState.update { it.copy(favoritesRealtime = map) }
            }
        }
    }

    /**
     * 整体刷新（重新定位、刷新附近公交与收藏线路实时到站）
     *
     * @param switchCity 为 true 时把城市切到定位城市（仅城市弹窗里的「重新定位」使用）。
     * 启动、授权、下拉刷新等自动场景传 false：只更新坐标，不覆盖用户已选的城市（Issue #6）。
     * 从未存过城市（首次安装）时，即使传 false 也会切到定位城市。
     */
    fun refresh(switchCity: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, isLocating = true, locationError = null) }

            val favJob = launch {
                val favResult = repository.refreshFavoritesRealtime()
                _uiState.update { it.copy(favoritesRealtime = favResult.getOrNull().orEmpty()) }
            }

            val gpsLocation = locationProvider.fetchCurrentLocation(forceFresh = true)

            val nearbyJob = launch {
                val (lat, lng, targetCity) = if (gpsLocation != null) {
                    val lat = gpsLocation.latitude
                    val lng = gpsLocation.longitude
                    repository.updateUserLocation(lat, lng)
                    // 带精度与定位时刻喂给换乘引擎，过期 / 不准的定位不参与下车站判定
                    trackingController.updateUserFix(
                        lat = lat,
                        lng = lng,
                        accuracyMeters = if (gpsLocation.hasAccuracy()) gpsLocation.accuracy else null,
                        elapsedRealtimeNanos = gpsLocation.elapsedRealtimeNanos
                    )

                    // 尝试反向根据坐标解析匹配城市
                    val resolvedCity = try {
                        repository.resolveCityByLocation(lat, lng).getOrNull()
                    } catch (_: Exception) {
                        null
                    }

                    val shouldSwitchCity = switchCity || !repository.hasSavedCity
                    if (resolvedCity != null && shouldSwitchCity) {
                        repository.selectCity(resolvedCity)
                        _uiState.update {
                            it.copy(
                                currentCity = resolvedCity,
                                locatedCity = resolvedCity,
                                selectedProvince = resolvedCity.province.ifBlank {
                                    ProvinceCityCatalog.inferProvince(resolvedCity.cityName)
                                },
                                currentLat = lat,
                                currentLng = lng,
                                isLocating = false,
                                locationError = null
                            )
                        }
                        Triple(lat, lng, resolvedCity)
                    } else {
                        // 保留用户已选城市，只记录定位城市供城市弹窗展示「定位: X」
                        _uiState.update {
                            it.copy(
                                locatedCity = resolvedCity ?: it.locatedCity,
                                currentLat = lat,
                                currentLng = lng,
                                isLocating = false,
                                locationError = null
                            )
                        }
                        Triple(lat, lng, repository.selectedCity.value)
                    }
                } else {
                    val currentCity = _uiState.value.currentCity
                    val state = _uiState.value
                    val lat = if (state.currentLat > 0.0) state.currentLat else ProvinceCityCatalog.getCityCenterCoordinates(currentCity.cityName).first
                    val lng = if (state.currentLng > 0.0) state.currentLng else ProvinceCityCatalog.getCityCenterCoordinates(currentCity.cityName).second
                    _uiState.update {
                        it.copy(
                            currentLat = lat,
                            currentLng = lng,
                            isLocating = false,
                            locationError = "定位不可用，已保留当前位置"
                        )
                    }
                    Triple(lat, lng, currentCity)
                }

                val limit = repository.nearbyStopsCount.value
                var stops = repository.getNearbyStops(targetCity, lat, lng, limit = limit).getOrNull().orEmpty()

                // 人不在已选城市（如出差在外）时，GPS 坐标查不到该城市的站点：改用已选城市中心点再查一次
                if (stops.isEmpty() && gpsLocation != null && !targetCity.matchesCity(_uiState.value.locatedCity)) {
                    val center = ProvinceCityCatalog.findCityCenterCoordinates(targetCity.cityName)
                    if (center != null) {
                        stops = repository.getNearbyStops(targetCity, center.first, center.second, limit = limit)
                            .getOrNull().orEmpty()
                        _uiState.update { it.copy(currentLat = center.first, currentLng = center.second) }
                    }
                }

                _uiState.update {
                    it.copy(
                        nearbyStops = stops,
                        isLoadingNearby = false
                    )
                }
            }

            favJob.join()
            nearbyJob.join()
            _uiState.update { it.copy(isRefreshing = false, isLocating = false, isLoadingNearby = false) }
            lastRealtimeRefreshAtMs = elapsedRealtimeMillis()
        }
    }

    /**
     * 设置轮询是否激活（生命周期感知或显式开关）。
     * 只应在 App 前台、当前 tab 是首页且首页在回退栈顶时激活（由 MainActivity 控制）。
     * 从暂停恢复时，若距上次刷新已超过一个轮询周期，立即补刷一次，而不是再等一个完整周期。
     */
    fun setPollingActive(active: Boolean) {
        val resuming = active && !pollingCoordinator.isPollingActive
        pollingCoordinator.setActive(active)
        if (!active) {
            catchUpPollJob?.cancel()
            catchUpPollJob = null
            return
        }
        val lastRefresh = lastRealtimeRefreshAtMs
        if (resuming && lastRefresh > 0L &&
            elapsedRealtimeMillis() - lastRefresh >= POLLING_INTERVAL_MS &&
            catchUpPollJob?.isActive != true
        ) {
            catchUpPollJob = viewModelScope.launch { pollRealtimeOnce() }
        }
    }

    /**
     * 暂停后台轮询，安全取消当前轮询 Job
     */
    fun pausePolling() {
        pollingCoordinator.pause()
    }

    /**
     * 恢复/启动后台轮询（幂等，避免重复启动）
     */
    fun resumePolling() {
        pollingCoordinator.resume()
    }

    fun setCityPickerOpen(isOpen: Boolean) {
        _uiState.update {
            val defaultProvince = if (isOpen) {
                it.currentCity.province.ifBlank { ProvinceCityCatalog.inferProvince(it.currentCity.cityName) }
            } else {
                it.selectedProvince
            }
            it.copy(isCityPickerOpen = isOpen, citySearchQuery = "", selectedProvince = defaultProvince)
        }
    }

    fun updateCitySearchQuery(query: String) {
        _uiState.update { it.copy(citySearchQuery = query) }
    }

    fun selectProvince(province: String) {
        _uiState.update { it.copy(selectedProvince = province) }
    }

    fun selectCity(city: TransitCity) {
        viewModelScope.launch {
            val state = _uiState.value
            val isLocated = city.matchesCity(state.locatedCity)
            val coords = if (isLocated) {
                // 优先用真实 GPS 坐标：当前坐标可能已回退为其他城市的中心点
                repository.userLocation.value ?: Pair(state.currentLat, state.currentLng)
            } else {
                ProvinceCityCatalog.getCityCenterCoordinates(city.cityName)
            }

            // 若所选城市仅某单一数据源支持，自动联动切换至支持的引擎
            if (city.cityId.isBlank() && !city.mygolbsCityCode.isNullOrBlank() && state.engineMode != EngineMode.MYGOLBS) {
                setEngineMode(EngineMode.MYGOLBS)
            } else if (city.cityId.isNotBlank() && city.mygolbsCityCode.isNullOrBlank() && state.engineMode != EngineMode.CHELAILE) {
                setEngineMode(EngineMode.CHELAILE)
            }

            _uiState.update {
                it.copy(
                    currentCity = city,
                    selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) },
                    isCityPickerOpen = false,
                    citySearchQuery = "",
                    currentLat = coords.first,
                    currentLng = coords.second,
                    searchResult = null
                )
            }
            repository.selectCity(city)
            loadNearbyStops(coords.first, coords.second, cityOverride = city)
            refreshFavoritesRealtime()
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResult = null, isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300L) // 300ms 防抖
            _uiState.update { it.copy(isSearching = true) }
            val state = _uiState.value
            val res = repository.search(
                city = state.currentCity,
                keyword = query.trim(),
                userLat = state.currentLat.takeIf { it > 0.0 },
                userLng = state.currentLng.takeIf { it > 0.0 }
            )
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResult = res.getOrNull()
                )
            }
        }
    }

    fun addSearchHistory(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            repository.addSearchHistory(query.trim())
        }
    }

    fun removeSearchHistory(query: String) {
        viewModelScope.launch {
            repository.removeSearchHistory(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
        }
    }

    fun toggleFavorite(item: FavoriteStationLine) {
        viewModelScope.launch {
            repository.toggleFavorite(item)
            refreshFavoritesRealtime()
        }
    }

    fun removeFavorite(id: String) {
        viewModelScope.launch {
            repository.removeFavorite(id)
        }
    }

    fun setEngineMode(mode: EngineMode) {
        repository.setEngineMode(mode)
    }

    fun setAggregatedPriority(priority: AggregatedSourcePriority) {
        repository.setAggregatedPriority(priority)
    }

    fun toggleEngineMode() {
        val nextMode = when (_uiState.value.engineMode) {
            EngineMode.MYGOLBS -> EngineMode.CHELAILE
            EngineMode.CHELAILE -> EngineMode.MYGOLBS
        }
        setEngineMode(nextMode)
    }

    fun stopTracking() {
        viewModelScope.launch {
            repository.updateTrackingSession(TrackingSessionState(isActive = false))
            try {
                // 直接停止追踪服务；分组追踪时同时清掉换乘组状态
                trackingController.stopTracking()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pausePolling()
        searchJob?.cancel()
    }

    companion object {
        const val DEFAULT_NEARBY_STOPS_LIMIT = 4
        const val MAX_NEARBY_STOPS_LIMIT = 16
        const val NEARBY_STOPS_LIMIT = 4
        const val POLLING_INTERVAL_MS = 15_000L

        /** 主动定位的收集窗口（定位代码已移到 [AndroidDeviceLocationProvider]） */
        const val LOCATION_WINDOW_MS = AndroidDeviceLocationProvider.LOCATION_WINDOW_MS

        /** 精度达到该值即可提前结束定位收集 */
        const val GOOD_ACCURACY_METERS = AndroidDeviceLocationProvider.GOOD_ACCURACY_METERS

        /** 兜底使用的旧定位最大年龄 */
        const val MAX_STALE_LOCATION_AGE_MS = AndroidDeviceLocationProvider.MAX_STALE_LOCATION_AGE_MS
    }
}
