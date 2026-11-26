package com.trackr.core.domain.tracking

import com.trackr.core.model.CandidateLine
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.model.WatchLine

/**
 * 站点页「收藏」一组一起等的线（第 21 步，v3「选几条一起等」）：存成只有 1 程、没有下车站的路线；
 * 下次开始这条路线时按一起等追（[TrackingEngine.begin]）。两个方向的换算都在这里，保证存进去的能原样读回。
 */
object WatchTogetherRoute {

    /** 收藏里的一条线还要带的展示信息（[WatchLine] 里没有的） */
    data class LineExtras(val startStopName: String = "")

    /**
     * 一起等目标变成 1 程路线。id 按「城市 + 站 + 线」固定：同一组线再存只会更新，不会多出一条。
     * 上车站坐标不写（[TransitLeg] 里是 GCJ-02，一起等目标里是 WGS-84，不能混用）。
     *
     * @param extras lineId -> 展示信息；没有的用默认值
     */
    fun toRoute(
        target: TrackingTarget.WatchTogether,
        extras: Map<String, LineExtras> = emptyMap()
    ): TransitGroup {
        val stopKey = target.stopId.ifBlank { target.physicalStId.orEmpty() }
        val lineKey = target.lines.map { it.lineId }.sorted().joinToString("-")
        val candidates = target.lines.map { line ->
            CandidateLine(
                cityId = target.cityId,
                cityName = target.cityName,
                lineId = line.lineId,
                lineName = line.lineName,
                source = line.source,
                direction = line.direction,
                fromStopOrder = line.stopOrder,
                toStopOrder = 0,
                startStopName = extras[line.lineId]?.startStopName.orEmpty(),
                endStopName = line.endStopName,
                mygolbsCityCode = target.mygolbsCityCode.ifBlank { null }
            )
        }
        return TransitGroup(
            id = "watch_${target.cityId}_${stopKey}_$lineKey",
            name = "${target.stopName} 一起等 ${target.lines.joinToString("、") { it.lineName }}",
            category = GroupCategory.CUSTOM,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = target.stopName,
                    fromStopId = target.stopId,
                    toStopName = "",
                    toStopId = "",
                    candidateLines = candidates
                )
            ),
            isTemporary = false,
            customGroupId = null
        )
    }

    /** 是不是「1 程、没有下车站」的一起等路线 */
    fun isWatchRoute(group: TransitGroup): Boolean {
        val leg = group.legs.singleOrNull() ?: return false
        return leg.toStopId.isBlank() && leg.toStopName.isBlank() && leg.candidateLines.isNotEmpty()
    }

    /** 1 程无下车站的路线换回一起等目标；别的路线返回 null */
    fun watchTargetOf(group: TransitGroup): TrackingTarget.WatchTogether? {
        if (!isWatchRoute(group)) return null
        val leg = group.legs.single()
        val first = leg.candidateLines.first()
        return TrackingTarget.WatchTogether(
            cityId = first.cityId,
            stopId = leg.fromStopId,
            stopName = leg.fromStopName,
            lines = leg.candidateLines.map { line ->
                WatchLine(
                    lineId = line.lineId,
                    lineName = line.lineName,
                    stopOrder = line.fromStopOrder,
                    endStopName = line.endStopName,
                    direction = line.direction,
                    source = line.source,
                    stopIdOnLine = leg.fromStopId
                )
            },
            cityName = first.cityName,
            mygolbsCityCode = leg.candidateLines.firstNotNullOfOrNull { it.mygolbsCityCode?.takeIf { c -> c.isNotBlank() } }.orEmpty()
        )
    }
}
