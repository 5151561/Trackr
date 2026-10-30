package com.trackr.domain.model

import kotlinx.serialization.Serializable

/**
 * 用户设定的定时通勤提醒配置
 */
@Serializable
data class ScheduledReminder(
    val id: String,                                        // 唯一标识 (UUID)
    val enabled: Boolean = true,                           // 是否启用
    val hour: Int,                                         // 小时 (0..23)
    val minute: Int,                                       // 分钟 (0..59)
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),       // 1=周一 .. 7=周日；若为空集合表示仅触发一次
    val cityId: String,
    val cityName: String,
    val lineId: String,
    val lineName: String,
    val direction: Int,
    val endStopName: String,
    val targetStopId: String,
    val targetStopName: String,
    val targetOrder: Int,
    val totalStops: Int = targetOrder,
    val dataSourceType: DataSourceType = DataSourceType.CHELAILE,
    val mygolbsCityCode: String? = null,
    val autoStartLiveTracking: Boolean = false             // 到点是否直接开启常驻前台跟车
) {
    val isOneTime: Boolean
        get() = daysOfWeek.isEmpty()

    fun formatTime(): String {
        return "%02d:%02d".format(hour, minute)
    }

    fun formatRepeatSummary(): String {
        return when {
            isOneTime -> "仅一次"
            daysOfWeek == setOf(1, 2, 3, 4, 5) -> "工作日 (周一至周五)"
            daysOfWeek == setOf(1, 2, 3, 4, 5, 6, 7) -> "每天"
            daysOfWeek == setOf(6, 7) -> "周末"
            else -> {
                val dayNames = mapOf(
                    1 to "一", 2 to "二", 3 to "三",
                    4 to "四", 5 to "五", 6 to "六", 7 to "日"
                )
                "周" + daysOfWeek.sorted().mapNotNull { dayNames[it] }.joinToString("、")
            }
        }
    }
}
