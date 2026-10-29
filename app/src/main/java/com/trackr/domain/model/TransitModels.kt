package com.trackr.domain.model

import kotlinx.serialization.Serializable

/**
 * 数据源标识：全国车来了 vs 全国掌上公交
 */
@Serializable
enum class DataSourceType(val displayName: String) {
    CHELAILE("车来了"),
    MYGOLBS("掌上公交")
}

/**
 * 引擎运行模式：双引擎融合 / 仅车来了 / 仅掌上公交
 */
@Serializable
enum class EngineMode(val displayName: String, val shortLabel: String) {
    ALL("双引擎融合", "双引擎"),
    CHELAILE("车来了", "车来了"),
    MYGOLBS("掌上公交", "掌上公交")
}

/**
 * 城市实体（聚合车来了 cityId 与掌上公交 cityCode）
 */
@Serializable
data class TransitCity(
    val cityId: String,
    val cityName: String,
    val pinyin: String = "",
    val supportSubway: Boolean = false,
    val isHot: Boolean = false,
    val mygolbsCityCode: String? = null,
    val province: String = ""
)

/**
 * 线路运营状态
 */
@Serializable
enum class LineOperatingState(val code: Int, val defaultLabel: String) {
    RUNNING(0, "运营中"),
    WAITING(-1, "等待发车"),
    SUSPENDED(-2, "临时停运"),
    ENDED(-3, "末班已过"),
    NO_REALTIME(1, "未开通实时")
}

/**
 * 公交线路方向摘要
 */
@Serializable
data class BusLineDirection(
    val lineId: String,
    val name: String,
    val lineNo: String = "",
    val direction: Int = 0,
    val startStop: String,
    val endStop: String,
    val firstTime: String = "",
    val lastTime: String = "",
    val price: String = "",
    val source: DataSourceType = DataSourceType.CHELAILE,
    val mygolbsCityCode: String = "",
    val mygolbsCityName: String = ""
)

/**
 * 聚合了正反两个方向的公交线路（用于搜索结果或列表展示）
 */
@Serializable
data class BusLineSummary(
    val name: String,
    val lineNo: String,
    val isSubway: Boolean = false,
    val directions: List<BusLineDirection>,
    val source: DataSourceType = DataSourceType.CHELAILE
) {
    val primaryDirection: BusLineDirection?
        get() = directions.firstOrNull { it.direction == 0 } ?: directions.firstOrNull()
}

/**
 * 线路上的站点实体
 */
@Serializable
data class BusStop(
    val stopId: String,
    val name: String,
    val order: Int, // 1-based 站序
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val physicalStId: String? = null,
    val namesakeStId: String? = null,
    val statusNote: String? = null, // 如掌上公交“响应式停靠”、“临时不停靠”
    val metroTransfers: List<String> = emptyList()
)

/**
 * 实时在途公交车实体
 * @param order 正在接近（或已停靠）的目标站点 1-based 站序
 * @param segmentProgress 车辆在 (order-1) -> order 之间的插值比例 [0.0, 1.0]；已到站时恒为 1.0
 * @param isArrived true = 已到站停靠 (state == 1)，false = 在途行驶 (state == 0)
 * @param etaSeconds 距离当前查询目标站（或终点站）的剩余秒数，<=0 表示无预测或已过站
 */
@Serializable
data class LiveBus(
    val busId: String,
    val plateOrFleetNo: String,
    val order: Int,
    val segmentProgress: Float = 1.0f,
    val isArrived: Boolean = false,
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val speedKmh: Double = 0.0,
    val capacityLevel: Int = 0, // 0=舒适, 1=适中, 2=拥挤, -1=未知
    val distanceToTargetMeters: Int? = null,
    val etaSeconds: Int = -1,
    val expectedArrivalTimeText: String? = null, // 如 "21:03"
    val nextStopName: String? = null
)

/**
 * 完整线路详情（含站点序列、全线车辆、反向线路信息、运营状态）
 */
