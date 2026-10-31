package com.trackr.data.remote.amap

/**
 * 线程安全的轻量 LRU 内存缓存（纯 JVM 实现，便于单元测试，不依赖 android.util.LruCache）。
 *
 * 用于高德输入提示、公交算路等「同参数短时间内重复请求」的场景，减少额度消耗。
 */
class AmapLruCache<K : Any, V : Any>(private val maxSize: Int) {

    init {
        require(maxSize > 0) { "maxSize 必须大于 0" }
    }

    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}
