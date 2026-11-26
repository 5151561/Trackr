package com.trackr.core.network.remote.amap

import com.trackr.core.network.remote.common.formUrlEncode
import com.trackr.core.network.remote.common.formatFixed
import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * commonMain 的定点格式化和 URL 编码在 JVM 上逐个对比原来的 `String.format(Locale.ROOT, …)` / `URLEncoder`。
 */
class AmapFormatJvmTest {

    @Test
    fun formatLngLat_usesDotDecimalSeparatorRegardlessOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("120.100000,30.200000", AmapTransitApiClient.formatLngLat("120.1,30.2"))
        } finally {
            Locale.setDefault(previous)
        }
        assertNull(AmapTransitApiClient.formatLngLat("bad"))
    }

    @Test
    fun formatFixed_matchesStringFormat() {
        val random = Random(8)
        val samples = buildList {
            // 坐标范围随机值 + 最多 9 位小数的「正好落在 .5」类边界值
            repeat(20_000) { add(random.nextDouble(-180.0, 180.0)) }
            repeat(5_000) { add((random.nextLong(0, 180_000_000_000L) / 1_000_000_000.0)) }
            repeat(2_000) { add(random.nextLong(0, 180_000_000L) / 1_000_000.0 + 0.0000005) }
            addAll(listOf(0.0, -0.0, 0.5, 1.0, 9.9999995, 99.9999996, 0.0000005, 0.00000049, 1e-7, 1e-12, 123456789.123, 120.1234565))
        }
        for (d in samples) {
            assertEquals(String.format(Locale.ROOT, "%.6f", d), formatFixed(d, 6), "value=$d")
        }
    }

    @Test
    fun formUrlEncode_matchesUrlEncoder() {
        val samples = listOf(
            "120.123457,30.100000", "杭州", "人民广场 地铁站", "k+/=?&#%", "a*b-c.d_e~f", "😀", "\u0000\u007f", "",
            "(括号)[方括号]{花括号}", "Ü ß é"
        )
        for (s in samples) {
            assertEquals(URLEncoder.encode(s, "UTF-8"), formUrlEncode(s), "value=$s")
        }
    }
}