@Serializable
data class BusLineDetail(
    val line: BusLineDirection,
    val reverseDirection: BusLineDirection? = null,
    val stops: List<BusStop>,
    val buses: List<LiveBus>,
    val operatingState: LineOperatingState = LineOperatingState.RUNNING,
    val statusDescription: String = "",
    val nextDepartureFromStart: String? = null,
    val targetOrder: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

/**
 * 附近站点卡片中的单条途经线路实时状态
 */
@Serializable
data class NearbyStopLineStatus(
    val line: BusLineDirection,
    val targetOrder: Int,
    val targetStopId: String,
    val targetStopName: String,
    val statusText: String = "",
    val preArrivalTime: String? = null,
    val approachingBuses: List<LiveBus> = emptyList()
)

/**
 * 附近公交站实体
 */
@Serializable
data class NearbyStop(
    val stopId: String,
    val name: String,
    val distanceMeters: Int? = null,
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val physicalStId: String? = null,
    val namesakeStId: String? = null,
    val isSubway: Boolean = false,
    val lines: List<NearbyStopLineStatus> = emptyList(),
    val subwayLineNames: List<String> = emptyList(),
    val source: DataSourceType = DataSourceType.CHELAILE
)

/**
 * 混合搜索结果
 */
@Serializable
data class TransitSearchResult(
    val keyword: String,
    val lines: List<BusLineSummary> = emptyList(),
    val stops: List<NearbyStop> = emptyList()
)

/**
 * 发车时刻表时段分块
 */
@Serializable
data class TimetableSegment(
    val fromTime: String,
    val toTime: String,
    val intervalMinutes: Int = -1,
    val intervalTips: String = "",
    val departures: List<String> = emptyList()
)

/**
 * 始发站发车时刻表（含今日预测与昨日实际班次回落）
 */
@Serializable
data class LineTimetable(
    val lineId: String,
    val lineName: String,
    val nextDeparture: String? = null,
    val nextDepartureSource: String? = null, // "今日发车预测" | "昨日实际班次"
    val todayDepartures: List<String> = emptyList(),
    val yesterdayDepartures: List<String> = emptyList(),
    val segments: List<TimetableSegment> = emptyList(),
    val depDesc: String = ""
)

/**
 * 收藏的「线路 + 候车站」组合（用于首页通勤卡、桌面小组件、一键通知栏追踪）
 */
@Serializable
data class FavoriteStationLine(
    val id: String, // "${cityId}_${lineId}_${targetOrder}"
    val cityId: String,
    val cityName: String,
    val lineId: String,
    val lineName: String,
    val lineNo: String = "",
    val direction: Int = 0,
    val startStop: String,
    val endStop: String,
    val targetStopId: String,
    val targetStopName: String,
    val targetOrder: Int,
    val totalStops: Int = 0,
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val source: DataSourceType = DataSourceType.CHELAILE,
    val mygolbsCityCode: String = "",
    val addedAtMillis: Long = System.currentTimeMillis()
)

/**
 * 实时乘车/候车通知栏追踪会话状态
 */
@Serializable
data class TrackingSessionState(
    val isActive: Boolean = false,
    val cityId: String = "",
    val lineId: String = "",
    val lineName: String = "",
    val endStopName: String = "",
    val targetStopId: String = "",
    val targetStopName: String = "",
    val targetOrder: Int = 0,
    val totalStops: Int = 0,
    val startTrackingOrder: Int = 1,
    val currentBusId: String? = null,
    val currentBusOrder: Int = 0,
    val currentBusProgress: Float = 0f,
    val isBusArrivedAtStop: Boolean = false,
    val stopsRemaining: Int = -1,
    val etaSeconds: Int = -1,
    val expectedArrivalTime: String? = null,
    val alertThresholdStops: Int = 2,
    val hasTriggeredApproachingAlert: Boolean = false,
    val hasTriggeredArrivedAlert: Boolean = false,
    val statusSummary: String = "正在连接实时公交数据…",
    val updatedAtMillis: Long = System.currentTimeMillis()
)
