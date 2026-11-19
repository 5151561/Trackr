package com.trackr.core.network.provider

import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.network.provider.model.StopDetailRequest

/**
 * 数据源 Provider 的公共骨架。
 *
 * 两边的 `supportsLine` / `supportsStop` / `supportsLocationRadar`、城市列表补省份
 * 以前各写一份，改一边容易漏另一边。这里收拢成一份，子类只回答「这个 id 是不是我的」。
 */
abstract class BaseTransitProvider : ITransitProvider {

    /** 这个线路/站点 id 是否属于本数据源 */
    protected abstract fun ownsId(id: String): Boolean

    /**
     * 请求里一个 id 都没给时算不算「本数据源的请求」。
     * 车来了按旧行为算自己（用于「显式掌上公交」的反向判断），掌上公交不算。
     */
    protected open val missingIdMeansOwned: Boolean
        get() = false

    final override fun supportsLine(lineId: String): Boolean = ownsId(lineId)

    final override fun supportsStop(request: StopDetailRequest): Boolean {
        val ids = listOfNotNull(request.stopId, request.physicalStId, request.namesakeStId)
        return if (ids.isEmpty()) missingIdMeansOwned else ids.any(::ownsId)
    }

    final override fun supportsLocationRadar(): Boolean = true

    override fun stopDetailIncludesBuses(): Boolean = true

    /** 统一给城市补省份；子类只负责把自己的城市列表造出来 */
    final override suspend fun getSupportedCities(): List<TransitCity> =
        loadSupportedCities().map { city ->
            if (city.province.isBlank() || city.province == "其他") {
                val areaCode = city.mygolbsCityCode ?: city.lenzAreaCode
                city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName, areaCode))
            } else {
                city
            }
        }

    protected abstract suspend fun loadSupportedCities(): List<TransitCity>
}
