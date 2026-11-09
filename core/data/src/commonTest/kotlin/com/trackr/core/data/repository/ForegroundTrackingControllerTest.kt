package com.trackr.core.data.repository

import com.trackr.core.model.*
import com.trackr.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundTrackingControllerTest {
    private val target = TrackingTarget.SingleLine(cityId = "sh", lineId = "L1", lineName = "1路",
        targetStopId = "s5", targetStopName = "站5", targetOrder = 5)

    private class Fixture(val test: TestScope) {
        val clock = FakeClock()
        val store = InMemoryTrackingSessionStore()
        val repo = DefaultTrackingRepository(store, test.backgroundScope, clock, clock)
        val realtime = FakeRealtimeRepository()
        val settings = FakeSettingsRepository()
        val location = FakeLocationSource(precisePermission = true)
        val effects = mutableListOf<String>()
        val controller = ForegroundTrackingController(repo, realtime, settings, location,
            test.backgroundScope, clock, clock, onEffect = { _, id -> effects += id },
            requestDispatcher = StandardTestDispatcher(test.testScheduler))
    }

    @Test fun backgroundCancelsPollingAndResumesWithNewGeneration() = runTest {
        val f = Fixture(this)
        assertFalse(f.controller.start(target))
        f.controller.setActive(true)
        runCurrent()
        assertTrue(f.controller.start(target))
        runCurrent()
        val before = f.repo.sessions.value.single()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.controller.setActive(false)
        runCurrent()
        assertTrue(f.repo.sessions.value.single().interrupted)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.controller.setActive(true)
        runCurrent()
        assertEquals(2, f.realtime.lineDetailCalls.size)
        assertTrue(f.repo.sessions.value.single().generation > before.generation)
        assertFalse(f.repo.sessions.value.single().interrupted)
    }

    @Test fun nonCooperativeLateResponseCannotUpdatePausedOrReplacedSession() = runTest {
        val f = Fixture(this)
        val pending = CompletableDeferred<Unit>()
        f.realtime.lineDetailHook = { withContext(NonCancellable) { pending.await() } }
        f.controller.setActive(true)
        runCurrent()
        f.controller.start(target)
        runCurrent()
        f.controller.setActive(false)
        val paused = f.repo.sessions.value.single()
        pending.complete(Unit)
        runCurrent()
        assertEquals(paused, f.repo.sessions.value.single())
        assertTrue(f.effects.isEmpty())
        f.controller.setActive(true)
        runCurrent()
        assertFalse(f.repo.sessions.value.single().interrupted)
    }

    @Test fun backgroundExpiredSessionIsRemovedBeforeAnyRefresh() = runTest {
        val f = Fixture(this)
        f.controller.setActive(true); runCurrent()
        f.controller.start(target); runCurrent()
        f.controller.setActive(false)
        f.clock.advanceBy(12.hours)
        f.controller.setActive(true); runCurrent()
        assertTrue(f.repo.sessions.value.isEmpty())
        assertEquals(1, f.realtime.lineDetailCalls.size)
    }

    @Test fun repeatedForegroundAndStartDoNotDuplicateWorkers() = runTest {
        val f = Fixture(this)
        f.controller.setActive(true); runCurrent()
        f.controller.start(target); runCurrent()
        repeat(3) { f.controller.setActive(true) }
        runCurrent()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.controller.start(target); runCurrent()
        assertEquals(1, f.repo.sessions.value.size)
        assertEquals(2, f.realtime.lineDetailCalls.size)
        f.controller.start(target.copy(lineId = "L2")); runCurrent()
        assertEquals(2, f.repo.sessions.value.size)
        f.controller.stopTracking(f.repo.sessions.value.first().sessionId); runCurrent()
        assertEquals("L2", f.repo.sessions.value.single().leg.lineId)
    }

    @Test fun failureBacksOffAndThenRecovers() = runTest {
        val f = Fixture(this)
        f.realtime.lineDetailResult = Result.failure(IllegalStateException("offline"))
        f.controller.setActive(true); runCurrent()
        f.controller.start(target); runCurrent()
        advanceTimeBy(89_999); runCurrent()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.realtime.lineDetailResult = null
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.realtime.lineDetailCalls.size)
        assertFalse(f.repo.sessions.value.single().refreshFailed)
        val fresh = f.repo.sessions.value.single().lastRealtimeAtMillis
        f.realtime.lineDetailResult = Result.failure(IllegalStateException("offline again"))
        f.clock.advanceBy(1.hours)
        advanceTimeBy(45_000); runCurrent()
        assertEquals(3, f.realtime.lineDetailCalls.size)
        assertTrue(f.repo.sessions.value.single().refreshFailed)
        assertEquals(fresh, f.repo.sessions.value.single().lastRealtimeAtMillis)
    }

    @Test fun closeBeforeDiskLoadDoesNotStartWorkers() = runTest {
        val f = Fixture(this)
        f.controller.setActive(true)
        f.controller.close()
        runCurrent()
        assertFalse(f.controller.start(target))
        assertTrue(f.realtime.lineDetailCalls.isEmpty())
    }

    @Test fun pauseDropsCachedLocationAndRejectsOldGeneration() = runTest {
        val f = Fixture(this)
        runCurrent()
        val session = assertNotNull(f.repo.start(target))
        f.repo.updateUserFix(UserFix(31.0, 121.0, 10f, f.clock.elapsed))
        f.repo.pauseAll()
        assertNull(f.repo.latestUserFix)
        assertFalse(f.repo.update(session.sessionId, session.generation) { error("must not apply") })
        f.repo.resumeAll()
        assertFalse(f.repo.update(session.sessionId, session.generation) { error("must not apply") })
    }

    @Test fun autoStopAfterBoarding_stopsSameStopWaitingLineAndKeepsDifferentStop() = runTest {
        val f = Fixture(this)
        val stopLat = 31.2300
        val stopLng = 121.4700
        fun north(m: Int) = stopLat + m / 111_000.0
        val waitStop = BusStop(stopId = "s5", name = "站5", order = 5, wgsLat = stopLat, wgsLng = stopLng)
        val otherStop = BusStop(stopId = "s9", name = "站9", order = 9, wgsLat = stopLat + 0.05, wgsLng = stopLng)

        f.realtime.lineDetailsMap["L1"] = BusLineDetail(
            line = BusLineDirection(lineId = "L1", name = "1路", startStop = "始", endStop = "终"),
            stops = listOf(waitStop),
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "b1", order = 5, isArrived = true, wgsLat = stopLat, wgsLng = stopLng))
        )
        f.realtime.lineDetailsMap["L2"] = BusLineDetail(
            line = BusLineDirection(lineId = "L2", name = "2路", startStop = "始", endStop = "终"),
            stops = listOf(waitStop),
            buses = listOf(LiveBus(busId = "b2", plateOrFleetNo = "b2", order = 2, wgsLat = north(-800), wgsLng = stopLng))
        )
        f.realtime.lineDetailsMap["L3"] = BusLineDetail(
            line = BusLineDirection(lineId = "L3", name = "3路", startStop = "始", endStop = "终"),
            stops = listOf(otherStop),
            buses = listOf(LiveBus(busId = "b3", plateOrFleetNo = "b3", order = 7, wgsLat = otherStop.wgsLat, wgsLng = stopLng))
        )

        f.controller.setActive(true)
        runCurrent()
        f.controller.updateUserFix(UserFix(stopLat, stopLng, 10f, f.clock.elapsed))
        assertTrue(f.controller.start(target))
        assertTrue(f.controller.start(target.copy(lineId = "L2", lineName = "2路")))
        assertTrue(f.controller.start(target.copy(lineId = "L3", lineName = "3路", targetStopId = "s9", targetStopName = "站9", targetOrder = 9)))
        runCurrent()
        assertEquals(3, f.repo.sessions.value.size)

        // 用户坐上 L1 的 b1 离开站5（两次新定位）
        f.clock.advanceBy(10.seconds)
        f.controller.updateUserFix(UserFix(north(150), stopLng, 10f, f.clock.elapsed))
        f.realtime.lineDetailsMap["L1"] = f.realtime.lineDetailsMap.getValue("L1").copy(
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "b1", order = 6, wgsLat = north(150), wgsLng = stopLng))
        )
        advanceTimeBy(10_000)
        runCurrent()

        f.clock.advanceBy(10.seconds)
        f.controller.updateUserFix(UserFix(north(300), stopLng, 10f, f.clock.elapsed))
        f.realtime.lineDetailsMap["L1"] = f.realtime.lineDetailsMap.getValue("L1").copy(
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "b1", order = 6, wgsLat = north(300), wgsLng = stopLng))
        )
        advanceTimeBy(10_000)
        runCurrent()

        // L1 和同站候车的 L2 都自动结束，不同站的 L3 保留
        assertEquals(listOf("L3"), f.repo.sessions.value.map { it.leg.lineId })
    }
}
