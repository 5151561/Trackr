package com.trackr.core.domain

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.ScheduledReminder
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 第 7 步把 JVM 才有的 API 换成了 Kotlin 通用写法。这里在 JVM 上逐个比对新旧写法，结果必须一模一样。
 * （只能放 androidHostTest：commonTest 里没有 String.format / Math。）
 */
class JvmParityTest {

    @Test
    fun formatDistanceText_matchesStringFormatLocaleChina() {
        for (meters in 1000..300_000) {
            val old = String.format(Locale.CHINA, "%.1f公里", meters / 1000.0)
            assertEquals(old, RouteProjectionMath.formatDistanceText(meters), "meters=$meters")
        }
        // 1 公里以下仍是「xx米」，0 和 null 为空
        assertEquals("999米", RouteProjectionMath.formatDistanceText(999))
        assertEquals("", RouteProjectionMath.formatDistanceText(0))
        assertEquals("", RouteProjectionMath.formatDistanceText(null))
    }

    @Test
    fun toRadians_bitIdenticalToMathToRadians() {
        var deg = -180.0
        while (deg <= 180.0) {
            assertEquals(Math.toRadians(deg).toRawBits(), RouteProjectionMath.toRadians(deg).toRawBits(), "deg=$deg")
            deg += 0.0137
        }
    }

    @Test
    fun distances_matchOldMathToRadiansFormula() {
        val points = listOf(
            doubleArrayOf(25.4310, 119.0077, 25.4402, 119.0123),
            doubleArrayOf(22.5431, 114.0579, 22.5470, 113.9420),
            doubleArrayOf(31.2304, 121.4737, 39.9042, 116.4074)
        )
        for ((lat1, lng1, lat2, lng2) in points.map { it.toList() }) {
            assertEquals(
                oldHaversineMeters(lat1, lng1, lat2, lng2).roundToInt(),
                RouteProjectionMath.calculateDistanceMeters(lat1, lng1, lat2, lng2)
            )
            assertEquals(
                oldCatalogHaversineMeters(lat1, lng1, lat2, lng2).toRawBits(),
                ProvinceCityCatalog.haversineMeters(lat1, lng1, lat2, lng2).toRawBits()
            )
        }
    }

    @Test
    fun roundToInt_matchesMathRoundForEtaMinutes() {
        // GroupLegTracker 的「约 N 分钟」原来用 Math.round(etaSeconds / 60.0)
        for (eta in 1..7200) {
            val x = eta / 60.0
            assertEquals(Math.round(x).toInt(), x.roundToInt(), "eta=$eta")
        }
    }

    @Test
    fun formatTime_matchesStringFormat() {
        for (hour in 0..23) for (minute in 0..59) {
            val reminder = ScheduledReminder(
                id = "r", hour = hour, minute = minute, cityId = "", cityName = "", lineId = "",
                lineName = "", direction = 0, endStopName = "", targetStopId = "", targetStopName = "", targetOrder = 1
            )
            assertEquals("%02d:%02d".format(hour, minute), reminder.formatTime())
        }
    }

    /** 第 7 步之前 `RouteProjectionMath.calculateDistanceMeters` / `ProvinceCityCatalog.haversineMeters` 的写法 */
    private fun oldHaversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return 6371000.0 * c
    }

    /** 第 7 步之前 `ProvinceCityCatalog.haversineMeters` 的写法 */
    private fun oldCatalogHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2.0).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2.0).let { it * it }
        val c = 2.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
        return 6371000.0 * c
    }
}
