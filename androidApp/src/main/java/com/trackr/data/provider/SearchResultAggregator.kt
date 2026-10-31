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
