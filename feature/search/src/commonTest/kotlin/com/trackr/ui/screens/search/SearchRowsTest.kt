package com.trackr.ui.screens.search

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TransitSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRowsTest {

    private fun dir(id: String, from: String, to: String, first: String = "", last: String = "", price: String = "") =
        BusLineDirection(lineId = id, name = "49路", startStop = from, endStop = to, firstTime = first, lastTime = last, price = price)

    private val line49 = BusLineSummary(
        name = "49路",
        lineNo = "49",
        directions = listOf(
            dir("49-0", "汉口路", "上海体育馆", "05:30", "23:00", "2元"),
            dir("49-1", "上海体育馆", "汉口路")
        )
    )

    @Test
    fun nullResult_noRows() {
        assertTrue(searchRows(null).isEmpty())
    }

    @Test
    fun eachDirection_isItsOwnRow_withStartToEndTitle() {
        val rows = searchRows(TransitSearchResult(keyword = "49", lines = listOf(line49)))

        assertEquals(SearchRow.Header(SearchRow.TITLE_LINES), rows[0])
        val lines = rows.filterIsInstance<SearchRow.LineDirection>()
        assertEquals(listOf("汉口路 → 上海体育馆", "上海体育馆 → 汉口路"), lines.map { it.title })
        assertEquals(listOf("49-0", "49-1"), lines.map { it.direction.lineId })
        assertEquals(listOf("49路", "49路"), lines.map { it.lineName })
        assertEquals(listOf(RowPosition.First, RowPosition.Last), lines.map { it.position })
    }

    @Test
    fun directionDetail_listsOnlyWhatIsKnown() {
        val subway = line49.copy(isSubway = true)
        assertEquals("05:30-23:00 · 2元", directionDetail(line49, line49.directions[0]))
        assertEquals("", directionDetail(line49, line49.directions[1]))
        assertEquals("地铁 · 05:30-23:00 · 2元", directionDetail(subway, subway.directions[0]))
        assertEquals("地铁", directionDetail(subway, subway.directions[1]))
    }

    @Test
    fun directionTitle_fallsBackWhenStopsMissing() {
        assertEquals("开往 上海体育馆", directionTitle(dir("x", "", "上海体育馆")))
        assertEquals("49路", directionTitle(dir("x", "", "")))
    }

    @Test
    fun stopRow_showsDistinctLineNames_orSubwayLines() {
        fun status(name: String, to: String) = NearbyStopLineStatus(
            line = BusLineDirection(lineId = "$name-$to", name = name, startStop = "a", endStop = to),
            targetOrder = 3,
            targetStopId = "s",
            targetStopName = "人民广场"
        )
        val busStop = NearbyStop(
            stopId = "s1",
            name = "人民广场",
            distanceMeters = 350,
            lines = listOf(status("49路", "汉口路"), status("49路", "上海体育馆"), status("71路", "申昆路"))
        )
        val subwayOnly = NearbyStop(stopId = "s2", name = "人民广场站", subwayLineNames = listOf("1号线", "2号线"))
        val bare = NearbyStop(stopId = "s3", name = "某站")

        val rows = searchRows(TransitSearchResult(keyword = "人民", stops = listOf(busStop, subwayOnly, bare)))
            .filterIsInstance<SearchRow.Stop>()

        assertEquals(listOf("49路 · 71路", "1号线 · 2号线", ""), rows.map { it.lineNames })
        assertEquals("350米", rows[0].distance)
        assertEquals("", rows[2].distance)
        assertEquals(listOf(RowPosition.First, RowPosition.Middle, RowPosition.Last), rows.map { it.position })

        // 公交和地铁都有：公交线在前，地铁线名接在后面
        val both = busStop.copy(subwayLineNames = listOf("1号线"))
        assertEquals("49路 · 71路 · 1号线", stopLineNames(both))
    }

    @Test
    fun sections_inOrder_linesThenStops_singleRowIsSingle() {
        val rows = searchRows(
            TransitSearchResult(
                keyword = "49",
                lines = listOf(line49.copy(directions = line49.directions.take(1))),
                stops = listOf(NearbyStop(stopId = "s1", name = "49路总站"))
            )
        )
        assertEquals(
            listOf(SearchRow.CONTENT_TYPE_HEADER, SearchRow.CONTENT_TYPE_LINE, SearchRow.CONTENT_TYPE_HEADER, SearchRow.CONTENT_TYPE_STOP),
            rows.map { it.contentType }
        )
        assertEquals(RowPosition.Single, (rows[1] as SearchRow.LineDirection).position)
        assertEquals(RowPosition.Single, (rows[3] as SearchRow.Stop).position)
    }

    @Test
    fun duplicateLinesOrStops_stillGetUniqueKeys() {
        val rows = searchRows(
            TransitSearchResult(
                keyword = "49",
                lines = listOf(line49, line49),
                stops = listOf(NearbyStop(stopId = "s1", name = "A"), NearbyStop(stopId = "s1", name = "A"), NearbyStop(stopId = "", name = "B"))
            )
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "key 不能重复：$keys")
    }
}
