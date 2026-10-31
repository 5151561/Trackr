package com.trackr.service

import android.content.Context

/**
 * ViewModel 控制追踪服务的入口，ViewModel 不再拿 Context 直接调 [TransferAutoSwitchManager]。
 *
 * 第 3 步只包一层，Android 实现原样转调原来的代码；第 7 / 12 步换成 `:core:domain` 的 `TrackingController`。
 */
interface TrackingController {

    /** 结束追踪：停止追踪服务；分组追踪时同时清掉换乘组状态 */
    fun stopTracking()

    /** 把带精度和定位时刻的手机定位喂给换乘引擎（下车站辅助判定用） */
    fun updateUserFix(lat: Double, lng: Double, accuracyMeters: Float?, elapsedRealtimeNanos: Long)
}

class AndroidTrackingController(context: Context) : TrackingController {

    private val appContext: Context = context.applicationContext ?: context

    override fun stopTracking() {
        TransferAutoSwitchManager.stopGroupTracking(appContext)
    }

    override fun updateUserFix(lat: Double, lng: Double, accuracyMeters: Float?, elapsedRealtimeNanos: Long) {
        TransferAutoSwitchManager.updateUserFix(
            lat = lat,
            lng = lng,
            accuracyMeters = accuracyMeters,
            elapsedRealtimeNanos = elapsedRealtimeNanos
        )
    }
}
