package com.trackr.core.network.remote.common

/**
 * 发车时刻表的公共规则。
 *
 * 给定「最晚几点」，推断下一班是几点、来自今日预测还是昨日实际。
 */
object TimetableRules {

    private val CLOCK = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

    /** 把 "7:43" / " 07:43 " 统一成 "07:43"；不是合法钟点返回 null */
    fun normalizeClock(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (!CLOCK.matches(value)) return null
        val (hour, minute) = value.split(":")
        return "${hour.padStart(2, '0')}:$minute"
    }

    fun isClock(raw: String?): Boolean = normalizeClock(raw) != null

    /** 选出来的下一班：时间 + 来源说明（UI 上显示在括号里） */
    data class Next(val time: String?, val source: String?)

    /**
     * 先看今日预测，没有再回落到昨日实际。
     *
     * [strict] 为 true 时要求「晚于」参考时间（对应原来传了 atTimeHHMM 的场景），
     * 否则允许「等于」（对应现在时刻正好是发车时间）。
     */
    fun resolveNext(
        todayDepartures: List<String>,
        yesterdayDepartures: List<String>,
        referenceHHMM: String,
        strict: Boolean,
        todayLabel: String = "今日发车预测",
        yesterdayLabel: String = "昨日实际班次"
    ): Next {
        val fromToday = todayDepartures.firstOrNull {
            if (strict) it > referenceHHMM else it >= referenceHHMM
        }
        val fromYesterday = yesterdayDepartures.firstOrNull {
            if (strict) it > referenceHHMM else it >= referenceHHMM
        }
        return when {
            fromToday != null -> Next(fromToday, todayLabel)
            fromYesterday != null -> Next(fromYesterday, yesterdayLabel)
            else -> Next(null, null)
        }
    }
}
