package com.trackr.ui.screens.more

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.ScheduledReminder
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MoreViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: Application
    private lateinit var fakeTransitRepository: FakeTransitRepository
    private lateinit var fakeReminderRepository: FakeScheduledReminderRepository
    private lateinit var viewModel: MoreViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        fakeTransitRepository = FakeTransitRepository()
        fakeReminderRepository = FakeScheduledReminderRepository()
        viewModel = MoreViewModel(
            application = app,
            repository = fakeTransitRepository,
            reminderRepository = fakeReminderRepository
        )
    }

    @Test
    fun testInitialState_reflectsRepositoryValues() = runTest {
        assertEquals(EngineMode.ALL, viewModel.engineMode.value)
        assertEquals(4, viewModel.nearbyStopsCount.value)
        assertEquals(TransitCodeProvider.ASK_ALWAYS, viewModel.preferredTransitCode.value)
        assertTrue(viewModel.reminders.value.isEmpty())

        // 验证统一 MoreUiState
        assertEquals(EngineMode.ALL, viewModel.uiState.value.engineMode)
        assertEquals(4, viewModel.uiState.value.nearbyStopsCount)
        assertEquals(TransitCodeProvider.ASK_ALWAYS, viewModel.uiState.value.preferredTransitCode)
        assertTrue(viewModel.uiState.value.reminders.isEmpty())
    }

    @Test
    fun testSetEngineMode_updatesRepositoryAndState() = runTest {
        viewModel.setEngineMode(EngineMode.MYGOLBS)
        assertEquals(EngineMode.MYGOLBS, viewModel.engineMode.value)
        assertEquals(EngineMode.MYGOLBS, fakeTransitRepository.engineMode.value)

        viewModel.setEngineMode(EngineMode.CHELAILE)
        assertEquals(EngineMode.CHELAILE, viewModel.engineMode.value)
        assertEquals(EngineMode.CHELAILE, fakeTransitRepository.engineMode.value)
    }

    @Test
    fun testSetNearbyStopsCount_clampsAndUpdatesRepository() = runTest {
        // 正常设置 8
        viewModel.setNearbyStopsCount(8)
        assertEquals(8, viewModel.nearbyStopsCount.value)
        assertEquals(8, fakeTransitRepository.nearbyStopsCount.value)

        // 超过最大 16，应被限制为 16
        viewModel.setNearbyStopsCount(25)
        assertEquals(16, viewModel.nearbyStopsCount.value)
        assertEquals(16, fakeTransitRepository.nearbyStopsCount.value)

        // 小于 1，应被限制为 1
        viewModel.setNearbyStopsCount(-5)
        assertEquals(1, viewModel.nearbyStopsCount.value)
        assertEquals(1, fakeTransitRepository.nearbyStopsCount.value)
    }

    @Test
    fun testSetPreferredTransitCode_updatesRepository() = runTest {
        viewModel.setPreferredTransitCode(TransitCodeProvider.ALIPAY)
        testScheduler.runCurrent()
        assertEquals(TransitCodeProvider.ALIPAY, viewModel.preferredTransitCode.value)
        assertEquals(TransitCodeProvider.ALIPAY, fakeTransitRepository.preferredTransitCodeProvider.value)

        viewModel.setPreferredTransitCode(TransitCodeProvider.UNIONPAY)
        testScheduler.runCurrent()
        assertEquals(TransitCodeProvider.UNIONPAY, viewModel.preferredTransitCode.value)
        assertEquals(TransitCodeProvider.UNIONPAY, fakeTransitRepository.preferredTransitCodeProvider.value)
    }

    @Test
    fun testToggleAndDeleteReminder_updatesReminderRepository() = runTest {
        val reminder = ScheduledReminder(
            id = "rem_001",
            enabled = true,
            hour = 8,
            minute = 30,
            cityId = "034",
            cityName = "上海",
            lineId = "line_101",
            lineName = "101路",
            direction = 0,
            endStopName = "人民广场",
            targetStopId = "stop_05",
            targetStopName = "南京东路",
            targetOrder = 5
        )
        fakeReminderRepository.saveReminder(reminder)
        testScheduler.runCurrent()

        assertEquals(1, viewModel.reminders.value.size)
        assertTrue(viewModel.reminders.value.first().enabled)

        // 切换禁用
        viewModel.toggleReminder("rem_001", false)
        testScheduler.runCurrent()
        assertFalse(viewModel.reminders.value.first().enabled)

        // 删除提醒
        viewModel.deleteReminder("rem_001")
        testScheduler.runCurrent()
        assertTrue(viewModel.reminders.value.isEmpty())
    }
}
