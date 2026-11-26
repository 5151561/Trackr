package com.trackr.core.testing

import com.trackr.core.domain.platform.MonotonicClock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * 测试用时钟：墙钟（[Clock]）和单调时钟（[MonotonicClock]）一起走，只在调用 [advanceBy] 时前进。
 *
 * @param startEpochMillis 墙钟起点（默认 2026-11-01 08:00 北京时间）
 * @param startElapsedMillis 单调时钟起点（模拟开机后的时长）
 */
class FakeClock(
    startEpochMillis: Long = DEFAULT_START_EPOCH_MILLIS,
    startElapsedMillis: Long = DEFAULT_START_ELAPSED_MILLIS
) : Clock, MonotonicClock {

    var nowMillis: Long = startEpochMillis
        private set

    var elapsed: Long = startElapsedMillis
        private set

    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)

    override fun elapsedMillis(): Long = elapsed

    /** 两个时钟同时前进；[duration] 不能为负 */
    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "时钟不能倒退：$duration" }
        val millis = duration.inWholeMilliseconds
        nowMillis += millis
        elapsed += millis
    }

    /** 只改墙钟（模拟用户改系统时间），单调时钟不动 */
    fun setWallClock(epochMillis: Long) {
        nowMillis = epochMillis
    }

    companion object {
        /** 2026-11-01T00:00:00Z，即北京时间 08:00 */
        const val DEFAULT_START_EPOCH_MILLIS = 1_793_491_200_000L

        const val DEFAULT_START_ELAPSED_MILLIS = 3_600_000L
    }
}
