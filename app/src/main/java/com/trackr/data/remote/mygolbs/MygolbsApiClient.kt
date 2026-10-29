package com.trackr.data.remote.mygolbs

import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.LiveBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 掌上公交（Mygolbs）全国实时公交直连 API 客户端
 * 基于 H5 数据面协议实现，严格携带 Origin 与 Referer 头，支持纯本地直连
 */
class MygolbsApiClient(private val httpClient: OkHttpClient) {

    companion object {
        const val API_URL = "https://h5.mygolbs.com/ApiData.do"
        const val ORIGIN = "https://h5.mygolbs.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val PRICE_REGEX = Regex("""票价[:：]\s*([^\s，,]+)""")

        private val DEFAULT_CITIES = mapOf(
            "玉林市" to "450900",
            "北流市" to "450981",
            "南宁市" to "450100",
            "柳州市" to "450200",
            "桂林市" to "450300"
        )
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

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Mygolbs HTTP error ${response.code}")
        }
        val bodyStr = response.body?.string() ?: throw IOException("Empty response body from Mygolbs")

        val json = JSONObject(bodyStr)
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
                    val name = item.optString("cityName")
                        .ifBlank { item.optString("CITYNAME") }
                    val code = item.optString("cityCode")
                        .ifBlank { item.optString("CITYCODE") }
                        .ifBlank { item.optString("code") }
                    if (name.isNotBlank() && code.isNotBlank()) {
                        result[name] = code
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
    suspend fun searchLines(cityCode: String, cityName: String, keyword: String): List<BusLineSummary> = withContext(Dispatchers.IO) {
        val results = ArrayList<BusLineSummary>()
        try {
            val json = try {
                callApi(
                    mapOf(
                        "CMD" to "114",
                        "CITYCODE" to cityCode,
                        "CITYNAME" to cityName,
                        "LINENAME" to keyword
                    )
                )
            } catch (_: Exception) {
                callApi(
                    mapOf(
                        "CMD" to "102",
                        "CITYCODE" to cityCode,
                        "CITYNAME" to cityName,
                        "LINENAME" to keyword
                    )
                )
            }

            val dataArr = json.optJSONArray("data") ?: json.optJSONArray("list") ?: JSONArray()
            val seenLines = HashSet<String>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val lineName = item.optString("lineName").ifBlank { item.optString("LINENAME") }
                if (lineName.isBlank() || !seenLines.add(lineName)) continue

                val startStation = item.optString("startStation").ifBlank { item.optString("firstStation") }
                val endStation = item.optString("endStation").ifBlank { item.optString("lastStation") }

                val dir1 = BusLineDirection(
                    lineId = "mg:$cityCode:$cityName:$lineName:1",
                    name = lineName,
                    lineNo = lineName,
                    direction = 0,
                    startStop = startStation,
                    endStop = endStation,
                    source = DataSourceType.MYGOLBS,
                    mygolbsCityCode = cityCode,
                    mygolbsCityName = cityName
                )
                val dir2 = BusLineDirection(
                    lineId = "mg:$cityCode:$cityName:$lineName:2",
                    name = lineName,
                    lineNo = lineName,
                    direction = 1,
                    startStop = endStation,
                    endStop = startStation,
                    source = DataSourceType.MYGOLBS,
                    mygolbsCityCode = cityCode,
                    mygolbsCityName = cityName
                )

                results.add(
                    BusLineSummary(
                        name = lineName,
                        lineNo = lineName,
                        isSubway = false,
                        directions = listOf(dir1, dir2),
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
     * 获取掌上公交线路详情（解析 mg:${cityCode}:${cityName}:${lineName}:${dir}）
     * 调用 CMD=103 获取站表/首末班/票价，调用 CMD=104 获取在途车辆及到站时间，使用 RouteProjectionMath 计算站间比例
     */
    suspend fun getLineDetail(lineId: String, targetOrder: Int? = null): BusLineDetail = withContext(Dispatchers.IO) {
        val parts = lineId.removePrefix("mg:").split(":")
        if (parts.size < 4) {
            throw IllegalArgumentException("Invalid Mygolbs lineId: $lineId. Expected format: mg:cityCode:cityName:lineName:dir")
        }
        val cityCode = parts[0]
        val cityName = parts[1]
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
                "DIRECTION" to dirStr
            )
        )

        val commonts = routeJson.optString("commonts")
        val priceMatch = PRICE_REGEX.find(commonts)
        val price = priceMatch?.groupValues?.getOrNull(1) ?: ""

        val firstLastArr = routeJson.optJSONArray("firstLast")
        val firstTime = firstLastArr?.optJSONObject(0)?.optString("first") ?: ""
        val lastTime = firstLastArr?.optJSONObject(0)?.optString("last") ?: ""

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
                "STATIONORDER" to effectiveTargetOrder.toString()
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
                    plateOrFleetNo = busNumber,
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
