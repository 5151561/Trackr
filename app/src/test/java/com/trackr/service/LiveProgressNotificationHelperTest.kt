package com.trackr.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.TrackingSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveProgressNotificationHelperTest {

    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testCalculateCurrentProgress_matchesRemainingStops() {
        // 目标候车站：第 10 站；基准为线路起点 → 目标站，共 9 个站间，maxProgress = 900
        val baseSession = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7
        )

        // 1. 剩 3 站且刚驶离第 7 站（order=8, progress=0.0）：已完成 5 个站间 → 500/900
        val stateAtStart = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.0f,
            stopsRemaining = 3
        )
        assertEquals(500, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStart))

        // 2. 剩 2 站、行驶到第 7 与第 8 站中点：已完成 6.5 个站间 → 650/900
        val stateMidHop1 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.5f,
            stopsRemaining = 2
        )
        assertEquals(650, LiveProgressNotificationHelper.calculateCurrentProgress(stateMidHop1))

        // 3. 停靠第 8 站（progress=1.0）且剩 2 站：已完成 7 个站间 → 700/900
        val stateAtStop8 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 1.0f,
            stopsRemaining = 2
        )
        assertEquals(700, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStop8))

        // 4. 停靠第 9 站且剩 1 站：已完成 8 个站间 → 800/900（绝不能提前满格）
        val stateAtStop9 = baseSession.copy(
            currentBusOrder = 9,
            currentBusProgress = 1.0f,
            stopsRemaining = 1
        )
        assertEquals(800, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStop9))

        // 5. 第 9 与第 10 站之间行驶（order=10, progress=0.6）且剩 0 站：已完成 8.6 个站间 → 860/900
        val stateApproaching = baseSession.copy(
            currentBusOrder = 10,
            currentBusProgress = 0.6f,
            stopsRemaining = 0
        )
        assertEquals(860, LiveProgressNotificationHelper.calculateCurrentProgress(stateApproaching))

        // 6. 车辆已到达目标站停靠（isArrived=true）：进度必须为满格 900/900
        val stateArrived = baseSession.copy(
            currentBusOrder = 10,
            currentBusProgress = 1.0f,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        assertEquals(900, LiveProgressNotificationHelper.calculateCurrentProgress(stateArrived))
    }

    @Test
    fun testCalculateCurrentProgress_isMonotonicWithRemainingStops() {
        val baseSession = TrackingSessionState(
            isActive = true,
            lineName = "788",
            targetStopName = "珞雄路雄楚大道",
            targetOrder = 11,
            startTrackingOrder = 6
        )

        val remainingStops = listOf(6, 4, 2, 1, 0)
        val progresses = remainingStops.map { remaining ->
            LiveProgressNotificationHelper.calculateCurrentProgress(
                baseSession.copy(
                    currentBusOrder = 11 - remaining,
                    currentBusProgress = 0.5f,
                    stopsRemaining = remaining
                )
            )
        }

        progresses.zipWithNext().forEach { (previous, next) ->
            assertTrue("剩余站数减少时进度必须单调递增：$progresses", next > previous)
        }
        assertEquals(350, progresses.first())
    }

    @Test
    fun testBuildNotification_capsuleAndContentTexts_avoidRemainingZeroStops() {
        // 验证 stopsRemaining == 0 且在途即将进站时，短文本显示“即将进站”而不是荒谬的“剩0站”
        val stateApproaching = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7,
            currentBusOrder = 10,
            currentBusProgress = 0.5f,
            stopsRemaining = 0,
            etaSeconds = 90,
            isBusArrivedAtStop = false
        )

        val notifApproaching = LiveProgressNotificationHelper.buildNotification(context, stateApproaching)
        assertNotNull(notifApproaching)
        val title = notifApproaching.extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = notifApproaching.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("标题应包含线路名", title.contains("49路"))
        assertTrue("正文应显示即将进站而非剩0站", text.contains("即将进站"))
        assertTrue("正文不应包含剩 0 站", !text.contains("剩 0 站"))

        // 验证到达状态
        val stateArrived = stateApproaching.copy(
            isBusArrivedAtStop = true,
            etaSeconds = 0
        )
        val notifArrived = LiveProgressNotificationHelper.buildNotification(context, stateArrived)
        val arrivedText = notifArrived.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("到达状态正文应显示车辆已到达", arrivedText.contains("车辆已到达"))
    }
}
