package com.trackr.data.provider.chelaile

import com.trackr.data.provider.ITransitProvider
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import com.trackr.domain.model.isPureSubwayStation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 车来了（Chelaile）数据源 Provider
 * 内聚加密请求、多物理站台并发补全、同源经纬度就近兜底与纯地铁过滤
 */
class ChelaileTransitProvider(
    private val chelaileClient: ChelaileApiClient
) : ITransitProvider {

    override val sourceType: DataSourceType = DataSourceType.CHELAILE

    override fun supportsLine(lineId: String): Boolean = !lineId.startsWith("mg:")

    override fun supportsStop(request: StopDetailRequest): Boolean {
        return request.stopId?.startsWith("mg:") != true &&
                request.physicalStId?.startsWith("mg:") != true &&
                request.namesakeStId?.startsWith("mg:") != true
    }

    override fun supportsLocationRadar(): Boolean = true

    override suspend fun getSupportedCities(): List<TransitCity> {
        return chelaileClient.getCities(hotOnly = false).map { city ->
            if (city.province.isBlank()) {
                city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName))
            } else {
                city
            }
        }
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? {
        return chelaileClient.reverseGeocodeCityName(wgsLat, wgsLng)
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): TransitSearchResult {
        return chelaileClient.search(
            cityId = city.cityId,
            keyword = keyword,
            userLat = userLat,
            userLng = userLng
        )
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> {
        val rawStops = chelaileClient.getNearbyStops(
            cityId = city.cityId,
            wgsLat = wgsLat,
            wgsLng = wgsLng,
            limit = limit
        ).filter { !it.isPureSubwayStation }

        // 车来了 encryptedHomePage 接口只对前 2 个站点填充实时到站数据（hasBusInfo: true），
        // 从第 3 个站点开始 lines 中的实时车辆为空且 shortDesc 为空。
        // 对缺少实时数据的站点，在 coroutineScope 中并发调用 getStopDetail 补全实时数据。
        val needsCompletion = rawStops.any { stop ->
            stop.lines.isNotEmpty() &&
                    stop.lines.all { it.approachingBuses.isEmpty() && it.statusText.isBlank() && it.preArrivalTime.isNullOrBlank() } &&
                    (!stop.physicalStId.isNullOrBlank() || stop.stopId.isNotBlank())
        }

        if (!needsCompletion) {
            return rawStops
        }

        return coroutineScope {
            rawStops.map { stop ->
                async {
                    val needsFetch = stop.lines.isNotEmpty() &&
                            stop.lines.all { it.approachingBuses.isEmpty() && it.statusText.isBlank() && it.preArrivalTime.isNullOrBlank() } &&
                            (!stop.physicalStId.isNullOrBlank() || stop.stopId.isNotBlank())

                    if (needsFetch) {
                        val subResult = runCatching {
                            chelaileClient.getStopDetail(
                                cityId = city.cityId,
                                physicalStId = stop.physicalStId.orEmpty(),
                                namesakeStId = stop.namesakeStId,
                                stationId = stop.stopId,
                                userLat = wgsLat,
                                userLng = wgsLng
                            )
                        }.getOrNull()

                        val matched = subResult?.firstOrNull {
                            (!stop.physicalStId.isNullOrBlank() && it.physicalStId == stop.physicalStId && it.lines.any { l -> l.line.lineId.isNotBlank() }) ||
                                    (stop.stopId.isNotBlank() && it.stopId == stop.stopId && it.lines.any { l -> l.line.lineId.isNotBlank() })
                        } ?: subResult?.firstOrNull { it.lines.any { l -> l.line.lineId.isNotBlank() } }

                        if (matched != null && matched.lines.isNotEmpty()) {
                            stop.copy(
                                lines = matched.lines,
                                subwayLineNames = if (matched.subwayLineNames.isNotEmpty()) matched.subwayLineNames else stop.subwayLineNames
                            )
                        } else stop
                    } else stop
                }
            }.awaitAll()
        }
    }

    override suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop> {
        // 阶段 1：主查询
        val rawStops = chelaileClient.getStopDetail(
            cityId = city.cityId,
            physicalStId = request.physicalStId.orEmpty(),
            namesakeStId = request.namesakeStId,
            stationId = request.stopId,
            userLat = request.userLat,
            userLng = request.userLng
        )

        // 车来了接口只为当前查询的 physicalStId 返回完整线路与实时数据，其他物理站台为占位空壳
        // 当存在多个物理站台且其他站台尚未填充完整线路时，并发补全各物理站台的完整线路信息
        val stops = if (rawStops.size > 1 && rawStops.any { s -> s.lines.isNotEmpty() && s.lines.all { it.line.lineId.isBlank() } }) {
            coroutineScope {
                rawStops.map { stop ->
                    async {
                        val needsFetch = stop.lines.isNotEmpty() && stop.lines.all { it.line.lineId.isBlank() }
                        if (needsFetch && !stop.physicalStId.isNullOrBlank()) {
                            val subResult = runCatching {
                                chelaileClient.getStopDetail(
                                    cityId = city.cityId,
                                    physicalStId = stop.physicalStId,
                                    namesakeStId = stop.namesakeStId ?: request.namesakeStId,
                                    stationId = stop.stopId.takeIf { it.isNotBlank() } ?: request.stopId,
                                    userLat = request.userLat,
                                    userLng = request.userLng
                                )
                            }.getOrNull()
                            val matched = subResult?.firstOrNull { it.physicalStId == stop.physicalStId && it.lines.any { l -> l.line.lineId.isNotBlank() } }
                                ?: subResult?.firstOrNull { it.lines.any { l -> l.line.lineId.isNotBlank() } }
                            if (matched != null) {
                                stop.copy(
                                    lines = matched.lines,
                                    subwayLineNames = if (matched.subwayLineNames.isNotEmpty()) matched.subwayLineNames else stop.subwayLineNames
                                )
                            } else stop
                        } else stop
                    }
                }.awaitAll()
            }
        } else {
            rawStops
        }

        val filteredStops = stops.filter { !it.isPureSubwayStation }
        if (filteredStops.isNotEmpty()) {
            return filteredStops
        }

        // 阶段 2：同源经纬度就近兜底与距离计算
        val stationLat = request.stationLat
        val stationLng = request.stationLng
        if (stationLat != null && stationLng != null && stationLat > 0.0 && stationLng > 0.0) {
            val nearby = chelaileClient.getNearbyStops(
                cityId = city.cityId,
                wgsLat = stationLat,
                wgsLng = stationLng,
                limit = 6
            ).filter { !it.isPureSubwayStation }

            val matched = if (!request.stopName.isNullOrBlank()) {
                val filtered = nearby.filter { it.name.contains(request.stopName) || request.stopName.contains(it.name) }
                if (filtered.isNotEmpty()) filtered else nearby
            } else {
                nearby
            }

            val userLat = request.userLat
            val userLng = request.userLng
            return if (userLat != null && userLng != null) {
                matched.map { s ->
                    if (s.distanceMeters == null || s.distanceMeters <= 0) {
                        val d = RouteProjectionMath.calculateDistanceMeters(userLat, userLng, s.wgsLat, s.wgsLng)
                        s.copy(distanceMeters = d)
                    } else s
                }
            } else {
                matched
            }
        }

        return emptyList()
    }

    override suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail {
        return chelaileClient.getLineDetail(
            cityId = city.cityId,
            lineId = request.lineId,
            targetOrder = request.targetOrder,
            stationId = request.stationId,
            wgsLat = request.wgsLat,
            wgsLng = request.wgsLng
        )
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): LineTimetable {
        val lineNo = line.lineNo.ifBlank { line.name }
        return chelaileClient.getTimetable(
            cityId = city.cityId,
            lineId = line.lineId,
            lineNo = lineNo,
            direction = line.direction,
            atTimeHHMM = atTimeHHMM
        )
    }
}
