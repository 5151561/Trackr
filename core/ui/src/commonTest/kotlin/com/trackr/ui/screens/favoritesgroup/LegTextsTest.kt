package com.trackr.ui.screens.favoritesgroup

import com.trackr.core.model.CandidateLine
import com.trackr.core.model.LegPhase
import com.trackr.ui.screens.favoritesgroup.components.etaMinutesOf
import com.trackr.ui.screens.favoritesgroup.components.isStopOrderUnaligned
import com.trackr.ui.screens.favoritesgroup.components.trackingPhaseLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 路线规划、路线详情还在用的几个纯函数（LegTexts.kt、BoardStopOrder.kt）。
 * 第 29 步删旧收藏卡片时从 TransitGroupCardTextTest 搬过来，断言不变。
 */
class LegTextsTest {

    private fun line(id: String, from: Int, to: Int) =
        CandidateLine(cityId = "0755", lineId = id, lineName = id, fromStopOrder = from, toStopOrder = to)

    @Test
    fun isStopOrderUnaligned_syntheticLineIdOnly() {
        assertFalse(isStopOrderUnaligned(line("B612", 5, 9)))
        assertTrue(isStopOrderUnaligned(line("line_123", 5, 9)))
    }

    @Test
    fun trackingPhaseLabel_legAndPhase() {
        assertEquals("第 1/2 程 · 等车", trackingPhaseLabel(0, 2, LegPhase.WAITING_BOARD))
        assertEquals("第 2/2 程 · 乘车", trackingPhaseLabel(1, 2, LegPhase.ON_BOARD))
    }

    @Test
    fun lineDetailNavigation_usesBoardStopOrder() {
        assertEquals(5, line("B612", 5, 9).boardStopOrderForDetail())
        assertEquals(9, line("line_1", 0, 9).boardStopOrderForDetail())
    }

    @Test
    fun etaMinutesOf_prefersEtaThenStopsEstimate() {
        assertEquals(2, etaMinutesOf(90, 3))
        assertEquals(6, etaMinutesOf(-1, 3))
        assertEquals(1, etaMinutesOf(0, 0))
        assertNull(etaMinutesOf(-1, -1))
    }
}
