package com.trackr.core.network.remote.amap

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 线程安全的轻量 LRU 内存缓存（commonMain，不依赖 android.util.LruCache）。
 *
 * 用于高德输入提示、公交算路等「同参数短时间内重复请求」的场景，减少额度消耗。
 * 写法：atomicfu 锁 + 普通 LinkedHashMap「读时删了再插」，最旧的在最前（重构计划 §2.10）。
 */
class AmapLruCache<K : Any, V : Any>(private val maxSize: Int) {

    init {
        require(maxSize > 0) { "maxSize 必须大于 0" }
    }

    private val lock = SynchronizedObject()
    private val map = LinkedHashMap<K, V>()

    fun get(key: K): V? = synchronized(lock) {
        val value = map.remove(key) ?: return@synchronized null
        map[key] = value
        value
    }

    fun put(key: K, value: V) {
        synchronized(lock) {
            map.remove(key)
            map[key] = value
            while (map.size > maxSize) {
                map.remove(map.keys.iterator().next())
            }
        }
    }

    fun remove(key: K) {
        synchronized(lock) { map.remove(key) }
    }

    fun clear() {
        synchronized(lock) { map.clear() }
    }

    fun size(): Int = synchronized(lock) { map.size }
}
