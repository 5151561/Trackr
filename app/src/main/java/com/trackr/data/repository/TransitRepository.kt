package com.trackr.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.data.remote.mygolbs.MygolbsApiClient
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一实时公交数据仓库实现
 * 融合车来了与掌上公交双数据源，支持纯本地直连、混合检索、静态站表缓存及收藏持久化
 */
class TransitRepository(
    private val context: Context,
    private val chelaileClient: ChelaileApiClient,
    private val mygolbsClient: MygolbsApiClient
) : ITransitRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE)

    private val _selectedCity = MutableStateFlow(loadInitialCity())
    override val selectedCity: StateFlow<TransitCity> = _selectedCity.asStateFlow()

    private val _favorites = MutableStateFlow(loadInitialFavorites())
    override val favorites: StateFlow<List<FavoriteStationLine>> = _favorites.asStateFlow()

    private val _activeTrackingSession = MutableStateFlow(TrackingSessionState())
    override val activeTrackingSession: StateFlow<TrackingSessionState> = _activeTrackingSession.asStateFlow()

    // 内存中的静态站表缓存（lineId -> List<BusStop>）
    private val stopsCache = ConcurrentHashMap<String, List<BusStop>>()
    private var cachedCities: List<TransitCity> = emptyList()

    private fun loadInitialCity(): TransitCity {
        val cityJson = prefs.getString("selected_city_json", null)
        if (!cityJson.isNullOrBlank()) {
            try {
                val city = json.decodeFromString<TransitCity>(cityJson)
                val prov = if (city.province.isNotBlank()) city.province else ProvinceCityCatalog.inferProvince(city.cityName)
                return city.copy(province = prov)
            } catch (_: Exception) {}
        }
        return TransitCity(cityId = "034", cityName = "上海", pinyin = "ShangHai", supportSubway = true, isHot = true, province = "直辖市")
    }

    private fun loadInitialFavorites(): List<FavoriteStationLine> {
        val favJson = prefs.getString("favorites_json", null)
        if (!favJson.isNullOrBlank()) {
            try {
                return json.decodeFromString<List<FavoriteStationLine>>(favJson)
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun saveFavorites(favs: List<FavoriteStationLine>) {
        val str = json.encodeToString(favs)
        prefs.edit().putString("favorites_json", str).apply()
    }

    override suspend fun selectCity(city: TransitCity) {
        val effectiveCity = if (city.province.isBlank()) {
            city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName))
        } else {
            city
        }
        _selectedCity.value = effectiveCity
        withContext(Dispatchers.IO) {
            val str = json.encodeToString(effectiveCity)
            prefs.edit().putString("selected_city_json", str).apply()
        }
    }

    override suspend fun getSupportedCities(forceRefresh: Boolean): Result<List<TransitCity>> = runCatching {
        if (!forceRefresh && cachedCities.isNotEmpty()) {
            return@runCatching cachedCities
        }
        val cities = chelaileClient.getCities(hotOnly = false).map { city ->
            if (city.province.isBlank()) {
                city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName))
            } else {
                city
            }
        }
        cachedCities = cities
        cities
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?> = runCatching {
        withContext(Dispatchers.IO) {
            // 1) 优先尝试车来了 GPS 逆地理服务
            var resolvedName: String? = chelaileClient.reverseGeocodeCityName(wgsLat, wgsLng)

            // 2) 若为空，尝试 Android 系统原生 Geocoder
            if (resolvedName.isNullOrBlank()) {
                resolvedName = try {
                    if (android.location.Geocoder.isPresent()) {
                        @Suppress("DEPRECATION")
                        android.location.Geocoder(context, java.util.Locale.CHINA)
                            .getFromLocation(wgsLat, wgsLng, 1)
                            ?.firstOrNull()
                            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                    } else null
                } catch (_: Exception) {
                    null
                }
            }

            // 3) 若仍为空，使用内置离线核心城市 Haversine 距离（80km 以内）兜底
            if (resolvedName.isNullOrBlank()) {
                resolvedName = ProvinceCityCatalog.findNearestCityName(wgsLat, wgsLng)
            }

            if (resolvedName.isNullOrBlank()) {
                return@withContext null
            }

            val cleanTarget = ProvinceCityCatalog.cleanCityName(resolvedName)
            val supportedCities = getSupportedCities().getOrDefault(emptyList())

            // 4) 在支持的城市列表中匹配（无后缀精准与包含匹配）
            val matched = supportedCities.firstOrNull { city ->
                val cleanItem = ProvinceCityCatalog.cleanCityName(city.cityName)
                cleanItem == cleanTarget ||
                        cleanItem.contains(cleanTarget) ||
                        cleanTarget.contains(cleanItem)
            }

            if (matched != null) {
                val prov = if (matched.province.isNotBlank()) {
                    matched.province
                } else {
                    ProvinceCityCatalog.inferProvince(matched.cityName)
                }
                return@withContext matched.copy(province = prov)
            }

            // 若 getSupportedCities() 中未包含，但为 ProvinceCityCatalog 中已知城市，构造返回以确保定位切换城市永不失败
            if (cleanTarget.isNotBlank() && ProvinceCityCatalog.isKnownCity(cleanTarget)) {
                val inferredProvince = ProvinceCityCatalog.inferProvince(cleanTarget)
                return@withContext TransitCity(
                    cityId = "",
                    cityName = cleanTarget,
                    province = inferredProvince
                )
            }

            null
        }
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): Result<List<NearbyStop>> = runCatching {
        chelaileClient.getNearbyStops(
            cityId = city.cityId,
            wgsLat = wgsLat,
            wgsLng = wgsLng,
            limit = limit
        )
    }

    override suspend fun getStopDetail(
        city: TransitCity,
        physicalStId: String,
        namesakeStId: String?,
        wgsLat: Double?,
        wgsLng: Double?
    ): Result<List<NearbyStop>> = runCatching {
        chelaileClient.getStopDetail(
            cityId = city.cityId,
            physicalStId = physicalStId,
            namesakeStId = namesakeStId,
            wgsLat = wgsLat,
            wgsLng = wgsLng
        )
    }

    override suspend fun search(city: TransitCity, keyword: String): Result<TransitSearchResult> = runCatching {
        coroutineScope {
            val chelaileDeferred = async {
                runCatching { chelaileClient.search(city.cityId, keyword) }
                    .getOrDefault(TransitSearchResult(keyword))
            }

            val mygolbsCode = city.mygolbsCityCode ?: if (city.cityName.contains("玉林")) "450900" else null
            val mygolbsDeferred = async {
                if (mygolbsCode != null) {
                    runCatching { mygolbsClient.searchLines(mygolbsCode, city.cityName, keyword) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

            val chelaileRes = chelaileDeferred.await()
            val mygolbsRes = mygolbsDeferred.await()

            // 合并去重逻辑：车来了已有的同名线路保持车来了优先，掌上公交补充未覆盖线路
            val mergedLines = ArrayList<BusLineSummary>(chelaileRes.lines)
            val existingNames = chelaileRes.lines.map { it.name.trim() }.toSet()

            for (mgLine in mygolbsRes) {
                if (!existingNames.contains(mgLine.name.trim())) {
                    mergedLines.add(mgLine)
                }
            }

            // 按纯数字升序、汉字在后的规则智能排序
            mergedLines.sortWith { a, b ->
                RouteProjectionMath.compareLineNames(a.name, b.name)
            }

            TransitSearchResult(
                keyword = keyword,
                lines = mergedLines,
                stops = chelaileRes.stops
            )
        }
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
    ): Result<BusLineDetail> = runCatching {
        val isMygolbs = lineId.startsWith("mg:") || source == DataSourceType.MYGOLBS
        val detail = if (isMygolbs) {
            mygolbsClient.getLineDetail(lineId, targetOrder)
        } else {
            chelaileClient.getLineDetail(
                cityId = city.cityId,
                lineId = lineId,
                targetOrder = targetOrder,
                stationId = stationId,
                wgsLat = wgsLat,
                wgsLng = wgsLng
            )
        }

        // 静态站表内存缓存维护
        val effectiveStops = if (detail.stops.isNotEmpty()) {
            stopsCache[lineId] = detail.stops
            detail.stops
        } else {
            stopsCache[lineId] ?: detail.stops
        }

        if (effectiveStops !== detail.stops) {
            detail.copy(stops = effectiveStops)
        } else {
            detail
        }
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): Result<LineTimetable> = runCatching {
        if (line.source == DataSourceType.MYGOLBS || line.lineId.startsWith("mg:")) {
            LineTimetable(
                lineId = line.lineId,
                lineName = line.name,
                depDesc = "该线路暂不提供首站预测时刻表，请参考运营首末班：${line.firstTime} - ${line.lastTime}"
            )
        } else {
            val lineNo = line.lineNo.ifBlank { line.name }
            chelaileClient.getTimetable(
                cityId = city.cityId,
                lineId = line.lineId,
                lineNo = lineNo,
                direction = line.direction,
                atTimeHHMM = atTimeHHMM
            )
        }
    }

    override suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>> = runCatching {
        val favs = _favorites.value
        if (favs.isEmpty()) return@runCatching emptyMap()

        coroutineScope {
            val tasks = favs.map { fav ->
                async {
                    val city = TransitCity(
                        cityId = fav.cityId,
                        cityName = fav.cityName,
                        mygolbsCityCode = fav.mygolbsCityCode.takeIf { it.isNotBlank() }
                    )
                    val lineRes = runCatching {
                        getLineDetail(
                            city = city,
                            lineId = fav.lineId,
                            targetOrder = fav.targetOrder,
                            stationId = fav.targetStopId,
                            source = fav.source
                        ).getOrNull()
                    }.getOrNull()

                    if (lineRes != null) {
                        val approaching = lineRes.buses.filter { it.order <= fav.targetOrder }
                        val lineStatus = NearbyStopLineStatus(
                            line = lineRes.line,
                            targetOrder = fav.targetOrder,
                            targetStopId = fav.targetStopId,
                            targetStopName = fav.targetStopName,
                            statusText = lineRes.statusDescription,
                            preArrivalTime = lineRes.nextDepartureFromStart,
                            approachingBuses = approaching
                        )
                        val stop = NearbyStop(
                            stopId = fav.targetStopId,
                            name = fav.targetStopName,
                            wgsLat = fav.wgsLat,
                            wgsLng = fav.wgsLng,
                            lines = listOf(lineStatus)
                        )
                        fav.id to stop
                    } else null
                }
            }
            tasks.awaitAll().filterNotNull().toMap()
        }
    }

    override suspend fun toggleFavorite(item: FavoriteStationLine): Boolean {
        val current = _favorites.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        val isAdded: Boolean
        if (index >= 0) {
            current.removeAt(index)
            isAdded = false
        } else {
            current.add(0, item)
            isAdded = true
        }
        _favorites.value = current
        withContext(Dispatchers.IO) {
            saveFavorites(current)
        }
        return isAdded
    }

    override suspend fun removeFavorite(id: String) {
        val current = _favorites.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _favorites.value = current
            withContext(Dispatchers.IO) {
                saveFavorites(current)
            }
        }
    }

    override fun updateTrackingSession(state: TrackingSessionState) {
        _activeTrackingSession.value = state
    }
}
