package com.trackr.core.data.provider

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.isPureSubwayStation
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 数据源模式对应的数据源 */
val EngineMode.dataSource: DataSourceType
    get() = when (this) {
        EngineMode.CUSTOM -> DataSourceType.CUSTOM
    }

/**
 * 实时公交数据源协调器。
 *
 * Trackr 不内置任何第三方数据源，只有用户自行接入的那一个（[DataSourceType.CUSTOM]）。
 * 协调器负责在 Provider 之上做通用的整理：城市省份 / 上级地级市补全、搜索结果去地铁并排序、
 * 失败兜底为空结果等。`mode` 参数保留只为不改调用方签名。
 */
class TransitProviderCoordinator(
    val providers: Map<DataSourceType, ITransitProvider>
) {
    constructor(provider: ITransitProvider) : this(mapOf(provider.sourceType to provider))

    private var cachedCities: List<TransitCity> = emptyList()
    private var cachedCitiesKey: String? = null

    /** 某个数据源对应的 Provider；没接入返回 null */
    fun providerFor(source: DataSourceType): ITransitProvider? = providers[source]

    private val provider: ITransitProvider?
        get() = providers[DataSourceType.CUSTOM] ?: providers.values.firstOrNull()

    private fun requireProvider(what: String): ITransitProvider =
        provider ?: throw IllegalStateException("No transit provider for $what")

    /**
     * 数据源支持的城市列表：补全省份，并按 6 位国标区划码补全县级城市的上级地级市。
     */
    suspend fun getSupportedCities(forceRefresh: Boolean = false): List<TransitCity> {
        val key = provider?.cacheKey
        if (!forceRefresh && cachedCities.isNotEmpty() && cachedCitiesKey == key) {
            return cachedCities
        }
        val raw = runCatching { provider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())

        // 同名城市去重（保留第一个）
        val seen = HashSet<String>()
        val result = raw.filter { city ->
            val cityKey = city.uniqueKey
            cityKey.isNotBlank() && seen.add(cityKey)
        }.map { city ->
            val effectiveProv = if (city.province.isNotBlank() && city.province != "其他") {
                city.province
            } else {
                ProvinceCityCatalog.inferProvince(city.cityName, city.mygolbsCityCode)
            }
            city.copy(province = effectiveProv)
        }

        // 依据区划码补全县级城市的上级地级市，使县级市 / 县能按「省 → 地级市 → 县级」归组展示和定位校验
        val prefectureByCode = HashMap<String, String>()
        for (city in result) {
            val code = city.mygolbsCityCode?.trim()?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: continue
            if (code.endsWith("00") && !prefectureByCode.containsKey(code)) {
                prefectureByCode[code] = city.cityName
            }
        }
        val enriched = result.map { city ->
            if (!city.parentCity.isNullOrBlank()) return@map city
            val prefCode = ProvinceCityCatalog.prefectureCodeOf(city.mygolbsCityCode)
            val parent = prefCode?.let { prefectureByCode[it] }
                ?.takeIf { ProvinceCityCatalog.cleanCityName(it) != ProvinceCityCatalog.cleanCityName(city.cityName) }
            if (parent != null) city.copy(parentCity = parent) else city
        }

        cachedCities = enriched
        cachedCitiesKey = key
        return enriched
    }

    /**
     * 城市全部线路；数据源不支持「列全部」时返回空表（首页退回搜索）。
     */
    suspend fun listAllLines(
        city: TransitCity,
        @Suppress("UNUSED_PARAMETER") mode: EngineMode = EngineMode.CUSTOM
    ): List<BusLineSummary> {
        val p = provider ?: return emptyList()
        return runCatching { p.listAllLines(city) }.getOrNull().orEmpty()
    }

    /**
     * 这批城市里「数据源连不上」的城市名（数据源在 manifest 里声明支持城市在线探测时才有意义）。
     */
    suspend fun unreachableCities(cities: List<TransitCity>): Set<String> {
        val p = provider ?: return emptySet()
        val candidates = cities.take(24)
        if (candidates.isEmpty()) return emptySet()
        return coroutineScope {
            candidates.map { city ->
                async { city.cityName to runCatching { p.isCityReachable(city) }.getOrDefault(true) }
            }.map { it.await() }
        }.filterNot { it.second }.map { it.first }.toSet()
    }

    /** 逆地理定位：返回城市名，不支持或失败返回 null */
    suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? {
        val p = provider?.takeIf { it.supportsLocationRadar() } ?: return null
        return runCatching { p.resolveCityByLocation(wgsLat, wgsLng) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 逆地理区县（未清洗），不支持或失败返回 null */
    suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String? {
        val p = provider?.takeIf { it.supportsLocationRadar() } ?: return null
        return runCatching { p.resolveDistrictByLocation(wgsLat, wgsLng) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 附近站点；数据源不支持或失败时返回空表 */
    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4,
        @Suppress("UNUSED_PARAMETER") mode: EngineMode = EngineMode.CUSTOM
    ): List<NearbyStop> {
        val p = provider?.takeIf { it.supportsLocationRadar() } ?: return emptyList()
        return runCatching { p.getNearbyStops(city, wgsLat, wgsLng, limit) }.getOrDefault(emptyList())
    }

    /**
     * 搜索：去掉地铁线路和纯地铁站，线路按名字自然排序。
     */
    suspend fun search(
        city: TransitCity,
        keyword: String,
        @Suppress("UNUSED_PARAMETER") mode: EngineMode = EngineMode.CUSTOM,
        userLat: Double? = null,
        userLng: Double? = null
    ): TransitSearchResult {
        val raw = requireProvider("search").search(city, keyword, userLat, userLng)
        // 没有地铁的在途实时数据，地铁线路和纯地铁站不展示
        val lines = raw.lines
            .filterNot { it.isSubway || it.name.contains("地铁") }
            .sortedWith { a, b -> RouteProjectionMath.compareLineNames(a.name, b.name) }
        val stops = raw.stops.filterNot { it.isPureSubwayStation }
        return TransitSearchResult(keyword = keyword, lines = lines, stops = stops)
    }

    /** 站点详情 */
    suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest,
        @Suppress("UNUSED_PARAMETER") mode: EngineMode = EngineMode.CUSTOM
    ): List<NearbyStop> = requireProvider("stop ${request.stopId}").getStopDetail(city, request)

    /** 线路详情 */
    suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest,
        @Suppress("UNUSED_PARAMETER") mode: EngineMode = EngineMode.CUSTOM
    ): BusLineDetail {
        val target = request.source?.let { providers[it] } ?: requireProvider("lineId: ${request.lineId}")
        return target.getLineDetail(city, request)
    }

    /** 运营时刻表 */
    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null,
        @Suppress("UNUSED_PARAMETER") mode: EngineMode = EngineMode.CUSTOM
    ): LineTimetable {
        val target = providers[line.source] ?: requireProvider("timetable: ${line.lineId}")
        return target.getTimetable(city, line, atTimeHHMM)
    }
}
