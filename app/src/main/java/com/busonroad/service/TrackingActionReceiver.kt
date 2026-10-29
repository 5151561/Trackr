package com.busonroad.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收来自常驻通知栏 Live Updates 交互按钮的广播，代理调用 BusTrackingService
 */
class TrackingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_TRACKING -> {
                BusTrackingService.stopTracking(context)
            }
            ACTION_CYCLE_THRESHOLD -> {
                BusTrackingService.cycleThreshold(context)
            }
        }
    }

    companion object {
        const val ACTION_STOP_TRACKING = "com.busonroad.action.STOP_TRACKING"
        const val ACTION_CYCLE_THRESHOLD = "com.busonroad.action.CYCLE_THRESHOLD"
    }
}
