package com.trackr.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.data.local.RoomReminderScheduleStateStore
import com.trackr.core.data.local.RoomReminderStore
import com.trackr.core.data.local.SerialWriter
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.database.createInMemoryTrackrDatabase
import com.trackr.core.model.ScheduledReminder
import com.trackr.util.CachedReminderScheduleState
import com.trackr.util.ReminderAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 新存储（Room `reminder` 表 + `lastScheduledTriggerAt` 列）上的 ScheduledReminderRepository。
 * 旧存储上的同名用例在 [ScheduledReminderRepositoryTest]；这里重点是并发写不丢、计划触发时刻跟着提醒走。
 */
@RunWith(RobolectricTestRunner::class)
class RoomScheduledReminderRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: TrackrDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var scheduleState: CachedReminderScheduleState

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = createInMemoryTrackrDatabase(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scheduleState = runBlocking { CachedReminderScheduleState.create(RoomReminderScheduleStateStore(db), SerialWriter(scope)) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun newRepository() = ScheduledReminderRepository(
        store = RoomReminderStore(db),
        alarms = ReminderAlarmScheduler(context, scheduleState),
        ioDispatcher = Dispatchers.IO
    )

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
    fun saveToggleDelete_andReload() = runBlocking {
        val repository = newRepository()
        repository.saveReminder(sampleReminder("a", daysOfWeek = setOf(3, 1, 5)))
        repository.saveReminder(sampleReminder("b", daysOfWeek = emptySet()))
        repository.toggleReminder("a", false)

        val reloaded = newRepository()
        assertEquals(listOf("a", "b"), reloaded.reminders.value.map { it.id })
        assertFalse(reloaded.getReminderById("a")!!.enabled)
        assertEquals(setOf(1, 3, 5), reloaded.getReminderById("a")!!.daysOfWeek)
        assertTrue(reloaded.getReminderById("b")!!.isOneTime)
        assertEquals(repository.reminders.value, reloaded.reminders.value)

        reloaded.deleteReminder("b")
        assertNull(newRepository().getReminderById("b"))
    }

    @Test
    fun concurrentSaves_doNotLoseUpdates() = runBlocking {
        val repository = newRepository()
        val count = 40
        coroutineScope {
            (1..count).map { index ->
                async(Dispatchers.Default) { repository.saveReminder(sampleReminder("concurrent-$index")) }
            }.awaitAll()
        }

        assertEquals(count, repository.reminders.value.size)
        assertEquals(count, repository.reminders.value.map { it.id }.toSet().size)
        assertEquals(count, newRepository().reminders.value.size)
    }

    @Test
    fun concurrentToggleAndSave_keepsBothChanges() = runBlocking {
        val repository = newRepository()
        val base = (1..20).map { sampleReminder("base-$it") }
        base.forEach { repository.saveReminder(it) }

        coroutineScope {
            val toggles = base.map { reminder ->
                async(Dispatchers.Default) { repository.toggleReminder(reminder.id, false) }
            }
            val saves = (1..20).map { index ->
                async(Dispatchers.Default) { repository.saveReminder(sampleReminder("extra-$index")) }
            }
            (toggles + saves).awaitAll()
        }

        val list = newRepository().reminders.value
        assertEquals(40, list.size)
        assertTrue(list.filter { it.id.startsWith("base-") }.none { it.enabled })
        assertTrue(list.filter { it.id.startsWith("extra-") }.all { it.enabled })
    }

    @Test
    fun scheduledTrigger_isStoredInReminderRow_andSurvivesListChanges() = runBlocking {
        val repository = newRepository()
        repository.saveReminder(sampleReminder("once", daysOfWeek = emptySet()))
        scheduleState.flush()
        val planned = db.reminders().getLastScheduledTriggerAt("once")
        assertNotNull(planned)
        assertEquals(planned, scheduleState.get("once"))

        // 改别的提醒（整份写回列表）不会冲掉已记下的触发时刻
        repository.saveReminder(sampleReminder("other"))
        scheduleState.flush()
        assertEquals(planned, db.reminders().getLastScheduledTriggerAt("once"))

        // 新实例从库里读回
        val reopened = CachedReminderScheduleState.create(RoomReminderScheduleStateStore(db), SerialWriter(scope))
        assertEquals(planned, reopened.get("once"))

        // 关掉提醒时清掉
        repository.toggleReminder("once", false)
        scheduleState.flush()
        assertNull(scheduleState.get("once"))
        assertNull(db.reminders().getLastScheduledTriggerAt("once"))
    }
}
