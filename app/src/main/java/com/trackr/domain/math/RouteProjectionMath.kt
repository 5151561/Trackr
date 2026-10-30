package com.trackr.domain.math

import com.trackr.domain.model.BusStop
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 车辆在走向条（Route Rail）上的站间正交投影插值与同名反向站匹配工具
 * 算法移植自 misakano7545/yulin-bus-activity
 */
object RouteProjectionMath {

    /**
     * 计算车辆在「上一站 prevStop (order-1) → 正在接近的站 targetStop (order)」段内的比例 [0.0f, 1.0f]。
     * - 1.0f 表示到达 targetStop
     * - 0.0f 表示刚离开 prevStop
     * - 若车辆 state == Arrived (isArrived = true)，恒返回 1.0f
     */
    fun computeSegmentProgress(
        busLat: Double,
        busLng: Double,
        isArrived: Boolean,
        prevStop: BusStop?,
        targetStop: BusStop?
    ): Float {
        if (isArrived || prevStop == null || targetStop == null) return 1.0f
        if (busLat == 0.0 || busLng == 0.0 ||
            prevStop.wgsLat == 0.0 || prevStop.wgsLng == 0.0 ||
            targetStop.wgsLat == 0.0 || targetStop.wgsLng == 0.0
        ) {
            return 1.0f
        }
        val k = cos(busLat * Math.PI / 180.0)
        val ax = prevStop.wgsLng * k
        val ay = prevStop.wgsLat
        val bx = targetStop.wgsLng * k
        val by = targetStop.wgsLat
        val dx = bx - ax
        val dy = by - ay
        val dd = dx * dx + dy * dy
        if (dd <= 1e-12) return 1.0f

        val t = (((busLng * k - ax) * dx) + ((busLat - ay) * dy)) / dd
        return min(1.0, max(0.0, t)).toFloat()
    }

    /**
     * 剥离站名中的中英文括号附注，获取核心站名（例如 "金城商厦（市中医院）" -> "金城商厦"）
     */
    fun coreStopName(rawName: String): String {
        return rawName.replace(Regex("[（(][^）)]*[）)]"), "").trim()
    }

    /**
     * 在反向线路站表中查找与当前选中站对应的同名候车站：
     * 1. 优先精确匹配全站名
     * 2. 次选匹配剥离括号后的核心站名（唯一命中时采用）
     * 3. 兜底返回按比例对称的站点或首站
     */
    fun findOppositeStop(currentStop: BusStop, reverseStops: List<BusStop>): BusStop? {
        if (reverseStops.isEmpty()) return null
        reverseStops.firstOrNull { it.name == currentStop.name }?.let { return it }
        val core = coreStopName(currentStop.name)
        val coreMatches = reverseStops.filter { coreStopName(it.name) == core }
        if (coreMatches.size == 1) return coreMatches.first()
        return coreMatches.firstOrNull() ?: reverseStops.firstOrNull()
    }

    /**
     * 格式化剩余秒数为可读中文文案
     */
    fun formatEtaText(etaSeconds: Int, isArrivedAtTarget: Boolean = false): String {
        if (isArrivedAtTarget) return "已到站"
        if (etaSeconds <= 0) return "暂无预测"
        if (etaSeconds <= 60) return "即将到站"
        val mins = (etaSeconds / 60.0).roundToInt().coerceAtLeast(1)
        return "约 $mins 分钟"
    }

    /**
     * 计算两个经纬度坐标点之间的测地直线距离（米，基于 Haversine 公式）
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Int? {
        if (lat1 <= 0.0 || lng1 <= 0.0 || lat2 <= 0.0 || lng2 <= 0.0) return null
        val earthRadius = 6371000.0 // 地球平均半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return (earthRadius * c).roundToInt()
    }

    /**
     * 格式化距离文本（小于1公里显示米，大于等于1公里显示公里）
     */
    fun formatDistanceText(meters: Int?): String {
        if (meters == null || meters <= 0) return ""
        return if (meters < 1000) {
            "${meters}米"
        } else {
            String.format(java.util.Locale.CHINA, "%.1f公里", meters / 1000.0)
        }
    }

    /**
     * 路线名称智能排序：纯数字线路按数值升序排在前面，带字母/汉字专线排在后面
     */
    fun compareLineNames(a: String, b: String): Int {
        val cleanA = a.removeSuffix(" [掌上公交]").removeSuffix("[掌上公交]").trim()
        val cleanB = b.removeSuffix(" [掌上公交]").removeSuffix("[掌上公交]").trim()
        val numA = cleanA.removeSuffix("路").toIntOrNull()
        val numB = cleanB.removeSuffix("路").toIntOrNull()
        return when {
            numA != null && numB != null -> {
                val cmp = numA.compareTo(numB)
                if (cmp != 0) cmp else a.compareTo(b)
            }
            numA != null -> -1
            numB != null -> 1
            else -> a.compareTo(b)
        }
    }
}
