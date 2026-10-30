package com.trackr.data.parser

import android.net.Uri
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.UUID

/**
 * 高德地图与百度地图分享方案解析与本地公交线路对齐引擎
 */
object MapShareParser {

    private val URL_REGEX = Regex("https?://[a-zA-Z0-9.-]*(?:amap\\.com|gaode\\.com|baidu\\.com)[a-zA-Z0-9/._?=&%#~-]*")

    /**
     * 解析结果中的单程原始段
     */
    data class RawLeg(
        val fromStop: String,
        val toStop: String,
        val lineName: String
    )

    /**
     * 原始解析结果
     */
    data class ParsedRoutePlan(
        val originName: String,
        val destinationName: String,
        val legs: List<RawLeg>,
        val resolvedUrl: String? = null
    )

    /**
     * 检测文本中是否包含地图分享特征
     */
    fun isMapShareContent(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return lower.contains("高德地图") || lower.contains("百度地图") ||
                lower.contains("amap.com") || lower.contains("baidu.com") ||
                lower.contains("gaode.com") ||
                ((lower.contains("从") || lower.contains("起点")) && (lower.contains("到") || lower.contains("终点") || lower.contains("至")) &&
                        (lower.contains("乘坐") || lower.contains("换乘") || lower.contains("转乘") || lower.contains("乘") || lower.contains("坐") || lower.contains("路") || lower.contains("号线") || lower.contains("线"))) ||
                (lower.contains("->") || lower.contains("➔") || lower.contains("-->"))
    }

    /**
     * 从文本中提取高德/百度短链接或普通链接
     */
    fun extractMapUrl(text: String): String? {
        return URL_REGEX.find(text)?.value
    }

