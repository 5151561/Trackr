package com.trackr.ui.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 轮询执行策略接口（策略模式）
 */
interface PollingStrategy {
    /**
     * 在调用方协程内执行轮询循环
     *
     * @param onAction 每次轮询周期到达时的回调动作
     */
    suspend fun execute(onAction: suspend () -> Unit)
}

/**
 * 固定延迟轮询策略：每个周期等待固定的 [delayMs] 后触发 [onAction]
 */
class FixedDelayPollingStrategy(
    val delayMs: Long
) : PollingStrategy {
    override suspend fun execute(onAction: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay(delayMs)
            try {
                onAction()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // 保障定时循环不因单次动作异常而中断
            }
        }
    }
}

/**
 * 倒计时轮询策略：每 1 秒触发一次 [onTick]，到期归零时重置倒计时并触发 [onAction]
 *
 * @property intervalSeconds 倒计时周期（秒），默认为 10 秒
 * @property onTick 每秒剩余时间回调
 */
class CountdownPollingStrategy(
    val intervalSeconds: Int = 10,
    val onTick: (remainingSeconds: Int) -> Unit
) : PollingStrategy {
    @Volatile
    private var remainingSeconds: Int = intervalSeconds

    val currentRemainingSeconds: Int
        get() = remainingSeconds

    fun reset(seconds: Int = intervalSeconds) {
        remainingSeconds = seconds
        onTick(seconds)
    }

    override suspend fun execute(onAction: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay(1000L)
            if (remainingSeconds <= 1) {
                remainingSeconds = intervalSeconds
                onTick(remainingSeconds)
                try {
                    onAction()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // 保障倒计时循环不受单次动作异常影响
                }
            } else {
                remainingSeconds -= 1
                onTick(remainingSeconds)
            }
        }
    }
}

/**
 * 轮询协调器（面向对象封装）：
 * 统一管理轮询生命周期、并发安全锁定与协程取消。
 */
class PollingCoordinator(
    private val scope: CoroutineScope,
    val strategy: PollingStrategy,
    initialEnabled: Boolean = true,
    private val onAction: suspend () -> Unit
) {
    private val lock = Any()

    @Volatile
    private var isEnabled: Boolean = initialEnabled

    @Volatile
    private var job: Job? = null

    val isPollingActive: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    val autoPollingEnabled: Boolean
        get() = synchronized(lock) { isEnabled }

    init {
        if (initialEnabled) {
            resume()
        }
    }

    /**
     * 设置轮询是否激活（显式开关或生命周期管理）
     */
    fun setActive(active: Boolean) {
        synchronized(lock) {
            isEnabled = active
            if (active) {
                resumeInternal()
            } else {
                pauseInternal()
            }
        }
    }

    /**
     * 暂停后台轮询，安全取消当前协程 Job
     */
    fun pause() {
        synchronized(lock) {
            pauseInternal()
        }
    }

    /**
     * 恢复/启动后台轮询（幂等，若已在运行或被禁用则忽略）
     */
    fun resume() {
        synchronized(lock) {
            resumeInternal()
        }
    }

    /**
     * 重启倒计时（若当前策略为倒计时策略）
     */
    fun restartCountdown(seconds: Int = 10) {
        synchronized(lock) {
            if (strategy is CountdownPollingStrategy) {
                strategy.reset(seconds)
                if (job?.isActive == true) {
                    pauseInternal()
                    resumeInternal()
                }
            }
        }
    }

    private fun pauseInternal() {
        job?.cancel()
        job = null
    }

    private fun resumeInternal() {
        if (!isEnabled) return
        if (job?.isActive == true) return
        job = scope.launch {
            strategy.execute(onAction)
        }
    }
}
