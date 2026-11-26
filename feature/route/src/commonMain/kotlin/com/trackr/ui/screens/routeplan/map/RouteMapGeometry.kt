package com.trackr.ui.screens.routeplan.map

import com.trackr.core.domain.math.CoordTransform
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.GeoPoint
import com.trackr.core.model.ProvinceCityCatalog

// 地图要画什么（纯函数，第 24 步从旧 RoutePlanScreen 的 AmapRealVectorMapView 里拆出来，平台实现只管画）。
// 所有点都是 GCJ-02（高德方案自带的坐标就是 GCJ-02）。

/** 选中方案的一个覆盖物；平台实现按列表顺序画（先画的在下面） */
sealed interface RouteMapOverlay {
    /** 步行：虚线 */
    data class Walk(val points: List<GeoPoint>) : RouteMapOverlay

    /** 乘车：第 [legIndex] 程的实线（第 1 程主色，之后换色） */
    data class Ride(val points: List<GeoPoint>, val legIndex: Int) : RouteMapOverlay

    /** 点标记：换乘站 / 起点 / 终点 */
    data class Marker(val point: GeoPoint, val title: String) : RouteMapOverlay
}

/** 未选中方案的一条细线：点序列 + 是否步行 */
data class SchemeLine(val points: List<GeoPoint>, val isWalk: Boolean)

private fun stopPoint(lat: Double, lng: Double): GeoPoint? =
    if (lat > 0.0 && lng > 0.0) GeoPoint(lat, lng) else null

/** 方案起点：优先用户选择的出发地 POI，否则回落首段上车站 */
fun schemeOrigin(scheme: AmapTransitSchemeResult): GeoPoint? =
    scheme.originPoint
        ?: scheme.group.legs.firstOrNull()?.let { stopPoint(it.fromStopLat, it.fromStopLng) }

/** 方案终点：优先用户选择的目的地 POI，否则回落末段下车站 */
fun schemeDestination(scheme: AmapTransitSchemeResult): GeoPoint? =
    scheme.destinationPoint
        ?: scheme.group.legs.lastOrNull()?.let { stopPoint(it.toStopLat, it.toStopLng) }

/**
 * 方案的折线（画未选中方案的细灰线）：乘车优先用 ridePath 形状点，没有才用上下车站直线；
 * 步行用 walkPathBefore / finalWalkPath。
 */
fun schemeLines(scheme: AmapTransitSchemeResult): List<SchemeLine> {
    val result = mutableListOf<SchemeLine>()
    scheme.group.legs.forEach { leg ->
        if (leg.walkPathBefore.size >= 2) result += SchemeLine(leg.walkPathBefore, isWalk = true)
        val from = stopPoint(leg.fromStopLat, leg.fromStopLng)
        val to = stopPoint(leg.toStopLat, leg.toStopLng)
        if (leg.ridePath.size >= 2) {
            result += SchemeLine(leg.ridePath, isWalk = false)
        } else if (from != null && to != null) {
            result += SchemeLine(listOf(from, to), isWalk = false)
        }
    }
    if (scheme.finalWalkPath.size >= 2) result += SchemeLine(scheme.finalWalkPath, isWalk = true)
    return result
}

/**
 * 选中方案的覆盖物（743e932：按 polyline 形状点画，不是两点直线）：
 * - 上车前步行：优先真实 walkPathBefore，否则换乘两站直连虚线；
 * - 乘车：优先真实 ridePath，为空时回落上下车站两点；
 * - 第 2 程起在上车站放「换乘」标记；
 * - 末段步行：优先 finalWalkPath，否则末站连到目的地 POI；
 * - 最后放起点、终点标记。
 */
fun selectedSchemeOverlays(scheme: AmapTransitSchemeResult): List<RouteMapOverlay> = buildList {
    fun walk(points: List<GeoPoint>) {
        if (points.size >= 2) add(RouteMapOverlay.Walk(points))
    }

    var prevLegTo: GeoPoint? = null
    scheme.group.legs.forEachIndexed { index, leg ->
        val from = stopPoint(leg.fromStopLat, leg.fromStopLng)
        val to = stopPoint(leg.toStopLat, leg.toStopLng)

        val previous = prevLegTo
        if (leg.walkPathBefore.size >= 2) {
            walk(leg.walkPathBefore)
        } else if (previous != null && from != null && previous != from) {
            walk(listOf(previous, from))
        }

        if (leg.ridePath.size >= 2) {
            add(RouteMapOverlay.Ride(leg.ridePath, index))
        } else if (from != null && to != null) {
            add(RouteMapOverlay.Ride(listOf(from, to), index))
        }

        if (from != null && index != 0) {
            add(RouteMapOverlay.Marker(from, "换乘: ${leg.fromStopName}"))
        }
        if (to != null) prevLegTo = to
    }

    val lastTo = prevLegTo
    val destinationPoi = scheme.destinationPoint
    if (scheme.finalWalkPath.size >= 2) {
        walk(scheme.finalWalkPath)
    } else if (lastTo != null && destinationPoi != null && lastTo != destinationPoi) {
        walk(listOf(lastTo, destinationPoi))
    }

    schemeOrigin(scheme)?.let { add(RouteMapOverlay.Marker(it, "起点")) }
    schemeDestination(scheme)?.let { add(RouteMapOverlay.Marker(it, "终点")) }
}

