package com.trackr.domain.model

import kotlinx.serialization.Serializable

/**
 * 路线分组所属分类
 */
@Serializable
enum class GroupCategory(val displayName: String) {
    COMMUTE("上班通勤"),
    LEISURE("休闲出行"),
    CUSTOM("自定义"),
    TEMPORARY("地图临时方案")
}

/**
 * 收藏中心自定义分组实体
 */
@Serializable
data class FavoriteGroup(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false
)

/**
 * 单段行程中可乘坐的候选公交/地铁线路
 */
@Serializable
data class CandidateLine(
    val cityId: String,
    val cityName: String = "",
    val lineId: String,
    val lineName: String,
    val source: DataSourceType = DataSourceType.CHELAILE,
    val direction: Int = 0,
    val fromStopOrder: Int = 1,
    val toStopOrder: Int = 1,
    val startStopName: String = "",
    val endStopName: String = "",
    val mygolbsCityCode: String? = null
)

/**
 * 候选线路实时状态信息（多线同达卡片胶囊与详情）
 */
@Serializable
data class CandidateLineRealtimeInfo(
    val lineId: String,
    val lineName: String,
    val etaText: String = "",          // e.g. "1 站 · 约 2 分钟"
    val shortChipEta: String = "",     // e.g. "1站" or "等待发车"
    val stopsAway: Int = Int.MAX_VALUE,
    val estimatedMinutes: Int = Int.MAX_VALUE,
    val directionText: String = "",    // e.g. "恒丰工业城方向"
    val vehiclePlate: String = "",     // e.g. "粤B·58921"
    val rideStopsCount: Int = 0,
    val isFastest: Boolean = false
)

/**
 * 地理坐标点（GCJ-02 火星坐标系，与高德地图渲染一致）
 */
@Serializable
data class GeoPoint(
    val lat: Double,
    val lng: Double
)

/**
 * 串联行程中的单程段（例如：第一程、换乘第二程）
 *
 * @param ridePath 主线路（第一条候选线路）的实际乘车行驶轨迹点序列（GCJ-02）
 * @param walkPathBefore 上车前（上一段下车点/起点 POI -> 本段上车站）的步行轨迹点序列（GCJ-02）
 */
@Serializable
data class TransitLeg(
    val legIndex: Int,
    val fromStopName: String,
    val fromStopId: String = "",
    val toStopName: String,
    val toStopId: String = "",
    val fromStopLat: Double = 0.0,
    val fromStopLng: Double = 0.0,
    val toStopLat: Double = 0.0,
    val toStopLng: Double = 0.0,
    val candidateLines: List<CandidateLine> = emptyList(),
    val walkingDistanceMeters: Int = 0,
    val ridePath: List<GeoPoint> = emptyList(),
    val walkPathBefore: List<GeoPoint> = emptyList()
) {
    val primaryLine: CandidateLine?
        get() = candidateLines.firstOrNull()
}

/**
 * 串联路线分组（包含多段换乘行程）
 */
@Serializable
data class TransitGroup(
    val id: String,
    val name: String,
    val category: GroupCategory = GroupCategory.CUSTOM,
    val legs: List<TransitLeg> = emptyList(),
    val isTemporary: Boolean = false,
    val sortOrder: Int = 0,
    val customGroupId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val totalLegs: Int
        get() = legs.size

    val originStopName: String
        get() = legs.firstOrNull()?.fromStopName.orEmpty()

    val destinationStopName: String
        get() = legs.lastOrNull()?.toStopName.orEmpty()
}

/**
 * 整组跨站换乘实时追踪会话状态
 */
@Serializable
data class GroupTrackingSessionState(
    val groupId: String = "",
    val groupName: String = "",
    val activeLegIndex: Int = 0,
    val totalLegs: Int = 0,
    val currentLeg: TransitLeg? = null,
    val trackingSessionState: TrackingSessionState? = null,
    val isCompleted: Boolean = false,
    val transferPromptMessage: String? = null
)
