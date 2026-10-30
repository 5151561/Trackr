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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.UUID

/**
 * 高德地图与百度地图分享方案解析与本地公交线路对齐引擎
 */
object MapShareParser {

    private val URL_REGEX = Regex("https?://[a-zA-Z0-9.-]*(?:amap\\.com|baidu\\.com)[a-zA-Z0-9/._?=&%#-]*")

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
        val containsMapKeyword = text.contains("高德地图") || text.contains("百度地图") ||
                text.contains("amap.com") || text.contains("baidu.com") ||
                (text.contains("从") && text.contains("到") && (text.contains("乘坐") || text.contains("换乘")))
        return containsMapKeyword
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

        val cleanText = text.replace("\n", " ").trim()

        // 1. 尝试提取全局「从 A 到 B」起点和终点
        val overallRegex = Regex("从\\s*([^\\s,，到]+?)\\s*到\\s*([^\\s,，。:：乘坐]+?)(?:[。:：,，]|乘坐|\\s)")
        val overallMatch = overallRegex.find(cleanText)
        val globalOrigin = overallMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val globalDest = overallMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()

        val rawLegs = mutableListOf<RawLeg>()

        // 2. 尝试多段换乘提取：
        // 模式 A: "乘坐 LINE1 [方向] [到 STOP_TRANS]，在 STOP_TRANS [换乘|转乘] LINE2 到 STOP_DEST"
        // 模式 B: "乘坐 LINE1 到 STOP_TRANS ，换乘 LINE2 到 STOP_DEST"
        // 模式 C: "乘坐 LINE1 ，换乘 LINE2 到 STOP_DEST"
        val transferPattern = Regex(
            "乘坐\\s*([^\\s,，(（到]+)(?:\\([^)]*\\)|（[^）]*）)?(?:\\s*(?:在|到)\\s*([^\\s,，到]+))?\\s*(?:[，,]|\\s)*(?:在\\s*[^\\s,，到]+?\\s*)?(?:换乘|转乘)\\s*([^\\s,，(（到]+)(?:\\([^)]*\\)|（[^）]*）)?\\s*到\\s*([^\\s,，。]+)"
        )
        val transferMatch = transferPattern.find(cleanText)

        if (transferMatch != null) {
            val line1 = transferMatch.groupValues[1].trim()
            val transferStopRaw = transferMatch.groupValues[2].trim()
            val line2 = transferMatch.groupValues[3].trim()
            val destStop = transferMatch.groupValues[4].trim()

            val origin = globalOrigin.ifEmpty { "起点站" }
            val transferStop = transferStopRaw.ifEmpty { "换乘站" }
            rawLegs.add(RawLeg(fromStop = origin, toStop = transferStop, lineName = line1))
            rawLegs.add(RawLeg(fromStop = transferStop, toStop = destStop, lineName = line2))
        } else {
            // 模式 D: 单程直达 "乘坐 LINE 到 STOP" 或 "从 A 到 B 乘坐 LINE"
            val singlePattern = Regex("乘坐\\s*([^\\s,，(（到。]+)(?:\\([^)]*\\)|（[^）]*）)?(?:\\s*到\\s*([^\\s,，。]+))?")
            val singleMatch = singlePattern.find(cleanText)
            if (singleMatch != null) {
                val line = singleMatch.groupValues[1].trim()
                val target = singleMatch.groupValues.getOrNull(2)?.trim().orEmpty()
                val from = globalOrigin.ifEmpty { "起点站" }
                val to = target.ifEmpty { globalDest.ifEmpty { "终点站" } }
                rawLegs.add(RawLeg(fromStop = from, toStop = to, lineName = line))
            } else if (globalOrigin.isNotEmpty() && globalDest.isNotEmpty()) {
                // 仅识别出起终点，未识别出明确线路
                rawLegs.add(RawLeg(fromStop = globalOrigin, toStop = globalDest, lineName = "待选公交"))
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
                    toStop = cleanTextStopName(it.toStop)
                )
            }
        )
    }

    /**
     * 规范化剥离站名后缀
     */
    private fun cleanTextStopName(name: String): String {
        return name.replace(Regex("^(?:到达|前往|到达的)?\\s*"), "")
            .replace(Regex("[【】\\[\\]]"), "")
            .trim()
    }

    /**
     * 异步跟踪短链接 302 重定向并提取参数
     */
    suspend fun resolveUrlRedirect(shortUrl: String, client: OkHttpClient): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(shortUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .build()

            // 禁用自动 followRedirects 以便捕获 301/302 Location 头
            val noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val response = noRedirectClient.newCall(request).execute()
            val location = response.header("Location")
            response.close()

            if (!location.isNullOrBlank()) {
                location
            } else {
                // 如果没有 302，则尝试常规请求获取最终的 URL
                val followResponse = client.newCall(request).execute()
                val finalUrl = followResponse.request.url.toString()
                followResponse.close()
                finalUrl
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从解析出的高德/百度 URL 参数中补全信息
     */
    fun enrichPlanFromUrl(plan: ParsedRoutePlan?, resolvedUrl: String?): ParsedRoutePlan? {
        if (resolvedUrl.isNullOrBlank()) return plan

        val uri = runCatching { Uri.parse(resolvedUrl) }.getOrNull() ?: return plan
        // 尝试从常见高德百度 query 参数提取起终点：sname, dname, sn, dn, from, to
        fun getDecodedParam(vararg keys: String): String? {
            for (key in keys) {
                val value = uri.getQueryParameter(key)
                if (!value.isNullOrBlank()) {
                    return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                }
            }
            return null
        }

        val urlOrigin = getDecodedParam("sname", "sn", "from")
        val urlDest = getDecodedParam("dname", "dn", "to")

        val newOrigin = if (!urlOrigin.isNullOrBlank()) cleanTextStopName(urlOrigin) else plan?.originName.orEmpty()
        val newDest = if (!urlDest.isNullOrBlank()) cleanTextStopName(urlDest) else plan?.destinationName.orEmpty()

        if (plan == null) {
            if (newOrigin.isNotBlank() && newDest.isNotBlank()) {
                return ParsedRoutePlan(
                    originName = newOrigin,
                    destinationName = newDest,
                    legs = listOf(RawLeg(fromStop = newOrigin, toStop = newDest, lineName = "待选公交")),
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

            if (coordinator != null && rawLeg.lineName.isNotBlank() && rawLeg.lineName != "待选公交") {
                try {
                    val searchResult = coordinator.search(
                        city = defaultCity,
                        keyword = rawLeg.lineName,
                        mode = com.trackr.domain.model.EngineMode.CHELAILE
                    )
                    val matchedSummaries = searchResult.lines.filter { line ->
                        line.name.contains(rawLeg.lineName, ignoreCase = true) ||
                                rawLeg.lineName.contains(line.name, ignoreCase = true)
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

                            if (detail != null) {
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
                                }
                            }
                        }
                        if (candidates.isNotEmpty()) break
                    }
                } catch (_: Exception) {}
            }

            // 若真实检索未匹配到（离线/网络或路线名有差异），创建兜底 CandidateLine 以供用户确认与修改
            if (candidates.isEmpty()) {
                candidates.add(
                    CandidateLine(
                        cityId = defaultCity.cityId,
                        cityName = defaultCity.cityName,
                        lineId = "parsed_${UUID.randomUUID().toString().take(8)}",
                        lineName = rawLeg.lineName,
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
