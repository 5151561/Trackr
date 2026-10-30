package com.trackr.data.provider

import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TransitSearchResult
import com.trackr.domain.model.isPureSubwayStation

/**
 * 实时公交多源检索结果聚合器
 * 负责单源/多源结果合并、同名打标（[掌上公交] / (掌上公交)）、纯地铁过滤以及自然排序
 */
class SearchResultAggregator {

    fun aggregate(
        keyword: String,
        mode: EngineMode,
        chelaileResult: TransitSearchResult? = null,
        mygolbsResult: TransitSearchResult? = null
    ): TransitSearchResult {
        val mergedLines = ArrayList<BusLineSummary>()
        val mergedStops = ArrayList<NearbyStop>()

        when (mode) {
            EngineMode.MYGOLBS -> {
                if (mygolbsResult != null) {
                    mergedLines.addAll(mygolbsResult.lines)
                    mergedStops.addAll(mygolbsResult.stops)
                }
            }
            EngineMode.CHELAILE -> {
                if (chelaileResult != null) {
                    mergedLines.addAll(chelaileResult.lines)
                    mergedStops.addAll(chelaileResult.stops)
                }
            }
            EngineMode.ALL -> {
                val clLines = chelaileResult?.lines.orEmpty()
                val clStops = chelaileResult?.stops.orEmpty()
                val mgLines = mygolbsResult?.lines.orEmpty()
                val mgStops = mygolbsResult?.stops.orEmpty()

                // 合并线路：以车来了线路为基准，掌上公交同名线路追加 " [掌上公交]" 后缀并存展示
                mergedLines.addAll(clLines)
                val existingLineNames = clLines.map { it.name.trim() }.toSet()
                for (mgLine in mgLines) {
                    val rawName = mgLine.name.removeSuffix(" [掌上公交]").removeSuffix("[掌上公交]").trim()
                    if (existingLineNames.contains(rawName)) {
                        mergedLines.add(mgLine.copy(name = "$rawName [掌上公交]"))
                    } else {
                        mergedLines.add(mgLine)
                    }
                }

                // 合并站点：以车来了站点为基准，掌上公交同名站点追加 " (掌上公交)" 后缀并存展示
                mergedStops.addAll(clStops)
                val existingStopNames = clStops.map { it.name.trim() }.toSet()
                for (mgStop in mgStops) {
                    val rawName = mgStop.name.removeSuffix(" (掌上公交)").removeSuffix("(掌上公交)").removeSuffix("（掌上公交）").trim()
                    if (existingStopNames.contains(rawName)) {
                        mergedStops.add(mgStop.copy(name = "$rawName (掌上公交)"))
                    } else {
                        mergedStops.add(mgStop)
                    }
                }
            }
        }

        // 过滤纯地铁线路（第三方源无在途实时列车追踪数据）
        mergedLines.removeAll { it.isSubway || it.name.contains("地铁") }

        // 按线路名自然排序
        mergedLines.sortWith { a, b ->
            RouteProjectionMath.compareLineNames(a.name, b.name)
        }

        // 过滤纯地铁站点（仅保留地面公交站点）
        mergedStops.removeAll { it.isPureSubwayStation }

        return TransitSearchResult(
            keyword = keyword,
            lines = mergedLines,
            stops = mergedStops
        )
    }
}
