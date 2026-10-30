package com.trackr.data.provider

import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.AggregatedSourcePriority
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TransitSearchResult
import com.trackr.domain.model.isPureSubwayStation

/**
 * 实时公交多源检索结果聚合器
 * 负责单源/多源结果合并、同名打标（根据优先级为主源保留原名，副源追加 [掌上公交]/[车来了] 标识）、纯地铁过滤以及自然排序
 */
class SearchResultAggregator {

    fun aggregate(
        keyword: String,
        mode: EngineMode,
        chelaileResult: TransitSearchResult? = null,
        mygolbsResult: TransitSearchResult? = null,
        priority: AggregatedSourcePriority = AggregatedSourcePriority.CHELAILE_FIRST
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
                val primaryResult = if (priority.primarySource == DataSourceType.CHELAILE) chelaileResult else mygolbsResult
                val secondaryResult = if (priority.primarySource == DataSourceType.CHELAILE) mygolbsResult else chelaileResult
                val secondaryLineTag = if (priority.primarySource == DataSourceType.CHELAILE) " [掌上公交]" else " [车来了]"
                val secondaryStopTag = if (priority.primarySource == DataSourceType.CHELAILE) " (掌上公交)" else " (车来了)"

                val primaryLines = primaryResult?.lines.orEmpty()
                val primaryStops = primaryResult?.stops.orEmpty()
                val secondaryLines = secondaryResult?.lines.orEmpty()
                val secondaryStops = secondaryResult?.stops.orEmpty()

                fun cleanLineName(name: String): String =
                    name.removeSuffix(" [掌上公交]").removeSuffix("[掌上公交]")
                        .removeSuffix(" [车来了]").removeSuffix("[车来了]").trim()

                fun cleanStopName(name: String): String =
                    name.removeSuffix(" (掌上公交)").removeSuffix("(掌上公交)").removeSuffix("（掌上公交）")
                        .removeSuffix(" (车来了)").removeSuffix("(车来了)").removeSuffix("（车来了）").trim()

                // 主源线路优先放入
                mergedLines.addAll(primaryLines)
                val existingLineNames = primaryLines.map { cleanLineName(it.name) }.toSet()
                for (sLine in secondaryLines) {
                    val rawName = cleanLineName(sLine.name)
                    if (existingLineNames.contains(rawName)) {
                        mergedLines.add(sLine.copy(name = "$rawName$secondaryLineTag"))
                    } else {
                        mergedLines.add(sLine)
                    }
                }

                // 主源站点优先放入
                mergedStops.addAll(primaryStops)
                val existingStopNames = primaryStops.map { cleanStopName(it.name) }.toSet()
                for (sStop in secondaryStops) {
                    val rawName = cleanStopName(sStop.name)
                    if (existingStopNames.contains(rawName)) {
                        mergedStops.add(sStop.copy(name = "$rawName$secondaryStopTag"))
                    } else {
                        mergedStops.add(sStop)
                    }
                }
            }
        }

        // 过滤纯地铁线路（第三方源无在途实时列车追踪数据）
        mergedLines.removeAll { it.isSubway || it.name.contains("地铁") }

        // 按线路名自然排序（同名同号时主源排在前面）
        mergedLines.sortWith { a, b ->
            val cmp = RouteProjectionMath.compareLineNames(a.name, b.name)
            if (cmp != 0) {
                cmp
            } else {
                if (a.source == priority.primarySource && b.source != priority.primarySource) -1
                else if (b.source == priority.primarySource && a.source != priority.primarySource) 1
                else 0
            }
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