    /**
     * 纯文本模式解析高德与百度地图分享路线
     */
    fun parseShareText(text: String): ParsedRoutePlan? {
        if (text.isBlank()) return null

        val cleanText = text
            .replace(Regex("【(?:高德地图|百度地图|公交路线|路线规划|行程分享)[^】]*】"), " ")
            .replace(Regex("(?:我用高德地图分享了一个路线|我在高德地图分享了路线|我分享了这条路线给你|快来看看吧|复制后打开)[，,!！:：]?"), " ")
            .replace("\n", " ")
            .trim()

        // 1. 尝试提取全局起点和终点
        var globalOrigin = ""
        var globalDest = ""

        // 模式 1: "从 A 到/至/到达 B"
        val fromToRegex = Regex("从\\s*([^\\s,，到至【】\\[\\]]+?)\\s*(?:到|至|到达)\\s*([^\\s,，。:：乘坐乘坐【】\\[\\]]+)")
        val fromToMatch = fromToRegex.find(cleanText)
        if (fromToMatch != null) {
            globalOrigin = fromToMatch.groupValues[1].trim()
            globalDest = fromToMatch.groupValues[2].trim()
        }

        // 模式 2: "起点：A，终点：B" 或 "起点: A 终点: B"
        if (globalOrigin.isEmpty() || globalDest.isEmpty()) {
            val endpointRegex = Regex("起点\\s*[:：]\\s*([^\\s,，。]+)[\\s,，。]+终点\\s*[:：]\\s*([^\\s,，。]+)")
            val endpointMatch = endpointRegex.find(cleanText)
            if (endpointMatch != null) {
                globalOrigin = endpointMatch.groupValues[1].trim()
                globalDest = endpointMatch.groupValues[2].trim()
            }
        }

        // 模式 3: "A -> B" 或 "A ➔ B" 或 "A 至 B"
        if (globalOrigin.isEmpty() || globalDest.isEmpty()) {
            val arrowRegex = Regex("([^\\s,，。]+)\\s*(?:->|➔|至)\\s*([^\\s,，。]+)")
            val arrowMatch = arrowRegex.find(cleanText)
            if (arrowMatch != null) {
                val candidate1 = arrowMatch.groupValues[1].trim()
                val candidate2 = arrowMatch.groupValues[2].trim()
                if (!candidate1.contains("http") && !candidate2.contains("http")) {
                    globalOrigin = candidate1
                    globalDest = candidate2
                }
            }
        }

        globalOrigin = cleanTextStopName(globalOrigin)
        globalDest = cleanTextStopName(globalDest)

        val rawLegs = mutableListOf<RawLeg>()

        // 2. 尝试换乘多段提取
        // 匹配格式如："乘坐 地铁1号线(xxx) 到 龙翔桥 ，在 龙翔桥 换乘 7路 到 灵隐"
        // 或 "乘坐 156路 到 武林广场 ，换乘 地铁4号线 到 市民中心"
        val transferPattern = Regex(
            "(?:乘坐|搭乘|乘|坐)\\s*([^\\s,，(（到]+)(?:\\([^)]*\\)|（[^）]*）)?(?:\\s*(?:在|到)\\s*([^\\s,，到]+))?\\s*(?:[，,]|\\s)*(?:在\\s*[^\\s,，到]+?\\s*)?(?:换乘|转乘|转)\\s*([^\\s,，(（到]+)(?:\\([^)]*\\)|（[^）]*）)?\\s*到\\s*([^\\s,，。]+)"
        )
        val transferMatch = transferPattern.find(cleanText)

        if (transferMatch != null) {
            val line1 = cleanLineName(transferMatch.groupValues[1].trim())
            val transferStopRaw = cleanTextStopName(transferMatch.groupValues[2].trim())
            val line2 = cleanLineName(transferMatch.groupValues[3].trim())
            val destStop = cleanTextStopName(transferMatch.groupValues[4].trim())

            val origin = globalOrigin.ifEmpty { "起点站" }
            val transferStop = transferStopRaw.ifEmpty { "换乘站" }
            rawLegs.add(RawLeg(fromStop = origin, toStop = transferStop, lineName = line1))
            rawLegs.add(RawLeg(fromStop = transferStop, toStop = destStop, lineName = line2))
        } else {
            // 模式 B: 单程直达 "乘坐 LINE 到 STOP"
            val singlePattern = Regex("(?:乘坐|搭乘|乘|坐)\\s*([^\\s,，(（到。]+)(?:\\([^)]*\\)|（[^）]*）)?(?:\\s*(?:在|到)\\s*([^\\s,，。]+))?")
            val singleMatch = singlePattern.find(cleanText)
            if (singleMatch != null) {
                val line = cleanLineName(singleMatch.groupValues[1].trim())
                val target = cleanTextStopName(singleMatch.groupValues.getOrNull(2)?.trim().orEmpty())
                val from = globalOrigin.ifEmpty { "起点站" }
                val to = target.ifEmpty { globalDest.ifEmpty { "终点站" } }
                rawLegs.add(RawLeg(fromStop = from, toStop = to, lineName = line))
            } else if (globalOrigin.isNotEmpty() && globalDest.isNotEmpty()) {
                // 仅识别出起终点，检查中间是否有类似 "156路"、"地铁1号线" 的线路关键字
                val lineCandidate = extractLineNameFromText(cleanText)
                rawLegs.add(RawLeg(fromStop = globalOrigin, toStop = globalDest, lineName = lineCandidate))
            }
        }

        if (rawLegs.isEmpty()) return null

        val finalOrigin = globalOrigin.ifEmpty { rawLegs.first().fromStop }
        val finalDest = globalDest.ifEmpty { rawLegs.last().toStop }

        return ParsedRoutePlan(
            originName = cleanTextStopName(finalOrigin),
            destinationName = cleanTextStopName(finalDest),
            legs = rawLegs.map {
                it.copy(
                    fromStop = cleanTextStopName(it.fromStop),
                    toStop = cleanTextStopName(it.toStop),
                    lineName = cleanLineName(it.lineName)
                )
            }
        )
    }

    private fun cleanLineName(raw: String): String {
        return raw.replace(Regex("\\([^)]*\\)|（[^）]*）"), "")
            .replace(Regex("^(?:乘坐|乘|坐|搭乘)"), "")
            .trim()
    }

    private fun extractLineNameFromText(text: String): String {
        val lineRegex = Regex("(?:地铁)?\\d+号线|[0-9a-zA-Z]+路|快速公交[0-9a-zA-Z]+(?:线)?|B\\d+(?:线|路)?")
        val match = lineRegex.find(text)
        return match?.value?.trim() ?: "待选公交"
    }

