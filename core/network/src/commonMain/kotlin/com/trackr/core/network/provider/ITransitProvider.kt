package com.trackr.core.network.provider

import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest

/**
 * 实时公交数据源 Provider 标准契约。
 *
 * Trackr 自身只带一个实现：[com.trackr.core.network.provider.custom.CustomTransitProvider]，
 * 它把这些调用转成 Trackr 数据源协议（HTTP + JSON，见 docs/provider-api.md）发给用户自己部署的服务。
 */
interface ITransitProvider {
    val sourceType: DataSourceType

    /**
     * 当前配置的标识（如服务地址）。变了说明用户换了数据源，协调器据此丢掉城市列表等缓存。
     */
    val cacheKey: String
        get() = ""

    fun supportsLine(lineId: String): Boolean
    fun supportsStop(request: StopDetailRequest): Boolean
    fun supportsLocationRadar(): Boolean

    /**
     * 站点接口是否直接带回车辆明细（含全线站序）。
     *
     * 带的话可以直接用来判断「几站到」；不带时「一起等」会改为并发查线路详情。
     */
    fun stopDetailIncludesBuses(): Boolean = true

    /**
     * 这个城市此刻能不能查到数据。默认 true；数据源声明支持在线探测时才真去问。
     */
    suspend fun isCityReachable(city: TransitCity): Boolean = true

    /**
     * 这个城市的全部线路（首页直接列出来用）。
     * 返回 null 表示该数据源不支持「列全部」，调用方应退回搜索。
     */
    suspend fun listAllLines(city: TransitCity): List<BusLineSummary>? = null

    suspend fun getSupportedCities(): List<TransitCity>
    suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String?
    /** 逆地理区县名（未清洗，如 "北流市"、"玉州区"）；不支持的数据源返回 null */
    suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String? = null
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
