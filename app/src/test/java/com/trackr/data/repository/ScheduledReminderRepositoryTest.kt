package com.trackr.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.ScheduledReminder
import com.trackr.util.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
        context.getSharedPreferences("trackr_reminder_schedule_state", Context.MODE_PRIVATE)
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

    private fun sampleReminder(id: String, daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5)) = ScheduledReminder(
        id = id,
        enabled = true,
        hour = 7,
        minute = 45,
        daysOfWeek = daysOfWeek,
        cityId = "034",
        cityName = "上海",
        lineId = "line-$id",
        lineName = "70路",
        direction = 0,
        endStopName = "科技园",
        targetStopId = "stop-5",
        targetStopName = "大冲",
        targetOrder = 5
    )

    @Test
    fun testConcurrentSaves_doNotLoseUpdates() = runBlocking {
        // 使用真实多线程调度器制造并发读改写，Mutex 保证每次保存都不丢失
        val concurrentRepo = ScheduledReminderRepository(context, Dispatchers.IO)
        val count = 40
        coroutineScope {
            (1..count).map { index ->
                async(Dispatchers.Default) {
                    concurrentRepo.saveReminder(sampleReminder("concurrent-$index"))
                }
            }.awaitAll()
        }

        assertEquals(count, concurrentRepo.reminders.value.size)
        assertEquals(count, concurrentRepo.reminders.value.map { it.id }.toSet().size)

        // 持久化结果同样完整
        val reloaded = ScheduledReminderRepository(context, testDispatcher)
        assertEquals(count, reloaded.reminders.value.size)
    }

    @Test
    fun testConcurrentToggleAndSave_keepsBothChanges() = runBlocking {
        val concurrentRepo = ScheduledReminderRepository(context, Dispatchers.IO)
        val base = (1..20).map { sampleReminder("base-$it") }
        base.forEach { concurrentRepo.saveReminder(it) }

        coroutineScope {
            val toggles = base.map { reminder ->
                async(Dispatchers.Default) { concurrentRepo.toggleReminder(reminder.id, false) }
            }
            val saves = (1..20).map { index ->
                async(Dispatchers.Default) { concurrentRepo.saveReminder(sampleReminder("extra-$index")) }
            }
            (toggles + saves).awaitAll()
        }

        val list = concurrentRepo.reminders.value
        assertEquals(40, list.size)
        assertTrue(list.filter { it.id.startsWith("base-") }.none { it.enabled })
        assertTrue(list.filter { it.id.startsWith("extra-") }.all { it.enabled })
    }

    @Test
    fun testCorruptJson_isBackedUpAndNotSilentlyLost() = runTest(testDispatcher) {
        val prefs = context.getSharedPreferences("trackr_scheduled_reminders", Context.MODE_PRIVATE)
        val corrupt = "[{\"id\":\"broken\""
        prefs.edit().putString("reminders_list_json", corrupt).commit()

        val corruptRepo = ScheduledReminderRepository(context, testDispatcher)
        assertTrue(corruptRepo.reminders.value.isEmpty())

        val backups = prefs.all.filterKeys { it.startsWith(ScheduledReminderRepository.KEY_CORRUPT_BACKUP_PREFIX) }
        assertEquals(1, backups.size)
        assertEquals(corrupt, backups.values.first())

        // 之后的写入覆盖主 key，但备份仍在
        corruptRepo.saveReminder(sampleReminder("after-corrupt"))
        val backupsAfterWrite = prefs.all.filterKeys { it.startsWith(ScheduledReminderRepository.KEY_CORRUPT_BACKUP_PREFIX) }
        assertEquals(corrupt, backupsAfterWrite.values.single())
    }

    @Test
    fun testCorruptJson_keepsOnlyLatestBackup() {
        val prefs = context.getSharedPreferences("trackr_scheduled_reminders", Context.MODE_PRIVATE)
        prefs.edit().putString("reminders_list_json", "corrupt-1").commit()
        ScheduledReminderRepository(context, testDispatcher)
        Thread.sleep(5)
        prefs.edit().putString("reminders_list_json", "corrupt-2").commit()
        ScheduledReminderRepository(context, testDispatcher)

        val backups = prefs.all.filterKeys { it.startsWith(ScheduledReminderRepository.KEY_CORRUPT_BACKUP_PREFIX) }
        assertEquals(1, backups.size)
        assertEquals("corrupt-2", backups.values.single())
    }

    @Test
    fun testScheduleRecordsLastTrigger_andCancelClearsIt() = runTest(testDispatcher) {
        repository.saveReminder(sampleReminder("record-1", daysOfWeek = emptySet()))
        val planned = ReminderScheduler.getLastScheduledTriggerMillis(context, "record-1")
        assertNotNull(planned)
        assertTrue(planned!! > System.currentTimeMillis() - 1_000L)

        repository.toggleReminder("record-1", false)
        assertNull(ReminderScheduler.getLastScheduledTriggerMillis(context, "record-1"))
    }
}
