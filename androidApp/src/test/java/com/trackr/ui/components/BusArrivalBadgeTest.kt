package com.trackr.ui.components

import com.trackr.domain.model.LiveBus
import com.trackr.ui.components.common.computeArrivalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusArrivalBadgeTest {

    @Test
    fun computeArrivalStatus_arrivedAtStation() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 5,
            isArrived = true
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("已到站", status.text)
        assertTrue(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_approachingStation() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 5,
            isArrived = false
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("即将进站", status.text)
        assertTrue(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_remainingStopsWithEta() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 3,
            etaSeconds = 180
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("还有 2 站 · 约 3 分钟", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_remainingStopsWithoutEta() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 3,
            etaSeconds = -1
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("还有 2 站 · 暂无预测", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_invalidOrder_inTransit() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 0,
            etaSeconds = -1
        )
        val status = computeArrivalStatus(targetOrder = 0, approachingBus = bus)
        assertEquals("在途行驶中", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_prioritizesExplicitStatusText() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 3
        )
        val status = computeArrivalStatus(
            targetOrder = 5,
            approachingBus = bus,
            statusText = "末班车已过"
        )
        assertEquals("末班车已过", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_fallbackWhenNoBus() {
        val status = computeArrivalStatus(
            targetOrder = 5,
            approachingBus = null,
            fallbackStatusText = "暂无在途车辆"
        )
        assertEquals("暂无在途车辆", status.text)
        assertFalse(status.isHighlighted)
    }
}
