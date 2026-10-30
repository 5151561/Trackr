package com.trackr.data.provider

import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 实时公交统一数据源分发与融合协调器
 * 负责根据 EngineMode 策略路由、双源城市列表融合、跨源降级与结果聚合
 */
class TransitProviderCoordinator(
    val providers: Map<DataSourceType, ITransitProvider>,
    val aggregator: SearchResultAggregator = SearchResultAggregator()
) {
    constructor(
        chelaileProvider: ITransitProvider,
        mygolbsProvider: ITransitProvider,
        aggregator: SearchResultAggregator = SearchResultAggregator()
    ) : this(
        mapOf(
            DataSourceType.CHELAILE to chelaileProvider,
            DataSourceType.MYGOLBS to mygolbsProvider
        ),
        aggregator
    )

    private var cachedCities: List<TransitCity> = emptyList()

    /**
     * 并发获取车来了与掌上公交支持的城市列表，并进行编码与独有城市双源融合
     */
    suspend fun getSupportedCities(forceRefresh: Boolean = false): List<TransitCity> {
        if (!forceRefresh && cachedCities.isNotEmpty()) {
            return cachedCities
        }

        val chelaileProvider = providers[DataSourceType.CHELAILE]
        val mygolbsProvider = providers[DataSourceType.MYGOLBS]

        val (chelaileCities, mygolbsCities) = coroutineScope {
            val clDeferred = async {
                runCatching { chelaileProvider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())
            }
            val mgDeferred = async {
                runCatching { mygolbsProvider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())
            }
            Pair(clDeferred.await(), mgDeferred.await())
        }

        // 构建掌上公交映射 cleanName -> TransitCity
        val mgMap = LinkedHashMap<String, TransitCity>()
        for (mgCity in mygolbsCities) {
            val cleanName = ProvinceCityCatalog.cleanCityName(mgCity.cityName)
            if (cleanName.isNotBlank() && !mgMap.containsKey(cleanName)) {
                mgMap[cleanName] = mgCity
            }
        }

        val matchedMgKeys = HashSet<String>()
        val result = ArrayList<TransitCity>()

        // 1. 以车来了城市列表为主干，归一化名称匹配并填充 mygolbsCityCode
        for (cCity in chelaileCities) {
            val cleanName = ProvinceCityCatalog.cleanCityName(cCity.cityName)
            val mgMatch = mgMap[cleanName]
            val effectiveProv = if (cCity.province.isNotBlank()) {
                cCity.province
            } else {
                ProvinceCityCatalog.inferProvince(cCity.cityName)
            }
            val effectiveMgCode = if (!cCity.mygolbsCityCode.isNullOrBlank()) {
                cCity.mygolbsCityCode
            } else {
                mgMatch?.mygolbsCityCode
            }
            if (mgMatch != null) {
                matchedMgKeys.add(cleanName)
            }
            result.add(
                cCity.copy(
                    province = effectiveProv,
                    mygolbsCityCode = effectiveMgCode
                )
            )
        }

        // 2. 将掌上公交独有的城市（如县级市）追加合入列表
        for ((cleanName, mgCity) in mgMap) {
            if (!matchedMgKeys.contains(cleanName)) {
                val effectiveProv = if (mgCity.province.isNotBlank()) {
                    mgCity.province
                } else {
                    ProvinceCityCatalog.inferProvince(mgCity.cityName)
                }
                result.add(
                    mgCity.copy(province = effectiveProv)
                )
            }
        }

        cachedCities = result
        return result
    }

    /**
     * 逆地理定位：依次查询具备雷达能力的 Provider
     */
    suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? {
        for (provider in providers.values) {
            if (provider.supportsLocationRadar()) {
                val resolved = runCatching { provider.resolveCityByLocation(wgsLat, wgsLng) }.getOrNull()
                if (!resolved.isNullOrBlank()) {
                    return resolved
                }
            }
        }
        return null
    }

    /**
     * 获取周边站点：根据 EngineMode 策略路由至支持位置雷达能力的 Provider
     */
    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4,
        mode: EngineMode = EngineMode.ALL
    ): List<NearbyStop> {
        val targetProvider = when (mode) {
            EngineMode.CHELAILE -> providers[DataSourceType.CHELAILE]
            EngineMode.MYGOLBS -> providers[DataSourceType.MYGOLBS]
            EngineMode.ALL -> {
                providers[DataSourceType.CHELAILE]?.takeIf { it.supportsLocationRadar() }
                    ?: providers[DataSourceType.MYGOLBS]?.takeIf { it.supportsLocationRadar() }
                    ?: providers.values.firstOrNull { it.supportsLocationRadar() }
            }
        }

        if (targetProvider != null && targetProvider.supportsLocationRadar()) {
            val stops = runCatching { targetProvider.getNearbyStops(city, wgsLat, wgsLng, limit) }.getOrDefault(emptyList())
            if (stops.isNotEmpty()) return stops
        }

        // 双引擎模式下：若车来了未查到周边站点（如掌上公交独有城市），自动跨源降级至掌上公交雷达
        if (mode == EngineMode.ALL) {
            val fallbackProvider = providers[DataSourceType.MYGOLBS]?.takeIf { it.supportsLocationRadar() }
            if (fallbackProvider != null && fallbackProvider !== targetProvider) {
                return runCatching { fallbackProvider.getNearbyStops(city, wgsLat, wgsLng, limit) }.getOrDefault(emptyList())
            }
        }

        return emptyList()
    }

    /**
     * 搜索：根据 EngineMode 策略路由单源或并发请求双源，由 SearchResultAggregator 统一聚合
     */
    suspend fun search(
        city: TransitCity,
        keyword: String,
        mode: EngineMode,
        userLat: Double? = null,
        userLng: Double? = null
    ): TransitSearchResult = coroutineScope {
        val chelaileProvider = providers[DataSourceType.CHELAILE]
        val mygolbsProvider = providers[DataSourceType.MYGOLBS]

        when (mode) {
            EngineMode.CHELAILE -> {
                val res = chelaileProvider?.let {
                    runCatching { it.search(city, keyword, userLat, userLng) }.getOrNull()
                }
                aggregator.aggregate(keyword, mode, chelaileResult = res)
            }
            EngineMode.MYGOLBS -> {
                val res = mygolbsProvider?.let {
                    runCatching { it.search(city, keyword, userLat, userLng) }.getOrNull()
                }
                aggregator.aggregate(keyword, mode, mygolbsResult = res)
            }
            EngineMode.ALL -> {
                val clDeferred = async {
                    chelaileProvider?.let {
                        runCatching { it.search(city, keyword, userLat, userLng) }.getOrNull()
                    }
                }
                val mgDeferred = async {
                    mygolbsProvider?.let {
                        runCatching { it.search(city, keyword, userLat, userLng) }.getOrNull()
                    }
                }
                aggregator.aggregate(
                    keyword = keyword,
                    mode = mode,
                    chelaileResult = clDeferred.await(),
                    mygolbsResult = mgDeferred.await()
                )
            }
        }
    }

    /**
     * 站点详情：实现三级降级调度（特征识别 -> 主源查询含同源兜底 -> 跨源降级至掌上公交）
     */
    suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest,
        mode: EngineMode
    ): List<NearbyStop> {
        val chelaileProvider = providers[DataSourceType.CHELAILE]
        val mygolbsProvider = providers[DataSourceType.MYGOLBS]

        // 显式命中掌上公交特征或当前引擎模式为掌上公交
        val isMygolbs = mode == EngineMode.MYGOLBS ||
                mygolbsProvider?.supportsStop(request) == true

        if (isMygolbs) {
            return mygolbsProvider?.getStopDetail(city, request).orEmpty()
        }

        if (mode == EngineMode.CHELAILE) {
            return chelaileProvider?.getStopDetail(city, request).orEmpty()
        }

        // EngineMode.ALL：优先调用车来了（内含阶段 1 主查询 + 物理站台并发补全与阶段 2 同源经纬度兜底）
        val chelaileStops = chelaileProvider?.let {
            runCatching { it.getStopDetail(city, request) }.getOrNull()
        }.orEmpty()

        if (chelaileStops.isNotEmpty()) {
            return chelaileStops
        }

        // 阶段 3：跨源降级至掌上公交
        if (!request.stopName.isNullOrBlank() && mygolbsProvider != null) {
            val mgStops = runCatching {
                mygolbsProvider.getStopDetail(city, request)
            }.getOrNull().orEmpty()
            if (mgStops.any { it.lines.isNotEmpty() }) {
                return mgStops
            }
        }

        return emptyList()
    }

    /**
     * 线路详情：根据请求显式 source 或 lineId 前缀特征精准路由
     */
    suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail {
        val targetProvider = when {
            request.source != null -> providers[request.source]
            providers[DataSourceType.MYGOLBS]?.supportsLine(request.lineId) == true -> providers[DataSourceType.MYGOLBS]
            providers[DataSourceType.CHELAILE]?.supportsLine(request.lineId) == true -> providers[DataSourceType.CHELAILE]
            else -> providers.values.firstOrNull { it.supportsLine(request.lineId) } ?: providers[DataSourceType.CHELAILE]
        } ?: throw IllegalStateException("No provider found for lineId: ${request.lineId}")

        return targetProvider.getLineDetail(city, request)
    }

    /**
     * 运营时刻表：根据线路显式 source 或 lineId 前缀特征精准路由
     */
    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null
    ): LineTimetable {
        val targetProvider = when {
            line.source == DataSourceType.MYGOLBS || line.lineId.startsWith("mg:") -> providers[DataSourceType.MYGOLBS]
            line.source == DataSourceType.CHELAILE -> providers[DataSourceType.CHELAILE]
            providers[DataSourceType.MYGOLBS]?.supportsLine(line.lineId) == true -> providers[DataSourceType.MYGOLBS]
            else -> providers[DataSourceType.CHELAILE] ?: providers.values.firstOrNull()
        } ?: throw IllegalStateException("No provider found for timetable: ${line.lineId}")

        return targetProvider.getTimetable(city, line, atTimeHHMM)
    }
}
