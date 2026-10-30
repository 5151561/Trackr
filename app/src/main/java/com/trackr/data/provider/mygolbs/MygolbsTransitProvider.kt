package com.trackr.data.provider.mygolbs

import com.trackr.data.provider.ITransitProvider
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.data.remote.mygolbs.MygolbsApiClient
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
import kotlinx.coroutines.coroutineScope

/**
 * 掌上公交（Mygolbs）数据源 Provider
 * 内聚 mg: 协议识别、站名清洗、并发检索与时刻表友好提示
 */
class MygolbsTransitProvider(
    private val mygolbsClient: MygolbsApiClient
) : ITransitProvider {

    override val sourceType: DataSourceType = DataSourceType.MYGOLBS

    override fun supportsLine(lineId: String): Boolean = lineId.startsWith("mg:")

    override fun supportsStop(request: StopDetailRequest): Boolean {
        return request.stopId?.startsWith("mg:") == true ||
                request.physicalStId?.startsWith("mg:") == true ||
                request.namesakeStId?.startsWith("mg:") == true
    }

    override fun supportsLocationRadar(): Boolean = false

    override suspend fun getSupportedCities(): List<TransitCity> {
        val cityMap = mygolbsClient.getCities()
        return cityMap.map { (name, code) ->
            TransitCity(
                cityId = "",
                cityName = name,
                mygolbsCityCode = code,
                province = ProvinceCityCatalog.inferProvince(name)
            )
        }
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? = null

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): TransitSearchResult = coroutineScope {
        val linesDeferred = async {
            runCatching {
                mygolbsClient.searchLines(
                    cityCode = city.mygolbsCityCode.orEmpty(),
                    cityName = city.cityName,
                    keyword = keyword
                )
            }.getOrDefault(emptyList())
        }

        val stopsDeferred = async {
            runCatching {
                mygolbsClient.searchStops(
                    cityName = city.cityName,
                    keyword = keyword
                )
            }.getOrDefault(emptyList())
        }

        TransitSearchResult(
            keyword = keyword,
            lines = linesDeferred.await(),
            stops = stopsDeferred.await()
        )
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> = emptyList()

    override suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop> {
        val rawName = request.stopName
            ?: request.stopId?.removePrefix("mg:")?.split(":")?.lastOrNull()
            ?: request.physicalStId?.removePrefix("mg:")?.split(":")?.lastOrNull()
            ?: request.namesakeStId?.removePrefix("mg:")?.split(":")?.lastOrNull()
            ?: ""

        val cleanName = rawName
            .removeSuffix(" (掌上公交)")
            .removeSuffix("(掌上公交)")
            .removeSuffix("（掌上公交）")
            .removeSuffix(" [掌上公交]")
            .removeSuffix("[掌上公交]")
            .trim()

        if (cleanName.isBlank()) {
            return emptyList()
        }

        val stop = runCatching {
            mygolbsClient.getStopLines(city.cityName, cleanName)
        }.getOrNull()

        return if (stop != null && !stop.isPureSubwayStation) {
            listOf(stop)
        } else {
            emptyList()
        }
    }

    override suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail {
        return mygolbsClient.getLineDetail(request.lineId, request.targetOrder)
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): LineTimetable {
        val desc = if (line.firstTime.isNotBlank() || line.lastTime.isNotBlank()) {
            "该线路暂不提供首站预测时刻表，请参考运营首末班：${line.firstTime} - ${line.lastTime}"
        } else {
            "该线路暂不提供首站预测时刻表，请参考运营首末班"
        }
        return LineTimetable(
            lineId = line.lineId,
            lineName = line.name,
            depDesc = desc
        )
    }
}
