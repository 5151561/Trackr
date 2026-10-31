package com.trackr.service

import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState

/**
 * 追踪期间的定位频率（纯函数）：只在需要判断“是否上车 / 是否到下车站”的阶段开定位，其余时间关闭省电。
 */
object LocationCadence {

    enum class Tier(val intervalMillis: Long, val highAccuracy: Boolean) {
        /** 不主动定位 */
        OFF(0L, false),
        /** 车离上车站 ≤2 站或 ≤3 分钟：提前拿到准确位置，车到站时才能判断人在不在站 */
        WAITING_NEAR(10_000L, true),
        /** 车已到站、正在确认是否上车 */
        BOARDING_CHECK(5_000L, true),
        /** 乘车途中：低频，供错锁纠正 */
        RIDING(20_000L, false),
        /** 离下车站 ≤2 站：高频，供下车判定 */
        NEAR_DROP(8_000L, true)
    }

    private const val NEAR_STOPS = 2
    private const val NEAR_ETA_SECONDS = 180

    fun tierFor(session: TrackingSessionState): Tier {
        if (!session.isActive) return Tier.OFF
        return when (session.legPhase) {
            LegPhase.SINGLE -> Tier.OFF
            LegPhase.WAITING_BOARD -> when {
                session.isAwaitingBoardingConfirmation -> Tier.BOARDING_CHECK
                session.stopsRemaining in 0..NEAR_STOPS -> Tier.WAITING_NEAR
                session.etaSeconds in 1..NEAR_ETA_SECONDS -> Tier.WAITING_NEAR
                else -> Tier.OFF
            }
            LegPhase.ON_BOARD -> if (session.stopsRemaining in 0..NEAR_STOPS) Tier.NEAR_DROP else Tier.RIDING
        }
    }
}
