package com.trackr.data.remote.mygolbs

import com.trackr.data.remote.common.BaseTransitApiClient
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 掌上公交（Mygolbs）全国实时公交直连 API 客户端
 * 基于 H5 数据面协议实现，严格携带 Origin 与 Referer 头，支持纯本地直连
 */
class MygolbsApiClient(httpClient: OkHttpClient) : BaseTransitApiClient(httpClient) {

    companion object {
        const val API_URL = "https://h5.mygolbs.com/ApiData.do"
        const val ORIGIN = "https://h5.mygolbs.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val PRICE_REGEX = Regex("""票价[:：]\s*([^\s，,]+)""")

        private val DEFAULT_CITIES = mapOf(
            "北京市" to "110000",
            "上海市" to "310000",
            "广州市" to "440100",
            "深圳市" to "440300",
            "成都市" to "510100",
            "重庆市" to "500000",
            "武汉市" to "420100",
            "南京市" to "320100",
            "福州市" to "350100",
            "贵阳市" to "520100",
            "玉林市" to "450900",
            "北流市" to "450981",
            "南宁市" to "450100",
            "柳州市" to "450200",
            "桂林市" to "450300"
        )

        /**
         * 将任意城市名规范化为掌上公交接收的带行政区划名称（如“福州” -> “福州市”）
         */
        fun normalizeCityName(cityName: String, knownCityNames: Set<String> = DEFAULT_CITIES.keys): String {
            val trimmed = cityName.trim()
            if (trimmed.isEmpty()) return ""
            if (knownCityNames.contains(trimmed)) return trimmed
            val withShi = if (!trimmed.endsWith("市") && !trimmed.endsWith("县") && !trimmed.endsWith("区")) "${trimmed}市" else trimmed
            if (knownCityNames.contains(withShi)) return withShi
            val found = knownCityNames.firstOrNull { it.startsWith(trimmed) || trimmed.startsWith(it.removeSuffix("市")) }
            if (found != null) return found
            return withShi
        }
    }

    private val cachedCityNames = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(DEFAULT_CITIES.keys)
    }

    fun normalizeCityName(cityName: String): String {
        return normalizeCityName(cityName, cachedCityNames)
    }

    private suspend fun callApi(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
        for ((k, v) in params) {
            formBuilder.add(k, v)
        }

        val request = Request.Builder()
            .url(API_URL)
            .post(formBuilder.build())
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .build()

        val json = executeJson(request)
        val status = json.opt("status")?.toString()?.trim('"')
        if (status != "1") {
            val msg = json.optString("msg")
            throw IOException("Mygolbs upstream error status=$status msg=$msg")
        }
        json
    }

    /**
     * 查询掌上公交支持的城市列表（CMD=101，返回 城市名 -> citycode 映射）
     */
    suspend fun getCities(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, String>(DEFAULT_CITIES)
        try {
            val json = callApi(mapOf("CMD" to "101"))
            val dataArr = json.optJSONArray("data") ?: json.optJSONArray("list")
            if (dataArr != null) {
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val name = item.optString("cityname")
                        .ifBlank { item.optString("cityName") }
                        .ifBlank { item.optString("CITYNAME") }
                    val code = item.optString("cityCode")
                        .ifBlank { item.optString("CITYCODE") }
                        .ifBlank { item.optString("code") }
                        .ifBlank { "0" }
                    if (name.isNotBlank()) {
                        result[name] = code
                        cachedCityNames.add(name)
                    }
                }
            }
        } catch (_: Exception) {
            // 异常时沿用内置兜底映射
        }
        result
    }

    /**
     * 按关键字搜索线路（CMD=114 / CMD=102）
     * 为每条匹配线路生成正向与反向两个方向（lineId 为 mg:${cityCode}:${cityName}:${lineName}:1 与 :2）
     */
    suspend fun searchLines(cityCode: String = "", cityName: String, keyword: String): List<BusLineSummary> = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        val results = ArrayList<BusLineSummary>()
        try {
            val json = try {
                callApi(
                    mapOf(
                        "CMD" to "114",
                        "CITYNAME" to normalizedCity,
                        "KEYWORD" to keyword,
                        "CITYKEY" to ""
                    )
                )
            } catch (_: Exception) {
                callApi(
                    mapOf(
                        "CMD" to "102",
                        "CITYNAME" to normalizedCity,
                        "KEYWORD" to keyword,
                        "CITYKEY" to ""
                    )
                )
            }

            val dataArr = json.optJSONArray("buslines")
                ?: json.optJSONArray("data")
                ?: json.optJSONArray("list")
                ?: JSONArray()
            val lineMap = LinkedHashMap<String, MutableList<BusLineDirection>>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val lineName = item.optString("lineName").ifBlank { item.optString("LINENAME") }
                if (lineName.isBlank()) continue

                val upperOrDown = item.optString("upperOrDown").ifBlank { "1" }
                val startStation = item.optString("from")
                    .ifBlank { item.optString("startStation") }
                    .ifBlank { item.optString("firstStation") }
                val endStation = item.optString("to")
                    .ifBlank { item.optString("endStation") }
                    .ifBlank { item.optString("lastStation") }
                val price = item.optString("comments")
                val routeBean = item.optJSONObject("routeBean")
                val isSubway = routeBean?.optString("routeType") == "3" || lineName.contains("地铁")
                if (isSubway) continue // 过滤纯地铁线路

                val firstTime = routeBean?.optString("suTime").orEmpty()
                val lastTime = routeBean?.optString("euTime").orEmpty()
                val dirInt = if (upperOrDown == "1") 0 else 1

                val dir = BusLineDirection(
                    lineId = "mg:$cityCode:$normalizedCity:$lineName:$upperOrDown",
                    name = lineName,
                    lineNo = lineName,
                    direction = dirInt,
                    startStop = startStation,
                    endStop = endStation,
                    firstTime = firstTime,
                    lastTime = lastTime,
                    price = price,
                    source = DataSourceType.MYGOLBS,
                    mygolbsCityCode = cityCode,
                    mygolbsCityName = normalizedCity
                )

                lineMap.getOrPut(lineName) { mutableListOf() }.add(dir)
            }

            for ((lineName, dirs) in lineMap) {
                val effectiveDirs = if (dirs.size == 1) {
                    val d1 = dirs[0]
                    val revUpperOrDown = if (d1.direction == 0) "2" else "1"
                    val revDir = BusLineDirection(
                        lineId = "mg:$cityCode:$normalizedCity:$lineName:$revUpperOrDown",
                        name = lineName,
                        lineNo = lineName,
                        direction = if (d1.direction == 0) 1 else 0,
                        startStop = d1.endStop,
                        endStop = d1.startStop,
                        firstTime = d1.firstTime,
                        lastTime = d1.lastTime,
                        price = d1.price,
                        source = DataSourceType.MYGOLBS,
                        mygolbsCityCode = cityCode,
                        mygolbsCityName = normalizedCity
                    )
                    listOf(d1, revDir)
                } else {
                    dirs.sortedBy { it.direction }
                }

                results.add(
                    BusLineSummary(
                        name = lineName,
                        lineNo = lineName,
                        isSubway = false,
                        directions = effectiveDirs,
                        source = DataSourceType.MYGOLBS
                    )
                )
            }
        } catch (_: Exception) {
            // 搜索失败返回空列表
        }
        results
    }

    /**
     * 按关键字搜索公交站点（CMD=110 / CMD=102）
     */
    suspend fun searchStops(cityName: String, keyword: String): List<NearbyStop> = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        val results = ArrayList<NearbyStop>()
        try {
            val json = try {
                callApi(
                    mapOf(
                        "CMD" to "110",
                        "CITYNAME" to normalizedCity,
                        "KEYWORD" to keyword,
                        "CITYKEY" to ""
                    )
                )
            } catch (_: Exception) {
                callApi(
                    mapOf(
                        "CMD" to "102",
                        "CITYNAME" to normalizedCity,
                        "KEYWORD" to keyword,
                        "CITYKEY" to ""
                    )
                )
            }

            val dataArr = json.optJSONArray("busstations") ?: JSONArray()
            val seenStops = HashSet<String>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val stationName = item.optString("stationName").trim()
                if (stationName.isBlank() || !seenStops.add(stationName)) continue
                if (isSubwayStationName(stationName)) continue // 过滤纯地铁站（保留名称以地铁口命名的地面公交站）

                val lat = item.optDouble("station_lat", 0.0)
                val lng = item.optDouble("station_lon", 0.0)
                val stopId = "mg:$normalizedCity:$stationName"

                results.add(
                    NearbyStop(
                        stopId = stopId,
                        name = stationName,
                        wgsLat = lat,
                        wgsLng = lng,
                        physicalStId = stopId,
                        namesakeStId = stopId,
                        source = DataSourceType.MYGOLBS
                    )
                )
            }
        } catch (_: Exception) {
            // 搜索失败返回空列表
        }
        results
    }

    /**
     * 根据站点名获取停靠该站点的所有线路及实时状态（CMD=105）
     */
    suspend fun getStopLines(cityName: String, stationName: String): NearbyStop = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        val cleanStation = stationName.trim()
        val stopId = "mg:$normalizedCity:$cleanStation"
        try {
            val json = callApi(
                mapOf(
                    "CMD" to "105",
                    "CITYNAME" to normalizedCity,
                    "STATIONNAME" to cleanStation,
                    "MYLAT" to "",
                    "MYLNG" to "",
                    "CITYKEY" to ""
                )
            )

            val dataArr = json.optJSONArray("data") ?: JSONArray()
            val lineStatuses = ArrayList<NearbyStopLineStatus>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val lineName = item.optString("lineName").ifBlank { item.optString("routeName") }
                if (lineName.isBlank()) continue

                val upperOrDown = item.optString("upperOrDown").ifBlank { "1" }
                val from = item.optString("from")
                val to = item.optString("to")
                val comments = item.optString("comments")
                val neartext = item.optString("neartext")
                val neartime = item.optString("neartime")
                val stationOrder = item.optInt("stationOrder", 0)
                val routeBean = item.optJSONObject("routeBean")
                val isSubway = routeBean?.optString("routeType") == "3" || lineName.contains("地铁")
                if (isSubway) continue // 过滤纯地铁线路

                val firstTime = routeBean?.optString("suTime").orEmpty()
                val lastTime = routeBean?.optString("euTime").orEmpty()

                val lineDir = BusLineDirection(
                    lineId = "mg:0:$normalizedCity:$lineName:$upperOrDown",
                    name = lineName,
                    lineNo = lineName,
                    direction = if (upperOrDown == "1") 0 else 1,
                    startStop = from,
                    endStop = to,
                    firstTime = firstTime,
                    lastTime = lastTime,
                    price = comments,
                    source = DataSourceType.MYGOLBS,
                    mygolbsCityName = normalizedCity
                )

                val hasReal = routeBean?.let { rb ->
                    if (rb.has("hasReal")) rb.optInt("hasReal", 1) else 1
                } ?: 1

                val statusText = when {
                    hasReal == 0 -> "未开通实时"
                    neartext.isNotBlank() -> neartext
                    neartime.isNotBlank() -> "预计 $neartime 到达"
                    else -> "正在运营中"
                }

                lineStatuses.add(
                    NearbyStopLineStatus(
                        line = lineDir,
                        targetOrder = stationOrder,
                        targetStopId = stopId,
                        targetStopName = cleanStation,
                        statusText = statusText,
                        preArrivalTime = if (hasReal == 0) null else neartime.takeIf { it.isNotBlank() }
                    )
                )
            }

            NearbyStop(
                stopId = stopId,
                name = cleanStation,
                physicalStId = stopId,
                namesakeStId = stopId,
                lines = lineStatuses,
                source = DataSourceType.MYGOLBS
            )
        } catch (_: Exception) {
            NearbyStop(
                stopId = stopId,
                name = cleanStation,
                physicalStId = stopId,
                namesakeStId = stopId,
                source = DataSourceType.MYGOLBS
            )
        }
    }

    /**
     * 逆地理城市反查（CMD=108）：根据经纬度反查所在城市名
     */
    suspend fun reverseGeocodeCityName(wgsLat: Double, wgsLng: Double): String? = withContext(Dispatchers.IO) {
        if (wgsLat <= 0.0 || wgsLng <= 0.0) return@withContext null
        try {
            val json = callApi(
                mapOf(
                    "CMD" to "108",
                    "LAT" to wgsLat.toString(),
                    "LNG" to wgsLng.toString(),
                    "MYLAT" to wgsLat.toString(),
                    "MYLNG" to wgsLng.toString()
                )
            )
            val cityName = json.optString("msg").trim()
            if (cityName.isNotBlank() && cityName != "error") {
                cityName
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 探测周边公交站点（CMD=106）：根据经纬度获取附近站点列表并并发拉取实时到站
     */
    suspend fun getNearbyStops(
        cityName: String,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        try {
            val json = callApi(
                mapOf(
                    "CMD" to "106",
                    "CITYNAME" to normalizedCity,
                    "LAT" to wgsLat.toString(),
                    "LNG" to wgsLng.toString(),
                    "MYLAT" to wgsLat.toString(),
                    "MYLNG" to wgsLng.toString()
                )
            )
            val dataArr = json.optJSONArray("data") ?: return@withContext emptyList()
            val rawStationList = ArrayList<Triple<String, Double, Double>>() // name, lat, lng
            val distMap = HashMap<String, Int>()

            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                // 过滤纯地铁站点
                if (isSubwayStationName(name)) continue

                val lat = item.optDouble("lat", 0.0)
                val lng = item.optDouble("lon", 0.0)
                val dis = item.optInt("dis", 0)

                rawStationList.add(Triple(name, lat, lng))
                val resolvedDist = resolveDistance(dis.takeIf { it > 0 }, wgsLat, wgsLng, lat, lng)
                if (resolvedDist != null) {
                    distMap[name] = resolvedDist
                }
                if (rawStationList.size >= limit) break
            }

            if (rawStationList.isEmpty()) {
                return@withContext emptyList()
            }

            // 并发调用 getStopLines (CMD=105) 补齐停靠线路与实时在途数据
            coroutineScope {
                rawStationList.map { (name, lat, lng) ->
                    async {
                        val stop = getStopLines(normalizedCity, name)
                        stop.copy(
                            wgsLat = if (lat > 0.0) lat else stop.wgsLat,
                            wgsLng = if (lng > 0.0) lng else stop.wgsLng,
                            distanceMeters = distMap[name] ?: stop.distanceMeters,
                            source = DataSourceType.MYGOLBS
                        )
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 获取掌上公交线路详情（解析 mg:${cityCode}:${cityName}:${lineName}:${dir}）
     * 调用 CMD=103 获取站表/首末班/票价，调用 CMD=104 获取在途车辆及到站时间，使用 RouteProjectionMath 计算站间比例
     */
    suspend fun getLineDetail(lineId: String, targetOrder: Int? = null): BusLineDetail = withContext(Dispatchers.IO) {
        val parts = lineId.removePrefix("mg:").split(":")
        if (parts.size < 4) {
            throw IllegalArgumentException("Invalid Mygolbs lineId: $lineId. Expected format: mg:cityCode:cityName:lineName:dir")
        }
        val cityCode = parts[0]
        val cityName = normalizeCityName(parts[1])
        val lineName = parts[2]
        val dirStr = parts[3]
        val dirInt = dirStr.toIntOrNull() ?: 1

        // 1. 获取站表与基础信息 (CMD=103)
        val routeJson = callApi(
            mapOf(
                "CMD" to "103",
                "CITYNAME" to cityName,
                "CITYCODE" to cityCode,
                "LINENAME" to lineName,
                "DIRECTION" to dirStr,
                "CITYKEY" to ""
            )
        )

        val commonts = routeJson.optString("commonts")
        val priceMatch = PRICE_REGEX.find(commonts)
        val price = priceMatch?.groupValues?.getOrNull(1) ?: ""

        val firstLastArr = routeJson.optJSONArray("firstLast")
        val firstTime = firstLastArr?.optJSONObject(0)?.optString("first")
            ?: routeJson.optString("beginTime")
        val lastTime = firstLastArr?.optJSONObject(0)?.optString("last")
            ?: routeJson.optString("endTime")

        val dataArr = routeJson.optJSONArray("data") ?: JSONArray()
        val stopsList = ArrayList<BusStop>()
        for (i in 0 until dataArr.length()) {
            val st = dataArr.getJSONObject(i)
            val stStatus = st.optInt("stationsStatus", 1)
            val statusNote = when (stStatus) {
                0 -> "临时不停靠"
                2 -> "只上不下"
                3 -> "只下不上"
                4 -> "临时停靠"
                5 -> "响应式停靠"
                6 -> "响铃式停靠"
                7 -> "招手即停"
                8 -> "大站车停靠"
                else -> null
            }
            val order = st.optInt("stationOrder", i + 1)
            stopsList.add(
                BusStop(
                    stopId = order.toString(),
                    name = st.optString("stationName"),
                    order = order,
                    wgsLat = st.optDouble("station_lat", 0.0),
                    wgsLng = st.optDouble("station_lon", 0.0),
                    statusNote = statusNote
                )
            )
        }

        val totalStops = stopsList.size
        val effectiveTargetOrder = if (targetOrder != null && targetOrder > 0) targetOrder else totalStops

        // 2. 获取实时在途车辆与到站估计 (CMD=104)
        val realtimeJson = callApi(
            mapOf(
                "CMD" to "104",
                "CITYNAME" to cityName,
                "CITYCODE" to cityCode,
                "LINENAME" to lineName,
                "DIRECTION" to dirStr,
                "STATIONORDER" to effectiveTargetOrder.toString(),
                "CITYKEY" to ""
            )
        )

        val runState = realtimeJson.optInt("runState", 0)
        val hasReal = realtimeJson.optInt("hasReal", 1)

        val operatingState = when {
            runState == 1 -> LineOperatingState.SUSPENDED
            hasReal == 0 -> LineOperatingState.NO_REALTIME
            else -> LineOperatingState.RUNNING
        }
        val statusDescription = when {
            runState == 1 -> "该线路已停运"
            hasReal == 0 -> "该线路未开通实时数据查询"
            else -> ""
        }

        // 解析逐站到站预测 (routeOnStationRTimeInfoList)
        val onStationArr = realtimeJson.optJSONArray("routeOnStationRTimeInfoList") ?: JSONArray()
        val etaMap = HashMap<String, Double>() // busNumber -> 分钟
        for (i in 0 until onStationArr.length()) {
            val e = onStationArr.getJSONObject(i)
            val bNum = e.optString("busNumber")
            if (bNum.isNotBlank()) {
                etaMap[bNum] = e.optDouble("busToStationTime", 0.0)
            }
        }

        // 解析全线车辆并进行站间比例计算
        val listArr = realtimeJson.optJSONArray("list") ?: JSONArray()
        val liveBuses = ArrayList<LiveBus>()
        for (i in 0 until listArr.length()) {
            val b = listArr.getJSONObject(i)
            val busNumber = b.optString("busNumber")
            val bIndex = b.optInt("index", 0)
            val bOrder = bIndex + 1
            val statusType = b.optString("statusType")
            val isArrived = (statusType == "0")
            val bLat = b.optDouble("bus_lat", 0.0)
            val bLng = b.optDouble("bus_lng", 0.0)

            val prevStop = stopsList.find { it.order == bOrder - 1 }
            val curTargetStop = stopsList.find { it.order == bOrder }

            val segmentProgress = RouteProjectionMath.computeSegmentProgress(
                busLat = bLat,
                busLng = bLng,
                isArrived = isArrived,
                prevStop = prevStop,
                targetStop = curTargetStop
            )

            val etaMin = etaMap[busNumber]
            val etaSeconds = when {
                etaMin != null && etaMin >= 1.0 -> (etaMin * 60).toInt()
                etaMin != null && etaMin > 0.0 -> 60
                else -> -1
            }
            val etaText = if (etaSeconds > 0) RouteProjectionMath.formatEtaText(etaSeconds, isArrived && bOrder == effectiveTargetOrder) else null

            liveBuses.add(
                LiveBus(
                    busId = busNumber,
                    plateOrFleetNo = sanitizeBusPlate(busNumber),
                    order = bOrder,
                    segmentProgress = segmentProgress,
                    isArrived = isArrived,
                    wgsLat = bLat,
                    wgsLng = bLng,
                    speedKmh = 0.0,
                    capacityLevel = -1,
                    etaSeconds = etaSeconds,
                    expectedArrivalTimeText = etaText
                )
            )
        }

        val startStop = stopsList.firstOrNull()?.name ?: ""
        val endStop = stopsList.lastOrNull()?.name ?: ""

        val currentDirection = BusLineDirection(
            lineId = lineId,
            name = lineName,
            lineNo = lineName,
            direction = if (dirStr == "1") 0 else 1,
            startStop = startStop,
            endStop = endStop,
            firstTime = firstTime,
            lastTime = lastTime,
            price = price,
            source = DataSourceType.MYGOLBS,
            mygolbsCityCode = cityCode,
            mygolbsCityName = cityName
        )

        val revDirInt = if (dirStr == "1") 2 else 1
        val reverseDirection = BusLineDirection(
            lineId = "mg:$cityCode:$cityName:$lineName:$revDirInt",
            name = lineName,
            lineNo = lineName,
            direction = if (dirStr == "1") 1 else 0,
            startStop = endStop,
            endStop = startStop,
            firstTime = firstTime,
            lastTime = lastTime,
            price = price,
            source = DataSourceType.MYGOLBS,
            mygolbsCityCode = cityCode,
            mygolbsCityName = cityName
        )

        BusLineDetail(
            line = currentDirection,
            reverseDirection = reverseDirection,
            stops = stopsList,
            buses = liveBuses,
            operatingState = operatingState,
            statusDescription = statusDescription,
            targetOrder = targetOrder ?: 0,
            updatedAtMillis = System.currentTimeMillis()
        )
    }
}
