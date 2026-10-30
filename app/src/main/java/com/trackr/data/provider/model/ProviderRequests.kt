package com.trackr.data.provider.model

import com.trackr.domain.model.DataSourceType

/**
 * 站点详情标准化查询请求上下文
 */
data class StopDetailRequest(
    val stopId: String? = null,
    val stopName: String? = null,
    val physicalStId: String? = null,
    val namesakeStId: String? = null,
    val stationLat: Double? = null,
    val stationLng: Double? = null,
    val userLat: Double? = null,
    val userLng: Double? = null
)

/**
 * 线路详情标准化查询请求上下文
 */
data class LineDetailRequest(
    val lineId: String,
    val targetOrder: Int? = null,
    val stationId: String? = null,
    val wgsLat: Double? = null,
    val wgsLng: Double? = null,
    val source: DataSourceType? = null,
    val lineNameHint: String = "",
    val directionHint: Int = 0
)
