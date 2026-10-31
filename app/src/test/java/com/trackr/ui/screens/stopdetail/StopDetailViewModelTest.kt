package com.trackr.ui.screens.stopdetail

import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.TransitCity
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 记录站点请求次数、可挂起请求的仓库包装（其余行为委托给 FakeTransitRepository）
 */
private class CountingStopRepository(
    private val delegate: FakeTransitRepository
) : ITransitRepository by delegate {
    val stopDetailPhysicalIds = mutableListOf<String?>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getStopDetail(
        city: TransitCity,
        stopId: String?,
        stopName: String?,
        physicalStId: String?,
        namesakeStId: String?,
        stationLat: Double?,
        stationLng: Double?,
        userLat: Double?,
        userLng: Double?
    ): Result<List<NearbyStop>> {
        stopDetailPhysicalIds += physicalStId
        gate?.await()
        return delegate.getStopDetail(city, stopId, stopName, physicalStId, namesakeStId, stationLat, stationLng, userLat, userLng)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StopDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeRepository: FakeTransitRepository
    private val defaultCity = TransitCity(cityId = "034", cityName = "上海")

    private val sampleStop1 = NearbyStop(
        stopId = "stop_001",
        name = "人民广场（广东路）",
        physicalStId = "phy_01",
        lines = listOf(
            NearbyStopLineStatus(
                line = BusLineDirection(
                    lineId = "line_49",
                    name = "49路",
                    startStop = "汉口路",
                    endStop = "上海体育馆",
                    source = DataSourceType.CHELAILE
                ),
                targetOrder = 2,
                targetStopId = "stop_001",
                targetStopName = "人民广场（广东路）",
                statusText = "即将到站"
            )
        )
    )

    private val sampleStop2 = NearbyStop(
        stopId = "stop_002",
        name = "人民广场（福州路）",
        physicalStId = "phy_02",
        lines = listOf(
            NearbyStopLineStatus(
                line = BusLineDirection(
                    lineId = "line_18",
                    name = "18路",
                    startStop = "鲁迅公园",
                    endStop = "世博会博物馆",
                    source = DataSourceType.CHELAILE
                ),
                targetOrder = 5,
                targetStopId = "stop_002",
                targetStopName = "人民广场（福州路）",
                statusText = "2站 / 5分钟"
            )
        )
    )

    @Before
    fun setup() {
        fakeRepository = FakeTransitRepository(initialCity = defaultCity)
        fakeRepository.stopDetailResult = Result.success(listOf(sampleStop1, sampleStop2))
    }

    private fun createViewModel(): StopDetailViewModel {
        return StopDetailViewModel(
            cityId = "034",
            stopId = "stop_001",
            initialStopName = "人民广场",
            repository = fakeRepository,
            enableAutoPolling = false
        )
    }

    @Test
    fun testInitialLoad_populatesStopsAndResolvesName() = runTest {
        val viewModel = createViewModel()

        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.stops.size)
        assertEquals("人民广场（广东路）", state.stopName)
        assertEquals(0, state.selectedStopIndex)
        assertNull(state.errorMessage)
    }

    @Test
    fun testSelectStopIndex_updatesSelectedStop() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.selectStopIndex(1)
        assertEquals(1, viewModel.uiState.value.selectedStopIndex)
    }

    @Test
    fun testToggleFavorite_updatesFavoriteIds() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val targetLine = sampleStop1.lines.first()
        viewModel.toggleFavorite(sampleStop1, targetLine)
        testScheduler.runCurrent()

        val expectedFavId = "034_line_49_2"
        assertTrue(viewModel.uiState.value.favoriteIds.contains(expectedFavId))

        // Toggle again to remove
        viewModel.toggleFavorite(sampleStop1, targetLine)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.favoriteIds.contains(expectedFavId))
    }

    @Test
    fun testPollingLifecycle_pauseResumeAndExplicitControl() = runTest {
        val vmWithAutoPolling = StopDetailViewModel(
            cityId = "034",
            stopId = "stop_001",
            initialStopName = "人民广场",
            repository = fakeRepository,
            enableAutoPolling = true
        )
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.pausePolling()
        assertFalse(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.resumePolling()
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.setPollingActive(false)
        assertFalse(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.setPollingActive(true)
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.pausePolling()
    }

    @Test
    fun testPollingLifecycle_disabledByDefaultInTest() = runTest {
        val viewModel = createViewModel()
        assertFalse(viewModel.isPollingActive)

        viewModel.setPollingActive(true)
        assertTrue(viewModel.isPollingActive)

        viewModel.pausePolling()
        assertFalse(viewModel.isPollingActive)
    }

    @Test
    fun testSelectStopIndex_changedPlatform_refreshesWithThatPlatform() = runTest {
        val repo = CountingStopRepository(fakeRepository)
        val viewModel = StopDetailViewModel(
            cityId = "034",
            stopId = "stop_001",
            initialStopName = "人民广场",
            repository = repo,
            enableAutoPolling = false
        )
        testScheduler.runCurrent()
        assertEquals(1, repo.stopDetailPhysicalIds.size)

        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        assertEquals(listOf<String?>(null, "phy_02"), repo.stopDetailPhysicalIds)
        assertEquals(1, viewModel.uiState.value.selectedStopIndex)

        // 选中同一站台不重复请求
        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        assertEquals(2, repo.stopDetailPhysicalIds.size)
    }

    @Test
    fun testPolling_skipsWhenPreviousLoadStillInFlight() = runTest {
        val repo = CountingStopRepository(fakeRepository)
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val viewModel = StopDetailViewModel(
            cityId = "034",
            stopId = "stop_001",
            initialStopName = "人民广场",
            repository = repo,
            enableAutoPolling = true
        )
        testScheduler.runCurrent()
        assertTrue(viewModel.isLoadInFlight)

        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(1, repo.stopDetailPhysicalIds.size)

        gate.complete(Unit)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(2, repo.stopDetailPhysicalIds.size)

        viewModel.pausePolling()
    }

    @Test
    fun testScreenResume_refreshesOnlyWhenStale() = runTest {
        var now = 0L
        val repo = CountingStopRepository(fakeRepository)
        val viewModel = StopDetailViewModel(
            cityId = "034",
            stopId = "stop_001",
            initialStopName = "人民广场",
            repository = repo,
            enableAutoPolling = true,
            clockMillis = { now }
        )
        testScheduler.runCurrent()

        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(1, repo.stopDetailPhysicalIds.size)

        viewModel.onScreenPaused()
        assertFalse(viewModel.isPollingActive)

        now += 11_000L
        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(2, repo.stopDetailPhysicalIds.size)
        assertTrue(viewModel.isPollingActive)

        viewModel.onScreenPaused()
    }

    @Test
    fun testScreenResume_noopWhenAutoPollingDisabled() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onScreenResumed()
        assertFalse(viewModel.isPollingActive)
    }
}
