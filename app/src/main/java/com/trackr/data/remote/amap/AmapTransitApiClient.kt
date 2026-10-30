package com.trackr.data.remote.amap

import com.trackr.data.remote.common.BaseTransitApiClient
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * 高德地图 Web 服务开放 API 客户端。
 * 负责调用高德官方公交路径规划（Transit Integrated）及输入提示（Inputtips），
 * 将高德结构化的公交出行换乘方案直接转为应用内的路线串联分组 [TransitGroup]。
 */
class AmapTransitApiClient(
    httpClient: OkHttpClient
) : BaseTransitApiClient(httpClient) {

    companion object {
        private const val BASE_URL_AMAP = "https://restapi.amap.com/v3"
    }

    /**
     * 关键字提示（获取地点/车站名称与其经纬度）
     */
    suspend fun searchPoiTips(
        keyword: String,
        city: String = "",
        apiKey: String? = null
    ): Result<List<AmapTipItem>> = runCatching {
        if (keyword.isBlank()) return@runCatching emptyList()

        val key = apiKey?.trim()?.ifBlank { null }
            ?: throw IllegalStateException("未配置高德 Web 服务 Key，请在设置中配置自备 Key")
        val url = "$BASE_URL_AMAP/assistant/inputtips?keywords=${URLEncoder.encode(keyword.trim(), "UTF-8")}&city=${URLEncoder.encode(city.trim(), "UTF-8")}&key=$key"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val rootJson = executeJson(request)
        val status = rootJson.optString("status")
        if (status != "1") {
            val info = rootJson.optString("info", "未知错误")
            throw IllegalStateException("高德服务异常: $info")
        }

        val tipsArray = rootJson.optJSONArray("tips") ?: return@runCatching emptyList()
        val resultList = mutableListOf<AmapTipItem>()

        for (i in 0 until tipsArray.length()) {
            val tip = tipsArray.optJSONObject(i) ?: continue
            val name = tip.optString("name").trim()
            val location = tip.optString("location").trim()
            val district = tip.optString("district").trim()
            val address = tip.optString("address").trim()

            // 过滤没有经纬度或名称为空的无效项
            if (name.isNotEmpty() && location.contains(",")) {
                resultList.add(
                    AmapTipItem(
                        name = name,
                        district = district,
                        address = address,
                        location = location
                    )
                )
            }
        }
        resultList
    }

    /**
     * 高德公交路径规划
     * @param originLngLat 起点坐标 "lng,lat"
     * @param destinationLngLat 终点坐标 "lng,lat"
     * @param originName 起点名称
     * @param destName 终点名称
     * @param city 城市名称或城市编码
     */
    suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String = "起点",
        destName: String = "终点",
        city: String,
        apiKey: String? = null
    ): Result<List<AmapTransitSchemeResult>> = runCatching {
        val key = apiKey?.trim()?.ifBlank { null }
            ?: throw IllegalStateException("未配置高德 Web 服务 Key，请在设置中配置自备 Key")
        val url = "$BASE_URL_AMAP/direction/transit/integrated?origin=$originLngLat&destination=$destinationLngLat&city=${URLEncoder.encode(city.trim(), "UTF-8")}&strategy=0&key=$key"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val rootJson = executeJson(request)
        val status = rootJson.optString("status")
        if (status != "1") {
            val info = rootJson.optString("info", "未知错误")
            throw IllegalStateException("高德公交规划失败: $info")
        }

        val routeObj = rootJson.optJSONObject("route")
            ?: throw IllegalStateException("未返回有效公交路径数据")
        val transitsArray = routeObj.optJSONArray("transits")
            ?: return@runCatching emptyList()

        parseTransitsToSchemes(transitsArray, originName, destName, city)
    }

    /**
     * 将高德规划返回的多个方案（transits）转换为应用内统一的 [AmapTransitSchemeResult]
     */
    fun parseTransitsToSchemes(
        transitsArray: org.json.JSONArray,
        originName: String,
        destName: String,
        cityName: String
    ): List<AmapTransitSchemeResult> {
        val schemeResults = mutableListOf<AmapTransitSchemeResult>()

        for (i in 0 until transitsArray.length()) {
            val transitObj = transitsArray.optJSONObject(i) ?: continue
            val durationSeconds = transitObj.optInt("duration", 0)
            val durationMinutes = (durationSeconds + 59) / 60
            val walkingMeters = transitObj.optInt("walking_distance", 0)
            val costStr = transitObj.optString("cost", "0.0")

            val segmentsArray = transitObj.optJSONArray("segments") ?: continue
            val legs = mutableListOf<TransitLeg>()
            var legIndex = 0

            for (j in 0 until segmentsArray.length()) {
                val segObj = segmentsArray.optJSONObject(j) ?: continue
                val busObj = segObj.optJSONObject("bus") ?: continue
                val buslinesArray = busObj.optJSONArray("buslines") ?: continue
                if (buslinesArray.length() == 0) continue

                val candidateLines = mutableListOf<CandidateLine>()
                var departureStopName = ""
                var arrivalStopName = ""
                var fromLat = 0.0
                var fromLng = 0.0
                var toLat = 0.0
                var toLng = 0.0

                for (k in 0 until buslinesArray.length()) {
                    val lineObj = buslinesArray.optJSONObject(k) ?: continue
                    val rawName = lineObj.optString("name")
                    val lineName = rawName.substringBefore("(").trim()
                    val lineId = lineObj.optString("id")

                    val depStop = lineObj.optJSONObject("departure_stop")
                    val arrStop = lineObj.optJSONObject("arrival_stop")

                    if (depStop != null) {
                        departureStopName = depStop.optString("name")
                        val loc = depStop.optString("location").split(",")
                        if (loc.size == 2) {
                            fromLng = loc[0].toDoubleOrNull() ?: 0.0
                            fromLat = loc[1].toDoubleOrNull() ?: 0.0
                        }
                    }
                    if (departureStopName.isEmpty()) {
                        departureStopName = originName
                    }

                    if (arrStop != null) {
                        arrivalStopName = arrStop.optString("name")
                        val loc = arrStop.optString("location").split(",")
                        if (loc.size == 2) {
                            toLng = loc[0].toDoubleOrNull() ?: 0.0
                            toLat = loc[1].toDoubleOrNull() ?: 0.0
                        }
                    }
                    if (arrivalStopName.isEmpty()) {
                        arrivalStopName = destName
                    }

                    val viaNum = lineObj.optInt("via_num", 1)

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
                    val segWalking = segObj.optJSONObject("walking")
                    val walkingDist = segWalking?.optInt("distance", 0) ?: 0

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

            if (legs.isNotEmpty()) {
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

                schemeResults.add(
                    AmapTransitSchemeResult(
                        title = title,
                        durationMinutes = durationMinutes,
                        walkingMeters = walkingMeters,
                        costYuan = if (costStr.isNotEmpty() && costStr != "[]") costStr else "2.0",
                        transferCount = transferCount,
                        group = group
                    )
                )
            }
        }

        return schemeResults
    }
}
