package com.trackr.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandardCityCatalogTest {

    @Test
    fun catalogLoadsPrefecturesCountiesAndDistricts() {
        assertTrue(StandardCityCatalog.all.size > 3000)

        val shanghai = StandardCityCatalog.findByAdcode("310100")
        assertNotNull(shanghai)
        assertEquals("上海", shanghai.name)
        assertEquals("直辖市", shanghai.province)
        assertEquals(StandardCityLevel.CITY, shanghai.level)
        assertTrue(shanghai.isHot)

        // 兼容直辖市省级码
        assertEquals(shanghai, StandardCityCatalog.findByAdcode("310000"))

        val beiliu = StandardCityCatalog.findByAdcode("450981")
        assertNotNull(beiliu)
        assertEquals("北流", beiliu.name)
        assertEquals("广西", beiliu.province)
        assertEquals("玉林", beiliu.parentCity)
        assertEquals(StandardCityLevel.COUNTY, beiliu.level)

        val shunde = StandardCityCatalog.findByAdcode("440606")
        assertNotNull(shunde)
        assertEquals("顺德区", shunde.name)
        assertEquals("广东", shunde.province)
        assertEquals("佛山", shunde.parentCity)
        assertEquals(StandardCityLevel.DISTRICT, shunde.level)

        // 省直辖县直接归省，不挂虚构父级
        val xiantao = StandardCityCatalog.findByAdcode("429004")
        assertNotNull(xiantao)
        assertEquals("仙桃", xiantao.name)
        assertEquals("湖北", xiantao.province)
        assertNull(xiantao.parentCity)
    }

    @Test
    fun fallbackCities_includesCitiesAndCounties_excludesUrbanDistricts() {
        val fallback = StandardCityCatalog.fallbackCities()
        assertTrue(fallback.size > 2000)
        assertTrue(fallback.any { it.mygolbsCityCode == "310100" && it.cityName == "上海" })
        assertTrue(fallback.any { it.mygolbsCityCode == "450900" && it.cityName == "玉林" })
        assertTrue(fallback.any { it.mygolbsCityCode == "450981" && it.cityName == "北流" && it.parentCity == "玉林" })
        assertTrue(fallback.none { it.mygolbsCityCode == "440606" }, "兜底列表不铺开普通市辖区")
    }
}
