package com.trackr.data.remote.amap

import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GeoPoint
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 高德地图 Web 服务数据模型
 */

@Serializable
data class AmapTipItem(
    val name: String,
    val district: String = "",
    val address: String = "",
    val location: String = "" // "lng,lat"
)

@Serializable
data class AmapTransitSchemeResult(
    val title: String,
    val durationMinutes: Int,
    val walkingMeters: Int,
    val costYuan: String,
    val transferCount: Int,
    val group: TransitGroup,
    /** 用户选择的出发地 POI 坐标（GCJ-02），用于起点 marker；无则回落到首段上车站 */
    val originPoint: GeoPoint? = null,
    /** 用户选择的目的地 POI 坐标（GCJ-02），用于终点 marker；无则回落到末段下车站 */
    val destinationPoint: GeoPoint? = null,
    /** 最后一段（末站 -> 目的地 POI）的步行轨迹（GCJ-02） */
    val finalWalkPath: List<GeoPoint> = emptyList()
)
