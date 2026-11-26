package com.trackr.ui.components

import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 横排走向条的车位置：有坐标按 segmentProgress 插值，没坐标/已到站贴目标站，站序认不出就不画。
 */
class BusRailPositionTest {

    private val stops = (1..5).map { order ->
        BusStop(stopId = "s$order", name = "站$order", order = order)
    }

    @Test
    fun approachingThirdStop_halfway_sitsBetweenSecondAndThird() {
        val bus = bus(order = 3, progress = 0.5f, lat = 31.0, lng = 121.0)

        assertEquals(1.5f, busRailPosition(stops, bus)!!, 0.001f)
    }

    @Test
    fun noCoordinate_snapsToTargetStop() {
        val bus = bus(order = 3, progress = 0.5f)

        assertEquals(2f, busRailPosition(stops, bus)!!, 0.001f)
    }

    @Test
    fun arrived_snapsToTargetStop() {
        val bus = bus(order = 3, progress = 0.5f, lat = 31.0, lng = 121.0, arrived = true)

        assertEquals(2f, busRailPosition(stops, bus)!!, 0.001f)
    }

    @Test
    fun firstStop_neverGoesNegative() {
        val bus = bus(order = 1, progress = 0.9f, lat = 31.0, lng = 121.0)

        assertEquals(0f, busRailPosition(stops, bus)!!, 0.001f)
    }

    @Test
    fun unknownOrder_isNotDrawn() {
        val bus = bus(order = 99, progress = 0.5f, lat = 31.0, lng = 121.0)

        assertNull(busRailPosition(stops, bus))
    }

    @Test
    fun progressOutsideZeroOne_isClamped() {
        assertEquals(
            1f,
            busRailPosition(stops, bus(order = 3, progress = -2f, lat = 31.0, lng = 121.0))!!,
            0.001f
        )
        assertEquals(
            2f,
            busRailPosition(stops, bus(order = 3, progress = 9f, lat = 31.0, lng = 121.0))!!,
            0.001f
        )
    }

    private fun bus(
        order: Int,
        progress: Float,
        lat: Double = 0.0,
        lng: Double = 0.0,
        arrived: Boolean = false
    ) = LiveBus(
        busId = "bus_$order",
        plateOrFleetNo = "沪A1234",
        order = order,
        segmentProgress = progress,
        isArrived = arrived,
        wgsLat = lat,
        wgsLng = lng
    )
}
