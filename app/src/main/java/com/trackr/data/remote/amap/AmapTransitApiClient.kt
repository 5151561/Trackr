package com.trackr.data.remote.amap

import com.trackr.data.remote.common.BaseTransitApiClient
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GeoPoint
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
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

        /** 夜班车时段：22:00 ~ 次日 05:00 */
        fun nightFlagFor(hourOfDay: Int): Int = if (hourOfDay >= 22 || hourOfDay < 5) 1 else 0

        /** 按设备当前时间计算 nightflag */
        fun currentNightFlag(): Int = nightFlagFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

        /**
         * 把 "lng,lat" 规整为高德要求的 6 位小数格式（Locale.ROOT，避免部分语言环境用逗号做小数点）。
         * 格式非法时返回 null。
         */
        fun formatLngLat(lngLat: String): String? {
            val parts = lngLat.split(",")
            if (parts.size != 2) return null
            val lng = parts[0].trim().toDoubleOrNull() ?: return null
            val lat = parts[1].trim().toDoubleOrNull() ?: return null
            return String.format(Locale.ROOT, "%.6f,%.6f", lng, lat)
        }

        /** 6 位区县 adcode 归一到地级市 adcode（如 330106 -> 330100），非法时返回 null */
        fun cityAdcodeOf(adcode: String): String? {
            val code = adcode.trim()
            if (code.length != 6 || !code.all { it.isDigit() }) return null
            return code.take(4) + "00"
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /** 构造输入提示 URL（city 非空时附带 citylimit=true，只返回本城结果） */
        fun buildInputTipsUrl(
            keyword: String,
            city: String,
            key: String,
            location: String? = null
        ): String {
            val sb = StringBuilder("$BASE_URL_AMAP/assistant/inputtips?keywords=")
                .append(encode(keyword.trim()))
            val cityTrimmed = city.trim()
            if (cityTrimmed.isNotEmpty()) {
                sb.append("&city=").append(encode(cityTrimmed)).append("&citylimit=true")
            }
            location?.let { formatLngLat(it) }?.let { sb.append("&location=").append(encode(it)) }
            sb.append("&key=").append(encode(key))
            return sb.toString()
        }

        /**
         * 构造公交算路 URL。坐标统一 6 位小数并做 URL 编码；
         * [cityd] 非空时表示跨城，传给高德；[nightFlag] 为 1 时计算夜班车。
         */
        fun buildTransitIntegratedUrl(
            originLngLat: String,
            destinationLngLat: String,
            city: String,
            key: String,
            cityd: String? = null,
            nightFlag: Int = 0
        ): String {
            val origin = formatLngLat(originLngLat)
                ?: throw IllegalArgumentException("起点经纬度格式错误")
            val destination = formatLngLat(destinationLngLat)
                ?: throw IllegalArgumentException("终点经纬度格式错误")
            val sb = StringBuilder("$BASE_URL_AMAP/direction/transit/integrated?origin=")
                .append(encode(origin))
                .append("&destination=").append(encode(destination))
                .append("&city=").append(encode(city.trim()))
            cityd?.trim()?.ifBlank { null }?.let { sb.append("&cityd=").append(encode(it)) }
            sb.append("&strategy=0")
                .append("&nightflag=").append(if (nightFlag == 1) 1 else 0)
                .append("&key=").append(encode(key))
            return sb.toString()
        }
    }

    /**
     * 关键字提示（获取地点/车站名称与其经纬度）
     */
    suspend fun searchPoiTips(
        keyword: String,
        city: String = "",
        apiKey: String? = null,
        location: String? = null
    ): Result<List<AmapTipItem>> = runCatching {
        if (keyword.isBlank()) return@runCatching emptyList()

        val key = apiKey?.trim()?.ifBlank { null }
            ?: throw IllegalStateException("未配置高德 Web 服务 Key，请在设置中配置自备 Key")
        val url = buildInputTipsUrl(keyword, city, key, location)

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
            // 无值时高德会返回空数组 []，只认 6 位数字
            val adcode = tip.optString("adcode").trim().takeIf { cityAdcodeOf(it) != null }.orEmpty()

            // 过滤没有经纬度或名称为空的无效项
            if (name.isNotEmpty() && location.contains(",")) {
                resultList.add(
                    AmapTipItem(
                        name = name,
                        district = district,
                        address = address,
                        location = location,
                        adcode = adcode
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
     * @param cityd 跨城时的终点城市（adcode / 名称），同城传 null
     * @param nightFlag 是否计算夜班车（1 计算，0 不计算）
     */
    suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String = "起点",
        destName: String = "终点",
        city: String,
        apiKey: String? = null,
        cityd: String? = null,
        nightFlag: Int = currentNightFlag()
    ): Result<List<AmapTransitSchemeResult>> = runCatching {
        val key = apiKey?.trim()?.ifBlank { null }
            ?: throw IllegalStateException("未配置高德 Web 服务 Key，请在设置中配置自备 Key")
        val url = buildTransitIntegratedUrl(
            originLngLat = originLngLat,
            destinationLngLat = destinationLngLat,
            city = city,
            key = key,
            cityd = cityd,
            nightFlag = nightFlag
        )

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
            // 末段步行（最后一段只有 walking、bus.buslines 为空）：从末站步行到目的地 POI
            var finalWalkPath: List<GeoPoint> = emptyList()

            for (j in 0 until segmentsArray.length()) {
                val segObj = segmentsArray.optJSONObject(j) ?: continue

                // 本段“乘车前”步行轨迹（起点 POI/上一段下车点 -> 本段上车站）
                val segWalking = segObj.optJSONObject("walking")
                val walkingDist = segWalking?.optInt("distance", 0) ?: 0
                val walkPathBefore = parseWalkingPolyline(segWalking)

                val busObj = segObj.optJSONObject("bus")
                val buslinesArray = busObj?.optJSONArray("buslines")
                if (busObj == null || buslinesArray == null || buslinesArray.length() == 0) {
                    // 纯步行段：若无公交线路，视为末段步行（末站 -> 目的地）
                    if (walkPathBefore.isNotEmpty()) {
                        finalWalkPath = walkPathBefore
                    }
                    continue
                }

                val candidateLines = mutableListOf<CandidateLine>()
                // 站名/坐标/行驶轨迹以第一条候选线路（主线路）为准，不被后续候选线路覆盖
                var departureStopName = ""
                var arrivalStopName = ""
                var fromLat = 0.0
                var fromLng = 0.0
                var toLat = 0.0
                var toLng = 0.0
                var ridePath: List<GeoPoint> = emptyList()

                for (k in 0 until buslinesArray.length()) {
                    val lineObj = buslinesArray.optJSONObject(k) ?: continue
                    val rawName = lineObj.optString("name")
                    val lineName = rawName.substringBefore("(").trim()
                    val lineId = lineObj.optString("id")

                    val depStop = lineObj.optJSONObject("departure_stop")
                    val arrStop = lineObj.optJSONObject("arrival_stop")

                    var lineDepName = depStop?.optString("name").orEmpty()
                    var lineDepLat = 0.0
                    var lineDepLng = 0.0
                    depStop?.optString("location")?.split(",")?.let { loc ->
                        if (loc.size == 2) {
                            lineDepLng = loc[0].toDoubleOrNull() ?: 0.0
                            lineDepLat = loc[1].toDoubleOrNull() ?: 0.0
                        }
                    }
                    if (lineDepName.isEmpty()) lineDepName = originName

                    var lineArrName = arrStop?.optString("name").orEmpty()
                    var lineArrLat = 0.0
                    var lineArrLng = 0.0
                    arrStop?.optString("location")?.split(",")?.let { loc ->
                        if (loc.size == 2) {
                            lineArrLng = loc[0].toDoubleOrNull() ?: 0.0
                            lineArrLat = loc[1].toDoubleOrNull() ?: 0.0
                        }
                    }
                    if (lineArrName.isEmpty()) lineArrName = destName

                    val viaNum = lineObj.optInt("via_num", 1)

                    // 仅以第一条候选线路（主线路）填充站名、坐标与行驶轨迹
                    if (k == 0) {
                        departureStopName = lineDepName
                        arrivalStopName = lineArrName
                        fromLat = lineDepLat
                        fromLng = lineDepLng
                        toLat = lineArrLat
                        toLng = lineArrLng
                        ridePath = parsePolyline(lineObj.optString("polyline"))
                    }

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
                            startStopName = lineDepName,
                            endStopName = lineArrName
                        )
                    )
                }

                if (departureStopName.isNotEmpty() && arrivalStopName.isNotEmpty() && candidateLines.isNotEmpty()) {
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
                            walkingDistanceMeters = walkingDist,
                            ridePath = ridePath,
                            walkPathBefore = walkPathBefore
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
                        group = group,
                        finalWalkPath = finalWalkPath
                    )
                )
            }
        }

        return schemeResults
    }

    /**
     * 解析高德 Web 公交 polyline 字符串 "lng,lat;lng,lat;..." 为 GCJ-02 坐标点序列
     */
    private fun parsePolyline(raw: String?): List<GeoPoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { pair ->
            val parts = pair.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lng = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat = lat, lng = lng)
        }
    }

    /**
     * 解析 walking 段内 steps[].polyline，拼接为完整步行轨迹
     */
    private fun parseWalkingPolyline(walkingObj: JSONObject?): List<GeoPoint> {
        if (walkingObj == null) return emptyList()
        val stepsArray = walkingObj.optJSONArray("steps") ?: return emptyList()
        val points = mutableListOf<GeoPoint>()
        for (i in 0 until stepsArray.length()) {
            val step = stepsArray.optJSONObject(i) ?: continue
            points.addAll(parsePolyline(step.optString("polyline")))
        }
        return points
    }
}
