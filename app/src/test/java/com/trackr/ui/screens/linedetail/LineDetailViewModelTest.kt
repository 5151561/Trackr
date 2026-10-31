package com.trackr.ui.screens.linedetail

import com.trackr.data.repository.FakeTransitRepository
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.ui.components.RouteRailLayoutMode
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LineDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeRepository: FakeTransitRepository
    private val defaultCity = TransitCity(cityId = "034", cityName = "上海")
    private val forwardDirection = BusLineDirection(
        lineId = "line_001",
        name = "49路",
        direction = 0,
        startStop = "汉口路",
        endStop = "上海体育馆",
        source = DataSourceType.CHELAILE
    )
    private val reverseDirection = BusLineDirection(
        lineId = "line_002",
        name = "49路",
        direction = 1,
        startStop = "上海体育馆",
        endStop = "汉口路",
        source = DataSourceType.CHELAILE
    )
    private val stops = listOf(
        BusStop(stopId = "s1", name = "汉口路", order = 1),
        BusStop(stopId = "s2", name = "人民广场", order = 2),
        BusStop(stopId = "s3", name = "上海体育馆", order = 3)
    )
    private val buses = listOf(
        LiveBus(busId = "b1", plateOrFleetNo = "沪A1234", order = 2, etaSeconds = 120, isArrived = false)
    )

    @Before
    fun setup() {
        fakeRepository = FakeTransitRepository(initialCity = defaultCity)
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                reverseDirection = reverseDirection,
                stops = stops,
                buses = buses,
                targetOrder = 2
            )
        )
    }

    private fun createViewModel(
        targetOrder: Int? = null,
        stationId: String? = null
    ): LineDetailViewModel {
        return LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = targetOrder,
            initialStationId = stationId,
            initialSource = DataSourceType.CHELAILE,
            repository = fakeRepository,
            enableAutoPolling = false
        )
    }

    @Test
    fun testInitialLoad_populatesLineDetailAndSelectsTargetStop() = runTest {
        val viewModel = createViewModel(targetOrder = 2)

        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.lineDetail)
        assertEquals("49路", state.lineDetail?.line?.name)
        assertEquals("人民广场", state.selectedStop?.name)
        assertEquals(2, state.selectedStop?.order)
    }

    @Test
    fun testSelectStop_updatesSelectedStopAndPanelMode() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val newStop = stops[2]
        viewModel.selectStop(newStop)

        val state = viewModel.uiState.value
        assertEquals("上海体育馆", state.selectedStop?.name)
        assertEquals(PanelMode.STATION_CARD, state.panelMode)
    }

    @Test
    fun testFollowBus_updatesFollowedBusIdAndSetsPanelMode() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.selectBusToFollow(buses.first())
        assertEquals("b1", viewModel.uiState.value.followedBusId)
        assertEquals(PanelMode.BUS_FOLLOW, viewModel.uiState.value.panelMode)

        viewModel.backToStationCard()
        assertNull(viewModel.uiState.value.followedBusId)
        assertEquals(PanelMode.STATION_CARD, viewModel.uiState.value.panelMode)
    }

    @Test
    fun testSwitchLayoutMode_togglesBetweenHorizontalAndVertical() = runTest {
        val viewModel = createViewModel()

        assertEquals(RouteRailLayoutMode.Horizontal, viewModel.uiState.value.layoutMode)
        viewModel.toggleLayoutMode()
        assertEquals(RouteRailLayoutMode.Vertical, viewModel.uiState.value.layoutMode)
        viewModel.toggleLayoutMode()
        assertEquals(RouteRailLayoutMode.Horizontal, viewModel.uiState.value.layoutMode)
    }

    @Test
    fun testToggleFavorite_updatesRepositoryAndState() = runTest {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isFavorite)

        viewModel.toggleFavoriteStop()
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isFavorite)
        assertEquals(1, fakeRepository.favorites.value.size)
        assertEquals("人民广场", fakeRepository.favorites.value.first().targetStopName)

        viewModel.toggleFavoriteStop()
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isFavorite)
        assertTrue(fakeRepository.favorites.value.isEmpty())
    }

    @Test
    fun testTransitCodeSheet_openAndDismiss() = runTest {
        val viewModel = createViewModel()
        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)

        viewModel.openTransitCodeSheet()
        assertTrue(viewModel.uiState.value.isTransitCodeSheetOpen)

        viewModel.dismissTransitCodeSheet()
        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)
    }

    @Test
    fun testTransitCodeProvider_updatesRepositoryAndState() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals(TransitCodeProvider.ASK_ALWAYS, viewModel.uiState.value.preferredTransitCodeProvider)

        viewModel.setPreferredTransitCodeProvider(TransitCodeProvider.ALIPAY)
        testScheduler.runCurrent()

        assertEquals(TransitCodeProvider.ALIPAY, viewModel.uiState.value.preferredTransitCodeProvider)
        assertEquals(TransitCodeProvider.ALIPAY, fakeRepository.preferredTransitCodeProvider.value)

        viewModel.setPreferredTransitCodeProvider(TransitCodeProvider.UNIONPAY)
        testScheduler.runCurrent()

        assertEquals(TransitCodeProvider.UNIONPAY, viewModel.uiState.value.preferredTransitCodeProvider)
        assertEquals(TransitCodeProvider.UNIONPAY, fakeRepository.preferredTransitCodeProvider.value)
    }

    @Test
    fun testPollingLifecycle_pauseResumeAndExplicitControl() = runTest {
        val vmWithAutoPolling = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
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
    fun testSetAlertThreshold_updatesUiStateAndClamps() = runTest {
        val viewModel = createViewModel()
        // 默认阈值为 2 站
        assertEquals(2, viewModel.uiState.value.alertThresholdStops)

        viewModel.setAlertThreshold(1)
        assertEquals(1, viewModel.uiState.value.alertThresholdStops)

        viewModel.setAlertThreshold(3)
        assertEquals(3, viewModel.uiState.value.alertThresholdStops)

        // 边界保护 (超出 1..3 自动 coerceIn)
        viewModel.setAlertThreshold(0)
        assertEquals(1, viewModel.uiState.value.alertThresholdStops)

        viewModel.setAlertThreshold(5)
        assertEquals(3, viewModel.uiState.value.alertThresholdStops)
    }

    @Test
    fun testAllBusesPassed_fillsNextDepartureFromTimetable() = runTest {
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                stops = stops,
                buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A1234", order = 3)),
                nextDepartureFromStart = null
            )
        )
        fakeRepository.timetableResult = Result.success(
            LineTimetable(
                lineId = "line_001",
                lineName = "49路",
                todayDepartures = listOf("21:00", "21:40", "22:20")
            )
        )
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 1,
            repository = fakeRepository,
            enableAutoPolling = false,
            nowHHmm = { "21:28" }
        )
        testScheduler.runCurrent()

        assertEquals("21:40", viewModel.uiState.value.fallbackNextDeparture)
    }

    @Test
    fun testUpcomingBus_doesNotUseTimetableFallback() = runTest {
        fakeRepository.timetableResult = Result.success(
            LineTimetable(lineId = "line_001", lineName = "49路", todayDepartures = listOf("21:40"))
        )
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 3,
            repository = fakeRepository,
            enableAutoPolling = false,
            nowHHmm = { "21:28" }
        )
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.fallbackNextDeparture)
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
