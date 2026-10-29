package com.trackr

import com.trackr.domain.model.DataSourceType
import com.trackr.ui.navigation.ScreenKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationKeyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testScreenKey_homeSerialization() {
        val key: ScreenKey = ScreenKey.Home
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertEquals(ScreenKey.Home, deserialized)
    }

    @Test
    fun testScreenKey_searchSerialization() {
        val key: ScreenKey = ScreenKey.Search
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertEquals(ScreenKey.Search, deserialized)
    }

    @Test
    fun testScreenKey_lineDetailSerialization_preservesFields() {
        val key: ScreenKey = ScreenKey.LineDetail(
            cityId = "021",
            lineId = "line_999",
            targetOrder = 8,
            stopId = "stop_123",
            source = DataSourceType.MYGOLBS,
            lineName = "49路"
        )
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertTrue(deserialized is ScreenKey.LineDetail)
        val detail = deserialized as ScreenKey.LineDetail
        assertEquals("021", detail.cityId)
        assertEquals("line_999", detail.lineId)
        assertEquals(8, detail.targetOrder)
        assertEquals("stop_123", detail.stopId)
        assertEquals(DataSourceType.MYGOLBS, detail.source)
        assertEquals("49路", detail.lineName)
    }
}
