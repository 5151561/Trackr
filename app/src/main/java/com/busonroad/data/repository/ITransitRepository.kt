package com.busonroad.data.repository

import com.busonroad.domain.model.BusLineDetail
import com.busonroad.domain.model.BusLineDirection
import com.busonroad.domain.model.DataSourceType
import com.busonroad.domain.model.FavoriteStationLine
import com.busonroad.domain.model.LineTimetable
import com.busonroad.domain.model.NearbyStop
import com.busonroad.domain.model.TrackingSessionState
import com.busonroad.domain.model.TransitCity
import com.busonroad.domain.model.TransitSearchResult
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一实时公交数据仓库契约（融合车来了 + 掌上公交双引擎）
 */
interface ITransitRepository {
    val selectedCity: StateFlow<TransitCity>
    val favorites: StateFlow<List<FavoriteStationLine>>
    val activeTrackingSession: StateFlow<TrackingSessionState>

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
        physicalStId: String,
        namesakeStId: String? = null,
        wgsLat: Double? = null,
        wgsLng: Double? = null
    ): Result<List<NearbyStop>>

    suspend fun search(
        city: TransitCity,
        keyword: String
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
