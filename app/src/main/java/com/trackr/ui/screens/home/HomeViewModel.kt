package com.trackr.ui.screens.home

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.TrackrApp
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.model.AggregatedSourcePriority
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import com.trackr.widget.BusStopWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
    val engineMode: EngineMode = EngineMode.ALL,
    val aggregatedPriority: AggregatedSourcePriority = AggregatedSourcePriority.CHELAILE_FIRST
) {
    val filteredCities: List<TransitCity>
        get() {
            val baseList = when (engineMode) {
                EngineMode.MYGOLBS -> cities.filter { !it.mygolbsCityCode.isNullOrBlank() }
                EngineMode.CHELAILE -> cities.filter { it.cityId.isNotBlank() }
                EngineMode.ALL -> cities
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
    application: Application,
    private val repository: ITransitRepository,
    private val enableAutoPolling: Boolean = true
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        (application as? TrackrApp)?.repository ?: TrackrApp.instance.repository
    )

    constructor() : this(
        TrackrApp.instance,
        TrackrApp.instance.repository
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var autoPollingEnabled: Boolean = enableAutoPolling
    private var pollingJob: Job? = null
    private val pollingLock = Any()

    val isPollingActive: Boolean
        get() = pollingJob?.isActive == true

    private var searchJob: Job? = null
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    init {
        // 观察数据仓库中选中的城市
        viewModelScope.launch {
            repository.selectedCity.collect { city ->
                val state = _uiState.value
                if (state.currentCity.cityId == city.cityId && state.currentCity.cityName == city.cityName) {
                    return@collect
                }
                val isSameAsLocated = state.locatedCity?.cityId == city.cityId
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

        // 观察数据仓库收藏列表，并在变更时同步桌面快捷方式与微件
        viewModelScope.launch {
            repository.favorites.collect { favs ->
                _uiState.update { it.copy(favorites = favs) }
                BusStopWidgetProvider.updateDynamicShortcuts(getApplication(), favs)
                BusStopWidgetProvider.triggerRefresh(getApplication())
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
                if (prevPriority != priority && _uiState.value.engineMode == EngineMode.ALL) {
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

        // 开启 15 秒定时轮询
        if (enableAutoPolling) {
            resumePolling()
        }
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
    fun refreshLocationAndNearby() {
        refresh()
    }

    private fun hasLocationPermission(): Boolean {
        val context = getApplication<Application>()
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    private suspend fun fetchGpsLocation(forceFresh: Boolean = false): Location? {
        if (!hasLocationPermission()) return null
        val lm = locationManager ?: return null

        val candidateProviders = listOf(
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var bestFresh: Location? = null
        var bestStale: Location? = null
        val now = System.currentTimeMillis()
        val freshThreshold = if (forceFresh) 10_000L else 60_000L // 强制刷新时仅复用10秒内极新鲜的定位，超时则向硬件重新请求实时 fix

        for (provider in candidateProviders) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (now - loc.time < freshThreshold) {
                        if (bestFresh == null || (loc.hasAccuracy() && loc.accuracy < (bestFresh.accuracy.takeIf { bestFresh.hasAccuracy() } ?: Float.MAX_VALUE))) {
                            bestFresh = loc
                        }
                    }
                    if (bestStale == null || loc.time > bestStale.time) {
                        bestStale = loc
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        if (bestFresh != null) {
            return bestFresh
        }

        // 主动向可用 provider 发起高频监听，最多等待 4.5 秒
        val activeProviders = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { provider ->
            try {
                lm.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }

        if (activeProviders.isEmpty()) {
            return bestStale
        }

        val realTimeFix = withTimeoutOrNull(4500L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Exception) {
                        }
                        if (cont.isActive) {
                            cont.resume(location)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }

                var requestedAny = false
                for (provider in activeProviders) {
                    try {
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                        requestedAny = true
                    } catch (_: SecurityException) {
                    } catch (_: Exception) {
                    }
                }

                if (!requestedAny && cont.isActive) {
                    cont.resume(null)
                }
            }
        }

        return realTimeFix ?: bestStale
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
     * 手动触发整体刷新（带上重新定位、刷新附近公交与收藏线路实时到站）
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, isLocating = true, locationError = null) }

            val favJob = launch {
                val favResult = repository.refreshFavoritesRealtime()
                _uiState.update { it.copy(favoritesRealtime = favResult.getOrNull().orEmpty()) }
            }

            val gpsLocation = fetchGpsLocation(forceFresh = true)

            val nearbyJob = launch {
                val (lat, lng, targetCity) = if (gpsLocation != null) {
                    val lat = gpsLocation.latitude
                    val lng = gpsLocation.longitude
                    repository.updateUserLocation(lat, lng)

                    // 尝试反向根据坐标解析匹配城市
                    val resolvedCity = try {
                        repository.resolveCityByLocation(lat, lng).getOrNull()
                    } catch (_: Exception) {
                        null
                    }

                    val city = resolvedCity ?: _uiState.value.currentCity
                    if (resolvedCity != null) {
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
                    } else {
                        _uiState.update {
                            it.copy(
                                currentLat = lat,
                                currentLng = lng,
                                isLocating = false,
                                locationError = null
                            )
                        }
                    }
                    Triple(lat, lng, city)
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

                val nearbyResult = repository.getNearbyStops(targetCity, lat, lng, limit = repository.nearbyStopsCount.value)
                _uiState.update {
                    it.copy(
                        nearbyStops = nearbyResult.getOrNull().orEmpty(),
                        isLoadingNearby = false
                    )
                }
            }

            favJob.join()
            nearbyJob.join()
            _uiState.update { it.copy(isRefreshing = false, isLocating = false, isLoadingNearby = false) }
        }
    }

    /**
     * 设置轮询是否激活（生命周期感知或显式开关）
     */
    fun setPollingActive(active: Boolean) {
        synchronized(pollingLock) {
            autoPollingEnabled = active
            if (active) {
                resumePolling()
            } else {
                pausePolling()
            }
        }
    }

    /**
     * 暂停后台轮询，安全取消当前轮询 Job
     */
    fun pausePolling() {
        synchronized(pollingLock) {
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    /**
     * 恢复/启动后台轮询（幂等，避免重复启动）
     */
    fun resumePolling() {
        synchronized(pollingLock) {
            if (!autoPollingEnabled) return
            if (pollingJob?.isActive == true) return

            pollingJob = viewModelScope.launch {
                while (isActive) {
                    delay(POLLING_INTERVAL_MS)
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
                    }
                }
            }
        }
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
            val isLocated = city.cityId == state.locatedCity?.cityId
            val coords = if (isLocated) {
                Pair(state.currentLat, state.currentLng)
            } else {
                ProvinceCityCatalog.getCityCenterCoordinates(city.cityName)
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
            EngineMode.ALL -> EngineMode.MYGOLBS
            EngineMode.MYGOLBS -> EngineMode.CHELAILE
            EngineMode.CHELAILE -> EngineMode.ALL
        }
        setEngineMode(nextMode)
    }

    fun stopTracking() {
        viewModelScope.launch {
            repository.updateTrackingSession(TrackingSessionState(isActive = false))
            val stopIntent = Intent("com.trackr.action.STOP_TRACKING").apply {
                setPackage(getApplication<Application>().packageName)
            }
            try {
                getApplication<Application>().sendBroadcast(stopIntent)
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
    }
}
