package com.trackr.ui.screens.favoritesgroup.components

import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.LegPhase

// 第 24 步从 androidMain 的 TransitGroupCardText.kt 挪来（同包，原调用方不用改）：
// 规划页（:feature:route commonMain）和收藏页都用。

/** 估算时每坐一站大约用时（分钟） */
const val MINUTES_PER_STOP = 2

/**
 * 站序是否还没和实时数据对齐：合成线路（lineId 以 line_ 开头）或站序不合法。
 * 这种情况下算出来的站数是假的，不能展示给用户。
 */
fun isStopOrderUnaligned(line: CandidateLine): Boolean =
    line.lineId.startsWith("line_") || line.fromStopOrder <= 0 || line.fromStopOrder >= line.toStopOrder

/** 可信的乘车站数；站序未对齐时返回 null */
fun reliableRideStops(line: CandidateLine?): Int? {
    if (line == null || isStopOrderUnaligned(line)) return null
    return line.toStopOrder - line.fromStopOrder
}

/** 追踪徽章："第 1/2 程 · 等车" / "第 1/2 程 · 乘车" */
fun trackingPhaseLabel(activeLegIndex: Int, totalLegs: Int, phase: LegPhase?): String {
    val phaseText = if (phase == LegPhase.ON_BOARD) "乘车" else "等车"
    return "第 ${activeLegIndex + 1}/${totalLegs.coerceAtLeast(1)} 程 · $phaseText"
}

/** 由 ETA 秒数（优先，取整规则同 [ArrivalText.minutesOf]）或剩余站数粗估的分钟数；都没有时返回 null */
fun etaMinutesOf(etaSeconds: Int, stopsRemaining: Int): Int? =
    ArrivalText.minutesOf(etaSeconds)
        ?: if (stopsRemaining >= 0) (stopsRemaining * MINUTES_PER_STOP).coerceAtLeast(1) else null
