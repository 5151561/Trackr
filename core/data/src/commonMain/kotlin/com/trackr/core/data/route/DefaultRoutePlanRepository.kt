package com.trackr.core.data.route

import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.data.repository.AmapRouteGateway
import com.trackr.core.data.repository.RoutePlanRepository
import com.trackr.core.domain.math.CoordTransform
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.EngineMode
import com.trackr.core.model.GeoPoint
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.amap.AmapLruCache
import com.trackr.core.network.remote.amap.AmapTransitApiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [RoutePlanRepository] 的实现：第 17 步从 `FavoritesGroupViewModel` 原样搬来的输入提示 / 算路缓存、
 * 坐标补全、跨城 `cityd`、方案对齐和本地兜底（逻辑不变）。Koin `single`，缓存跟着进程。
 *
 * @param coordinator 实时数据源搜索 / 站点 / 线路（本地站点提示与本地兜底方案用）；为 null 时没有本地兜底（测试用）
 */
class DefaultRoutePlanRepository(
    private val gateway: AmapRouteGateway,
    private val alignment: RouteAlignmentService,
    private val coordinator: TransitProviderCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 墙钟（毫秒），用于算路缓存时间桶；测试可替换 */
    private val wallClockMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) : RoutePlanRepository {

    companion object {
        private const val TIPS_CACHE_SIZE = 64

        /** 算路结果缓存：最多 16 条，按 15 分钟时间桶失效 */
        private const val PLAN_CACHE_SIZE = 16
        const val PLAN_CACHE_BUCKET_MS = 15 * 60 * 1000L
    }

    /** 输入提示 LRU 缓存，键 = 城市 + 关键字 */
    private val tipsCache = AmapLruCache<String, List<AmapTipItem>>(TIPS_CACHE_SIZE)

    /** 算路 LRU 缓存，键 = 城市 + 6 位小数坐标 + 15 分钟时间桶 */
    private val planCache = AmapLruCache<String, List<AmapTransitSchemeResult>>(PLAN_CACHE_SIZE)

    override fun isConfigured(): Boolean = gateway.isConfigured()

    /** 查询输入提示：先查 LRU 缓存，再走高德（SDK 失败才走 Web），都没有结果时降级为本地站点搜索 */
    override suspend fun searchTips(keyword: String, city: TransitCity, location: String?): List<AmapTipItem> {
        val cacheKey = "${city.cityId}|${city.cityName}|$keyword"
        tipsCache.get(cacheKey)?.let { return it }

        val amapTips = if (gateway.isConfigured()) {
            gateway.searchTips(keyword, city.cityName, location).getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val tips = amapTips.ifEmpty { searchLocalStopTips(keyword, city) }
        if (tips.isNotEmpty()) {
            tipsCache.put(cacheKey, tips)
        }
        return tips
    }

    /** 补全缺少经纬度的起终点坐标（高德 SDK 成功但无结果时同样不再走 Web） */
    override suspend fun resolveLocation(
        tip: AmapTipItem,
        city: TransitCity,
        location: String?,
        useAmap: Boolean
    ): AmapTipItem {
        if (tip.location.contains(",")) return tip
        if (useAmap) {
            val amapFound = gateway.searchTips(tip.name, city.cityName, location)
                .getOrNull()?.firstOrNull { it.location.contains(",") }
            if (amapFound != null) return amapFound
        }
        val localFound = searchLocalStopTips(tip.name, city).firstOrNull { it.location.contains(",") }
        if (localFound != null) return localFound
        return tip
    }

    override suspend fun planTransit(
        origin: AmapTipItem,
        destination: AmapTipItem,
        city: TransitCity,
        useAmap: Boolean
    ): List<AmapTransitSchemeResult> {
        val effectiveOrigin = origin
        val effectiveDest = destination

        val cacheKey = buildPlanCacheKey(city, effectiveOrigin.location, effectiveDest.location)
        val cached = cacheKey?.let { planCache.get(it) }

        var schemes: List<AmapTransitSchemeResult> = emptyList()

        if (cached != null) {
            schemes = cached
        } else {
            if (useAmap && effectiveOrigin.location.contains(",") && effectiveDest.location.contains(",")) {
                schemes = gateway.planTransit(
                    originLngLat = effectiveOrigin.location,
                    destinationLngLat = effectiveDest.location,
                    originName = effectiveOrigin.name,
                    destName = effectiveDest.name,
                    cityName = city.cityName,
                    cityd = resolveCrossCityCode(effectiveOrigin, effectiveDest, city)
                ).getOrNull().orEmpty()

                if (schemes.isNotEmpty()) {
                    schemes = alignment.alignAmapSchemesWithRealtimeSource(schemes, city)
                }
            }

            if (schemes.isEmpty()) {
                schemes = planLocalTransitRoute(effectiveOrigin, effectiveDest, city)
            }

            // 将用户选择的起终点 POI 坐标（GCJ-02 "lng,lat"）注入方案，供地图起终点 marker 使用
            val originPoint = parseLngLatToGeoPoint(effectiveOrigin.location)
            val destPoint = parseLngLatToGeoPoint(effectiveDest.location)
            if (originPoint != null || destPoint != null) {
                schemes = schemes.map { it.copy(originPoint = originPoint, destinationPoint = destPoint) }
            }

            if (schemes.isNotEmpty() && cacheKey != null) {
                planCache.put(cacheKey, schemes)
            }
        }
        return schemes
    }

    private suspend fun searchLocalStopTips(keyword: String, city: TransitCity): List<AmapTipItem> {
        val coord = coordinator ?: return emptyList()
        return try {
            val searchRes = coord.search(city, keyword, EngineMode.CUSTOM)
            val stopTips = searchRes.stops.map { stop ->
                // 数据源站点坐标是 WGS-84，传给高德算路前转成 GCJ-02
                val loc = if (stop.wgsLat != 0.0 && stop.wgsLng != 0.0) {
                    val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(stop.wgsLat, stop.wgsLng)
                    "$gcjLng,$gcjLat"
                } else ""
                AmapTipItem(
                    name = stop.name,
                    district = city.cityName,
                    address = stop.name,
                    location = loc
                )
            }
            if (stopTips.isNotEmpty()) {
                stopTips
            } else {
                searchRes.lines.mapNotNull { line ->
                    val dir = line.directions.firstOrNull() ?: return@mapNotNull null
                    AmapTipItem(
                        name = dir.name,
                        district = city.cityName,
                        address = "${dir.startStop} ➔ ${dir.endStop}",
                        location = ""
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 算路缓存键：城市 + 6 位小数坐标 + 15 分钟时间桶；坐标非法时不缓存 */
    private fun buildPlanCacheKey(city: TransitCity, originLngLat: String, destLngLat: String): String? {
        val o = AmapTransitApiClient.formatLngLat(originLngLat) ?: return null
        val d = AmapTransitApiClient.formatLngLat(destLngLat) ?: return null
        val bucket = wallClockMillis() / PLAN_CACHE_BUCKET_MS
        return "${city.cityId}|${city.cityName}|$o|$d|$bucket"
    }

    /**
     * 起终点跨城时返回终点城市 adcode（传给高德 cityd），同城或拿不到终点城市时返回 null。
     * 起点有 adcode 时按地级市 adcode 比较；没有时（如"我的位置"）看终点行政区文本是否包含当前城市名。
     */
    private fun resolveCrossCityCode(origin: AmapTipItem, dest: AmapTipItem, city: TransitCity): String? {
        val destCityCode = AmapTransitApiClient.cityAdcodeOf(dest.adcode) ?: return null
        val originCityCode = AmapTransitApiClient.cityAdcodeOf(origin.adcode)
        val isCrossCity = if (originCityCode != null) {
            originCityCode != destCityCode
        } else {
            val shortName = city.cityName.trim().removeSuffix("市")
            dest.district.isNotBlank() && shortName.isNotBlank() && !dest.district.contains(shortName)
        }
        return if (isCrossCity) destCityCode else null
    }

    /** 解析高德 "lng,lat" 字符串为 GeoPoint（GCJ-02） */
    private fun parseLngLatToGeoPoint(lngLat: String): GeoPoint? {
        val parts = lngLat.split(",")
        if (parts.size != 2) return null
        val lng = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        return GeoPoint(lat = lat, lng = lng)
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun planLocalTransitRoute(
        origin: AmapTipItem,
        destination: AmapTipItem,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return emptyList()
        return withContext(ioDispatcher) {
            try {
                val originCore = RouteProjectionMath.coreStopName(origin.name)
                val destCore = RouteProjectionMath.coreStopName(destination.name)

                val originSearch = coord.search(city, origin.name, EngineMode.CUSTOM)
                val destSearch = coord.search(city, destination.name, EngineMode.CUSTOM)

                val originStops = originSearch.stops.filter {
                    it.name.contains(originCore) || originCore.contains(RouteProjectionMath.coreStopName(it.name))
                }.ifEmpty { originSearch.stops.take(2) }

                val destStops = destSearch.stops.filter {
                    it.name.contains(destCore) || destCore.contains(RouteProjectionMath.coreStopName(it.name))
                }.ifEmpty { destSearch.stops.take(2) }

                if (originStops.isEmpty() || destStops.isEmpty()) {
                    return@withContext emptyList()
                }

                val originNearby = mutableListOf<NearbyStop>()
                for (stop in originStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CUSTOM)
                    }.getOrNull().orEmpty()
                    originNearby.addAll(details)
                }
                val originLines = originNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                val destNearby = mutableListOf<NearbyStop>()
                for (stop in destStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CUSTOM)
                    }.getOrNull().orEmpty()
                    destNearby.addAll(details)
                }
                val destLines = destNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                val schemes = mutableListOf<AmapTransitSchemeResult>()

                for (origLine in originLines) {
                    val matchingDest = destLines.find {
                        (it.line.name == origLine.line.name || it.line.lineId == origLine.line.lineId) &&
                        it.line.direction == origLine.line.direction &&
                        origLine.targetOrder < it.targetOrder
                    }

                    if (matchingDest != null) {
                        val stopCount = (matchingDest.targetOrder - origLine.targetOrder).coerceAtLeast(1)
                        val durationMinutes = (stopCount * 3 + 2).coerceAtLeast(5)
                        val primaryLine = CandidateLine(
                            cityId = city.cityId,
                            cityName = city.cityName,
                            lineId = origLine.line.lineId,
                            lineName = origLine.line.name,
                            source = origLine.line.source,
                            direction = origLine.line.direction,
                            fromStopOrder = origLine.targetOrder,
                            toStopOrder = matchingDest.targetOrder,
                            startStopName = origLine.targetStopName,
                            endStopName = matchingDest.targetStopName,
                            mygolbsCityCode = city.mygolbsCityCode
                        )
                        val leg = TransitLeg(
                            legIndex = 0,
                            fromStopName = origLine.targetStopName,
                            toStopName = matchingDest.targetStopName,
                            candidateLines = listOf(primaryLine),
                            walkingDistanceMeters = 0
                        )
                        val title = "${origLine.line.name} 直达 · 共${stopCount}站"
                        val group = TransitGroup(
                            id = Uuid.random().toString(),
                            name = "${origin.name} ➔ ${destination.name} ($title)",
                            category = GroupCategory.TEMPORARY,
                            legs = listOf(leg),
                            isTemporary = true,
                            sortOrder = 0
                        )
                        schemes.add(
                            AmapTransitSchemeResult(
                                title = title,
                                durationMinutes = durationMinutes,
                                walkingMeters = 50,
                                costYuan = "2.0",
                                transferCount = 0,
                                group = group
                            )
                        )
                    }
                }

                if (schemes.size < 3) {
                    val origDetails = originLines.take(3).mapNotNull { l ->
                        runCatching {
                            coord.getLineDetail(city, LineDetailRequest(lineId = l.line.lineId, source = l.line.source, lineNameHint = l.line.name, directionHint = l.line.direction))
                        }.getOrNull()
                    }
                    val destDetails = destLines.take(3).mapNotNull { l ->
                        runCatching {
                            coord.getLineDetail(city, LineDetailRequest(lineId = l.line.lineId, source = l.line.source, lineNameHint = l.line.name, directionHint = l.line.direction))
                        }.getOrNull()
                    }

                    transferLoop@ for (d1 in origDetails) {
                        for (d2 in destDetails) {
                            if (d1.line.lineId == d2.line.lineId) continue
                            val d1Stops = d1.stops
                            val d2Stops = d2.stops

                            val fromStopInD1 = d1Stops.find { it.name.contains(originCore) || originCore.contains(RouteProjectionMath.coreStopName(it.name)) } ?: continue
                            val toStopInD2 = d2Stops.find { it.name.contains(destCore) || destCore.contains(RouteProjectionMath.coreStopName(it.name)) } ?: continue

                            for (s1 in d1Stops) {
                                if (s1.order <= fromStopInD1.order) continue
                                val s1Core = RouteProjectionMath.coreStopName(s1.name)
                                val s2 = d2Stops.find { RouteProjectionMath.coreStopName(it.name) == s1Core }
                                if (s2 != null && s2.order < toStopInD2.order) {
                                    val leg1 = TransitLeg(
                                        legIndex = 0,
                                        fromStopName = fromStopInD1.name,
                                        toStopName = s1.name,
                                        candidateLines = listOf(
                                            CandidateLine(
                                                cityId = city.cityId,
                                                cityName = city.cityName,
                                                lineId = d1.line.lineId,
                                                lineName = d1.line.name,
                                                source = d1.line.source,
                                                direction = d1.line.direction,
                                                fromStopOrder = fromStopInD1.order,
                                                toStopOrder = s1.order,
                                                startStopName = fromStopInD1.name,
                                                endStopName = s1.name
                                            )
                                        )
                                    )
                                    val leg2 = TransitLeg(
                                        legIndex = 1,
                                        fromStopName = s2.name,
                                        toStopName = toStopInD2.name,
                                        candidateLines = listOf(
                                            CandidateLine(
                                                cityId = city.cityId,
                                                cityName = city.cityName,
                                                lineId = d2.line.lineId,
                                                lineName = d2.line.name,
                                                source = d2.line.source,
                                                direction = d2.line.direction,
                                                fromStopOrder = s2.order,
                                                toStopOrder = toStopInD2.order,
                                                startStopName = s2.name,
                                                endStopName = toStopInD2.name
                                            )
                                        )
                                    )
                                    val stopsTotal = (s1.order - fromStopInD1.order) + (toStopInD2.order - s2.order)
                                    val durationMinutes = (stopsTotal * 3 + 6).coerceAtLeast(15)
                                    val title = "${d1.line.name} ➔ ${d2.line.name} · 1次换乘(${s1.name})"
                                    val group = TransitGroup(
                                        id = Uuid.random().toString(),
                                        name = "${origin.name} ➔ ${destination.name} ($title)",
                                        category = GroupCategory.TEMPORARY,
                                        legs = listOf(leg1, leg2),
                                        isTemporary = true,
                                        sortOrder = 0
                                    )
                                    schemes.add(
                                        AmapTransitSchemeResult(
                                            title = title,
                                            durationMinutes = durationMinutes,
                                            walkingMeters = 80,
                                            costYuan = "4.0",
                                            transferCount = 1,
                                            group = group
                                        )
                                    )
                                    if (schemes.size >= 5) break@transferLoop
                                }
                            }
                        }
                    }
                }

                schemes.distinctBy { it.title }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
