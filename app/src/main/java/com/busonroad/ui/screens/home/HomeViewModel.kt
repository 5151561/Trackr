package com.busonroad.ui.screens.home

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
import com.busonroad.BusOnRoadApp
import com.busonroad.data.repository.ITransitRepository
import com.busonroad.domain.model.FavoriteStationLine
import com.busonroad.domain.model.NearbyStop
import com.busonroad.domain.model.ProvinceCityCatalog
import com.busonroad.domain.model.TrackingSessionState
import com.busonroad.domain.model.TransitCity
import com.busonroad.domain.model.TransitSearchResult
import com.busonroad.widget.BusStopWidgetProvider
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
    val isRefreshing: Boolean = false
) {
    val filteredCities: List<TransitCity>
        get() {
            if (citySearchQuery.isBlank()) return cities
            val q = citySearchQuery.trim().lowercase()
            return cities.filter {
                it.cityName.lowercase().contains(q) ||
                it.pinyin.lowercase().contains(q) ||
                (it.province.ifBlank { ProvinceCityCatalog.inferProvince(it.cityName) }).lowercase().contains(q)
            }
        }
}

class HomeViewModel @JvmOverloads constructor(
    application: Application = BusOnRoadApp.instance,
    private val repository: ITransitRepository = (application as? BusOnRoadApp)?.repository ?: BusOnRoadApp.instance.repository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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

        // 观察数据仓库收藏列表，并在变更时同步桌面快捷方式
        viewModelScope.launch {
            repository.favorites.collect { favs ->
                _uiState.update { it.copy(favorites = favs) }
                BusStopWidgetProvider.updateDynamicShortcuts(getApplication(), favs)
            }
        }

        // 观察实时通知栏追踪会话
        viewModelScope.launch {
            repository.activeTrackingSession.collect { session ->
                _uiState.update { it.copy(activeTracking = session) }
            }
        }

        // 初始化加载支持的城市
        loadSupportedCities()

        // 刷新 GPS 定位并加载附近站点和收藏实时状态
        refreshLocationAndNearby()

        // 开启 15 秒定时轮询
        startRealtimePolling()
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLocating = true, locationError = null) }
            val gpsLocation = fetchGpsLocation()

            if (gpsLocation != null) {
                val lat = gpsLocation.latitude
                val lng = gpsLocation.longitude

                // 尝试反向根据坐标解析匹配城市
                val resolvedCity = try {
                    repository.resolveCityByLocation(lat, lng).getOrNull()
                } catch (_: Exception) {
                    null
                }

                val targetCity = resolvedCity ?: _uiState.value.currentCity
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

                loadNearbyStops(lat, lng, cityOverride = targetCity)
                refreshFavoritesRealtime()
            } else {
                val currentCity = _uiState.value.currentCity
                val fallback = ProvinceCityCatalog.getCityCenterCoordinates(currentCity.cityName)
                val lat = fallback.first
                val lng = fallback.second
                _uiState.update {
                    it.copy(
                        currentLat = lat,
                        currentLng = lng,
                        isLocating = false,
                        locationError = "定位不可用，已切换至当前城市中心"
                    )
                }
                loadNearbyStops(lat, lng, cityOverride = currentCity)
                refreshFavoritesRealtime()
            }
        }
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

    private suspend fun fetchGpsLocation(): Location? {
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

        for (provider in candidateProviders) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (now - loc.time < 10 * 60 * 1000L) {
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
            val result = repository.getNearbyStops(targetCity, lat, lng, limit = 12)
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
     * 手动触发整体刷新
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val state = _uiState.value
            val nearbyJob = launch {
                val result = repository.getNearbyStops(state.currentCity, state.currentLat, state.currentLng, limit = 12)
                _uiState.update { it.copy(nearbyStops = result.getOrNull().orEmpty()) }
            }
            val favJob = launch {
                val favResult = repository.refreshFavoritesRealtime()
                _uiState.update { it.copy(favoritesRealtime = favResult.getOrNull().orEmpty()) }
            }
            nearbyJob.join()
            favJob.join()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun startRealtimePolling() {
        viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                val state = _uiState.value
                if (!state.isRefreshing && !state.isLoadingNearby) {
                    val nearbyResult = repository.getNearbyStops(state.currentCity, state.currentLat, state.currentLng, limit = 12)
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
            val res = repository.search(_uiState.value.currentCity, query.trim())
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResult = res.getOrNull()
                )
            }
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

    fun stopTracking() {
        viewModelScope.launch {
            repository.updateTrackingSession(TrackingSessionState(isActive = false))
            val stopIntent = Intent("com.busonroad.action.STOP_TRACKING").apply {
                setPackage(getApplication<Application>().packageName)
            }
            try {
                getApplication<Application>().sendBroadcast(stopIntent)
            } catch (_: Exception) {
            }
        }
    }
}
