package com.trackr.data.remote.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 同 key 请求合并（in-flight 去重）+ 短 TTL 内存缓存。
 *
 * - 同一 key 并发调用只执行一次 [get] 传入的 loader，其余调用方等待同一结果。
 * - loader 在独立的 [scope] 中执行：某个调用方被取消只影响它自己的等待，不会让其他等待者失败；
 *   只有当所有等待者都取消时才取消底层请求。
 * - 只缓存成功结果，失败不缓存；[ttlMillis] <= 0 时只做去重不缓存。
 * - 缓存按 LRU 淘汰，最多 [maxEntries] 条。
 */
class SingleFlightCache<K : Any, V : Any>(
    private val ttlMillis: Long,
    private val maxEntries: Int = 64,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    private class Flight<V>(val deferred: Deferred<V>) {
        /** 当前等待者数量，受 [lock] 保护 */
        var waiters: Int = 0
    }

    private class Entry<V>(val value: V, val atMillis: Long)

    private val lock = Any()
    private val inFlight = HashMap<K, Flight<V>>()
    private val cache = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
            return size > maxEntries
        }
    }

    /**
     * 读取未过期的缓存值，不触发请求
     */
    fun getCached(key: K): V? = synchronized(lock) { freshLocked(key) }

    /**
     * 移除指定 key 的缓存（不影响正在进行的请求）
     */
    fun invalidate(key: K) {
        synchronized(lock) { cache.remove(key) }
    }

    /**
     * 清空缓存（不影响正在进行的请求）
     */
    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    /**
     * 当前正在进行中的请求数（测试用）
     */
    val inFlightCount: Int
        get() = synchronized(lock) { inFlight.size }

    /**
     * 命中未过期缓存直接返回；否则加入同 key 的进行中请求，或发起新请求。
     */
    suspend fun get(key: K, loader: suspend () -> V): V {
        val flight: Flight<V> = synchronized(lock) {
            freshLocked(key)?.let { return it }
            val existing = inFlight[key]
            val target = if (existing != null) {
                existing
            } else {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    val value = loader()
                    if (ttlMillis > 0) {
                        synchronized(lock) { cache[key] = Entry(value, nowMillis()) }
                    }
                    value
                }
                val created = Flight(deferred)
                inFlight[key] = created
                deferred.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight[key] === created) inFlight.remove(key)
                    }
                }
                created
            }
            target.waiters++
            target
        }
        flight.deferred.start()
        try {
            return flight.deferred.await()
        } finally {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters <= 0 && !flight.deferred.isCompleted) {
                    // 所有等待者都已离开：撤销进行中请求，后续调用重新发起
                    if (inFlight[key] === flight) inFlight.remove(key)
                    flight.deferred.cancel()
                }
            }
        }
    }

    private fun freshLocked(key: K): V? {
        if (ttlMillis <= 0) return null
        val entry = cache[key] ?: return null
        if (nowMillis() - entry.atMillis < ttlMillis) return entry.value
        cache.remove(key)
        return null
    }
}