    /**
     * 规范化剥离站名多余词缀
     */
    private fun cleanTextStopName(name: String): String {
        return name.replace(Regex("^(?:到达|前往|到达的|在)?\\s*"), "")
            .replace(Regex("(?:下车|上车)$"), "")
            .replace(Regex("[【】\\[\\]]"), "")
            .trim()
    }

    /**
     * 异步跟踪短链接 301/302 多级重定向并提取深层目标 URL 或自定义 scheme
     */
    suspend fun resolveUrlRedirect(shortUrl: String, client: OkHttpClient): String? = withContext(Dispatchers.IO) {
        try {
            var currentUrl = shortUrl
            var hops = 0
            val maxHops = 5

            val noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            while (hops < maxHops) {
                hops++
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    .build()

                val response = noRedirectClient.newCall(request).execute()
                val statusCode = response.code
                val location = response.header("Location")
                val responseBody = runCatching { response.body.string() }.getOrNull()
                response.close()

                if (!location.isNullOrBlank()) {
                    if (location.startsWith("android-amap://") || location.startsWith("baidumap://")) {
                        return@withContext location
                    }
                    val nextUrl = currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
                    currentUrl = nextUrl
                    if (nextUrl.contains("sname=") || nextUrl.contains("origin=") || nextUrl.contains("dname=")) {
                        return@withContext nextUrl
                    }
                } else if (statusCode == 200) {
                    if (!responseBody.isNullOrBlank()) {
                        // 尝试从 HTML meta refresh 或 deep link 提取
                        val metaRefresh = Regex("url=([^\"'>\\s]+)", RegexOption.IGNORE_CASE).find(responseBody)
                        if (metaRefresh != null) {
                            val target = metaRefresh.groupValues[1]
                            currentUrl = currentUrl.toHttpUrlOrNull()?.resolve(target)?.toString() ?: target
                            continue
                        }
                        val deepLink = Regex("href=[\"'](android-amap://[^\"']+|baidumap://[^\"']+)[\"']").find(responseBody)
                        if (deepLink != null) {
                            return@withContext deepLink.groupValues[1]
                        }
                    }
                    return@withContext currentUrl
                } else {
                    break
                }
            }
            currentUrl
        } catch (_: Exception) {
            shortUrl
        }
    }

    /**
     * 从解析出的高德/百度 URL 参数中补全信息
     */
    fun enrichPlanFromUrl(plan: ParsedRoutePlan?, resolvedUrl: String?): ParsedRoutePlan? {
        if (resolvedUrl.isNullOrBlank()) return plan

        val uri = runCatching { Uri.parse(resolvedUrl) }.getOrNull() ?: return plan

        fun getDecodedParam(vararg keys: String): String? {
            for (key in keys) {
                var value = uri.getQueryParameter(key)
                if (value.isNullOrBlank() && uri.fragment != null && uri.fragment!!.contains("?")) {
                    val fragmentQuery = uri.fragment!!.substringAfter("?")
                    val fragmentUri = runCatching { Uri.parse("https://dummy.com/?$fragmentQuery") }.getOrNull()
                    value = fragmentUri?.getQueryParameter(key)
                }
                if (!value.isNullOrBlank()) {
                    val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                    // 兼容百度地图形如 "name:天安门|latlng:39.9,116.4" 或 "latlng:39.9,116.4|name:天安门"
                    if (decoded.contains("name:")) {
                        val after = decoded.substringAfter("name:")
                        val name = after.substringBefore("|").substringBefore("&").trim()
                        if (name.isNotEmpty()) return name
                    }
                    if (decoded.contains("|")) {
                        val nonCoord = decoded.split("|").firstOrNull { !it.contains(",") && !it.contains("latlng") }
                        if (!nonCoord.isNullOrBlank()) return nonCoord.trim()
                    }
                    return decoded.trim()
                }
            }
            return null
        }

        val urlOrigin = getDecodedParam("sname", "sn", "from", "origin", "start", "start_name", "startPoint")
        val urlDest = getDecodedParam("dname", "dn", "to", "destination", "dest", "end", "end_name", "endPoint")
        val urlLine = getDecodedParam("line", "lines", "busline", "linename")

        val newOrigin = if (!urlOrigin.isNullOrBlank()) cleanTextStopName(urlOrigin) else plan?.originName.orEmpty()
        val newDest = if (!urlDest.isNullOrBlank()) cleanTextStopName(urlDest) else plan?.destinationName.orEmpty()
        val lineName = if (!urlLine.isNullOrBlank()) cleanLineName(urlLine) else "待选公交"

        if (plan == null) {
            if (newOrigin.isNotBlank() && newDest.isNotBlank()) {
                return ParsedRoutePlan(
                    originName = newOrigin,
                    destinationName = newDest,
                    legs = listOf(RawLeg(fromStop = newOrigin, toStop = newDest, lineName = lineName)),
                    resolvedUrl = resolvedUrl
                )
            }
            return null
        }

        return plan.copy(
            originName = newOrigin.ifEmpty { plan.originName },
            destinationName = newDest.ifEmpty { plan.destinationName },
            resolvedUrl = resolvedUrl
        )
    }

