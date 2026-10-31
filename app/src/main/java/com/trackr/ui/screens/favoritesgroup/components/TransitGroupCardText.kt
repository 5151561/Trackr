package com.trackr.ui.screens.favoritesgroup.components

import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TransitGroup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 收藏页卡片用到的纯文案 / 估算函数（不依赖 Compose，便于单测）。
 */

/** 估算时每坐一站大约用时（分钟） */
internal const val MINUTES_PER_STOP = 2

/** 步行速度（米 / 分钟） */
internal const val WALK_METERS_PER_MINUTE = 80

/**
 * 站序是否还没和实时数据对齐：合成线路（lineId 以 line_ 开头）或站序不合法。
 * 这种情况下算出来的站数是假的，不能展示给用户。
 */
internal fun isStopOrderUnaligned(line: CandidateLine): Boolean =
    line.lineId.startsWith("line_") || line.fromStopOrder <= 0 || line.fromStopOrder >= line.toStopOrder

/** 可信的乘车站数；站序未对齐时返回 null */
internal fun reliableRideStops(line: CandidateLine?): Int? {
    if (line == null || isStopOrderUnaligned(line)) return null
    return line.toStopOrder - line.fromStopOrder
}

/** "坐 N 站"；站序未对齐时为"站数待确认" */
internal fun rideStopsText(line: CandidateLine?): String =
    reliableRideStops(line)?.let { "坐 $it 站" } ?: "站数待确认"

/**
 * 卡片标题下的一行摘要。
 * - 直达："直达 · 坐 4 站"（有步行时追加 " · 步行 120 米"）
 * - 换乘："换乘 1 次 · 步行 170 米"，没有步行时写"同站换乘"
 * 分组里没有可靠的总时长，所以不写"约 N 分钟"。
 */
internal fun groupSummaryText(group: TransitGroup, selectedLines: List<CandidateLine?>): String {
    val totalWalk = group.legs.sumOf { it.walkingDistanceMeters }
    return if (group.legs.size <= 1) {
        val base = "直达 · ${rideStopsText(selectedLines.firstOrNull())}"
        if (totalWalk > 0) "$base · 步行 $totalWalk 米" else base
    } else {
        val transfers = group.legs.size - 1
        if (totalWalk > 0) "换乘 $transfers 次 · 步行 $totalWalk 米" else "换乘 $transfers 次 · 同站换乘"
    }
}

/** 卡片标题：起点 → 终点；缺站名时回落分组名 */
internal fun groupTitleText(group: TransitGroup): String {
    val from = group.originStopName
    val to = group.destinationStopName
    return if (from.isNotBlank() && to.isNotBlank()) "$from → $to" else group.name
}

/** 追踪徽章："第 1/2 程 · 等车" / "第 1/2 程 · 乘车" */
internal fun trackingPhaseLabel(activeLegIndex: Int, totalLegs: Int, phase: LegPhase?): String {
    val phaseText = if (phase == LegPhase.ON_BOARD) "乘车" else "等车"
    return "第 ${activeLegIndex + 1}/${totalLegs.coerceAtLeast(1)} 程 · $phaseText"
}

/**
 * 分程进度条每格的填充比例：走过的程满格，当前程等车半格、乘车满格，后面的程空。
 */
internal fun legProgressFractions(totalLegs: Int, activeLegIndex: Int, phase: LegPhase?): List<Float> =
    List(totalLegs.coerceAtLeast(1)) { index ->
        when {
            index < activeLegIndex -> 1f
            index == activeLegIndex -> if (phase == LegPhase.ON_BOARD) 1f else 0.5f
            else -> 0f
        }
    }

/** 由 ETA 秒数（优先）或剩余站数粗估的分钟数；都没有时返回 null */
internal fun etaMinutesOf(etaSeconds: Int, stopsRemaining: Int): Int? = when {
    etaSeconds > 0 -> (etaSeconds / 60.0).roundToInt().coerceAtLeast(1)
    stopsRemaining >= 0 -> (stopsRemaining * MINUTES_PER_STOP).coerceAtLeast(1)
    else -> null
}

