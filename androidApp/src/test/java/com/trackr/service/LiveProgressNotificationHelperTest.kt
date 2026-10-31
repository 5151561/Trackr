package com.trackr.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        LiveProgressNotificationHelper.liveNotificationEnabledProvider = { true }
    }

    @After
    fun tearDown() {
        LiveProgressNotificationHelper.resetLiveNotificationEnabledProvider()
    }

    @Test
    fun testCalculateCurrentProgress_matchesRemainingStops() {
        // 目标候车站：第 10 站，追踪起始站：第 7 站；追踪区间共 3 个站间，maxProgress = 300
        val baseSession = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7
        )

        // 1. 剩 3 站且刚驶离第 7 站（order=8, progress=0.0）：尚未走出追踪区间 → 0/300
        val stateAtStart = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.0f,
            stopsRemaining = 3
        )
        assertEquals(0, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStart))

        // 2. 剩 2 站、行驶到第 7 与第 8 站中点：已完成 0.5 个站间 → 50/300
        val stateMidHop1 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.5f,
            stopsRemaining = 2
        )
        assertEquals(50, LiveProgressNotificationHelper.calculateCurrentProgress(stateMidHop1))

        // 3. 停靠第 8 站（progress=1.0）且剩 2 站：已完成 1 个站间 → 100/300
        val stateAtStop8 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 1.0f,
            stopsRemaining = 2
        )
        assertEquals(100, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStop8))

        // 4. 停靠第 9 站且剩 1 站：已完成 2 个站间 → 200/300（绝不能提前满格）
        val stateAtStop9 = baseSession.copy(
            currentBusOrder = 9,
            currentBusProgress = 1.0f,
            stopsRemaining = 1
        )
        assertEquals(200, LiveProgressNotificationHelper.calculateCurrentProgress(stateAtStop9))

        // 5. 第 9 与第 10 站之间行驶（order=10, progress=0.6）且剩 0 站：已完成 2.6 个站间 → 260/300
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
    fun testCalculateCurrentProgress_isMonotonicWithRemainingStops() {
        val baseSession = TrackingSessionState(
            isActive = true,
            lineName = "788",
            targetStopName = "珞雄路雄楚大道",
            targetOrder = 11,
            startTrackingOrder = 6
        )

        // 追踪区间：第 6 → 第 11 站，共 5 个站间，maxProgress = 500
        val remainingStops = listOf(4, 3, 2, 1, 0)
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
        // 剩 4 站：completed = 5 - (4 + 0.5) = 0.5 → 50/500
        assertEquals(50, progresses.first())
        // 剩 0 站：completed = 5 - 0.5 = 4.5 → 450/500
        assertEquals(450, progresses.last())
    }

    @Test
    fun testBuildNotification_capsuleAndContentTexts_avoidRemainingZeroStops() {
        // stopsRemaining == 0 但 ETA 90 秒（不在 1..45 秒）：还没到站，写“下一站就到”，不写“剩 0 站”也不写“即将进站”
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
        assertEquals("上海体育馆：2 分钟 · 下一站就到", text)
        assertTrue("正文不应包含剩 0 站", !text.contains("剩 0 站"))
        assertFalse("没到 45 秒内不应说进站", text.contains("即将进站") || text.contains("到站了"))
        assertEquals("下一站到", LiveProgressNotificationHelper.shortCriticalTextFor(stateApproaching))

        // 验证到达状态
        val stateArrived = stateApproaching.copy(
            isBusArrivedAtStop = true,
            etaSeconds = 0
        )
        val notifArrived = LiveProgressNotificationHelper.buildNotification(context, stateArrived)
        val arrivedText = notifArrived.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertEquals("上海体育馆：到站了，快去上车", arrivedText)
        assertEquals("到站了", LiveProgressNotificationHelper.shortCriticalTextFor(stateArrived))
    }

    // ---------------- 分组换乘两阶段 ----------------

    private fun waitingState() = TrackingSessionState(
        isActive = true,
        lineName = "B612",
        targetStopName = "深大北门",
        targetOrder = 9,
        boardStopName = "科技园",
        boardStopOrder = 5,
        startTrackingOrder = 2,
        currentBusOrder = 3,
        currentBusProgress = 1.0f,
        stopsRemaining = 2,
        etaSeconds = 240,
        legPhase = LegPhase.WAITING_BOARD,
        currentLegIndex = 0,
        totalLegs = 2
    )

    private fun onBoardState() = waitingState().copy(
        legPhase = LegPhase.ON_BOARD,
        startTrackingOrder = 5,
        currentBusOrder = 7,
        currentBusProgress = 1.0f,
        stopsRemaining = 2,
        etaSeconds = 300
    )

    @Test
    fun shortCriticalText_byPhase() {
        assertEquals("B612 2站", LiveProgressNotificationHelper.shortCriticalTextFor(waitingState()))
        assertEquals("等车2站", LiveProgressNotificationHelper.shortCriticalTextFor(waitingState().copy(lineName = "高峰专线12")))
        assertEquals("2站下车", LiveProgressNotificationHelper.shortCriticalTextFor(onBoardState()))
        val arrivedTransfer = onBoardState().copy(stopsRemaining = 0, isBusArrivedAtStop = true)
        assertEquals("换乘", LiveProgressNotificationHelper.shortCriticalTextFor(arrivedTransfer))
        val arrivedLast = arrivedTransfer.copy(currentLegIndex = 1)
        assertEquals("已到站", LiveProgressNotificationHelper.shortCriticalTextFor(arrivedLast))
        listOf(waitingState(), onBoardState(), arrivedTransfer, waitingState().copy(stopsRemaining = 12)).forEach {
            assertTrue(LiveProgressNotificationHelper.shortCriticalTextFor(it).length <= 7)
        }
    }

    @Test
    fun buildTexts_waitingBoard() {
        val texts = LiveProgressNotificationHelper.buildTexts(waitingState(), nextLineName = "M492")
        assertEquals("B612 4 分钟到 科技园", texts.title)
        assertEquals("还有 2 站", texts.content)
        assertEquals("第 1/2 程 · 等车", texts.subText)

        val noEta = LiveProgressNotificationHelper.buildTexts(waitingState().copy(etaSeconds = -1))
        assertEquals("B612 还有 2 站到 科技园", noEta.title)
        assertEquals("暂无到站时间", noEta.content)

        val nextStop = LiveProgressNotificationHelper.buildTexts(waitingState().copy(stopsRemaining = 0, etaSeconds = 90))
        assertEquals("B612 2 分钟到 科技园", nextStop.title)
        assertEquals("下一站就到", nextStop.content)
        assertEquals("下一站到", LiveProgressNotificationHelper.shortCriticalTextFor(waitingState().copy(stopsRemaining = 0, etaSeconds = 90)))

        val arrived = waitingState().copy(stopsRemaining = 0, etaSeconds = 30)
        val arrivedTexts = LiveProgressNotificationHelper.buildTexts(arrived)
        assertEquals("B612 到站了 · 科技园", arrivedTexts.title)
        assertEquals("快去上车，上车后点「我已上车」", arrivedTexts.content)
        assertEquals("到站了", LiveProgressNotificationHelper.shortCriticalTextFor(arrived))
    }

    @Test
    fun buildTexts_onBoard_withTransferAndRange() {
        val texts = LiveProgressNotificationHelper.buildTexts(onBoardState(), nextLineName = "M492")
        assertEquals("还有 2 站在 深大北门 下车", texts.title)
        assertEquals("约 5 分钟 · 下车后换乘 M492 · 科技园 → 深大北门", texts.content)
        assertEquals("第 1/2 程 · 乘车", texts.subText)

        // 最后一程不写换乘
        val last = LiveProgressNotificationHelper.buildTexts(onBoardState().copy(currentLegIndex = 1), nextLineName = null)
        assertFalse(last.content.contains("换乘"))
        assertEquals("第 2/2 程 · 乘车", last.subText)
    }

    @Test
    fun notificationActions_byPhase() {
        val waiting = LiveProgressNotificationHelper.notificationActions(waitingState())
        assertEquals(listOf("我已上车", "提前2站提醒", "结束"), waiting.map { it.label })
        assertEquals(TrackingActionReceiver.ACTION_CONFIRM_BOARDED, waiting.first().intentAction)

        val onBoard = LiveProgressNotificationHelper.notificationActions(onBoardState())
        assertEquals(listOf("跳到下一程", "结束"), onBoard.map { it.label })
        assertEquals(TrackingActionReceiver.ACTION_NEXT_LEG, onBoard.first().intentAction)

        val onBoardLast = LiveProgressNotificationHelper.notificationActions(onBoardState().copy(currentLegIndex = 1))
        assertEquals(listOf("结束"), onBoardLast.map { it.label })

        val single = LiveProgressNotificationHelper.notificationActions(TrackingSessionState(isActive = true))
        assertEquals(listOf("提前2站提醒(切换)", "结束追踪"), single.map { it.label })
    }

    @Test
    fun progressRange_followsPhase() {
        // 等车：选中车辆时所在站(2) → 上车站(5)，共 3 个站间；车停在第 3 站、剩 2 站 → 1/3
        assertEquals(2 to 5, LiveProgressNotificationHelper.progressRangeOf(waitingState()))
        assertEquals(100, LiveProgressNotificationHelper.calculateCurrentProgress(waitingState()))
        // 乘车：上车站(5) → 下车站(9)，共 4 个站间；车停在第 7 站、剩 2 站 → 2/4
        assertEquals(5 to 9, LiveProgressNotificationHelper.progressRangeOf(onBoardState()))
        assertEquals(200, LiveProgressNotificationHelper.calculateCurrentProgress(onBoardState()))
    }

    @Test
    fun buildNotification_onBoard_keepsProgressStyleAndPhaseActions() {
        val notification = LiveProgressNotificationHelper.buildNotification(context, onBoardState(), nextLineName = "M492")
        assertEquals("还有 2 站在 深大北门 下车", notification.extras.getCharSequence("android.title")?.toString())
        assertEquals("第 1/2 程 · 乘车", notification.extras.getCharSequence("android.subText")?.toString())
        assertEquals(listOf("跳到下一程", "结束"), notification.actions.map { it.title.toString() })
        assertEquals(400, notification.extras.getInt("android.progressMax"))
        assertEquals(200, notification.extras.getInt("android.progress"))
    }
}
