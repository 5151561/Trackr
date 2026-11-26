package com.trackr.core.data.provider

import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitProviderCoordinatorTest {

    private val city = TransitCity(cityId = "", cityName = "上海", mygolbsCityCode = "310100")

    private fun summary(name: String, isSubway: Boolean = false) = BusLineSummary(
        name = name,
        lineNo = name,
        isSubway = isSubway,
        directions = listOf(BusLineDirection(lineId = "id_$name", name = name, startStop = "A", endStop = "B"))
    )

    @Test
    fun noProvider_citiesEmpty_searchFails() = runTest {
        val coordinator = TransitProviderCoordinator(emptyMap())
        assertTrue(coordinator.getSupportedCities().isEmpty())
        assertTrue(coordinator.getNearbyStops(city, 31.2, 121.4).isEmpty())
        assertNull(coordinator.resolveCityByLocation(31.2, 121.4))
        assertFailsWith<IllegalStateException> { coordinator.search(city, "1") }
    }

    @Test
    fun search_dropsSubwayAndSortsNaturally() = runTest {
        val provider = FakeProvider().apply {
            searchResult = TransitSearchResult(
                keyword = "1",
                lines = listOf(summary("10路"), summary("地铁1号线"), summary("2路"), summary("M1", isSubway = true)),
                stops = listOf(
                    NearbyStop(stopId = "s1", name = "人民广场"),
                    NearbyStop(stopId = "s2", name = "人民广场（M）")
                )
            )
        }
        val result = TransitProviderCoordinator(provider).search(city, "1")

        assertEquals(listOf("2路", "10路"), result.lines.map { it.name })
        assertEquals(listOf("s1"), result.stops.map { it.stopId })
    }

    @Test
    fun cities_inferProvinceAndPrefecture_andDedupe() = runTest {
        val provider = FakeProvider().apply {
            cities = listOf(
                TransitCity(cityId = "", cityName = "玉林", mygolbsCityCode = "450900"),
                TransitCity(cityId = "", cityName = "北流", mygolbsCityCode = "450981"),
                TransitCity(cityId = "", cityName = "北流", mygolbsCityCode = "450981")
            )
        }
        val cities = TransitProviderCoordinator(provider).getSupportedCities()

        assertEquals(listOf("玉林", "北流"), cities.map { it.cityName })
        assertEquals("广西", cities[1].province)
        assertEquals("玉林", cities[1].parentCity)
    }

    @Test
    fun cities_cacheResetsWhenSourceChanges() = runTest {
        val provider = FakeProvider().apply {
            cacheKeyValue = "https://a.test"
            cities = listOf(TransitCity(cityId = "a", cityName = "上海"))
        }
        val coordinator = TransitProviderCoordinator(provider)
        assertEquals("a", coordinator.getSupportedCities().single().cityId)

        provider.cities = listOf(TransitCity(cityId = "b", cityName = "上海"))
        assertEquals("a", coordinator.getSupportedCities().single().cityId, "同一数据源走缓存")

        provider.cacheKeyValue = "https://b.test"
        assertEquals("b", coordinator.getSupportedCities().single().cityId, "换了数据源要重拉")
    }

    @Test
    fun providerFailure_nearbyAndCitiesFallBackToEmpty() = runTest {
        val provider = FakeProvider().apply { fail = true }
        val coordinator = TransitProviderCoordinator(provider)
        assertTrue(coordinator.getSupportedCities().isEmpty())
        assertTrue(coordinator.getNearbyStops(city, 31.2, 121.4).isEmpty())
        assertTrue(coordinator.listAllLines(city).isEmpty())
        assertTrue(coordinator.unreachableCities(listOf(city)).isEmpty())
    }

    private class FakeProvider : ITransitProvider {
        override val sourceType: DataSourceType = DataSourceType.CUSTOM
        var cacheKeyValue = ""
        override val cacheKey: String get() = cacheKeyValue
        var cities: List<TransitCity> = emptyList()
        var searchResult = TransitSearchResult(keyword = "")
        var fail = false

        private fun check() {
            if (fail) throw RuntimeException("boom")
        }

        override fun supportsLine(lineId: String) = true
        override fun supportsStop(request: StopDetailRequest) = true
        override fun supportsLocationRadar() = true
        override suspend fun getSupportedCities(): List<TransitCity> = cities.also { check() }
        override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? = null.also { check() }
        override suspend fun listAllLines(city: TransitCity): List<BusLineSummary>? = null.also { check() }
        override suspend fun isCityReachable(city: TransitCity): Boolean = true.also { check() }
        override suspend fun search(city: TransitCity, keyword: String, userLat: Double?, userLng: Double?) =
            searchResult.also { check() }
        override suspend fun getNearbyStops(city: TransitCity, wgsLat: Double, wgsLng: Double, limit: Int): List<NearbyStop> =
            emptyList<NearbyStop>().also { check() }
        override suspend fun getStopDetail(city: TransitCity, request: StopDetailRequest): List<NearbyStop> = emptyList()
        override suspend fun getLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail = error("unused")
        override suspend fun getTimetable(city: TransitCity, line: BusLineDirection, atTimeHHMM: String?): LineTimetable =
            error("unused")
    }
}