    /**
     * 全流程解析与城市公交实际线路对齐，生成结构化临时路线分组
     */
    suspend fun parseAndAlign(
        text: String,
        defaultCity: TransitCity,
        coordinator: TransitProviderCoordinator?,
        client: OkHttpClient? = null
    ): TransitGroup? = withContext(Dispatchers.IO) {
        var plan = parseShareText(text)
        val extractedUrl = extractMapUrl(text)

        if (extractedUrl != null && client != null) {
            val resolvedUrl = resolveUrlRedirect(extractedUrl, client)
            plan = enrichPlanFromUrl(plan, resolvedUrl)
        }

        if (plan == null || plan.legs.isEmpty()) {
            return@withContext null
        }

        val alignedLegs = mutableListOf<TransitLeg>()

        for ((index, rawLeg) in plan.legs.withIndex()) {
            val candidates = mutableListOf<CandidateLine>()
            var fromStopLat = 0.0
            var fromStopLng = 0.0
            var toStopLat = 0.0
            var toStopLng = 0.0

            if (coordinator != null) {
                try {
                    // 若有明确线路名称，优先按线路搜索
                    val queryLine = if (rawLeg.lineName.isNotBlank() && rawLeg.lineName != "待选公交") rawLeg.lineName else ""
                    if (queryLine.isNotEmpty()) {
                        val searchResult = coordinator.search(
                            city = defaultCity,
                            keyword = queryLine,
                            mode = com.trackr.domain.model.EngineMode.CHELAILE
                        )

                        // 宽容匹配线路名
                        val cleanQuery = queryLine.replace("路", "").replace("号线", "").replace("地铁", "")
                        val matchedSummaries = searchResult.lines.filter { line ->
                            val cleanLine = line.name.replace("路", "").replace("号线", "").replace("地铁", "")
                            cleanLine.contains(cleanQuery, ignoreCase = true) || cleanQuery.contains(cleanLine, ignoreCase = true)
                        }

                        for (summary in matchedSummaries.take(3)) {
                            for (dir in summary.directions) {
                                val detail = runCatching {
                                    coordinator.getLineDetail(
                                        city = defaultCity,
                                        request = com.trackr.data.provider.model.LineDetailRequest(
                                            lineId = dir.lineId,
                                            source = dir.source,
                                            lineNameHint = dir.name,
                                            directionHint = dir.direction
                                        )
                                    )
                                }.getOrNull()

                                if (detail != null && detail.stops.isNotEmpty()) {
                                    val stops = detail.stops
                                    val fromCore = RouteProjectionMath.coreStopName(rawLeg.fromStop)
                                    val toCore = RouteProjectionMath.coreStopName(rawLeg.toStop)

                                    val fromStop = stops.find {
                                        RouteProjectionMath.coreStopName(it.name) == fromCore || it.name.contains(fromCore)
                                    }
                                    val toStop = stops.find {
                                        RouteProjectionMath.coreStopName(it.name) == toCore || it.name.contains(toCore)
                                    }

                                    if (fromStop != null && toStop != null && fromStop.order < toStop.order) {
                                        if (fromStopLat == 0.0) {
                                            fromStopLat = fromStop.wgsLat
                                            fromStopLng = fromStop.wgsLng
                                            toStopLat = toStop.wgsLat
                                            toStopLng = toStop.wgsLng
                                        }
                                        candidates.add(
                                            CandidateLine(
                                                cityId = defaultCity.cityId,
                                                cityName = defaultCity.cityName,
                                                lineId = dir.lineId,
                                                lineName = dir.name,
                                                source = dir.source,
                                                direction = dir.direction,
                                                fromStopOrder = fromStop.order,
                                                toStopOrder = toStop.order,
                                                startStopName = dir.startStop,
                                                endStopName = dir.endStop,
                                                mygolbsCityCode = defaultCity.mygolbsCityCode
                                            )
                                        )
                                        break
                                    } else if (candidates.isEmpty()) {
                                        // 线路匹配成功但站点未完全匹配，保留真实 lineId 兜底
                                        candidates.add(
                                            CandidateLine(
                                                cityId = defaultCity.cityId,
                                                cityName = defaultCity.cityName,
                                                lineId = dir.lineId,
                                                lineName = dir.name,
                                                source = dir.source,
                                                direction = dir.direction,
                                                fromStopOrder = 1,
                                                toStopOrder = stops.size.coerceAtLeast(2),
                                                startStopName = dir.startStop,
                                                endStopName = dir.endStop,
                                                mygolbsCityCode = defaultCity.mygolbsCityCode
                                            )
                                        )
                                    }
                                }
                            }
                            if (candidates.isNotEmpty()) break
                        }
                    }

                    // 若未提供线路名或线路名未查到，尝试按起点站检索经过的线路
                    if (candidates.isEmpty() && rawLeg.fromStop.isNotBlank() && rawLeg.fromStop != "起点站") {
                        val stopSearch = coordinator.search(
                            city = defaultCity,
                            keyword = rawLeg.fromStop,
                            mode = com.trackr.domain.model.EngineMode.CHELAILE
                        )
                        for (line in stopSearch.lines.take(2)) {
                            val dir = line.directions.firstOrNull() ?: continue
                            candidates.add(
                                CandidateLine(
                                    cityId = defaultCity.cityId,
                                    cityName = defaultCity.cityName,
                                    lineId = dir.lineId,
                                    lineName = dir.name,
                                    source = dir.source,
                                    direction = dir.direction,
                                    fromStopOrder = 1,
                                    toStopOrder = 2,
                                    startStopName = dir.startStop,
                                    endStopName = dir.endStop,
                                    mygolbsCityCode = defaultCity.mygolbsCityCode
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            // 若网络检索完全无匹配，兜底为待选线路
            if (candidates.isEmpty()) {
                candidates.add(
                    CandidateLine(
                        cityId = defaultCity.cityId,
                        cityName = defaultCity.cityName,
                        lineId = "parsed_${UUID.randomUUID().toString().take(8)}",
                        lineName = rawLeg.lineName.ifEmpty { "待选公交" },
                        source = DataSourceType.CHELAILE,
                        direction = 0,
                        fromStopOrder = 1,
                        toStopOrder = 2,
                        startStopName = rawLeg.fromStop,
                        endStopName = rawLeg.toStop,
                        mygolbsCityCode = defaultCity.mygolbsCityCode
                    )
                )
            }

            alignedLegs.add(
                TransitLeg(
                    legIndex = index,
                    fromStopName = rawLeg.fromStop,
                    toStopName = rawLeg.toStop,
                    fromStopLat = fromStopLat,
                    fromStopLng = fromStopLng,
                    toStopLat = toStopLat,
                    toStopLng = toStopLng,
                    candidateLines = candidates
                )
            )
        }

        val groupTitle = if (plan.originName.isNotBlank() && plan.destinationName.isNotBlank()) {
            "${plan.originName} ➔ ${plan.destinationName}"
        } else {
            "地图导入路线"
        }

        TransitGroup(
            id = "imported_${UUID.randomUUID()}",
            name = groupTitle,
            category = GroupCategory.TEMPORARY,
            legs = alignedLegs,
            isTemporary = true,
            sortOrder = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
}
