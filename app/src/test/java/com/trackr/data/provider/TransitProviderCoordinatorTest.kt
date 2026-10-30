package com.trackr.data.provider

import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransitProviderCoordinatorTest {

    private lateinit var fakeChelaileProvider: FakeTestTransitProvider
    private lateinit var fakeMygolbsProvider: FakeTestTransitProvider
    private lateinit var coordinator: TransitProviderCoordinator

    private val testCity = TransitCity(
        cityId = "034",
        cityName = "上海",
        pinyin = "ShangHai",
        supportSubway = true,
        isHot = true,
        province = "直辖市"
    )

    private fun createSummary(
        name: String,
        lineNo: String = name,
        isSubway: Boolean = false,
        source: DataSourceType = DataSourceType.CHELAILE
    ): BusLineSummary {
        val dir = BusLineDirection(
            lineId = "${source.name}_$name",
            name = name,
            lineNo = lineNo,
            startStop = "始发站",
            endStop = "终点站",
            source = source
        )
        return BusLineSummary(
            name = name,
            lineNo = lineNo,
            isSubway = isSubway,
            directions = listOf(dir),
            source = source
        )
    }

    @Before
    fun setUp() {
        fakeChelaileProvider = FakeTestTransitProvider(
            sourceType = DataSourceType.CHELAILE,
            supportsRadar = true,
            lineSupportPredicate = { !it.startsWith("mg:") },
            stopSupportPredicate = {
                it.stopId?.startsWith("mg:") != true &&
                        it.physicalStId?.startsWith("mg:") != true &&
                        it.namesakeStId?.startsWith("mg:") != true
            }
        )

        fakeMygolbsProvider = FakeTestTransitProvider(
            sourceType = DataSourceType.MYGOLBS,
            supportsRadar = true,
            lineSupportPredicate = { it.startsWith("mg:") },
            stopSupportPredicate = {
                it.stopId?.startsWith("mg:") == true ||
                        it.physicalStId?.startsWith("mg:") == true ||
                        it.namesakeStId?.startsWith("mg:") == true
            }
        )

        coordinator = TransitProviderCoordinator(
            chelaileProvider = fakeChelaileProvider,
            mygolbsProvider = fakeMygolbsProvider
        )
    }

    @Test
    fun testSearch_inAllMode_mergesAndDeduplicatesAndAppendsSuffix() = runBlocking {
        // Arrange
        fakeChelaileProvider.searchResult = TransitSearchResult(
            keyword = "1",
            lines = listOf(
                createSummary(name = "1路", source = DataSourceType.CHELAILE),
                createSummary(name = "10路", source = DataSourceType.CHELAILE),
                createSummary(name = "地铁1号线", isSubway = true, source = DataSourceType.CHELAILE)
            ),
            stops = listOf(
                NearbyStop(stopId = "cl_s1", name = "人民广场", isSubway = false),
                NearbyStop(stopId = "cl_metro_s", name = "人民广场(M)", isSubway = false) // 纯地铁站
            )
        )

        fakeMygolbsProvider.searchResult = TransitSearchResult(
            keyword = "1",
            lines = listOf(
                createSummary(name = "1路", source = DataSourceType.MYGOLBS),
                createSummary(name = "2路", source = DataSourceType.MYGOLBS)
            ),
            stops = listOf(
                NearbyStop(stopId = "mg:s1", name = "人民广场", isSubway = false),
                NearbyStop(stopId = "mg:s2", name = "五角场", isSubway = false)
            )
        )

        // Act
        val result = coordinator.search(testCity, "1", EngineMode.ALL)

        // Assert
        assertEquals(1, fakeChelaileProvider.searchCallCount)
        assertEquals(1, fakeMygolbsProvider.searchCallCount)

        // 地铁1号线应被过滤
        assertFalse(result.lines.any { it.name.contains("地铁") || it.isSubway })

        // 同名线路应被追加 [掌上公交] 并并存展示
        val lineNames = result.lines.map { it.name }
        assertTrue(lineNames.contains("1路"))
        assertTrue(lineNames.contains("1路 [掌上公交]"))
        assertTrue(lineNames.contains("2路"))
        assertTrue(lineNames.contains("10路"))

        // 自然排序：1路, 1路 [掌上公交], 2路, 10路
        assertEquals("1路", result.lines[0].name)
        assertEquals("1路 [掌上公交]", result.lines[1].name)
        assertEquals("2路", result.lines[2].name)
        assertEquals("10路", result.lines[3].name)

        // 纯地铁站点人民广场(M)应被过滤
        assertFalse(result.stops.any { it.name.contains("(M)") })

        // 同名站点应追加 (掌上公交)
        val stopNames = result.stops.map { it.name }
        assertTrue(stopNames.contains("人民广场"))
        assertTrue(stopNames.contains("人民广场 (掌上公交)"))
        assertTrue(stopNames.contains("五角场"))
    }

    @Test
    fun testSearch_inChelaileOnlyMode_onlyQueriesChelaile() = runBlocking {
        fakeChelaileProvider.searchResult = TransitSearchResult(
            keyword = "20",
            lines = listOf(
                createSummary(name = "20路", source = DataSourceType.CHELAILE)
            )
        )

        val result = coordinator.search(testCity, "20", EngineMode.CHELAILE)

        assertEquals(1, fakeChelaileProvider.searchCallCount)
        assertEquals(0, fakeMygolbsProvider.searchCallCount)
        assertEquals(1, result.lines.size)
        assertEquals("20路", result.lines[0].name)
    }

    @Test
    fun testSearch_inMygolbsOnlyMode_onlyQueriesMygolbs() = runBlocking {
        fakeMygolbsProvider.searchResult = TransitSearchResult(
            keyword = "20",
            lines = listOf(
                createSummary(name = "20路", source = DataSourceType.MYGOLBS)
            )
        )

        val result = coordinator.search(testCity, "20", EngineMode.MYGOLBS)

        assertEquals(0, fakeChelaileProvider.searchCallCount)
        assertEquals(1, fakeMygolbsProvider.searchCallCount)
        assertEquals(1, result.lines.size)
        assertEquals("20路", result.lines[0].name)
    }

    @Test
    fun testGetStopDetail_withMgPrefix_routesDirectlyToMygolbs() = runBlocking {
        val dir = BusLineDirection(
            lineId = "mg:l1",
            name = "1路",
            startStop = "始发站",
            endStop = "终点站",
            source = DataSourceType.MYGOLBS
        )
        val mgStop = NearbyStop(
            stopId = "mg:450900:玉林:一中",
            name = "一中",
            lines = listOf(
                NearbyStopLineStatus(
                    line = dir,
                    targetOrder = 1,
                    targetStopId = "mg:450900:玉林:一中",
                    targetStopName = "一中"
                )
            )
        )
        fakeMygolbsProvider.stopDetailResult = listOf(mgStop)

        val request = StopDetailRequest(stopId = "mg:450900:玉林:一中", stopName = "一中")
        val result = coordinator.getStopDetail(testCity, request, EngineMode.ALL)

        assertEquals(0, fakeChelaileProvider.stopDetailCallCount)
        assertEquals(1, fakeMygolbsProvider.stopDetailCallCount)
        assertEquals(1, result.size)
        assertEquals("一中", result[0].name)
    }

    @Test
    fun testGetStopDetail_inChelaileMode_doesNotFallbackToMygolbsWhenEmpty() = runBlocking {
        fakeChelaileProvider.stopDetailResult = emptyList()
        val dir = BusLineDirection(
            lineId = "l1",
            name = "1路",
            startStop = "A",
            endStop = "B",
            source = DataSourceType.MYGOLBS
        )
        fakeMygolbsProvider.stopDetailResult = listOf(
            NearbyStop(
                stopId = "mg:s1",
                name = "人民广场",
                lines = listOf(
                    NearbyStopLineStatus(
                        line = dir,
                        targetOrder = 1,
                        targetStopId = "s1",
                        targetStopName = "人民广场"
                    )
                )
            )
        )

        val request = StopDetailRequest(stopId = "normal_id", stopName = "人民广场")
        val result = coordinator.getStopDetail(testCity, request, EngineMode.CHELAILE)

        assertEquals(1, fakeChelaileProvider.stopDetailCallCount)
        assertEquals(0, fakeMygolbsProvider.stopDetailCallCount)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testGetStopDetail_inAllMode_fallsBackToMygolbsWhenChelaileReturnsEmpty() = runBlocking {
        // Stage 1 + 2 (Chelaile) returns empty
        fakeChelaileProvider.stopDetailResult = emptyList()

        val dir = BusLineDirection(
            lineId = "mg:l1",
            name = "1路",
            startStop = "A",
            endStop = "B",
            source = DataSourceType.MYGOLBS
        )
        // Stage 3 (Mygolbs) returns stop with line info
        val mgStop = NearbyStop(
            stopId = "mg:s1",
            name = "人民广场",
            lines = listOf(
                NearbyStopLineStatus(
                    line = dir,
                    targetOrder = 1,
                    targetStopId = "mg:s1",
                    targetStopName = "人民广场"
                )
            )
        )
        fakeMygolbsProvider.stopDetailResult = listOf(mgStop)

        val request = StopDetailRequest(stopId = "normal_id", stopName = "人民广场")
        val result = coordinator.getStopDetail(testCity, request, EngineMode.ALL)

        assertEquals(1, fakeChelaileProvider.stopDetailCallCount)
        assertEquals(1, fakeMygolbsProvider.stopDetailCallCount)
        assertEquals(1, result.size)
        assertEquals("人民广场", result[0].name)
    }

    @Test
    fun testGetStopDetail_inAllMode_doesNotFallbackIfStopNameIsBlank() = runBlocking {
        fakeChelaileProvider.stopDetailResult = emptyList()
        val dir = BusLineDirection(
            lineId = "mg:l1",
            name = "1路",
            startStop = "A",
            endStop = "B",
            source = DataSourceType.MYGOLBS
        )
        fakeMygolbsProvider.stopDetailResult = listOf(
            NearbyStop(
                stopId = "mg:s1",
                name = "人民广场",
                lines = listOf(
                    NearbyStopLineStatus(
                        line = dir,
                        targetOrder = 1,
                        targetStopId = "s1",
                        targetStopName = "人民广场"
                    )
                )
            )
        )

        val request = StopDetailRequest(stopId = "normal_id", stopName = null)
        val result = coordinator.getStopDetail(testCity, request, EngineMode.ALL)

        assertEquals(1, fakeChelaileProvider.stopDetailCallCount)
        assertEquals(0, fakeMygolbsProvider.stopDetailCallCount)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testGetLineDetail_routesCorrectlyByPrefixAndSource() = runBlocking {
        val clDir = BusLineDirection(lineId = "cl_100", name = "100路", startStop = "A", endStop = "B", source = DataSourceType.CHELAILE)
        val mgDir = BusLineDirection(lineId = "mg:100", name = "100路", startStop = "A", endStop = "B", source = DataSourceType.MYGOLBS)
        val clDetail = BusLineDetail(line = clDir, stops = emptyList(), buses = emptyList())
        val mgDetail = BusLineDetail(line = mgDir, stops = emptyList(), buses = emptyList())

        fakeChelaileProvider.lineDetailResult = clDetail
        fakeMygolbsProvider.lineDetailResult = mgDetail

        // 1. By lineId prefix mg:
        val res1 = coordinator.getLineDetail(testCity, LineDetailRequest(lineId = "mg:100"))
        assertEquals("mg:100", res1.line.lineId)
        assertEquals(1, fakeMygolbsProvider.lineDetailCallCount)

        // 2. Normal lineId
        val res2 = coordinator.getLineDetail(testCity, LineDetailRequest(lineId = "cl_100"))
        assertEquals("cl_100", res2.line.lineId)
        assertEquals(1, fakeChelaileProvider.lineDetailCallCount)

        // 3. Explicit source
        val res3 = coordinator.getLineDetail(testCity, LineDetailRequest(lineId = "unknown", source = DataSourceType.MYGOLBS))
        assertEquals(2, fakeMygolbsProvider.lineDetailCallCount)
    }

    @Test
    fun testGetTimetable_routesCorrectly() = runBlocking {
        fakeChelaileProvider.timetableResult = LineTimetable(lineId = "cl_1", lineName = "1路", depDesc = "车来了时刻表")
        fakeMygolbsProvider.timetableResult = LineTimetable(lineId = "mg:1", lineName = "1路", depDesc = "掌上公交首末班")

        val clLine = BusLineDirection(lineId = "cl_1", name = "1路", startStop = "A", endStop = "B", source = DataSourceType.CHELAILE)
        val mgLine = BusLineDirection(lineId = "mg:1", name = "1路", startStop = "A", endStop = "B", source = DataSourceType.MYGOLBS)

        val t1 = coordinator.getTimetable(testCity, clLine)
        assertEquals("车来了时刻表", t1.depDesc)
        assertEquals(1, fakeChelaileProvider.timetableCallCount)

        val t2 = coordinator.getTimetable(testCity, mgLine)
        assertEquals("掌上公交首末班", t2.depDesc)
        assertEquals(1, fakeMygolbsProvider.timetableCallCount)
    }

    @Test
    fun testGetSupportedCities_mergesDualSourceAndFillsMygolbsCode() = runBlocking {
        fakeChelaileProvider.supportedCitiesResult = listOf(
            TransitCity(cityId = "034", cityName = "上海", province = "直辖市"),
            TransitCity(cityId = "027", cityName = "北京", province = "直辖市"),
            TransitCity(cityId = "440", cityName = "玉林", province = "广西")
        )

        fakeMygolbsProvider.supportedCitiesResult = listOf(
            TransitCity(cityId = "", cityName = "上海市", mygolbsCityCode = "310000"),
            TransitCity(cityId = "", cityName = "玉林市", mygolbsCityCode = "450900"),
            TransitCity(cityId = "", cityName = "北流市", mygolbsCityCode = "450981")
        )

        val cities = coordinator.getSupportedCities(forceRefresh = true)

        assertEquals(4, cities.size)

        val shanghai = cities.first { it.cityName == "上海" }
        assertEquals("034", shanghai.cityId)
        assertEquals("310000", shanghai.mygolbsCityCode)

        val beijing = cities.first { it.cityName == "北京" }
        assertEquals("027", beijing.cityId)
        assertNull(beijing.mygolbsCityCode)

        val yulin = cities.first { it.cityName == "玉林" }
        assertEquals("440", yulin.cityId)
        assertEquals("450900", yulin.mygolbsCityCode)

        // 掌上公交独有城市追加合入
        val beiliu = cities.first { it.cityName == "北流市" }
        assertEquals("450981", beiliu.mygolbsCityCode)
        assertEquals("广西", beiliu.province)

        // 验证缓存有效：forceRefresh = false 时不再调用底层 provider
        val cached = coordinator.getSupportedCities(forceRefresh = false)
        assertEquals(cities.size, cached.size)
        assertEquals(1, fakeChelaileProvider.supportedCitiesCallCount)
        assertEquals(1, fakeMygolbsProvider.supportedCitiesCallCount)
    }

    @Test
    fun testResolveCityByLocation_queriesRadarProvider() = runBlocking {
        fakeChelaileProvider.resolveCityResult = "上海"

        val resolved = coordinator.resolveCityByLocation(31.23, 121.47)

        assertEquals("上海", resolved)
        assertEquals(1, fakeChelaileProvider.resolveCityCallCount)
        assertEquals(0, fakeMygolbsProvider.resolveCityCallCount)
    }

    @Test
    fun testGetNearbyStops_routesToRadarProvider() = runBlocking {
        fakeChelaileProvider.nearbyStopsResult = listOf(
            NearbyStop(stopId = "s1", name = "站台A")
        )

        val stops = coordinator.getNearbyStops(testCity, 31.23, 121.47)

        assertEquals(1, stops.size)
        assertEquals("站台A", stops[0].name)
        assertEquals(1, fakeChelaileProvider.nearbyStopsCallCount)
    }

    @Test
    fun testGetNearbyStops_withMygolbsMode_routesToMygolbsRadar() = runBlocking {
        fakeChelaileProvider.nearbyStopsResult = listOf(
            NearbyStop(stopId = "s1", name = "站台A")
        )
        fakeMygolbsProvider.nearbyStopsResult = listOf(
            NearbyStop(stopId = "s_mg", name = "掌上站台B")
        )

        val stops = coordinator.getNearbyStops(testCity, 31.23, 121.47, mode = EngineMode.MYGOLBS)

        assertEquals(1, stops.size)
        assertEquals("掌上站台B", stops[0].name)
        assertEquals(0, fakeChelaileProvider.nearbyStopsCallCount)
        assertEquals(1, fakeMygolbsProvider.nearbyStopsCallCount)
    }

    @Test
    fun testGetNearbyStops_withAllMode_chelaileEmpty_fallsBackToMygolbsRadar() = runBlocking {
        fakeChelaileProvider.nearbyStopsResult = emptyList()
        fakeMygolbsProvider.nearbyStopsResult = listOf(
            NearbyStop(stopId = "s_mg", name = "掌上兜底站台")
        )

        val stops = coordinator.getNearbyStops(testCity, 31.23, 121.47, mode = EngineMode.ALL)

        assertEquals(1, stops.size)
        assertEquals("掌上兜底站台", stops[0].name)
        assertEquals(1, fakeChelaileProvider.nearbyStopsCallCount)
        assertEquals(1, fakeMygolbsProvider.nearbyStopsCallCount)
    }
}

/**
 * 离线 Fake ITransitProvider，用于精准控制返回值与统计调用计数
 */
class FakeTestTransitProvider(
    override val sourceType: DataSourceType,
    private val supportsRadar: Boolean = false,
    private val lineSupportPredicate: (String) -> Boolean = { true },
    private val stopSupportPredicate: (StopDetailRequest) -> Boolean = { true }
) : ITransitProvider {

    var searchCallCount = 0
    var stopDetailCallCount = 0
    var lineDetailCallCount = 0
    var timetableCallCount = 0
    var supportedCitiesCallCount = 0
    var resolveCityCallCount = 0
    var nearbyStopsCallCount = 0

    var supportedCitiesResult: List<TransitCity> = emptyList()
    var resolveCityResult: String? = null
    var searchResult: TransitSearchResult = TransitSearchResult("")
    var nearbyStopsResult: List<NearbyStop> = emptyList()
    var stopDetailResult: List<NearbyStop> = emptyList()
    var lineDetailResult: BusLineDetail = BusLineDetail(
        line = BusLineDirection(lineId = "dummy", name = "dummy", startStop = "A", endStop = "B"),
        stops = emptyList(),
        buses = emptyList()
    )
    var timetableResult: LineTimetable = LineTimetable(lineId = "dummy", lineName = "dummy")

    override fun supportsLine(lineId: String): Boolean = lineSupportPredicate(lineId)

    override fun supportsStop(request: StopDetailRequest): Boolean = stopSupportPredicate(request)

    override fun supportsLocationRadar(): Boolean = supportsRadar

    override suspend fun getSupportedCities(): List<TransitCity> {
        supportedCitiesCallCount++
        return supportedCitiesResult
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? {
        resolveCityCallCount++
        return resolveCityResult
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): TransitSearchResult {
        searchCallCount++
        return searchResult
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> {
        nearbyStopsCallCount++
        return nearbyStopsResult
    }

    override suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop> {
        stopDetailCallCount++
        return stopDetailResult
    }

    override suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail {
        lineDetailCallCount++
        return lineDetailResult
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): LineTimetable {
        timetableCallCount++
        return timetableResult
    }
}
