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
    fun testCalculateCurrentProgress_accurateHopMapping() {
        // 目标候车站：第 10 站；追踪起始基准站：第 7 站。跨度为 3 站，maxProgress = 300
        val baseSession = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7
        )

        // 1. 车辆在第 7 站刚出发开往第 8 站（progress=0.0）：连续位置 7.0，已完成 0 hop，进度必须为 0
        val stateAtStart = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.0f,
            stopsRemaining = 3
        )
        assertEquals(0, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStart))

        // 2. 车辆在第 7 站与第 8 站之间中点（progress=0.5）：连续位置 7.5，已完成 0.5 hop，进度必须为 50/300
        val stateMidHop1 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.5f,
            stopsRemaining = 2
        )
        assertEquals(50, LiveProgressNotificationHelper.calculateCurrentProgress(stateMidHop1))

        // 3. 车辆到达第 8 站停靠（progress=1.0）：连续位置 8.0，已完成 1 hop，进度必须为 100/300
        val stateAtStop8 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 1.0f,
            stopsRemaining = 2
        )
        assertEquals(100, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStop8))

        // 4. 车辆到达第 9 站停靠（progress=1.0）：连续位置 9.0，还差 1 站才到目标站，已完成 2 hops，进度必须严格为 200/300（绝不能提前满格）
        val stateAtStop9 = baseSession.copy(
            currentBusOrder = 9,
            currentBusProgress = 1.0f,
            stopsRemaining = 1
        )
        assertEquals(200, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStop9))

        // 5. 车辆在第 9 站与第 10 站之间行驶（order=10, progress=0.6）：连续位置 9.6，已完成 2.6 hops，进度必须为 260/300
        val stateApproaching = baseSession.copy(
            currentBusOrder = 10,
            currentBusProgress = 0.6f,
            stopsRemaining = 0
        )
        assertEquals(260, LiveProgressNotificationHelper.calculateCurrentProgress(stateApproaching))

        // 6. 车辆已到达目标站停靠（isArrived=true）：进度必须为满格 300/300
        val stateArrived = baseSession.copy(
            currentBusOrder = 10,
            currentBusProgress = 1.0f,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        assertEquals(300, LiveProgressNotificationHelper.calculateCurrentProgress(stateArrived))
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
