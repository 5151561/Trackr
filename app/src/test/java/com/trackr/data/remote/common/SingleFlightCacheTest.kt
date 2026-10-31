package com.trackr.data.remote.common

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightCacheTest {

    private var now = 1_000L

    private fun newCache(ttlMillis: Long = 6_000L, maxEntries: Int = 64) = SingleFlightCache<String, String>(
        ttlMillis = ttlMillis,
        maxEntries = maxEntries,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        nowMillis = { now }
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
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndGet(); gate.await(); "v" } }
        awaitCondition { calls.get() == 1 && cache.inFlightCount == 1 }
        val second = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndGet(); gate.await(); "v2" } }
        delay(50)
        yield()
        gate.complete(Unit)

        assertEquals("v", first.await())
        assertEquals("v", second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellingOneWaiter_doesNotFailOthers() = runBlocking {
        val cache = newCache()
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndGet(); gate.await(); "v" } }
        awaitCondition { calls.get() == 1 }
        val second = async(Dispatchers.Default) { cache.get("k") { calls.incrementAndGet(); "other" } }
        delay(50)

        first.cancelAndJoin()
        gate.complete(Unit)

        assertEquals("v", second.await())
        assertEquals(1, calls.get())
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
        val calls = AtomicInteger(0)
        try {
            cache.get("k") { calls.incrementAndGet(); throw IOException("boom") }
            fail("应抛出 IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("boom") == true)
        }
        assertEquals("ok", cache.get("k") { calls.incrementAndGet(); "ok" })
        assertEquals(2, calls.get())
    }

    @Test
    fun successIsCachedWithinTtlAndExpiresAfter() = runBlocking {
        val cache = newCache(ttlMillis = 6_000L)
        val calls = AtomicInteger(0)
        assertEquals("v1", cache.get("k") { "v${calls.incrementAndGet()}" })

        now += 5_999L
        assertEquals("v1", cache.get("k") { "v${calls.incrementAndGet()}" })

        now += 1L
        assertEquals("v2", cache.get("k") { "v${calls.incrementAndGet()}" })
        assertEquals(2, calls.get())
    }

    @Test
    fun zeroTtl_onlyDeduplicates() = runBlocking {
        val cache = newCache(ttlMillis = 0L)
        val calls = AtomicInteger(0)
        cache.get("k") { "v${calls.incrementAndGet()}" }
        cache.get("k") { "v${calls.incrementAndGet()}" }
        assertEquals(2, calls.get())
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
