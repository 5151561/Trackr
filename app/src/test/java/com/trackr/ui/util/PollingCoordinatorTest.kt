package com.trackr.ui.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollingCoordinatorTest {

    @Test
    fun testFixedDelayPollingStrategy_triggersActionPeriodically() = runTest {
        var actionCount = 0
        val strategy = FixedDelayPollingStrategy(delayMs = 1000L)
        val coordinator = PollingCoordinator(
            scope = this,
            strategy = strategy,
            initialEnabled = true,
            onAction = { actionCount++ }
        )

        assertTrue(coordinator.isPollingActive)
        assertEquals(0, actionCount)

        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(1, actionCount)

        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(3, actionCount)

        coordinator.pause()
        assertFalse(coordinator.isPollingActive)
    }

    @Test
    fun testFixedDelayPollingStrategy_actionExceptionDoesNotBreakLoop() = runTest {
        var actionCount = 0
        val strategy = FixedDelayPollingStrategy(delayMs = 500L)
        val coordinator = PollingCoordinator(
            scope = this,
            strategy = strategy,
            initialEnabled = true,
            onAction = {
                actionCount++
                if (actionCount == 1) {
                    throw IllegalStateException("Simulated network timeout")
                }
            }
        )

        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1, actionCount)

        // 下一次依然能正常触发
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, actionCount)

        coordinator.pause()
    }

    @Test
    fun testCountdownPollingStrategy_ticksEverySecondAndTriggersOnExpiry() = runTest {
        val ticks = mutableListOf<Int>()
        var actionCount = 0
        val strategy = CountdownPollingStrategy(
            intervalSeconds = 3,
            onTick = { remaining -> ticks.add(remaining) }
        )
        val coordinator = PollingCoordinator(
            scope = this,
            strategy = strategy,
            initialEnabled = true,
            onAction = { actionCount++ }
        )

        assertEquals(3, strategy.currentRemainingSeconds)

        // 1 秒后 -> 2
        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(listOf(2), ticks)
        assertEquals(0, actionCount)

        // 2 秒后 -> 1
        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(listOf(2, 1), ticks)
        assertEquals(0, actionCount)

        // 3 秒后 -> 归零触发重置为 3 并触发 action
        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(listOf(2, 1, 3), ticks)
        assertEquals(1, actionCount)

        coordinator.pause()
    }

    @Test
    fun testCountdownPollingStrategy_actionExceptionDoesNotStopCountdown() = runTest {
        var actionCount = 0
        val strategy = CountdownPollingStrategy(
            intervalSeconds = 2,
            onTick = {}
        )
        val coordinator = PollingCoordinator(
            scope = this,
            strategy = strategy,
            initialEnabled = true,
            onAction = {
                actionCount++
                throw RuntimeException("Failure during refresh")
            }
        )

        // 2 秒到期
        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(1, actionCount)

        // 再次 2 秒到期
        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(2, actionCount)

        coordinator.pause()
    }

    @Test
    fun testPollingCoordinator_lifecycleAndExplicitControl() = runTest {
        var actionCount = 0
        val strategy = FixedDelayPollingStrategy(delayMs = 1000L)
        val coordinator = PollingCoordinator(
            scope = this,
            strategy = strategy,
            initialEnabled = false,
            onAction = { actionCount++ }
        )

        assertFalse(coordinator.isPollingActive)
        assertFalse(coordinator.autoPollingEnabled)

        // 当 autoPollingEnabled 为 false 时，调用 resume 不应启动
        coordinator.resume()
        assertFalse(coordinator.isPollingActive)

        // 通过 setActive(true) 激活
        coordinator.setActive(true)
        assertTrue(coordinator.autoPollingEnabled)
        assertTrue(coordinator.isPollingActive)

        // 幂等测试：重复调用 resume 不应导致重复任务
        coordinator.resume()
        assertTrue(coordinator.isPollingActive)

        // 暂停轮询
        coordinator.pause()
        assertFalse(coordinator.isPollingActive)
        assertTrue(coordinator.autoPollingEnabled) // 暂停不改变启用配置状态

        // 恢复轮询
        coordinator.resume()
        assertTrue(coordinator.isPollingActive)

        // 显式关闭
        coordinator.setActive(false)
        assertFalse(coordinator.autoPollingEnabled)
        assertFalse(coordinator.isPollingActive)
    }

    @Test
    fun testPollingCoordinator_restartCountdown() = runTest {
        val ticks = mutableListOf<Int>()
        var actionCount = 0
        val strategy = CountdownPollingStrategy(
            intervalSeconds = 10,
            onTick = { ticks.add(it) }
        )
        val coordinator = PollingCoordinator(
            scope = this,
            strategy = strategy,
            initialEnabled = true,
            onAction = { actionCount++ }
        )

        // 经过 2 秒
        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(listOf(9, 8), ticks)

        // 触发立即重置倒计时为 10
        coordinator.restartCountdown(10)
        assertEquals(10, ticks.last())
        assertEquals(10, strategy.currentRemainingSeconds)
        assertTrue(coordinator.isPollingActive)

        // 从重置后的时间起，再经过 1 秒应为 9
        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(9, ticks.last())

        coordinator.pause()
    }

    @Test
    fun testPollingCoordinator_restartCountdownWhenDisabled_stillNotifiesTick() = runTest {
        val ticks = mutableListOf<Int>()
        val strategy = CountdownPollingStrategy(
            intervalSeconds = 10,
            onTick = { ticks.add(it) }
        )
        val coordinator = PollingCoordinator(
            scope = this,
            strategy = strategy,
            initialEnabled = false,
            onAction = {}
        )

        assertFalse(coordinator.isPollingActive)
        coordinator.restartCountdown(10)
        assertEquals(listOf(10), ticks)
        assertEquals(10, strategy.currentRemainingSeconds)
        assertFalse(coordinator.isPollingActive)
    }

    @Test
    fun testPollingCoordinator_scopeCancellationTerminatesPolling() = runTest {
        val customScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val strategy = FixedDelayPollingStrategy(delayMs = 1000L)
        val coordinator = PollingCoordinator(
            scope = customScope,
            strategy = strategy,
            initialEnabled = true,
            onAction = {}
        )

        assertTrue(coordinator.isPollingActive)
        customScope.cancel()
        assertFalse(coordinator.isPollingActive)
    }
}
