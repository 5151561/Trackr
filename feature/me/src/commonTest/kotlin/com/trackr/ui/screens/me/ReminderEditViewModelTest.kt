package com.trackr.ui.screens.me

import com.trackr.core.model.ScheduledReminder
import com.trackr.core.testing.FakeReminderAlarms
import com.trackr.core.testing.MainDispatcherHolder
import com.trackr.data.repository.FakeScheduledReminderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderEditViewModelTest {

    private val main = MainDispatcherHolder()

    private val existing = ScheduledReminder(
        id = "rem_1",
        enabled = true,
        hour = 7,
        minute = 50,
        daysOfWeek = setOf(1, 2, 3, 4, 5),
        cityId = "034",
        cityName = "上海",
        lineId = "line_325",
        lineName = "325",
        direction = 0,
        endStopName = "人民广场",
        targetStopId = "stop_05",
        targetStopName = "科技园",
        targetOrder = 5,
        autoStartLiveTracking = false
    )

    private lateinit var reminders: FakeScheduledReminderRepository
    private lateinit var alarms: FakeReminderAlarms
    private lateinit var notices: MeNotices

    @BeforeTest
    fun setup() {
        main.install()
        reminders = FakeScheduledReminderRepository(initialReminders = listOf(existing))
        alarms = FakeReminderAlarms(exactAllowed = true)
        notices = MeNotices()
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    private fun createViewModel(id: String = existing.id) = ReminderEditViewModel(
        reminderId = id,
        reminderRepository = reminders,
        alarms = alarms,
        notices = notices
    )

    @Test
    fun loadsExistingReminder_unchanged() {
        val state = createViewModel().uiState.value
        assertEquals(existing, state.draft)
        assertFalse(state.missing)
        assertFalse(state.changed)
        assertFalse(state.showExactAlarmHint)
    }

    @Test
    fun missingReminder_showsMissing() {
        val state = createViewModel(id = "gone").uiState.value
        assertTrue(state.missing)
        assertNull(state.draft)
    }

    @Test
    fun editTimeAndDays_thenSave_replacesInPlace_andPostsNotice() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.onEvent(ReminderEditEvent.SetTime(8, 5))
        viewModel.onEvent(ReminderEditEvent.ToggleDay(5))
        viewModel.onEvent(ReminderEditEvent.ToggleDay(6))
        assertTrue(viewModel.uiState.value.changed)
        assertEquals(setOf(1, 2, 3, 4, 6), viewModel.uiState.value.draft!!.daysOfWeek)

        viewModel.onEvent(ReminderEditEvent.Save)
        testScheduler.advanceUntilIdle()

        val saved = reminders.reminders.value.single()
        assertEquals("rem_1", saved.id)
        assertEquals(8, saved.hour)
        assertEquals(5, saved.minute)
        assertEquals(setOf(1, 2, 3, 4, 6), saved.daysOfWeek)
        // 线路、站不变
        assertEquals(existing.lineId, saved.lineId)
        assertEquals(existing.targetStopId, saved.targetStopId)
        assertTrue(viewModel.uiState.value.closed)
        assertEquals("提醒已保存：周一、二、三、四、六 08:05 · 325", notices.messages.value.single().text)
    }

    @Test
    fun presetDays_andOneTime() {
        val viewModel = createViewModel()
        viewModel.onEvent(ReminderEditEvent.SetDays((1..7).toSet()))
        assertEquals((1..7).toSet(), viewModel.uiState.value.draft!!.daysOfWeek)

        viewModel.onEvent(ReminderEditEvent.SetDays(emptySet()))
        assertTrue(viewModel.uiState.value.draft!!.isOneTime)

        // 改回原样就算没改
        viewModel.onEvent(ReminderEditEvent.SetDays(setOf(1, 2, 3, 4, 5)))
        assertFalse(viewModel.uiState.value.changed)
    }

    @Test
    fun exactAlarmHint_onlyWhenAutoStartAndNoExactPermission() {
        alarms.exactAllowed = false
        val viewModel = createViewModel()

        // 自动追踪没开：不提示
        assertFalse(viewModel.uiState.value.showExactAlarmHint)

        viewModel.onEvent(ReminderEditEvent.SetAutoStart(true))
        assertTrue(viewModel.uiState.value.showExactAlarmHint)

        viewModel.onEvent(ReminderEditEvent.SetAutoStart(false))
        assertFalse(viewModel.uiState.value.showExactAlarmHint)
    }

    @Test
    fun exactAlarmHint_shownOnOpen_andClearsAfterGrantOnResume() {
        reminders = FakeScheduledReminderRepository(initialReminders = listOf(existing.copy(autoStartLiveTracking = true)))
        alarms.exactAllowed = false
        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.showExactAlarmHint)

        viewModel.onEvent(ReminderEditEvent.OpenExactAlarmSettings)
        assertEquals(1, alarms.openSettingsCount)
        assertTrue(viewModel.uiState.value.userMessages.isEmpty())

        // 用户在系统设置里开了「闹钟和提醒」，回到页面重查
        alarms.exactAllowed = true
        viewModel.onScreenResumed()
        assertFalse(viewModel.uiState.value.showExactAlarmHint)
    }

    @Test
    fun exactAlarmSettings_cannotOpen_showsMessage() {
        alarms.exactAllowed = false
        alarms.openSettingsResult = false
        val viewModel = createViewModel()

        viewModel.onEvent(ReminderEditEvent.OpenExactAlarmSettings)
        val message = viewModel.uiState.value.userMessages.single()
        assertTrue(message.text.contains("闹钟和提醒"))

        viewModel.onEvent(ReminderEditEvent.MessageShown(message.id))
        assertTrue(viewModel.uiState.value.userMessages.isEmpty())
    }

    @Test
    fun delete_needsConfirm_thenRemoves() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.onEvent(ReminderEditEvent.AskDelete)
        assertTrue(viewModel.uiState.value.confirmingDelete)
        viewModel.onEvent(ReminderEditEvent.CancelDelete)
        assertFalse(viewModel.uiState.value.confirmingDelete)
        assertNotNull(reminders.getReminderById("rem_1"))

        viewModel.onEvent(ReminderEditEvent.AskDelete)
        viewModel.onEvent(ReminderEditEvent.ConfirmDelete)
        testScheduler.advanceUntilIdle()
        assertNull(reminders.getReminderById("rem_1"))
        assertTrue(viewModel.uiState.value.closed)
        assertEquals("提醒已删除", notices.messages.value.single().text)
    }

    @Test
    fun saveTwice_savesOnce() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        viewModel.onEvent(ReminderEditEvent.SetTime(9, 0))
        viewModel.onEvent(ReminderEditEvent.Save)
        viewModel.onEvent(ReminderEditEvent.Save)
        testScheduler.advanceUntilIdle()

        assertEquals(1, notices.messages.value.size)
    }
}
