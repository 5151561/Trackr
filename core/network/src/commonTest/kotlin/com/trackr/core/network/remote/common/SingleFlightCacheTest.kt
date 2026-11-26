package com.trackr.core.network.remote.common

import com.trackr.core.domain.platform.MonotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalAtomicApi::class)
class SingleFlightCacheTest {

    private var now = 1_000L

    private fun newCache(ttlMillis: Long = 6_000L, maxEntries: Int = 64) = SingleFlightCache<String, String>(
        ttlMillis = ttlMillis,
        maxEntries = maxEntries,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clock = MonotonicClock { now }
    )

    /** 等待直到条件成立（最多 2 秒），用于跨线程的进行中状态 */
    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2_000L) {
            while (!condition()) delay(5)
        }
    }

    @Test
    fun concurrentCallsWithSameKey_loadOnce() = runBlocking {
        val cache = newCache()
        val calls = AtomicInt(0)
        val gate = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndFetch(); gate.await(); "v" } }
        awaitCondition { calls.load() == 1 && cache.inFlightCount == 1 }
        val second = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndFetch(); gate.await(); "v2" } }
        delay(50)
        yield()
        gate.complete(Unit)

        assertEquals("v", first.await())
        assertEquals("v", second.await())
        assertEquals(1, calls.load())
    }

    @Test
    fun cancellingOneWaiter_doesNotFailOthers() = runBlocking {
        val cache = newCache()
        val calls = AtomicInt(0)
        val gate = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndFetch(); gate.await(); "v" } }
        awaitCondition { calls.load() == 1 }
        val second = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndFetch(); "other" } }
        delay(50)

        first.cancelAndJoin()
        gate.complete(Unit)

        assertEquals("v", second.await())
        assertEquals(1, calls.load())
    }

    @Test
    fun cancellingAllWaiters_cancelsLoaderAndNextCallReloads() = runBlocking {
        val cache = newCache()
        val loaderCancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()

        val waiter = async(Dispatchers.Default) {
            cache.get("k") {
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    "never"
                } catch (e: Throwable) {
                    loaderCancelled.complete(Unit)
                    throw e
                }
            }
        }
        started.await()
        waiter.cancelAndJoin()
        withTimeout(2_000L) { loaderCancelled.await() }
        assertEquals(0, cache.inFlightCount)

        assertEquals("fresh", cache.get("k") { "fresh" })
    }

    @Test
    fun failure_isNotCached() = runBlocking {
        val cache = newCache()
        val calls = AtomicInt(0)
        try {
            cache.get("k") { calls.incrementAndFetch(); throw IOException("boom") }
            fail("应抛出 IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("boom") == true)
        }
        assertEquals("ok", cache.get("k") { calls.incrementAndFetch(); "ok" })
        assertEquals(2, calls.load())
    }

    @Test
    fun successIsCachedWithinTtlAndExpiresAfter() = runBlocking {
        val cache = newCache(ttlMillis = 6_000L)
        val calls = AtomicInt(0)
        assertEquals("v1", cache.get("k") { "v${calls.incrementAndFetch()}" })

        now += 5_999L
        assertEquals("v1", cache.get("k") { "v${calls.incrementAndFetch()}" })

        now += 1L
        assertEquals("v2", cache.get("k") { "v${calls.incrementAndFetch()}" })
        assertEquals(2, calls.load())
    }

    @Test
    fun zeroTtl_onlyDeduplicates() = runBlocking {
        val cache = newCache(ttlMillis = 0L)
        val calls = AtomicInt(0)
        cache.get("k") { "v${calls.incrementAndFetch()}" }
        cache.get("k") { "v${calls.incrementAndFetch()}" }
        assertEquals(2, calls.load())
        assertNull(cache.getCached("k"))
    }

    @Test
    fun cacheIsBoundedByMaxEntries() = runBlocking {
        val cache = newCache(maxEntries = 2)
        cache.get("a") { "A" }
        cache.get("b") { "B" }
        cache.get("c") { "C" }
        assertNull(cache.getCached("a"))
        assertEquals("B", cache.getCached("b"))
        assertEquals("C", cache.getCached("c"))
    }

    @Test
    fun invalidate_removesEntry() = runBlocking {
        val cache = newCache()
        cache.get("k") { "v" }
        cache.invalidate("k")
        assertNull(cache.getCached("k"))
    }
}
