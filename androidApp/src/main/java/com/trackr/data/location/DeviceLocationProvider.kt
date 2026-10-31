package com.trackr.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 设备定位（WGS-84）。ViewModel 通过它取定位，不再直接持有 Context / LocationManager。
 *
 * 第 3 步只是把两个 ViewModel 里原有的定位代码搬到这里，算法不变；
 * 第 8 步再换成 `LocationRepository` + `LocationSource`。
 */
interface DeviceLocationProvider {

    /**
     * 首页用：取一次当前定位，先看 lastKnown，不够新或不够准时在窗口内主动监听取最准的一个。
     * 没有定位权限或拿不到时返回 null。
     */
    suspend fun fetchCurrentLocation(forceFresh: Boolean): Location?

    /** 路线规划「我的位置」兜底用：只看系统 lastKnown，10 分钟内、精度最高的一个；返回 (lat, lng) */
    fun lastKnownWgsLocation(): Pair<Double, Double>?
}

/** [DeviceLocationProvider] 的 Android 实现：代码原样搬自 `HomeViewModel.fetchGpsLocation` 和 `FavoritesGroupViewModel.lastKnownWgsLocation` */
class AndroidDeviceLocationProvider(context: Context) : DeviceLocationProvider {

    private val appContext: Context = context.applicationContext ?: context
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun hasLocationPermission(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    /**
     * 获取一次设备定位（WGS-84）。
     *
     * 1. 先看各 provider 的 lastKnown：够新（强制刷新 10 秒 / 否则 60 秒内）且精度 ≤100m 的直接用；
     * 2. 否则在 [LOCATION_WINDOW_MS] 窗口内主动监听，收集多个 fix 取精度最高的（拿到 ≤100m 的提前结束）；
     * 3. 窗口内什么都没拿到时，才退回 lastKnown 里最新的一个，且只接受 [MAX_STALE_LOCATION_AGE_MS] 内的。
     * 年龄一律用单调时钟 elapsedRealtimeNanos 计算，不受用户改系统时间影响。
     */
    override suspend fun fetchCurrentLocation(forceFresh: Boolean): Location? {
        if (!hasLocationPermission()) return null
        val lm = locationManager ?: return null

        val candidateProviders = listOf(
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var bestFresh: Location? = null
        var bestStale: Location? = null
        val freshThreshold = if (forceFresh) 10_000L else 60_000L // 强制刷新时仅复用10秒内极新鲜的定位，超时则向硬件重新请求实时 fix

        for (provider in candidateProviders) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    val age = locationAgeMillis(loc)
                    if (age < 0L) continue
                    if (age < freshThreshold && isMoreAccurate(loc, bestFresh)) {
                        bestFresh = loc
                    }
                    if (age <= MAX_STALE_LOCATION_AGE_MS &&
                        (bestStale == null || age < locationAgeMillis(bestStale))
                    ) {
                        bestStale = loc
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        val freshCandidate = bestFresh
        if (freshCandidate != null && isGoodEnough(freshCandidate)) {
            return freshCandidate
        }

        // 主动向可用 provider 发起高频监听，在窗口内收集多个 fix
        val activeProviders = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { provider ->
            try {
                lm.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }

        if (activeProviders.isEmpty()) {
            return freshCandidate ?: bestStale
        }

        // 窗口内收到的最准 fix（监听回调与本协程都在主线程，无并发问题）
        var bestLive: Location? = null
        val goodFix = withTimeoutOrNull(LOCATION_WINDOW_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (isMoreAccurate(location, bestLive)) {
                            bestLive = location
                        }
                        if (isGoodEnough(location)) {
                            try {
                                lm.removeUpdates(this)
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) {
                                cont.resume(location)
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }

                var requestedAny = false
                for (provider in activeProviders) {
                    try {
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                        requestedAny = true
                    } catch (_: SecurityException) {
                    } catch (_: Exception) {
                    }
                }

                if (!requestedAny && cont.isActive) {
                    cont.resume(null)
                }
            }
        }

        val liveCandidate = bestLive
        return goodFix
            ?: pickMoreAccurate(liveCandidate, freshCandidate)
            ?: bestStale
    }

    /**
     * 系统 lastKnown 定位兜底（WGS-84）：先检查定位权限，只接受 10 分钟内的定位，多个来源取精度最高的。
     */
    @SuppressLint("MissingPermission")
    override fun lastKnownWgsLocation(): Pair<Double, Double>? {
        val app = appContext
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            try {
                lm.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }.filter { locationAgeMillis(it) in 0..MAX_LAST_KNOWN_AGE_MS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        return best?.let { it.latitude to it.longitude }
    }

    /** 定位年龄（毫秒）：优先用单调时钟 elapsedRealtimeNanos（不受改系统时间影响），缺失（为 0）时回落墙钟 time */
    private fun locationAgeMillis(loc: Location): Long =
        if (loc.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - loc.time
        }

    private fun accuracyOf(loc: Location): Float = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE

    private fun isGoodEnough(loc: Location): Boolean = loc.hasAccuracy() && loc.accuracy <= GOOD_ACCURACY_METERS

    private fun isMoreAccurate(candidate: Location, current: Location?): Boolean =
        current == null || accuracyOf(candidate) < accuracyOf(current)

    private fun pickMoreAccurate(a: Location?, b: Location?): Location? = when {
        a == null -> b
        b == null -> a
        accuracyOf(a) <= accuracyOf(b) -> a
        else -> b
    }

    companion object {
        /** 主动定位的收集窗口 */
        const val LOCATION_WINDOW_MS = 4_500L

        /** 精度达到该值即可提前结束定位收集 */
        const val GOOD_ACCURACY_METERS = 100f

        /** 兜底使用的旧定位最大年龄（首页） */
        const val MAX_STALE_LOCATION_AGE_MS = 10 * 60 * 1000L

        /** 路线规划「我的位置」兜底：系统 lastKnown 定位的最大年龄 */
        const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L
    }
}
