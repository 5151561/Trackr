package com.trackr.core.testing

import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.UserFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FakesTest {

    @Test
    fun fakeClock_advancesWallAndMonotonicTogether() {
        val clock = FakeClock()
        val wall0 = clock.now().toEpochMilliseconds()
        val mono0 = clock.elapsedMillis()

        clock.advanceBy(90.seconds)

        assertEquals(wall0 + 90_000L, clock.now().toEpochMilliseconds())
        assertEquals(mono0 + 90_000L, clock.elapsedMillis())
    }

    @Test
    fun fakeClock_wallClockChangeDoesNotMoveMonotonic() {
        val clock = FakeClock()
        val mono0 = clock.elapsedMillis()

        clock.setWallClock(0L)

        assertEquals(0L, clock.now().toEpochMilliseconds())
        assertEquals(mono0, clock.elapsedMillis())
        assertFailsWith<IllegalArgumentException> { clock.advanceBy((-1).seconds) }
    }

    @Test
    fun fakeReminderAlarms_schedulesEnabledAndCancelsDisabled() {
        val alarms = FakeReminderAlarms(nextTriggerOf = { 1_000L })
        val on = reminder("on", enabled = true)
        val off = reminder("off", enabled = false)

        alarms.schedule(off)
        alarms.rescheduleAll(listOf(on, off))

        assertEquals(1_000L, alarms.lastScheduledTriggerMillis("on"))
        assertNull(alarms.lastScheduledTriggerMillis("off"))

        alarms.cancel("on")
        assertNull(alarms.lastScheduledTriggerMillis("on"))
    }

    @Test
    fun fakeTransitCodeLauncher_onlyOpensInstalledProvider() {
        val launcher = FakeTransitCodeLauncher(installed = setOf(TransitCodeProvider.ALIPAY))

        assertTrue(launcher.open(TransitCodeProvider.ALIPAY))
        assertFalse(launcher.open(TransitCodeProvider.UNIONPAY))
        assertFalse(launcher.open(TransitCodeProvider.ASK_ALWAYS))
        assertEquals(listOf(TransitCodeProvider.ALIPAY), launcher.opened)
    }

    @Test
    fun fakeLocationSource_recordsTiersAndLastKnown() {
        val source = FakeLocationSource()
        val fix = UserFix(lat = 25.43, lng = 119.0, accuracyMeters = 8f, elapsedMillis = 1_000L)

        source.fixes(LocationCadence.Tier.OFF)
        source.fixes(LocationCadence.Tier.NEAR_DROP)
        source.emit(fix)

        assertEquals(listOf(LocationCadence.Tier.OFF, LocationCadence.Tier.NEAR_DROP), source.requestedTiers)
        assertEquals(fix, source.lastKnown())
    }

    private fun reminder(id: String, enabled: Boolean) = ScheduledReminder(
        id = id,
        hour = 7,
        minute = 30,
        cityId = "051",
        cityName = "莆田",
        lineId = "L59",
        lineName = "59路",
        direction = 0,
        endStopName = "汽车站",
        targetStopId = "S1",
        targetStopName = "擢英中学",
        targetOrder = 7,
        enabled = enabled
    )
}
