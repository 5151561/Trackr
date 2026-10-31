package com.trackr.data.repository

import com.trackr.domain.model.TransitCity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #6：定位在县城时应匹配到县城，而不是所属地级市
 */
class CountyLevelCityMatchTest {

    private val yulin = TransitCity(cityId = "", cityName = "玉林", mygolbsCityCode = "450900", province = "广西")
    private val beiliu = TransitCity(cityId = "", cityName = "北流", mygolbsCityCode = "450981", province = "广西")
    private val liaoningChaoyang = TransitCity(cityId = "", cityName = "朝阳", mygolbsCityCode = "211300", province = "辽宁")
    private val supported = listOf(yulin, beiliu, liaoningChaoyang)

    @Test
    fun countyLevelCity_matchesCounty() {
        assertEquals(beiliu, matchCountyLevelCity("北流市", "玉林", supported))
    }

    @Test
    fun county_withSuffixXian_matches() {
        val luchuan = TransitCity(cityId = "", cityName = "陆川县", province = "广西")
        assertEquals(luchuan, matchCountyLevelCity("陆川县", "玉林", supported + luchuan))
    }

    @Test
    fun urbanDistrict_isLeftToPrefectureMatch() {
        assertNull(matchCountyLevelCity("玉州区", "玉林", supported))
    }

    @Test
    fun beijingChaoyangDistrict_doesNotMatchLiaoningChaoyang() {
        assertNull(matchCountyLevelCity("朝阳区", "北京", supported))
    }

    @Test
    fun provinceMismatch_isRejected() {
        assertNull(matchCountyLevelCity("北流市", "杭州", supported))
    }

    @Test
    fun countyNotSupported_returnsNull() {
        assertNull(matchCountyLevelCity("容县", "玉林", supported))
    }

    @Test
    fun blankDistrict_returnsNull() {
        assertNull(matchCountyLevelCity(null, "玉林", supported))
        assertNull(matchCountyLevelCity("  ", "玉林", supported))
    }
}
