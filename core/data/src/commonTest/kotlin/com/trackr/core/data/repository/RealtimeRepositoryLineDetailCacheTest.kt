package com.trackr.core.data.repository

import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 线路详情 in-flight 去重与短 TTL 缓存测试（数据源为假 Provider，不发 HTTP）。
 * 原 :androidApp 的 TransitRepositoryLineDetailCacheTest；第 8 步缓存逻辑搬进 [DefaultRealtimeRepository]，用例原样搬来。
 */
@OptIn(ExperimentalAtomicApi::class)
class RealtimeRepositoryLineDetailCacheTest {

    private class GatedLineProvider(override val sourceType: DataSourceType) : ITransitProvider {
        val lineDetailCalls = AtomicInt(0)
        @kotlin.concurrent.Volatile var gate: CompletableDeferred<Unit>? = null
        @kotlin.concurrent.Volatile var failNext: Boolean = false
        @kotlin.concurrent.Volatile var timetableNextDeparture: String? = null

        override fun supportsLine(lineId: String): Boolean = true
        override fun supportsStop(request: StopDetailRequest): Boolean = true
        override fun supportsLocationRadar(): Boolean = false
        override suspend fun getSupportedCities(): List<TransitCity> = emptyList()
        override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? = null
        override suspend fun search(city: TransitCity, keyword: String, userLat: Double?, userLng: Double?) =
            TransitSearchResult(keyword)
        override suspend fun getNearbyStops(city: TransitCity, wgsLat: Double, wgsLng: Double, limit: Int) =
            emptyList<NearbyStop>()
        override suspend fun getStopDetail(city: TransitCity, request: StopDetailRequest) = emptyList<NearbyStop>()
        override suspend fun getTimetable(city: TransitCity, line: BusLineDirection, atTimeHHMM: String?) =
            LineTimetable(
                lineId = line.lineId,
                lineName = line.name,
                nextDeparture = timetableNextDeparture
            )

        override suspend fun getLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail {
            val n = lineDetailCalls.incrementAndFetch()
            gate?.await()
            if (failNext) {
                failNext = false
                throw IOException("upstream error")
            }
            return BusLineDetail(
                line = BusLineDirection(lineId = request.lineId, name = "49路", startStop = "A", endStop = "B", source = sourceType),
                stops = listOf(BusStop(stopId = "s1", name = "A", order = 1)),
                buses = emptyList(),
                targetOrder = request.targetOrder ?: 0,
                updatedAtMillis = n.toLong()
            )
        }
    }

    private val city = TransitCity(cityId = "034", cityName = "上海")
    private var now = 10_000L
    private lateinit var provider: GatedLineProvider
    private lateinit var repository: DefaultRealtimeRepository

    @BeforeTest
    fun setUp() {
        provider = GatedLineProvider(DataSourceType.CUSTOM)
        repository = DefaultRealtimeRepository(
            coordinator = TransitProviderCoordinator(provider),
            settings = FakeSettingsRepository(),
            favoritesRepository = FakeFavoritesRepository(),
            searchHistory = FakeSearchHistoryRepository(),
            userLocation = { null },
            clock = MonotonicClock { now },
            fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    }

    private suspend fun awaitCalls(expected: Int) {
        withTimeout(2_000L) {
            while (provider.lineDetailCalls.load() < expected) delay(5)
        }
    }

    @Test
    fun concurrentSameKey_requestsUpstreamOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate

        val a = async(Dispatchers.Default) { repository.getLineDetail(city, "line_1", targetOrder = 3, stationId = "s3") }
        awaitCalls(1)
        val b = async(Dispatchers.Default) { repository.getLineDetail(city, "line_1", targetOrder = 3, stationId = "s3") }
        delay(50)
        gate.complete(Unit)

        assertTrue(a.await().isSuccess)
        assertTrue(b.await().isSuccess)
        assertEquals(1, provider.lineDetailCalls.load())
    }

    @Test
    fun withinTtl_reusesResult_andRefetchesAfterTtl() = runBlocking {
        val first = repository.getLineDetail(city, "line_1", targetOrder = 3).getOrThrow()
        now += DefaultRealtimeRepository.LINE_DETAIL_CACHE_TTL_MS - 1
        val second = repository.getLineDetail(city, "line_1", targetOrder = 3).getOrThrow()
        assertEquals(first.updatedAtMillis, second.updatedAtMillis)
        assertEquals(1, provider.lineDetailCalls.load())

        now += 1
        repository.getLineDetail(city, "line_1", targetOrder = 3).getOrThrow()
        assertEquals(2, provider.lineDetailCalls.load())
    }

    @Test
    fun differentTargetOrder_isSeparateKey() = runBlocking {
        repository.getLineDetail(city, "line_1", targetOrder = 3)
        repository.getLineDetail(city, "line_1", targetOrder = 4)
        repository.getLineDetail(city, "line_1", targetOrder = null)
        assertEquals(3, provider.lineDetailCalls.load())
    }

    @Test
    fun failure_isNotCached() = runBlocking {
        provider.failNext = true
        val failed = repository.getLineDetail(city, "line_1", targetOrder = 3)
        assertTrue(failed.isFailure)

        val ok = repository.getLineDetail(city, "line_1", targetOrder = 3)
        assertTrue(ok.isSuccess)
        assertEquals(2, provider.lineDetailCalls.load())
    }

    @Test
    fun cancelledCaller_doesNotFailOtherWaiter() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate

        val a = async(Dispatchers.Default) { repository.getLineDetail(city, "line_1", targetOrder = 3) }
        awaitCalls(1)
        val b = async(Dispatchers.Default) { repository.getLineDetail(city, "line_1", targetOrder = 3) }
        delay(50)

        a.cancelAndJoin()
        gate.complete(Unit)

        assertTrue(b.await().isSuccess)
        assertEquals(1, provider.lineDetailCalls.load())
    }

    @Test
    fun favoriteWithoutBuses_fallsBackToTimetableNextDeparture() = runBlocking {
        provider.timetableNextDeparture = "07:20"
        val favorites = FakeFavoritesRepository(
            initialFavorites = listOf(
                FavoriteStationLine(
                    id = "fav_1",
                    cityId = city.cityId,
                    cityName = city.cityName,
                    lineId = "line_1",
                    lineName = "49路",
                    startStop = "A",
                    endStop = "B",
                    targetStopId = "s1",
                    targetStopName = "A",
                    targetOrder = 1
                )
            )
        )
        val repo = DefaultRealtimeRepository(
            coordinator = TransitProviderCoordinator(provider),
            settings = FakeSettingsRepository(),
            favoritesRepository = favorites,
            searchHistory = FakeSearchHistoryRepository(),
            userLocation = { null },
            clock = MonotonicClock { now },
            fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        val result = repo.refreshFavoritesRealtime().getOrThrow()

        assertEquals("07:20", result.getValue("fav_1").lines.single().preArrivalTime)
    }
}
