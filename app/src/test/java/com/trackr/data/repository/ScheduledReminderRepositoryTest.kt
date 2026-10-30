package com.trackr.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.ScheduledReminder
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScheduledReminderRepositoryTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ScheduledReminderRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("trackr_scheduled_reminders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = ScheduledReminderRepository(context, testDispatcher)
    }

    @Test
    fun testSaveAndRetrieveReminder() = runTest(testDispatcher) {
        val reminder = ScheduledReminder(
            id = "rem-1",
            enabled = true,
            hour = 8,
            minute = 15,
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            cityId = "034",
            cityName = "上海",
            lineId = "line-101",
            lineName = "70路",
            direction = 0,
            endStopName = "科技园",
            targetStopId = "stop-5",
            targetStopName = "大冲",
            targetOrder = 5
        )

        repository.saveReminder(reminder)

        val list = repository.reminders.value
        assertEquals(1, list.size)
        assertEquals("rem-1", list[0].id)
        assertEquals("08:15", list[0].formatTime())
        assertEquals("工作日 (周一至周五)", list[0].formatRepeatSummary())

        // 验证重新构建实例也能读取持久化数据
        val newRepo = ScheduledReminderRepository(context, testDispatcher)
        val reloaded = newRepo.getReminderById("rem-1")
        assertNotNull(reloaded)
        assertEquals("70路", reloaded?.lineName)
    }

    @Test
    fun testToggleAndToggleBack() = runTest(testDispatcher) {
        val reminder = ScheduledReminder(
            id = "rem-2",
            enabled = true,
            hour = 9,
            minute = 0,
            daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7),
            cityId = "034",
            cityName = "上海",
            lineId = "line-202",
            lineName = "985路",
            direction = 1,
            endStopName = "陆家嘴",
            targetStopId = "stop-10",
            targetStopName = "浦东南路",
            targetOrder = 10
        )

        repository.saveReminder(reminder)
        assertTrue(repository.getReminderById("rem-2")!!.enabled)

        repository.toggleReminder("rem-2", false)
        assertFalse(repository.getReminderById("rem-2")!!.enabled)

        repository.toggleReminder("rem-2", true)
        assertTrue(repository.getReminderById("rem-2")!!.enabled)
    }

    @Test
    fun testDeleteReminder() = runTest(testDispatcher) {
        val reminder = ScheduledReminder(
            id = "rem-3",
            enabled = true,
            hour = 18,
            minute = 30,
            daysOfWeek = emptySet(),
            cityId = "034",
            cityName = "上海",
            lineId = "line-303",
            lineName = "82路",
            direction = 0,
            endStopName = "前滩",
            targetStopId = "stop-1",
            targetStopName = "耀华路",
            targetOrder = 1
        )

        repository.saveReminder(reminder)
        assertEquals(1, repository.reminders.value.size)

        repository.deleteReminder("rem-3")
        assertEquals(0, repository.reminders.value.size)
        assertNull(repository.getReminderById("rem-3"))
    }
}
