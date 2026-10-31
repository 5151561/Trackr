package com.trackr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 接收来自常驻通知栏 Live Updates 交互按钮的广播，代理调用 BusTrackingService
 */
class TrackingActionReceiver : BroadcastReceiver(), KoinComponent {

    private val transferSwitcher: TransferAutoSwitcher by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_TRACKING -> {
                // 同时清掉换乘组状态，避免停止后收藏页仍显示“追踪中”的换乘组
                transferSwitcher.stopGroupTracking(context)
            }
            ACTION_CYCLE_THRESHOLD -> {
                BusTrackingService.cycleThreshold(context)
            }
            ACTION_CONFIRM_BOARDED -> {
                transferSwitcher.confirmBoarded(context)
            }
            ACTION_NEXT_LEG -> {
                transferSwitcher.switchToNextLeg(context)
            }
        }
    }

    companion object {
        const val ACTION_STOP_TRACKING = "com.trackr.action.STOP_TRACKING"
        const val ACTION_CYCLE_THRESHOLD = "com.trackr.action.CYCLE_THRESHOLD"
        const val ACTION_CONFIRM_BOARDED = "com.trackr.action.CONFIRM_BOARDED"
        const val ACTION_NEXT_LEG = "com.trackr.action.NEXT_LEG"
    }
}
