package com.trackr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoordTransformTest {

    @Test
    fun `境外坐标不偏移`() {
        val (tokyoLat, tokyoLng) = CoordTransform.wgs84ToGcj02(35.6812, 139.7671)
        assertEquals(35.6812, tokyoLat, 1e-9)
        assertEquals(139.7671, tokyoLng, 1e-9)
    }

    @Test
    fun `国内坐标偏移量级符合火星坐标特征`() {
        // GCJ-02 相对 WGS-84 的偏移在百米级，换算成度大约是 0.001~0.02
        val wgsLat = 31.0
        val wgsLng = 121.0
        val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)

        val dLat = abs(gcjLat - wgsLat)
        val dLng = abs(gcjLng - wgsLng)
        assertTrue("纬度偏移异常: $dLat", dLat in 0.001..0.02)
        assertTrue("经度偏移异常: $dLng", dLng in 0.001..0.02)
        assertNotEquals(wgsLat, gcjLat)
    }

    @Test
    fun `往返转换误差小于一米`() {
        // 国内任意点，仅用于验证正反转换的自洽性
        val wgsLat = 31.0
        val wgsLng = 121.0
        val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
        val (backLat, backLng) = CoordTransform.gcj02ToWgs84(gcjLat, gcjLng)

        // 1e-5 度 ≈ 1.1 米
        assertEquals(wgsLat, backLat, 1e-5)
        assertEquals(wgsLng, backLng, 1e-5)
    }

    @Test
    fun `坐标串转换保持经度在前`() {
        val result = CoordTransform.wgs84PairToGcj02Pair("114.0579,22.5431")
        val parts = result.split(",")
        assertEquals(2, parts.size)
        val lng = parts[0].toDouble()
        val lat = parts[1].toDouble()
        // 经度是两位数以上且大于纬度，转换后顺序不能颠倒
        assertTrue("经度纬度疑似颠倒: $result", lng > 100.0 && lat in 20.0..25.0)
    }

    @Test
    fun `非法坐标串原样返回`() {
        assertEquals("", CoordTransform.wgs84PairToGcj02Pair(""))
        assertEquals("abc", CoordTransform.wgs84PairToGcj02Pair("abc"))
        assertEquals("1.0,2.0,3.0", CoordTransform.wgs84PairToGcj02Pair("1.0,2.0,3.0"))
        assertEquals("0.0,0.0", CoordTransform.wgs84PairToGcj02Pair("0.0,0.0"))
    }
}
