package com.trackr.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat

/**
 * 追踪服务内的定位来源（系统 LocationManager，返回 WGS-84）。
 *
 * 频率由 [LocationCadence] 决定，[apply] 只在档位变化时重新注册监听；停止追踪时必须调用 [stop]。
 * 只在有精确定位权限时工作：模糊定位精度在公里级，无法判断人是否在车上。
 */
internal class ServiceLocationSource(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** 每个 provider 一个监听器：同一个监听器重复注册会替换掉前一个 provider */
    private val listeners = mutableMapOf<String, LocationListenerCompat>()

    private var activeTier: LocationCadence.Tier = LocationCadence.Tier.OFF

    /** 最近一次被采用的定位 */
    var latestFix: Location? = null
        private set

    /** 收到新定位后的回调（主线程） */
    var onFix: ((Location) -> Unit)? = null

    fun hasPrecisePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 最近一次定位是否足够新、足够准，可用于跟车判定。
     */
    fun usableFix(maxAgeMillis: Long = MAX_FIX_AGE_MILLIS, maxAccuracyMeters: Float = MAX_ACCURACY_METERS): Location? {
        val fix = latestFix ?: return null
        if (!fix.hasAccuracy() || fix.accuracy > maxAccuracyMeters) return null
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
        return fix.takeIf { ageMillis in 0..maxAgeMillis }
    }

    @SuppressLint("MissingPermission") // 权限在 hasPrecisePermission() 中检查
    fun apply(tier: LocationCadence.Tier) {
        if (tier == activeTier) return
        stopUpdates()
        if (tier == LocationCadence.Tier.OFF) return
        val lm = locationManager ?: return
        if (!hasPrecisePermission()) return

        val request = LocationRequestCompat.Builder(tier.intervalMillis)
            .setQuality(
                if (tier.highAccuracy) LocationRequestCompat.QUALITY_HIGH_ACCURACY
                else LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
            )
            .setMinUpdateIntervalMillis(tier.intervalMillis / 2)
            .build()
        val executor = ContextCompat.getMainExecutor(context)

        pickProviders(lm, tier).forEach { provider ->
            val listener = LocationListenerCompat { location -> accept(location) }
            try {
                LocationManagerCompat.requestLocationUpdates(lm, provider, request, executor, listener)
                listeners[provider] = listener
            } catch (e: Exception) {
                Log.w(TAG, "Cannot request location updates from $provider", e)
            }
        }
        if (listeners.isNotEmpty()) activeTier = tier
    }

    /** 停止定位并清空缓存的定位 */
    fun stop() {
        stopUpdates()
        latestFix = null
    }

    private fun stopUpdates() {
        val lm = locationManager
        if (lm != null) {
            listeners.values.forEach { listener ->
                runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
            }
        }
        listeners.clear()
        activeTier = LocationCadence.Tier.OFF
    }

    /**
     * 多个 provider 同时回调时：新定位不够准、而手上的定位仍新且准，就保留旧的。
     */
    private fun accept(location: Location) {
        val current = latestFix
        val newIsPoor = !location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS
        if (current != null && newIsPoor) {
            val currentAgeMillis = (location.elapsedRealtimeNanos - current.elapsedRealtimeNanos) / 1_000_000L
            val currentIsGood = current.hasAccuracy() && current.accuracy <= MAX_ACCURACY_METERS
            if (currentIsGood && currentAgeMillis in 0..KEEP_GOOD_FIX_MILLIS) return
        }
        latestFix = location
        onFix?.invoke(location)
    }

    private fun pickProviders(lm: LocationManager, tier: LocationCadence.Tier): List<String> {
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        return buildList {
            if (tier.highAccuracy && LocationManager.GPS_PROVIDER in enabled) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in enabled) add(LocationManager.NETWORK_PROVIDER)
            if (isEmpty() && LocationManager.GPS_PROVIDER in enabled) add(LocationManager.GPS_PROVIDER)
        }
    }

    companion object {
        private const val TAG = "ServiceLocationSource"
        const val MAX_FIX_AGE_MILLIS = 30_000L
        const val MAX_ACCURACY_METERS = 50f
        private const val KEEP_GOOD_FIX_MILLIS = 15_000L
    }
}
