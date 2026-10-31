package com.trackr.data.remote.amap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmapLruCacheTest {

    @Test
    fun evictsLeastRecentlyUsedEntryWhenFull() {
        val cache = AmapLruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        // 访问 a 使其变为最近使用
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)

        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
        assertEquals(2, cache.size())
    }
}
