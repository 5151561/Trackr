package com.trackr.core.data.repository

import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #6 / #57：定位在县城时应匹配到县城，而不是所属地级市
 */
class CountyLevelCityMatchTest {

    private val yulin = TransitCity(cityId = "", cityName = "玉林", mygolbsCityCode = "450900", province = "广西")
    private val beiliu = TransitCity(cityId = "", cityName = "北流", mygolbsCityCode = "450981", province = "广西", parentCity = "玉林")
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
    fun twoCharCounty_preservesXianSuffixAndMatches() {
        val rongxian = TransitCity(cityId = "", cityName = "容县", mygolbsCityCode = "450921", province = "广西", parentCity = "玉林")
        assertEquals("容县", ProvinceCityCatalog.cleanCityName("容县"))
        assertEquals("范县", ProvinceCityCatalog.cleanCityName("范县"))
        assertEquals(rongxian, matchCountyLevelCity("容县", "玉林", supported + rongxian))
    }

    @Test
    fun urbanDistrict_isLeftToPrefectureMatch() {
        assertNull(matchCountyLevelCity("玉州区", "玉林", supported))
    }

    @Test
    fun beijingChaoyangDistrict_doesNotMatchLiaoningChaoyang() {
        assertNull(matchCountyLevelCity("朝阳区", "北京", supported))
        assertNull(matchCountyLevelCity("朝阳区", null, supported))
    }

    @Test
    fun independentCountyLevelDistrict_inSameProvince_matches() {
        val shunde = TransitCity(cityId = "101", cityName = "顺德", province = "广东", parentCity = "佛山")
        assertEquals(shunde, matchCountyLevelCity("顺德区", "佛山市", supported + shunde))
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

    @Test
    fun resolveCityByLocation_supplementsMissingDistrictFromPlatformGeocoder() = runTest {
        // 模拟数据源只返回地级市「玉林」、没给区县，系统 Geocoder 补出「北流市」
        val provider = StubGeoProvider(
            cities = listOf(
                TransitCity(cityId = "", cityName = "玉林市", mygolbsCityCode = "450900"),
                TransitCity(cityId = "", cityName = "北流市", mygolbsCityCode = "450981")
            ),
            resolvedCity = "玉林",
            resolvedDistrict = null
        )
        val repo = DefaultRealtimeRepository(
            coordinator = TransitProviderCoordinator(provider),
            settings = FakeSettingsRepository(),
            favoritesRepository = FakeFavoritesRepository(),
            searchHistory = FakeSearchHistoryRepository(),
            userLocation = { null },
            geocoder = PlatformGeocoder { _, _ -> "玉林市" to "北流市" }
        )

        val resolved = repo.resolveCityByLocation(22.708, 110.354).getOrNull()
        assertEquals("北流", ProvinceCityCatalog.cleanCityName(resolved?.cityName.orEmpty()))
        assertEquals("450981", resolved?.mygolbsCityCode)
        assertEquals("广西", resolved?.province)
        assertEquals("玉林", ProvinceCityCatalog.cleanCityName(resolved?.parentCity.orEmpty()))
    }

    @Test
    fun resolveCityByLocation_withoutDataSource_matchesCountyFromStandardCatalog() = runTest {
        val provider = StubGeoProvider(cities = emptyList())
        val repo = DefaultRealtimeRepository(
            coordinator = TransitProviderCoordinator(provider),
            settings = FakeSettingsRepository(),
            favoritesRepository = FakeFavoritesRepository(),
            searchHistory = FakeSearchHistoryRepository(),
            userLocation = { null },
            geocoder = PlatformGeocoder { _, _ -> "玉林市" to "北流市" }
        )

        val resolved = repo.resolveCityByLocation(22.708, 110.354).getOrNull()
        assertEquals("北流", resolved?.cityName)
        assertEquals("450981", resolved?.mygolbsCityCode)
        assertEquals("广西", resolved?.province)
        assertEquals("玉林", resolved?.parentCity)
    }

    @Test
    fun resolveCityByLocation_prefersExactPrefectureOverContainsMatch() = runTest {
        val provider = StubGeoProvider(
            cities = listOf(
                TransitCity(cityId = "901", cityName = "唐山汉沽", province = "河北"),
                TransitCity(cityId = "041", cityName = "唐山", province = "河北")
            ),
            resolvedCity = "唐山",
            resolvedDistrict = "路北区"
        )
        val repo = DefaultRealtimeRepository(
            coordinator = TransitProviderCoordinator(provider),
            settings = FakeSettingsRepository(),
            favoritesRepository = FakeFavoritesRepository(),
            searchHistory = FakeSearchHistoryRepository(),
            userLocation = { null }
        )

        val resolved = repo.resolveCityByLocation(39.635, 118.175).getOrNull()
        assertEquals("唐山", resolved?.cityName)
    }

    private class StubGeoProvider(
        private val cities: List<TransitCity>,
        private val resolvedCity: String? = null,
        private val resolvedDistrict: String? = null
    ) : ITransitProvider {
        override val sourceType: DataSourceType = DataSourceType.CUSTOM
        override fun supportsLine(lineId: String): Boolean = true
        override fun supportsStop(request: StopDetailRequest): Boolean = true
        override fun supportsLocationRadar(): Boolean = true
        override suspend fun getSupportedCities(): List<TransitCity> = cities
        override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? = resolvedCity
        override suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String? = resolvedDistrict
        override suspend fun getNearbyStops(city: TransitCity, wgsLat: Double, wgsLng: Double, limit: Int): List<NearbyStop> = emptyList()
        override suspend fun getStopDetail(city: TransitCity, request: StopDetailRequest): List<NearbyStop> = emptyList()
        override suspend fun search(city: TransitCity, keyword: String, userLat: Double?, userLng: Double?): TransitSearchResult =
            TransitSearchResult(keyword, emptyList(), emptyList())
        override suspend fun getLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail = error("unused")
        override suspend fun getTimetable(city: TransitCity, line: BusLineDirection, atTimeHHMM: String?): LineTimetable = error("unused")
    }
}
