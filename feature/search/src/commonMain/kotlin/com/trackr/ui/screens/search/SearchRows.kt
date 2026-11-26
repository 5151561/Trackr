package com.trackr.ui.screens.search

import androidx.compose.runtime.Immutable
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitSearchResult

/** 行在所属卡片里的位置：决定圆角和分隔线（同一段的行画成一张卡，v3 稿） */
enum class RowPosition { Single, First, Middle, Last }

/** 搜索结果列表里的一行（`LazyColumn` 的 item），[key] 在整张列表里唯一 */
@Immutable
sealed interface SearchRow {
    val key: String
    val contentType: String

    /** 段头「线路」「车站」 */
    data class Header(val title: String) : SearchRow {
        override val key: String get() = "header:$title"
        override val contentType: String get() = CONTENT_TYPE_HEADER
    }

    /** 线路的一个方向：点了直接进这个方向的线路详情 */
    data class LineDirection(
        override val key: String,
        /** 徽章上的线路名，如「49路」 */
        val lineName: String,
        /** 「始发 → 终点」 */
        val title: String,
        /** 小字：地铁 · 运营时间 · 票价，有哪项写哪项；都没有为空 */
        val detail: String,
        val direction: BusLineDirection,
        val position: RowPosition
    ) : SearchRow {
        override val contentType: String get() = CONTENT_TYPE_LINE
    }

    /** 一个车站：点了进站点详情 */
    data class Stop(
        override val key: String,
        /** 小字：途经线路「49路 · 71路」，没有就写地铁线名；都没有为空 */
        val lineNames: String,
        /** 「350米」「1.2公里」，没有距离为空 */
        val distance: String,
        val stop: NearbyStop,
        val position: RowPosition
    ) : SearchRow {
        override val contentType: String get() = CONTENT_TYPE_STOP
    }

    companion object {
        const val CONTENT_TYPE_HEADER = "search_header"
        const val CONTENT_TYPE_LINE = "search_line_direction"
        const val CONTENT_TYPE_STOP = "search_stop"
        const val TITLE_LINES = "线路"
        const val TITLE_STOPS = "车站"
    }
}

/**
 * 把搜索结果摊平成列表行：「线路」段每个方向一行，「车站」段每站一行。
 * 接口偶尔会给重复的线路 / 站，key 重复时加序号，免得 `LazyColumn` 因重复 key 崩掉。
 */
fun searchRows(result: TransitSearchResult?): List<SearchRow> {
    if (result == null) return emptyList()
    val rows = mutableListOf<SearchRow>()
    val usedKeys = mutableMapOf<String, Int>()
    fun unique(base: String): String {
        val seen = usedKeys[base]
        usedKeys[base] = (seen ?: 0) + 1
        return if (seen == null) base else "$base#$seen"
    }

    val directions = result.lines.flatMap { summary -> summary.directions.map { summary to it } }
    if (directions.isNotEmpty()) {
        rows += SearchRow.Header(SearchRow.TITLE_LINES)
        directions.forEachIndexed { index, (summary, direction) ->
            val baseKey = direction.lineId.ifBlank { "${summary.name}_${direction.startStop}_${direction.endStop}" }
            rows += SearchRow.LineDirection(
                key = unique("line:${direction.source.name}:$baseKey"),
                lineName = summary.name.ifBlank { direction.name },
                title = directionTitle(direction),
                detail = directionDetail(summary, direction),
                direction = direction,
                position = positionOf(index, directions.size)
            )
        }
    }

    if (result.stops.isNotEmpty()) {
        rows += SearchRow.Header(SearchRow.TITLE_STOPS)
        result.stops.forEachIndexed { index, stop ->
            val baseKey = stop.stopId.ifBlank { "${stop.name}_${stop.wgsLat}_${stop.wgsLng}" }
            rows += SearchRow.Stop(
                key = unique("stop:${stop.source.name}:$baseKey"),
                lineNames = stopLineNames(stop),
                distance = RouteProjectionMath.formatDistanceText(stop.distanceMeters),
                stop = stop,
                position = positionOf(index, result.stops.size)
            )
        }
    }
    return rows
}

/** 「始发 → 终点」；缺始发写「开往 终点」，都缺写线路名 */
internal fun directionTitle(direction: BusLineDirection): String = when {
    direction.startStop.isNotBlank() && direction.endStop.isNotBlank() ->
        "${direction.startStop} → ${direction.endStop}"
    direction.endStop.isNotBlank() -> "开往 ${direction.endStop}"
    else -> direction.name
}

internal fun directionDetail(summary: BusLineSummary, direction: BusLineDirection): String = listOfNotNull(
    "地铁".takeIf { summary.isSubway },
    "${direction.firstTime}-${direction.lastTime}".takeIf {
        direction.firstTime.isNotBlank() && direction.lastTime.isNotBlank()
    },
    direction.price.takeIf { it.isNotBlank() }
).joinToString(" · ")

internal fun stopLineNames(stop: NearbyStop): String {
    val busLines = stop.lines.map { it.line.name }.filter { it.isNotBlank() }
    val subwayLines = stop.subwayLineNames.filter { it.isNotBlank() }
    // 公交线在前，地铁线名接在后面（旧卡片有地铁线时总会显示）
    return (busLines + subwayLines).distinct().joinToString(" · ")
}

private fun positionOf(index: Int, size: Int): RowPosition = when {
    size == 1 -> RowPosition.Single
    index == 0 -> RowPosition.First
    index == size - 1 -> RowPosition.Last
    else -> RowPosition.Middle
}
