package com.trackr.core.model

/**
 * 手机定位快照（WGS-84）。
 *
 * @param accuracyMeters 水平精度（米），未知为 null（未知时不参与判定）
 * @param elapsedMillis 定位时刻，单调时钟毫秒（Android：`SystemClock.elapsedRealtime` 时基，
 *   即 `Location.elapsedRealtimeNanos / 1_000_000`）。不受用户改系统时间影响，进程重建后不可比较，所以不落盘。
 */
data class UserFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val elapsedMillis: Long
)
