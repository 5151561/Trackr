package com.trackr.data.remote.amap

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.route.BusPath
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.RouteSearch
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * 基于高德轻量级 Android 搜索 SDK（com.amap.api:search）实现的公交路径规划与地点提示服务。
 * 遵循开源安全规范，所有 Key 均由外部注入或通过 AmapSdkManager 动态设置，源码零硬编码。
 */
class AmapSdkTransitService(
    private val context: Context
) {

    /**
     * POI 输入关键字联想与提示
     */
    suspend fun searchPoiTips(
        keyword: String,
        city: String = "",
        customApiKey: String? = null
    ): Result<List<AmapTipItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@runCatching emptyList()

            // 动态注入自备 Key 与隐私合规
            val hasKey = AmapSdkManager.applyApiKey(context, customApiKey)
            if (!hasKey) {
                throw IllegalStateException("未配置有效的高德 Android Key，请在弹窗中配置自备 Key")
            }

            val query = InputtipsQuery(kw, city.trim())
            query.cityLimit = false
            val inputtips = Inputtips(context, query)
            val tips = inputtips.requestInputtips() ?: return@runCatching emptyList()

            val result = mutableListOf<AmapTipItem>()
            for (tip in tips) {
                val name = tip.name?.trim().orEmpty()
                val point = tip.point
                if (name.isNotEmpty() && point != null) {
                    val location = "${point.longitude},${point.latitude}"
                    result.add(
                        AmapTipItem(
                            name = name,
                            district = tip.district?.trim().orEmpty(),
                            address = tip.address?.trim().orEmpty(),
                            location = location
                        )
                    )
                }
            }
            result
        }
    }

    /**
     * 高德公交路径规划
     * @param originLngLat 起点坐标 "lng,lat"
     * @param destinationLngLat 终点坐标 "lng,lat"
     */
    suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String = "起点",
        destName: String = "终点",
        city: String,
        customApiKey: String? = null
    ): Result<List<AmapTransitSchemeResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val hasKey = AmapSdkManager.applyApiKey(context, customApiKey)
            if (!hasKey) {
                throw IllegalStateException("未配置有效的高德 Android Key，请在弹窗中配置自备 Key")
            }

            val originParts = originLngLat.split(",")
            val destParts = destinationLngLat.split(",")
            if (originParts.size != 2 || destParts.size != 2) {
                throw IllegalArgumentException("起终点经纬度格式错误")
            }

            val originLng = originParts[0].toDoubleOrNull() ?: throw IllegalArgumentException("起点经度无效")
            val originLat = originParts[1].toDoubleOrNull() ?: throw IllegalArgumentException("起点纬度无效")
            val destLng = destParts[0].toDoubleOrNull() ?: throw IllegalArgumentException("终点经度无效")
            val destLat = destParts[1].toDoubleOrNull() ?: throw IllegalArgumentException("终点纬度无效")

            val fromPoint = LatLonPoint(originLat, originLng)
            val toPoint = LatLonPoint(destLat, destLng)

            val fromAndTo = RouteSearch.FromAndTo(fromPoint, toPoint)
            val busRouteQuery = RouteSearch.BusRouteQuery(
                fromAndTo,
                RouteSearch.BUS_DEFAULT,
                city.trim(),
                0
            )

            val routeSearch = RouteSearch(context)
            val busRouteResult = routeSearch.calculateBusRoute(busRouteQuery)
                ?: throw IllegalStateException("未能规划出公交路径")

            parseBusRouteResult(busRouteResult, originName, destName, city)
        }
    }

    companion object {
        /**
         * 将 SDK 返回的 [BusRouteResult] 转换为统一的 [AmapTransitSchemeResult]
         */
        fun parseBusRouteResult(
            busRouteResult: BusRouteResult,
            originName: String,
            destName: String,
            cityName: String
        ): List<AmapTransitSchemeResult> {
            val paths = busRouteResult.paths ?: return emptyList()
            val schemeResults = mutableListOf<AmapTransitSchemeResult>()

            for (path in paths) {
                val scheme = convertBusPathToScheme(path, originName, destName, cityName)
                if (scheme != null) {
                    schemeResults.add(scheme)
                }
            }
            return schemeResults
        }

        fun convertBusPathToScheme(
            path: BusPath,
            originName: String,
            destName: String,
            cityName: String
        ): AmapTransitSchemeResult? {
            val durationSeconds = path.duration.toInt()
            val durationMinutes = (durationSeconds + 59) / 60
            val walkingMeters = path.walkDistance.toInt()
            val costYuan = String.format(Locale.ROOT, "%.1f", path.cost)

            val steps = path.steps ?: return null
            val legs = mutableListOf<TransitLeg>()
            var legIndex = 0

            for (step in steps) {
                val busLines = step.busLines
                if (busLines.isNullOrEmpty()) continue

                val candidateLines = mutableListOf<CandidateLine>()
                var departureStopName = ""
                var arrivalStopName = ""
                var fromLat = 0.0
                var fromLng = 0.0
                var toLat = 0.0
                var toLng = 0.0

                for (lineItem in busLines) {
                    val rawName = lineItem.busLineName.orEmpty()
                    val lineName = rawName.substringBefore("(").trim()
                    val lineId = lineItem.busLineId.orEmpty()

                    val depStop = lineItem.departureBusStation
                    val arrStop = lineItem.arrivalBusStation

                    if (depStop != null && !depStop.busStationName.isNullOrEmpty()) {
                        departureStopName = depStop.busStationName.orEmpty()
                        depStop.latLonPoint?.let { pt ->
                            fromLng = pt.longitude
                            fromLat = pt.latitude
                        }
                    } else {
                        val firstStation = lineItem.busStations?.firstOrNull()
                        departureStopName = firstStation?.busStationName?.ifBlank { null } ?: originName
                        firstStation?.latLonPoint?.let { pt ->
                            fromLng = pt.longitude
                            fromLat = pt.latitude
                        }
                    }

                    if (arrStop != null && !arrStop.busStationName.isNullOrEmpty()) {
                        arrivalStopName = arrStop.busStationName.orEmpty()
                        arrStop.latLonPoint?.let { pt ->
                            toLng = pt.longitude
                            toLat = pt.latitude
                        }
                    } else {
                        val lastStation = lineItem.busStations?.lastOrNull()
                        arrivalStopName = lastStation?.busStationName?.ifBlank { null } ?: destName
                        lastStation?.latLonPoint?.let { pt ->
                            toLng = pt.longitude
                            toLat = pt.latitude
                        }
                    }

                    val viaNum = lineItem.passStationNum.coerceAtLeast(1)

                    candidateLines.add(
                        CandidateLine(
                            cityId = "",
                            cityName = cityName,
                            lineId = lineId,
                            lineName = lineName,
                            source = DataSourceType.CHELAILE,
                            direction = 0,
                            fromStopOrder = 1,
                            toStopOrder = 1 + viaNum,
                            startStopName = departureStopName,
                            endStopName = arrivalStopName
                        )
                    )
                }

                if (departureStopName.isNotEmpty() && arrivalStopName.isNotEmpty() && candidateLines.isNotEmpty()) {
                    val walkingDist = step.walk?.distance?.toInt() ?: 0

                    legs.add(
                        TransitLeg(
                            legIndex = legIndex++,
                            fromStopName = departureStopName,
                            fromStopId = "",
                            fromStopLat = fromLat,
                            fromStopLng = fromLng,
                            toStopName = arrivalStopName,
                            toStopId = "",
                            toStopLat = toLat,
                            toStopLng = toLng,
                            candidateLines = candidateLines,
                            walkingDistanceMeters = walkingDist
                        )
                    )
                }
            }

            if (legs.isEmpty()) return null

            val transferCount = (legs.size - 1).coerceAtLeast(0)
            val lineNamesSummary = legs.mapNotNull { it.primaryLine?.lineName }.joinToString(" ➔ ")
            val title = if (transferCount == 0) {
                "$lineNamesSummary 直达"
            } else {
                "$lineNamesSummary · ${transferCount}次换乘"
            }

            val group = TransitGroup(
                id = UUID.randomUUID().toString(),
                name = "$originName ➔ $destName ($title)",
                category = GroupCategory.TEMPORARY,
                legs = legs,
                isTemporary = true,
                sortOrder = 0
            )

            return AmapTransitSchemeResult(
                title = title,
                durationMinutes = durationMinutes,
                walkingMeters = walkingMeters,
                costYuan = if (costYuan.isNotEmpty()) costYuan else "2.0",
                transferCount = transferCount,
                group = group
            )
        }
    }
}
