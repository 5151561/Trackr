package com.trackr.ui.screens.routeplan.map

import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.GeoPoint
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 地图画什么：按形状点画线（743e932）、缺点才退回两点、城市中心转 GCJ-02（301c124） */
class RouteMapGeometryTest {

    private val p1 = GeoPoint(30.10, 120.10)
    private val p2 = GeoPoint(30.20, 120.20)
    private val p3 = GeoPoint(30.30, 120.30)

    private fun leg(
        index: Int,
        from: GeoPoint,
        to: GeoPoint,
        ridePath: List<GeoPoint> = emptyList(),
        walkBefore: List<GeoPoint> = emptyList()
    ) = TransitLeg(
        legIndex = index,
        fromStopName = "上$index",
        toStopName = "下$index",
        fromStopLat = from.lat,
        fromStopLng = from.lng,
        toStopLat = to.lat,
        toStopLng = to.lng,
        candidateLines = listOf(CandidateLine(cityId = "033", lineId = "l$index", lineName = "${index}路")),
        ridePath = ridePath,
        walkPathBefore = walkBefore
    )

    private fun scheme(legs: List<TransitLeg>, destination: GeoPoint? = null, finalWalk: List<GeoPoint> = emptyList()) =
        AmapTransitSchemeResult(
            title = "s",
            durationMinutes = 30,
            walkingMeters = 300,
            costYuan = "2",
            transferCount = legs.size - 1,
            group = TransitGroup(id = "g", name = "g", legs = legs),
            destinationPoint = destination,
            finalWalkPath = finalWalk
        )

    @Test
    fun selectedScheme_ridesFollowShapePoints_notStraightLine() {
        val shape = listOf(p1, GeoPoint(30.12, 120.18), GeoPoint(30.18, 120.15), p2)
        val overlays = selectedSchemeOverlays(scheme(listOf(leg(0, p1, p2, ridePath = shape))))

        val ride = overlays.filterIsInstance<RouteMapOverlay.Ride>().single()
        assertEquals(shape, ride.points)
        assertEquals(0, ride.legIndex)
        assertEquals(listOf("起点", "终点"), overlays.filterIsInstance<RouteMapOverlay.Marker>().map { it.title })
    }

    @Test
    fun selectedScheme_withoutShape_fallsBackToStops_andWalksBetweenTransfers() {
        val transferFrom = GeoPoint(30.21, 120.21)
        val dest = GeoPoint(30.40, 120.40)
        val overlays = selectedSchemeOverlays(
            scheme(
                listOf(leg(0, p1, p2), leg(1, transferFrom, p3)),
                destination = dest
            )
        )

        assertEquals(
            listOf(
                RouteMapOverlay.Ride(listOf(p1, p2), 0),
                RouteMapOverlay.Walk(listOf(p2, transferFrom)),
                RouteMapOverlay.Ride(listOf(transferFrom, p3), 1),
                RouteMapOverlay.Marker(transferFrom, "换乘: 上1"),
                RouteMapOverlay.Walk(listOf(p3, dest)),
                RouteMapOverlay.Marker(p1, "起点"),
                RouteMapOverlay.Marker(dest, "终点")
            ),
            overlays
        )
    }

    @Test
    fun selectedScheme_prefersRealWalkPaths() {
        val walkBefore = listOf(GeoPoint(30.05, 120.05), p1)
        val finalWalk = listOf(p2, GeoPoint(30.25, 120.25))
        val overlays = selectedSchemeOverlays(
            scheme(listOf(leg(0, p1, p2, walkBefore = walkBefore)), destination = GeoPoint(30.25, 120.25), finalWalk = finalWalk)
        )
        val walks = overlays.filterIsInstance<RouteMapOverlay.Walk>().map { it.points }
        assertEquals(listOf(walkBefore, finalWalk), walks)
    }

    @Test
    fun otherSchemeLines_andBounds_useShapePoints() {
        val shape = listOf(p1, GeoPoint(30.15, 120.12), p2)
        val s = scheme(listOf(leg(0, p1, p2, ridePath = shape)))
        assertEquals(listOf(SchemeLine(shape, isWalk = false)), schemeLines(s))
        assertEquals(shape + listOf(p1, p2, p1, p2), schemeBoundsPoints(s))
    }

    @Test
    fun missingStopCoordinates_drawNothingForThatLeg() {
        val s = scheme(listOf(leg(0, GeoPoint(0.0, 0.0), p2)))
        assertEquals(emptyList(), schemeLines(s))
        assertNull(schemeOrigin(s))
    }

