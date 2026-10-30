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
 * 串联行程中的单程段（例如：第一程、换乘第二程）
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
    val walkingDistanceMeters: Int = 0
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
