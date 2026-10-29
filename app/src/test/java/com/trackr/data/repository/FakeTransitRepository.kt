package com.trackr.data.repository

import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 可控的内存 Fake TransitRepository，用于 Unit 测试与 Compose UI 行为测试
 */
class FakeTransitRepository(
    initialCity: TransitCity = TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", isHot = true),
    initialFavorites: List<FavoriteStationLine> = emptyList()
) : ITransitRepository {

    private val _selectedCity = MutableStateFlow(initialCity)
    override val selectedCity: StateFlow<TransitCity> = _selectedCity.asStateFlow()

    private val _favorites = MutableStateFlow(initialFavorites)
    override val favorites: StateFlow<List<FavoriteStationLine>> = _favorites.asStateFlow()

    private val _activeTrackingSession = MutableStateFlow(TrackingSessionState())
    override val activeTrackingSession: StateFlow<TrackingSessionState> = _activeTrackingSession.asStateFlow()

    private val _userLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    // 可配置的测试数据钩子
    var supportedCitiesResult: Result<List<TransitCity>> = Result.success(listOf(initialCity))
    var nearbyStopsResult: Result<List<NearbyStop>> = Result.success(emptyList())
    var stopDetailResult: Result<List<NearbyStop>> = Result.success(emptyList())
    var searchResult: Result<TransitSearchResult> = Result.success(TransitSearchResult(keyword = ""))
    var lineDetailResult: Result<BusLineDetail>? = null
    var timetableResult: Result<LineTimetable> = Result.success(LineTimetable(lineId = "", lineName = ""))
    var favoritesRealtimeResult: Result<Map<String, NearbyStop>> = Result.success(emptyMap())

    override fun updateUserLocation(lat: Double, lng: Double) {
        _userLocation.value = lat to lng
    }

    override suspend fun selectCity(city: TransitCity) {
        _selectedCity.value = city
    }

    override suspend fun getSupportedCities(forceRefresh: Boolean): Result<List<TransitCity>> {
        return supportedCitiesResult
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?> {
        return Result.success(_selectedCity.value)
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): Result<List<NearbyStop>> {
        return nearbyStopsResult
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
    ): Result<List<NearbyStop>> {
        return stopDetailResult
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): Result<TransitSearchResult> {
        return searchResult
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
        lineDetailResult?.let { return it }
        val direction = BusLineDirection(
            lineId = lineId,
            name = if (lineNameHint.isNotBlank()) lineNameHint else "测试线路",
            startStop = "起点站",
            endStop = "终点站",
            source = source
        )
        val fallback = BusLineDetail(
            line = direction,
            stops = emptyList(),
            buses = emptyList()
        )
        return Result.success(fallback)
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): Result<LineTimetable> {
        return timetableResult
    }

    override suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>> {
        return favoritesRealtimeResult
    }

    override suspend fun toggleFavorite(item: FavoriteStationLine): Boolean {
        val current = _favorites.value
        val exists = current.any { it.id == item.id }
        if (exists) {
            _favorites.update { list -> list.filterNot { it.id == item.id } }
            return false
        } else {
            _favorites.update { list -> list + item }
            return true
        }
    }

    override suspend fun removeFavorite(id: String) {
        _favorites.update { list -> list.filterNot { it.id == id } }
    }

    override fun updateTrackingSession(state: TrackingSessionState) {
        _activeTrackingSession.value = state
    }
}
