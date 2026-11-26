package com.trackr.service

import androidx.test.core.app.ApplicationProvider
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.model.TrackingTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 第 25 步：开始追踪时把「我的」里的到站前提醒写进目标 */
@RunWith(RobolectricTestRunner::class)
class AndroidTrackingControllerTest {

    private val repository = DefaultTrackingRepository(
        InMemoryTrackingSessionStore(),
        CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    )
    private var globalStops = 3
    private val startedSessions = mutableListOf<String>()

    private val controller = AndroidTrackingController(
        ApplicationProvider.getApplicationContext(),
        repository,
        alertThresholdStops = { globalStops },
        starter = { _, sessionId -> startedSessions += sessionId }
    )

    private val target = TrackingTarget.SingleLine(
        cityId = "034",
        lineId = "line_325",
        lineName = "325",
        targetStopId = "stop_05",
        targetStopName = "科技园",
        targetOrder = 5,
        alertThresholdStops = 1
    )

    @Test
    fun start_usesGlobalAlertThreshold() {
        assertTrue(controller.start(target))

        val session = repository.session.value!!
        assertEquals(3, session.state.alertThresholdStops)
        assertEquals(3, (session.target as TrackingTarget.SingleLine).alertThresholdStops)
        assertEquals(listOf(session.sessionId), startedSessions)
    }

    @Test
    fun start_readsSettingEachTime() {
        controller.start(target)
        globalStops = 1
        controller.start(target)
        assertEquals(1, repository.session.value!!.state.alertThresholdStops)
    }

    @Test
    fun start_whenServiceNotAllowed_restoresPrevious() {
        val failing = AndroidTrackingController(
            ApplicationProvider.getApplicationContext(),
            repository,
            alertThresholdStops = { 2 },
            starter = { _, _ -> throw IllegalStateException("background") }
        )
        assertEquals(false, failing.start(target))
        assertNull(repository.session.value)
    }
}
