package com.trackr.data.repository

import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一实时公交数据仓库契约（融合车来了 + 掌上公交双引擎）
 */
interface ITransitRepository {
    val selectedCity: StateFlow<TransitCity>
    val engineMode: StateFlow<EngineMode>
    val favorites: StateFlow<List<FavoriteStationLine>>
    val activeTrackingSession: StateFlow<TrackingSessionState>
    val userLocation: StateFlow<Pair<Double, Double>?>

    fun setEngineMode(mode: EngineMode)
    fun updateUserLocation(lat: Double, lng: Double)
    suspend fun selectCity(city: TransitCity)
    suspend fun getSupportedCities(forceRefresh: Boolean = false): Result<List<TransitCity>>
    suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?>

    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 10
    ): Result<List<NearbyStop>>

    suspend fun getStopDetail(
        city: TransitCity,
        stopId: String? = null,
        stopName: String? = null,
        physicalStId: String? = null,
        namesakeStId: String? = null,
        stationLat: Double? = null,
        stationLng: Double? = null,
        userLat: Double? = null,
        userLng: Double? = null
    ): Result<List<NearbyStop>>

    suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double? = null,
        userLng: Double? = null
    ): Result<TransitSearchResult>

    suspend fun getLineDetail(
        city: TransitCity,
        lineId: String,
        targetOrder: Int? = null,
        stationId: String? = null,
        wgsLat: Double? = null,
        wgsLng: Double? = null,
        source: DataSourceType = if (lineId.startsWith("mg:")) DataSourceType.MYGOLBS else DataSourceType.CHELAILE,
        lineNameHint: String = "",
        directionHint: Int = 0
    ): Result<BusLineDetail>

    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null
    ): Result<LineTimetable>

    suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>>

    suspend fun toggleFavorite(item: FavoriteStationLine): Boolean
    suspend fun removeFavorite(id: String)

    fun updateTrackingSession(state: TrackingSessionState)
}
