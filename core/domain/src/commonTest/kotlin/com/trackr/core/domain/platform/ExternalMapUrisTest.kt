package com.trackr.core.domain.platform

import com.trackr.core.domain.math.CoordTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalMapUrisTest {

    private val wgsLat = 31.23
    private val wgsLng = 121.47
    private val name = "人民广场（广东路）"

    @Test
    fun `高德链接是GCJ02坐标加编码站名`() {
        val uri = ExternalMapUris.uriFor(ExternalMapApp.AMAP, wgsLat, wgsLng, name)
        val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)

        assertTrue(uri.startsWith("androidamap://route/plan/"), uri)
        assertTrue(uri.contains("dlat=${ExternalMapUris.formatCoord(gcjLat)}"), uri)
        assertTrue(uri.contains("dlon=${ExternalMapUris.formatCoord(gcjLng)}"), uri)
        assertTrue(uri.contains("&dev=0&t=2"), uri)
        // 站名不编码拼进去会被括号截断，链接里不能出现原文
        assertFalse(uri.contains("人民广场"), uri)
    }

    @Test
    fun `百度链接是BD09坐标`() {
        val uri = ExternalMapUris.uriFor(ExternalMapApp.BAIDU, wgsLat, wgsLng, name)
        val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
        val (bdLat, bdLng) = CoordTransform.gcj02ToBd09(gcjLat, gcjLng)

        assertTrue(uri.startsWith("baidumap://map/direction"), uri)
        assertTrue(
            uri.contains("latlng:${ExternalMapUris.formatCoord(bdLat)},${ExternalMapUris.formatCoord(bdLng)}"),
            uri
        )
        assertTrue(uri.contains("&mode=walking"), uri)
        // BD-09 和 GCJ-02 不是一回事，坐标串必须已经变掉
        assertFalse(uri.contains("${ExternalMapUris.formatCoord(gcjLat)},${ExternalMapUris.formatCoord(gcjLng)}"), uri)
    }

    @Test
    fun `腾讯链接是GCJ02坐标`() {
        val uri = ExternalMapUris.uriFor(ExternalMapApp.TENCENT, wgsLat, wgsLng, name)
        val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)

        assertTrue(uri.startsWith("qqmap://map/routeplan"), uri)
        assertTrue(uri.contains("type=walk"), uri)
        assertTrue(
            uri.contains("tocoord=${ExternalMapUris.formatCoord(gcjLat)},${ExternalMapUris.formatCoord(gcjLng)}"),
            uri
        )
    }

    @Test
    fun `系统geo链接用WGS84原坐标`() {
        val uri = ExternalMapUris.systemGeoUri(wgsLat, wgsLng, name)
        assertTrue(uri.startsWith("geo:31.23,121.47?q=31.23,121.47("), uri)
        assertTrue(uri.endsWith(")"), uri)
    }

    @Test
    fun `坐标格式化六位小数去尾零`() {
        assertEquals("121.47", ExternalMapUris.formatCoord(121.47))
        assertEquals("31.2304", ExternalMapUris.formatCoord(31.2304))
        assertEquals("0", ExternalMapUris.formatCoord(0.0))
        assertEquals("-73.9857", ExternalMapUris.formatCoord(-73.9857))
        assertEquals("121.473701", ExternalMapUris.formatCoord(121.4737009))
    }

    @Test
    fun `百分号编码只放行非保留字符`() {
        assertEquals("abcXYZ-_.~019", ExternalMapUris.percentEncode("abcXYZ-_.~019"))
        assertEquals("%20", ExternalMapUris.percentEncode(" "))
        assertEquals("%EF%BC%88", ExternalMapUris.percentEncode("（"))
        assertFalse(ExternalMapUris.percentEncode(name).contains("人民"))
    }
}
