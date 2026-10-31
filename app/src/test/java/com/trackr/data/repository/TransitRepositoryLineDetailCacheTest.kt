package com.trackr.data.repository

import androidx.test.core.app.ApplicationProvider
import com.trackr.data.provider.ITransitProvider
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实 TransitRepository 的线路详情 in-flight 去重与短 TTL 缓存测试（数据源为假 Provider，不发 HTTP）
 */
@RunWith(RobolectricTestRunner::class)
class TransitRepositoryLineDetailCacheTest {

    private class GatedLineProvider(override val sourceType: DataSourceType) : ITransitProvider {
        val lineDetailCalls = AtomicInteger(0)
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failNext: Boolean = false

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
            LineTimetable(lineId = line.lineId, lineName = line.name)

        override suspend fun getLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail {
            val n = lineDetailCalls.incrementAndGet()
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
    private lateinit var repository: TransitRepository

    @Before
    fun setUp() {
        provider = GatedLineProvider(DataSourceType.CHELAILE)
        repository = TransitRepository(
            context = ApplicationProvider.getApplicationContext(),
            coordinator = TransitProviderCoordinator(
                chelaileProvider = provider,
                mygolbsProvider = GatedLineProvider(DataSourceType.MYGOLBS)
            ),
            nowMillis = { now },
            fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    }

    private suspend fun awaitCalls(expected: Int) {
        withTimeout(2_000L) {
            while (provider.lineDetailCalls.get() < expected) delay(5)
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
        assertEquals(1, provider.lineDetailCalls.get())
    }

    @Test
    fun withinTtl_reusesResult_andRefetchesAfterTtl() = runBlocking {
        val first = repository.getLineDetail(city, "line_1", targetOrder = 3).getOrThrow()
        now += TransitRepository.LINE_DETAIL_CACHE_TTL_MS - 1
        val second = repository.getLineDetail(city, "line_1", targetOrder = 3).getOrThrow()
        assertEquals(first.updatedAtMillis, second.updatedAtMillis)
        assertEquals(1, provider.lineDetailCalls.get())

        now += 1
        repository.getLineDetail(city, "line_1", targetOrder = 3).getOrThrow()
        assertEquals(2, provider.lineDetailCalls.get())
    }

    @Test
    fun differentTargetOrder_isSeparateKey() = runBlocking {
        repository.getLineDetail(city, "line_1", targetOrder = 3)
        repository.getLineDetail(city, "line_1", targetOrder = 4)
        repository.getLineDetail(city, "line_1", targetOrder = null)
        assertEquals(3, provider.lineDetailCalls.get())
    }

    @Test
    fun failure_isNotCached() = runBlocking {
        provider.failNext = true
        val failed = repository.getLineDetail(city, "line_1", targetOrder = 3)
        assertTrue(failed.isFailure)

        val ok = repository.getLineDetail(city, "line_1", targetOrder = 3)
        assertTrue(ok.isSuccess)
        assertEquals(2, provider.lineDetailCalls.get())
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
        assertEquals(1, provider.lineDetailCalls.get())
    }
}
