package com.trackr.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84（GPS 原始坐标）与中国火星坐标 GCJ-02 互转。
 *
 * 坐标系分工：
 * - **系统定位**（LocationManager / Geocoder）与「车来了」接口均使用 **WGS-84**；
 * - **高德地图**渲染、公交算路、POI 检索、`LatLng` / `LatLonPoint` 全部要求 **GCJ-02**。
 *
 * 中国境内两者偏差 300~600 米。把 WGS-84 直接丢给高德，会让起点标记落到隔壁街区、
 * 算路方案整体偏移；反向把 GCJ-02 当 WGS-84 传给「车来了」，则会把附近站点算错。
 * 因此所有跨链路的坐标都必须在边界处显式转换，不允许"看起来差不多就直接传"。
 */
object CoordTransform {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 是否在中国境外（境外不做偏移，WGS-84 与 GCJ-02 等价） */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02（传给高德地图 / 高德算路前调用） */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val mgLat = lat + (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val mgLng = lng + (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return mgLat to mgLng
    }

    /**
     * GCJ-02 → WGS-84（把高德返回的坐标交给「车来了」或系统 Geocoder 前调用）。
     * 采用不动点迭代反算，3 轮后误差在厘米级。
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var wgsLat = lat
        var wgsLng = lng
        repeat(3) {
            val (gLat, gLng) = wgs84ToGcj02(wgsLat, wgsLng)
            wgsLat += lat - gLat
            wgsLng += lng - gLng
        }
        return wgsLat to wgsLng
    }

    /**
     * 将 `"lng,lat"` 格式的 WGS-84 坐标串转换为 GCJ-02 坐标串，格式非法时原样返回。
     * 用于拼装高德算路参数 `origin` / `destination`。
     */
    fun wgs84PairToGcj02Pair(lngLat: String): String {
        val parts = lngLat.split(",")
        if (parts.size != 2) return lngLat
        val lng = parts[0].trim().toDoubleOrNull() ?: return lngLat
        val lat = parts[1].trim().toDoubleOrNull() ?: return lngLat
        if (lng == 0.0 || lat == 0.0) return lngLat
        val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
        return "$gcjLng,$gcjLat"
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
