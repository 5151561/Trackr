package com.trackr.ui.screens.favoritesgroup

import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.ui.screens.favoritesgroup.components.CardHeadline
import com.trackr.ui.screens.favoritesgroup.components.boardTimeText
import com.trackr.ui.screens.favoritesgroup.components.cardHeadline
import com.trackr.ui.screens.favoritesgroup.components.etaMinutesOf
import com.trackr.ui.screens.favoritesgroup.components.boardTimeTextForLeg
import com.trackr.ui.screens.favoritesgroup.components.estimateBoardTimeMillis
import com.trackr.ui.screens.favoritesgroup.components.groupSummaryText
import com.trackr.ui.screens.favoritesgroup.components.groupTitleText
import com.trackr.ui.screens.favoritesgroup.components.isStopOrderUnaligned
import com.trackr.ui.screens.favoritesgroup.components.legProgressFractions
import com.trackr.ui.screens.favoritesgroup.components.nextLegLiveText
import com.trackr.ui.screens.favoritesgroup.components.rideStopsText
import com.trackr.ui.screens.favoritesgroup.components.shouldShowNextLegLive
import com.trackr.ui.screens.favoritesgroup.components.trackingPhaseLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TransitGroupCardTextTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-31 07:40 (Asia/Shanghai) */
    private val now: Long = LocalDateTime.of(2026, 10, 31, 7, 40).atZone(zone).toInstant().toEpochMilli()

    private fun line(id: String, from: Int, to: Int) =
        CandidateLine(cityId = "0755", lineId = id, lineName = id, fromStopOrder = from, toStopOrder = to)

    // ---------- 站数待确认 ----------

    @Test
    fun rideStopsText_alignedLine_showsStopCount() {
        assertEquals("坐 4 站", rideStopsText(line("B612", 5, 9)))
        assertFalse(isStopOrderUnaligned(line("B612", 5, 9)))
    }

    @Test
    fun rideStopsText_syntheticLineId_showsPending() {
        assertTrue(isStopOrderUnaligned(line("line_123", 5, 9)))
        assertEquals("站数待确认", rideStopsText(line("line_123", 5, 9)))
    }

    @Test
    fun rideStopsText_invalidStopOrders_showPending() {
        assertEquals("站数待确认", rideStopsText(line("B612", 0, 4)))
        assertEquals("站数待确认", rideStopsText(line("B612", 6, 6)))
        assertEquals("站数待确认", rideStopsText(line("B612", 8, 3)))
        assertEquals("站数待确认", rideStopsText(null))
    }

    @Test
    fun groupSummaryText_directAndTransfer() {
        val direct = TransitGroup(
            id = "g1", name = "n",
            legs = listOf(TransitLeg(0, "科技园", toStopName = "深大北门", candidateLines = listOf(line("B612", 5, 9))))
        )
        assertEquals("直达 · 坐 4 站", groupSummaryText(direct, listOf(line("B612", 5, 9))))
        assertEquals("直达 · 站数待确认", groupSummaryText(direct, listOf(line("line_1", 5, 9))))

        val transfer = TransitGroup(
            id = "g2", name = "n",
            legs = listOf(
                TransitLeg(0, "科技园", toStopName = "深大北门", walkingDistanceMeters = 0),
                TransitLeg(1, "深大北门", toStopName = "车公庙", walkingDistanceMeters = 120)
            )
        )
        assertEquals("换乘 1 次 · 步行 120 米", groupSummaryText(transfer, listOf(null, null)))
        assertEquals("科技园 → 车公庙", groupTitleText(transfer))
    }

    // ---------- 预计上车时间 ----------

    @Test
    fun boardTimeText_firstLegEtaPlusRideStops() {
        // 6 分钟后上第 1 程车 + 坐 4 站 × 2 分钟 = 14 分钟 → 07:54
        assertEquals("预计 07:54 上车", boardTimeText(now, 6, listOf(4), zoneId = zone))
    }

    @Test
    fun boardTimeText_includesWalkingAt80MetersPerMinute() {
        // 6 + 4×2 + ceil(200/80)=3 → 17 分钟 → 07:57
        assertEquals("预计 07:57 上车", boardTimeText(now, 6, listOf(4), walkMeters = 200, zoneId = zone))
    }

    @Test
    fun boardTimeText_unknownEtaOrUnalignedStops_returnsNull() {
        assertNull(boardTimeText(now, null, listOf(4), zoneId = zone))
        assertNull(boardTimeText(now, 6, listOf(null), zoneId = zone))
        assertNull(estimateBoardTimeMillis(now, -1, listOf(4)))
    }

    @Test
    fun boardTimeTextForLeg_thirdLegSumsAllPreviousRides() {
        // 未追踪：第 1 程 ETA 5 分钟，第 1 / 2 程分别坐 4 / 3 站 → 5 + 14 = 19 分钟 → 07:59
        val text = boardTimeTextForLeg(
            targetLegIndex = 2,
            anchorLegIndex = 0,
            anchorIsDropStop = false,
            anchorMinutes = 5,
            rideStopsPerLeg = listOf(4, 3, 6),
            walkMetersPerLeg = listOf(0, 0, 0),
            nowMillis = now,
            zoneId = zone
        )
        assertEquals("预计 07:59 上车", text)
    }

    @Test
    fun boardTimeTextForLeg_onBoardAnchorIsDropStop() {
        // 乘车中：离第 1 程下车站 5 分钟，下一程直接上车 → 07:45
        val text = boardTimeTextForLeg(
            targetLegIndex = 1,
            anchorLegIndex = 0,
            anchorIsDropStop = true,
            anchorMinutes = 5,
            rideStopsPerLeg = listOf(null, 3),
            walkMetersPerLeg = listOf(0, 0),
            nowMillis = now,
            zoneId = zone
        )
        assertEquals("预计 07:45 上车", text)
    }

    @Test
    fun boardTimeTextForLeg_previousLegUnaligned_returnsNull() {
        val text = boardTimeTextForLeg(
            targetLegIndex = 1,
            anchorLegIndex = 0,
            anchorIsDropStop = false,
            anchorMinutes = 5,
            rideStopsPerLeg = listOf(null, 3),
            walkMetersPerLeg = listOf(0, 0),
            nowMillis = now,
            zoneId = zone
        )
        assertNull(text)
    }

    // ---------- 追踪状态 ----------

    @Test
    fun trackingPhaseLabel_andProgressFractions() {
        assertEquals("第 1/2 程 · 等车", trackingPhaseLabel(0, 2, LegPhase.WAITING_BOARD))
        assertEquals("第 2/2 程 · 乘车", trackingPhaseLabel(1, 2, LegPhase.ON_BOARD))
        assertEquals(listOf(0.5f, 0f), legProgressFractions(2, 0, LegPhase.WAITING_BOARD))
        assertEquals(listOf(1f, 1f, 0f), legProgressFractions(3, 1, LegPhase.ON_BOARD))
    }

    @Test
    fun nextLegLive_onlyWhenOnBoardAndCloseToTransfer() {
        assertTrue(shouldShowNextLegLive(LegPhase.ON_BOARD, 2))
        assertFalse(shouldShowNextLegLive(LegPhase.ON_BOARD, 3))
        assertFalse(shouldShowNextLegLive(LegPhase.WAITING_BOARD, 1))

        val info = CandidateLineRealtimeInfo(lineId = "M492", lineName = "M492", stopsAway = 6, estimatedMinutes = 12)
        assertEquals("现在离 深大北门 6 站 · 约 12 分钟", nextLegLiveText("深大北门", info))
        assertNull(nextLegLiveText("深大北门", CandidateLineRealtimeInfo(lineId = "x", lineName = "x")))
    }

    @Test
    fun lineDetailNavigation_usesBoardStopOrder() {
        assertEquals(5, line("B612", 5, 9).boardStopOrderForDetail())
        assertEquals(9, line("line_1", 0, 9).boardStopOrderForDetail())
    }

    // ---------- 主数字（到站文字走 ArrivalText / ArrivalRules） ----------

    private fun waitingSession(stops: Int, eta: Int, arrived: Boolean = false) = TrackingSessionState(
        isActive = true,
        lineName = "B612",
        targetStopName = "深大北门",
        boardStopName = "科技园",
        stopsRemaining = stops,
        etaSeconds = eta,
        isBusArrivedAtStop = arrived,
        legPhase = LegPhase.WAITING_BOARD
    )

    @Test
    fun cardHeadline_waiting_minutesAndStops() {
        val h = cardHeadline(LegPhase.WAITING_BOARD, waitingSession(3, 360), "科技园", "深大北门", null, null, null)
        assertEquals(CardHeadline("6", "分钟后到 科技园", "还有 3 站", isSuccess = false), h)

        // ≤ 3 分钟用绿色
        assertTrue(cardHeadline(LegPhase.WAITING_BOARD, waitingSession(3, 120), "科技园", "深大北门", null, null, null).isSuccess)
    }

    @Test
    fun cardHeadline_waiting_zeroStopsNotImminent_isNextStop() {
        // 剩 0 站但 ETA 90 秒：还没到，主数字仍是分钟，小字写「下一站就到」
        val h = cardHeadline(LegPhase.WAITING_BOARD, waitingSession(0, 90), "科技园", "深大北门", null, null, null)
        assertEquals("2", h.number)
        assertEquals("下一站就到", h.detail)
        assertTrue(h.isSuccess)
    }

    @Test
    fun cardHeadline_waiting_arrivedByArrivalRules() {
        val byEta = cardHeadline(LegPhase.WAITING_BOARD, waitingSession(0, 30), "科技园", "深大北门", null, null, null)
        assertEquals(CardHeadline(null, "到站了 · 快去 科技园 上车", null, isSuccess = true), byEta)

        val byFlag = cardHeadline(LegPhase.WAITING_BOARD, waitingSession(0, -1, arrived = true), "科技园", "深大北门", null, null, null)
        assertNull(byFlag.number)
        assertEquals("到站了 · 快去 科技园 上车", byFlag.text)
    }

    @Test
    fun cardHeadline_untracked_usesRealtimeInfo() {
        val info = CandidateLineRealtimeInfo(
            lineId = "B612", lineName = "B612", stopsAway = 4, estimatedMinutes = 8, vehiclePlate = "粤B·58921"
        )
        assertEquals(
            CardHeadline("8", "分钟后到 科技园", "还有 4 站 · 粤B·58921"),
            cardHeadline(null, null, "科技园", "深大北门", info, null, null)
        )

        val arrived = info.copy(shortChipEta = "已到站", stopsAway = 0, estimatedMinutes = 1)
        assertEquals(
            CardHeadline(null, "到站了 · 快去 科技园 上车", "粤B·58921", isSuccess = true),
            cardHeadline(null, null, "科技园", "深大北门", arrived, null, null)
        )
    }

    @Test
    fun cardHeadline_onBoard_arrivedAtDropStop() {
        val onBoard = waitingSession(0, 30).copy(legPhase = LegPhase.ON_BOARD)
        assertEquals(
            CardHeadline(null, "到 深大北门 了，请下车", "下车后换乘 M492", isSuccess = true),
            cardHeadline(LegPhase.ON_BOARD, onBoard, "科技园", "深大北门", null, null, "M492")
        )

        val riding = waitingSession(2, 300).copy(legPhase = LegPhase.ON_BOARD)
        assertEquals(
            CardHeadline("2", "站后在 深大北门 下车", "约 5 分钟 · 下车后换乘 M492", isSuccess = true),
            cardHeadline(LegPhase.ON_BOARD, riding, "科技园", "深大北门", null, null, "M492")
        )
    }

    @Test
    fun etaMinutesOf_prefersEtaThenStopsEstimate() {
        assertEquals(2, etaMinutesOf(90, 3))
        assertEquals(6, etaMinutesOf(-1, 3))
        assertEquals(1, etaMinutesOf(0, 0))
        assertNull(etaMinutesOf(-1, -1))
    }
}