/** 候选线路实时信息里的可用分钟数；车辆未知时为 null */
internal fun CandidateLineRealtimeInfo.knownMinutes(): Int? =
    if (stopsAway == Int.MAX_VALUE || estimatedMinutes == Int.MAX_VALUE) null else estimatedMinutes

/**
 * 估算某一程的上车时间（毫秒时间戳）。
 *
 * @param nowMillis 当前时间
 * @param anchorMinutes 从现在到"起算点"的分钟数（如第 1 程车到上车站的 ETA）；null 表示估不出
 * @param rideStops 起算点到目标上车站之间每一程要坐的站数；任意一程为 null（站序未对齐）就估不出
 * @param walkMeters 起算点到目标上车站之间的步行距离合计
 * @return 预计上车时间；估不出返回 null
 */
internal fun estimateBoardTimeMillis(
    nowMillis: Long,
    anchorMinutes: Int?,
    rideStops: List<Int?>,
    walkMeters: Int = 0
): Long? {
    if (anchorMinutes == null || anchorMinutes < 0) return null
    if (rideStops.any { it == null }) return null
    val rideMinutes = rideStops.sumOf { it ?: 0 } * MINUTES_PER_STOP
    val walkMinutes = if (walkMeters > 0) ceil(walkMeters / WALK_METERS_PER_MINUTE.toDouble()).toInt() else 0
    val totalMinutes = anchorMinutes + rideMinutes + walkMinutes
    return nowMillis + totalMinutes * 60_000L
}

private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 毫秒时间戳格式化为 "HH:mm" */
internal fun formatClock(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    CLOCK_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zoneId))

/** "预计 07:58 上车"；估不出时为 null */
internal fun boardTimeText(
    nowMillis: Long,
    anchorMinutes: Int?,
    rideStops: List<Int?>,
    walkMeters: Int = 0,
    zoneId: ZoneId = ZoneId.systemDefault()
): String? = estimateBoardTimeMillis(nowMillis, anchorMinutes, rideStops, walkMeters)
    ?.let { "预计 ${formatClock(it, zoneId)} 上车" }

/**
 * 估算第 [targetLegIndex] 程的"预计 HH:mm 上车"。
 *
 * @param anchorLegIndex 起算的那一程
 * @param anchorIsDropStop true：[anchorMinutes] 是到该程下车站的分钟数（乘车中）；
 *   false：是车到该程上车站的分钟数（等车 / 未追踪）
 * @param rideStopsPerLeg 每程可信的乘车站数（站序未对齐为 null）
 * @param walkMetersPerLeg 每程上车前的步行距离
 */
internal fun boardTimeTextForLeg(
    targetLegIndex: Int,
    anchorLegIndex: Int,
    anchorIsDropStop: Boolean,
    anchorMinutes: Int?,
    rideStopsPerLeg: List<Int?>,
    walkMetersPerLeg: List<Int>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): String? {
    if (targetLegIndex <= anchorLegIndex) return null
    val firstRideLeg = if (anchorIsDropStop) anchorLegIndex + 1 else anchorLegIndex
    val rides = (firstRideLeg until targetLegIndex).map { rideStopsPerLeg.getOrNull(it) }
    val walk = ((anchorLegIndex + 1)..targetLegIndex).sumOf { walkMetersPerLeg.getOrElse(it) { 0 } }
    return boardTimeText(nowMillis, anchorMinutes, rides, walk, zoneId)
}

/**
 * 快到换乘站时，下一程车离换乘站的实时文案："现在离 深大北门 6 站 · 约 12 分钟"。
 * 车辆未知时返回 null。
 */
internal fun nextLegLiveText(transferStopName: String, info: CandidateLineRealtimeInfo?): String? {
    if (info == null || info.stopsAway == Int.MAX_VALUE) return null
    val minutes = info.knownMinutes()
    val base = "现在离 $transferStopName ${info.stopsAway} 站"
    return if (minutes != null) "$base · 约 $minutes 分钟" else base
}

/** 是否显示下一程的实时位置：乘车阶段且离下车站不超过 2 站 */
internal fun shouldShowNextLegLive(phase: LegPhase?, stopsRemaining: Int): Boolean =
    phase == LegPhase.ON_BOARD && stopsRemaining in 0..2
