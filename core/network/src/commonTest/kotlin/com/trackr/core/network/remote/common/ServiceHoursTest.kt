package com.trackr.core.network.remote.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceHoursTest {

    private fun windows(vararg pairs: Pair<String, String>) =
        pairs.map { ServiceHours.Window(it.first, it.second) }

    @Test
    fun normalWindow() {
        val w = windows("06:30" to "22:00")
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("12:00", w))
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("06:30", w))
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("22:00", w))
        assertEquals(ServiceHours.State.BEFORE_FIRST, ServiceHours.state("05:00", w))
        assertEquals(ServiceHours.State.AFTER_LAST, ServiceHours.state("23:50", w))
    }

    @Test
    fun crossMidnightWindow() {
        val w = windows("22:40" to "01:30")
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("23:55", w))
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("00:30", w))
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("22:40", w))
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("01:30", w))
        // 01:30 ~ 22:40 之间是今晚首班之前，不是末班之后
        assertEquals(ServiceHours.State.BEFORE_FIRST, ServiceHours.state("02:00", w))
        assertEquals(ServiceHours.State.BEFORE_FIRST, ServiceHours.state("12:00", w))
    }

    @Test
    fun multipleWindows() {
        val w = windows("04:30" to "05:40", "22:40" to "01:30")
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("04:45", w))
        assertEquals(ServiceHours.State.IN_SERVICE, ServiceHours.state("23:00", w))
        assertEquals(ServiceHours.State.BEFORE_FIRST, ServiceHours.state("02:00", w))
        assertEquals(ServiceHours.State.BEFORE_FIRST, ServiceHours.state("06:00", w))

        val gap = windows("06:30" to "09:00", "16:00" to "19:00")
        assertEquals(ServiceHours.State.BEFORE_FIRST, ServiceHours.state("12:00", gap))
        assertEquals(ServiceHours.State.AFTER_LAST, ServiceHours.state("20:00", gap))
    }

    @Test
    fun unknownWhenNoUsableWindow() {
        assertEquals(ServiceHours.State.UNKNOWN, ServiceHours.state("12:00", emptyList()))
        assertEquals(ServiceHours.State.UNKNOWN, ServiceHours.state("12:00", windows("" to "")))
        assertEquals(ServiceHours.State.UNKNOWN, ServiceHours.state("12:00", windows("abc" to "22:00")))
        assertEquals(ServiceHours.State.UNKNOWN, ServiceHours.state("abc", windows("06:00" to "22:00")))
    }
}