    @Test
    fun cityCenter_isConvertedFromWgs84ToGcj02() {
        val (wgsLat, wgsLng) = assertNotNull(ProvinceCityCatalog.findCityCenterCoordinates("杭州"))
        val gcj = assertNotNull(cityCenterGcj02("杭州"))
        assertNotEquals(GeoPoint(wgsLat, wgsLng), gcj)
        assertEquals(wgs84ToGcj02Point(wgsLat, wgsLng), gcj)
        assertNull(cityCenterGcj02("不存在的城市"))
    }

    // ---- 取景：只跟当前选中方案（否则 3 个方案各不相同，合并后缩到全市） ----

    private val nearA = GeoPoint(25.43, 119.00)
    private val nearB = GeoPoint(25.45, 119.03)
    private val farC = GeoPoint(25.90, 119.60)
    private val farD = GeoPoint(25.95, 119.65)

    private fun schemesForFraming() = listOf(
        scheme(listOf(leg(0, nearA, nearB))),
        scheme(listOf(leg(0, farC, farD))),
        scheme(listOf(leg(1, farD, farC)))
    )

    @Test
    fun framing_usesSelectedSchemeOnly_notAllSchemes() {
        val framing = assertNotNull(mapFramingFor(schemesForFraming(), selectedIndex = 0))
        assertEquals(MapFraming.Bounds(south = nearA.lat, west = nearA.lng, north = nearB.lat, east = nearB.lng), framing)
    }

    @Test
    fun framing_followsSelectedIndex() {
        val framing = assertNotNull(mapFramingFor(schemesForFraming(), selectedIndex = 1))
        assertEquals(MapFraming.Bounds(south = farC.lat, west = farC.lng, north = farD.lat, east = farD.lng), framing)
    }

    @Test
    fun framing_fallsBackToAllSchemes_whenSelectionHasNoPoints() {
        // 第 1 个方案坐标全缺（上下车站都是 0.0），只能退回全部方案合并
        val schemes = listOf(
            scheme(listOf(leg(0, GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)))),
            scheme(listOf(leg(0, nearA, nearB)))
        )
        val framing = assertNotNull(mapFramingFor(schemes, selectedIndex = 0))
        assertEquals(MapFraming.Bounds(south = nearA.lat, west = nearA.lng, north = nearB.lat, east = nearB.lng), framing)
    }

    @Test
    fun framing_outOfRangeIndex_fallsBackToAllSchemes() {
        val framing = assertNotNull(mapFramingFor(schemesForFraming(), selectedIndex = 9))
        assertEquals(MapFraming.Bounds(south = nearA.lat, west = nearA.lng, north = farD.lat, east = farD.lng), framing)
    }

    @Test
    fun framing_singlePoint_becomesPointFraming() {
        val framing = assertNotNull(mapFramingFor(listOf(scheme(listOf(leg(0, nearA, nearA)))), selectedIndex = 0))
        assertEquals(MapFraming.Point(nearA), framing)
    }

    @Test
    fun framing_noSchemes_isNull() {
        assertNull(mapFramingFor(emptyList(), selectedIndex = 0))
    }

    @Test
    fun padding_reservesBottomForTheSchemePanel() {
        // 1080x2400 的屏幕：下边距要至少盖住方案面板那一块（约 40%），左边距保持很窄
        val padding = mapFramingPadding(viewWidth = 1080, viewHeight = 2400)
        assertTrue(padding.bottom >= 960, "底部面板 40% 高应让出下边距，实际 ${padding.bottom}")
        assertTrue(padding.bottom > padding.left, "下边距必须明显大于左右边距")
        assertTrue(padding.top + padding.bottom <= 2400 * 0.8, "上下合计不能把可见区域挤没")
    }

    @Test
    fun padding_staysSymmetricFallback_whenSizeUnknown() {
        assertEquals(MapFramingPadding(180, 180, 180, 180), mapFramingPadding(viewWidth = 0, viewHeight = 0))
    }

    @Test
    fun padding_neverEatsTheWholeViewport_onShortScreens() {
        val padding = mapFramingPadding(viewWidth = 1080, viewHeight = 500, bottomPanelFraction = 0.9)
        assertTrue(padding.top + padding.bottom < 500, "极端比例下也要留出可见区域")
    }
}
