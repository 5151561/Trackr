package com.trackr.ui.screens.stopdetail

import com.trackr.data.repository.FakeTransitRepository
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.TransitCity
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
}
