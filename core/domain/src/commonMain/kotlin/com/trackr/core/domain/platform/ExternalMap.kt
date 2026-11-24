package com.trackr.core.domain.platform

import com.trackr.core.domain.math.CoordTransform
import kotlin.math.abs
import kotlin.math.round

/**
 * 外部地图（站点页「导航到此站」跳的第三方地图 App）。
 * 这里只有「打开哪家」的标识和显示名；包名、Intent 都是平台自己的事，不进 commonMain。
 */
enum class ExternalMapApp(val displayName: String) {
    AMAP("高德地图"),
    BAIDU("百度地图"),
    TENCENT("腾讯地图")
}

/**
 * 外部地图的调起链接（纯函数，方便单测）。
 *
 * 入参一律是 **WGS-84**（站点存的、系统定位给的都是 WGS-84）：
 * - 高德 / 腾讯的链接按 GCJ-02 解释坐标，先用 [CoordTransform.wgs84ToGcj02] 转；
 * - 百度的链接按 BD-09 解释坐标，再多转一层 [CoordTransform.gcj02ToBd09]；
 * - 系统 `geo:` 是通用协议，按 WGS-84 解释，原样传。
 *
 * 站名要百分号编码：站名里常有括号、间隔号，不编码拼进链接会被截断或解析错位。
 */
object ExternalMapUris {

    /** [app] 的导航链接；高德 t=0 是驾车、dev=0 表示坐标已是 GCJ-02 不用再偏移 */
    fun uriFor(app: ExternalMapApp, wgsLat: Double, wgsLng: Double, name: String): String = when (app) {
        ExternalMapApp.AMAP -> {
            val (lat, lng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
            "androidamap://route/plan/?sourceApplication=Trackr" +
                "&dlat=${formatCoord(lat)}&dlon=${formatCoord(lng)}&dname=${percentEncode(name)}&dev=0&t=0"
        }

        ExternalMapApp.BAIDU -> {
            val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
            val (bdLat, bdLng) = CoordTransform.gcj02ToBd09(gcjLat, gcjLng)
            "baidumap://map/direction?destination=latlng:${formatCoord(bdLat)},${formatCoord(bdLng)}" +
                "|name:${percentEncode(name)}&mode=driving&src=android.com.github.u5151561.trackr"
        }

        ExternalMapApp.TENCENT -> {
            val (lat, lng) = CoordTransform.wgs84ToGcj02(wgsLat, wgsLng)
            "qqmap://map/routeplan?type=drive&to=${percentEncode(name)}" +
                "&tocoord=${formatCoord(lat)},${formatCoord(lng)}&referer=trackr&policy=0"
        }
    }

    /** 系统 `geo:`（一家地图都没装时的兜底，交给系统挑能处理 geo: 的应用） */
    fun systemGeoUri(wgsLat: Double, wgsLng: Double, name: String): String =
        "geo:${formatCoord(wgsLat)},${formatCoord(wgsLng)}" +
            "?q=${formatCoord(wgsLat)},${formatCoord(wgsLng)}(${percentEncode(name)})"

    /** 坐标定点 6 位小数（约 0.1 米）并去掉末尾的 0；commonMain 没有 String.format，自己拼 */
    internal fun formatCoord(value: Double): String {
        val scaled = round(value * 1_000_000).toLong()
        val sign = if (scaled < 0) "-" else ""
        val magnitude = abs(scaled)
        val intPart = magnitude / 1_000_000
        val frac = (magnitude % 1_000_000).toString().padStart(6, '0').trimEnd('0')
        return if (frac.isEmpty()) "$sign$intPart" else "$sign$intPart.$frac"
    }

    private const val HEX = "0123456789ABCDEF"

    /** UTF-8 百分号编码：只放行 RFC 3986 的非保留字符，其余全部编码成 %XX */
    internal fun percentEncode(value: String): String {
        val bytes = value.encodeToByteArray()
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }
}