/** 方案里所有用于取景的点（折线 + 站点 + 起终点） */
fun schemeBoundsPoints(scheme: AmapTransitSchemeResult): List<GeoPoint> = buildList {
    schemeLines(scheme).forEach { addAll(it.points) }
    scheme.group.legs.forEach { leg ->
        stopPoint(leg.fromStopLat, leg.fromStopLng)?.let { add(it) }
        stopPoint(leg.toStopLat, leg.toStopLng)?.let { add(it) }
    }
    schemeOrigin(scheme)?.let { add(it) }
    schemeDestination(scheme)?.let { add(it) }
}

/** 地图取景：要么 fit 一个经纬度矩形，要么退化成一个点（单点方案直接定级放大） */
sealed interface MapFraming {
    /** 西南角 / 东北角（GCJ-02），已经是归一化过的顺序 */
    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) : MapFraming

    data class Point(val point: GeoPoint) : MapFraming
}

/** 取景四周内边距（像素）；对应高德 newLatLngBoundsRect 的 left / right / top / bottom */
data class MapFramingPadding(val left: Int, val right: Int, val top: Int, val bottom: Int) {
    val isSymmetric: Boolean get() = left == right && left == top && top == bottom
}

/** 底部方案面板大致盖住的地图高度比例（RoutePlanScreen 底部卡片 + 底栏约 40%） */
const val ROUTE_PLAN_PANEL_HEIGHT_FRACTION = 0.40

/** 范围小于这个跨度（约 11 米）当成一个点：零面积 bounds 高德会拒绝，不如直接定级 */
private const val FRAMING_MIN_SPAN_DEGREES = 1e-4

/**
 * 取景该用哪些点：**当前选中方案**优先（设计稿：选中的方案是主角，其余只画细灰线作参考），
 * 选中方案没有可用点（下标越界 / 坐标全缺）时才退回全部方案合并。
 *
 * 之前是所有方案合并成一个 bounds，3 个方案目的地各不相同，合并后范围极大，地图缩到城乡级别，
 * 一条线路都看不见。
 */
fun mapFramingFor(schemes: List<AmapTransitSchemeResult>, selectedIndex: Int): MapFraming? {
    val selected = schemes.getOrNull(selectedIndex)?.let { schemeBoundsPoints(it) }
    val points = selected?.takeIf { it.isNotEmpty() }
        ?: schemes.flatMap { schemeBoundsPoints(it) }
    return framingOf(points)
}

private fun framingOf(points: List<GeoPoint>): MapFraming? {
    if (points.isEmpty()) return null
    val south = points.minOf { it.lat }
    val north = points.maxOf { it.lat }
    val west = points.minOf { it.lng }
    val east = points.maxOf { it.lng }
    return if (north - south < FRAMING_MIN_SPAN_DEGREES && east - west < FRAMING_MIN_SPAN_DEGREES) {
        MapFraming.Point(points.first())
    } else {
        MapFraming.Bounds(south = south, west = west, north = north, east = east)
    }
}

/**
 * 取景内边距（像素）。底部方案面板盖住画面下约 [ROUTE_PLAN_PANEL_HEIGHT_FRACTION]，
 * 所以下边距要按面板高度留出来，路线才会落在面板上方的可见区域，而不是被卡片压住一半；
 * 顶部同样让出搜索岛的位置。尺寸还没量出来（宽高 <= 0）时退回旧的对称 180px。
 */
fun mapFramingPadding(
    viewWidth: Int,
    viewHeight: Int,
    bottomPanelFraction: Double = ROUTE_PLAN_PANEL_HEIGHT_FRACTION
): MapFramingPadding {
    if (viewWidth <= 0 || viewHeight <= 0) return MapFramingPadding(180, 180, 180, 180)
    val side = maxOf(24, (viewWidth * 0.06).toInt())
    val top = maxOf(24, (viewHeight * 0.10).toInt())
    // 上下合计不超过画面 80%，保证还有一段可用视野；面板最多按 45% 算，防止窄屏把画面挤没
    val bottomCap = minOf((viewHeight * 0.45).toInt(), viewHeight - top - (viewHeight * 0.20).toInt())
    val bottom = minOf((viewHeight * bottomPanelFraction).toInt(), maxOf(0, bottomCap))
    return MapFramingPadding(left = side, right = side, top = top, bottom = bottom)
}

/** 系统定位是 WGS-84，高德地图要 GCJ-02；两者在国内差 300~600 米 */
fun wgs84ToGcj02Point(lat: Double, lng: Double): GeoPoint {
    val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(lat, lng)
    return GeoPoint(gcjLat, gcjLng)
}

/**
 * 定位不可用时的兜底取景：当前城市中心。城市中心表是 WGS-84，先转 GCJ-02 再给地图（301c124，否则偏 300~600 米）。
 * 没收录的城市返回 null（宁可不动相机，也不回落到某个内置城市）。
 */
fun cityCenterGcj02(cityName: String): GeoPoint? =
    ProvinceCityCatalog.findCityCenterCoordinates(cityName)
        ?.let { (lat, lng) -> wgs84ToGcj02Point(lat, lng) }
