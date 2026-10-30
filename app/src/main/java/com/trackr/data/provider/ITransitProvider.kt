package com.trackr.data.provider

import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult

/**
 * 实时公交统一数据源 Provider 标准契约
 */
interface ITransitProvider {
    val sourceType: DataSourceType

    fun supportsLine(lineId: String): Boolean
    fun supportsStop(request: StopDetailRequest): Boolean
    fun supportsLocationRadar(): Boolean

    suspend fun getSupportedCities(): List<TransitCity>
    suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String?
    suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double? = null,
        userLng: Double? = null
    ): TransitSearchResult
    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4
    ): List<NearbyStop>
    suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop>
    suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail
    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null
    ): LineTimetable
}
