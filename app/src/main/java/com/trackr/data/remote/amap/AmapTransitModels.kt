package com.trackr.data.remote.amap

import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
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
    val group: TransitGroup
)
