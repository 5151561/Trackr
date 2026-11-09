package com.trackr.core.network.remote.lenz

/**
 * 朗斯「掌上出行」内置权威发车时间表（由官方微信公众号公布的冬季运营时刻表整理）。
 *
 * 朗斯服务器接口本身不提供完整的固定班次发车表，仅在实时车辆位置中返回下一班计划发车时间。
 * 这里内置范县 1~12 路全线、双向的发车时刻数据，供时刻表弹窗完整展示今日计划班次与分段发车间隔。
 */
data class LenzScheduledTimetable(
    val departures: List<String>,
    val intervalMinutes: Int = 30,
    val intervalTips: String = "约 30 分钟/班",
    val description: String = "参考范县城乡客运官方冬季时刻表（2023年10月起执行），实际发车时间请以车站为准"
)

private val HALF_HOUR_FANXIAN_UP = listOf(
    "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00",
    "10:30", "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00",
    "14:30", "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00"
)

private val HALF_HOUR_FANXIAN_DOWN = listOf(
    "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00",
    "10:30", "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00",
    "14:30", "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00", "18:30"
)

/**
 * 范县 1-12 路双向发车时刻表表映射：
 * key: routeName to direction (0 为上行/去程，1 为下行/回程)
 */
private val FANXIAN_SCHEDULES: Map<Pair<String, Int>, LenzScheduledTimetable> = mapOf(
    // 1路 范县至王楼镇（范县客运站 <-> 宋海客运站）
    ("范县至王楼镇" to 0) to LenzScheduledTimetable(
        departures = listOf(
            "06:00", "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00",
            "10:30", "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30",
            "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至王楼镇" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
            "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30",
            "16:00", "16:30", "17:00", "17:30", "18:00", "18:30", "18:50"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班（末班 18:50）"
    ),

    // 2路 范县至濮城镇（范县客运站 <-> 前毕庄）
    ("范县至濮城镇" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至濮城镇" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
            "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00",
            "16:30", "17:00", "17:30", "18:00", "18:40"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班（末班 18:40）"
    ),

    // 3路 范县至彭楼水库（范县客运站 <-> 彭楼水库）
    ("范县至彭楼水库" to 0) to LenzScheduledTimetable(
        departures = listOf(
            "06:00", "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00",
            "10:30", "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30",
            "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至彭楼水库" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
            "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30",
            "16:00", "16:30", "17:00", "17:30", "18:00", "18:30", "19:10"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班（末班 19:10）"
    ),

    // 4路 范县至辛庄（范县客运站 <-> 辛庄马棚）
    ("范县至辛庄" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至辛庄" to 1) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_DOWN,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 5路 范县至杨集乡（范县客运站 <-> 罗庄客运站）
    ("范县至杨集乡" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至杨集乡" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
            "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00",
            "16:30", "17:00", "17:30", "18:00"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 6路 范县至陈庄（范县客运站 <-> 杨吴庄客运站）
    ("范县至陈庄" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至陈庄" to 1) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_DOWN,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 7路 范县至龙王庄镇（范县客运站 <-> 西许楼客运站）
    ("范县至龙王庄镇" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至龙王庄镇" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
            "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00",
            "16:30", "17:00", "17:30", "18:00"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 8路 范县至陆集乡（线路1东行）（8路线路1 范县客运站 <-> 刘庄客运站）
    ("范县至陆集乡（线路1东行）" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至陆集乡（线路1东行）" to 1) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_DOWN,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 8路 范县至陆集乡（线路2南行）（8路线路2 范县客运站 <-> 刘庄客运站）
    ("范县至陆集乡（线路2南行）" to 0) to LenzScheduledTimetable(
        departures = listOf(
            "07:00", "08:00", "09:00", "10:00", "11:00", "12:00",
            "13:00", "14:00", "15:00", "16:00", "17:00", "18:00"
        ),
        intervalMinutes = 60,
        intervalTips = "约 60 分钟/班"
    ),
    ("范县至陆集乡（线路2南行）" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "07:00", "08:00", "09:00", "10:00", "11:00", "12:00",
            "13:00", "14:00", "15:00", "16:00", "17:00", "18:00"
        ),
        intervalMinutes = 60,
        intervalTips = "约 60 分钟/班"
    ),

    // 9路 范县至张庄乡（范县客运站 <-> 张庄客运站）
    ("范县至张庄乡" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至张庄乡" to 1) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_DOWN,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 10路 范县至颜村铺乡（范县客运站 <-> 颜村铺革命旧址）
    ("范县至颜村铺乡" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至颜村铺乡" to 1) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_DOWN,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 11路 范县至高码头镇（线路1 范县客运站 <-> 高码头乔洼）
    ("范县至高码头镇" to 0) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_UP,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县至高码头镇" to 1) to LenzScheduledTimetable(
        departures = HALF_HOUR_FANXIAN_DOWN,
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 11路 范县-仲庄-高码头（线路2 范县客运站 <-> 高码头客运站）
    ("范县-仲庄-高码头" to 0) to LenzScheduledTimetable(
        departures = listOf(
            "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
            "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30",
            "16:00", "16:30", "17:00", "17:30"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),
    ("范县-仲庄-高码头" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
            "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00",
            "16:30", "17:00"
        ),
        intervalMinutes = 30,
        intervalTips = "约 30 分钟/班"
    ),

    // 12路 范县至陈庄（石大庙）（范县客运站 <-> 陈庄石大庙）
    ("范县至陈庄（石大庙）" to 0) to LenzScheduledTimetable(
        departures = listOf(
            "06:50", "07:30", "08:30", "09:30", "10:30", "11:30",
            "12:30", "13:30", "14:30", "15:30", "16:30", "17:30", "18:00"
        ),
        intervalMinutes = 60,
        intervalTips = "约 60 分钟/班（早晚高峰加密）"
    ),
    ("范县至陈庄（石大庙）" to 1) to LenzScheduledTimetable(
        departures = listOf(
            "06:30", "07:10", "08:00", "09:00", "10:00", "11:00",
            "12:00", "13:00", "14:00", "15:00", "16:00", "17:00", "18:00", "18:30"
        ),
        intervalMinutes = 60,
        intervalTips = "约 60 分钟/班（早晚高峰加密）"
    )
)

/** 查找城市线路的权威冬季发车时间表 */
internal fun findLenzScheduledTimetable(
    cityName: String?,
    routeName: String,
    direction: Int
): LenzScheduledTimetable? {
    if (cityName != "范县") return null
    return FANXIAN_SCHEDULES[routeName to direction]
}
