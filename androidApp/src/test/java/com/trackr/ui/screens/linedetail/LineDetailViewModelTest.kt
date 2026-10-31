package com.trackr.ui.screens.linedetail

import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.ITransitRepository
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
import kotlinx.coroutines.CompletableDeferred
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

/**
 * 记录线路请求次数、可挂起请求的仓库包装（其余行为委托给 FakeTransitRepository）
 */
private class CountingLineRepository(
    private val delegate: FakeTransitRepository
) : ITransitRepository by delegate {
    val lineDetailTargetOrders = mutableListOf<Int?>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getLineDetail(
        city: TransitCity,
        lineId: String,
        targetOrder: Int?,
        stationId: String?,
        wgsLat: Double?,
        wgsLng: Double?,
        source: DataSourceType,
        lineNameHint: String,
        directionHint: Int
    ): Result<BusLineDetail> {
        lineDetailTargetOrders += targetOrder
        gate?.await()
        return delegate.getLineDetail(city, lineId, targetOrder, stationId, wgsLat, wgsLng, source, lineNameHint, directionHint)
    }
}

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
            reminderRepository = FakeScheduledReminderRepository(),
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
            reminderRepository = FakeScheduledReminderRepository(),
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
            reminderRepository = FakeScheduledReminderRepository(),
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
            reminderRepository = FakeScheduledReminderRepository(),
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

    @Test
    fun testInitialLoad_withTargetOrder_requestsOnce() = runTest {
        val repo = CountingLineRepository(fakeRepository)
        LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            repository = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            enableAutoPolling = false
        )
        testScheduler.runCurrent()

        assertEquals(listOf<Int?>(2), repo.lineDetailTargetOrders)
    }

    @Test
    fun testInitialLoad_withoutTargetOrder_followsUpOnceForDefaultStop() = runTest {
        val repo = CountingLineRepository(fakeRepository)
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            repository = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            enableAutoPolling = false
        )
        testScheduler.runCurrent()

        // 首车在第 2 站 → 默认候车站为第 3 站；只补一次精确请求
        assertEquals(listOf<Int?>(null, 3), repo.lineDetailTargetOrders)
        assertEquals(3, viewModel.uiState.value.selectedStop?.order)
    }

    @Test
    fun testPolling_skipsWhenPreviousLoadStillInFlight() = runTest {
        val repo = CountingLineRepository(fakeRepository)
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            repository = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            enableAutoPolling = true
        )
        testScheduler.runCurrent()
        assertTrue(viewModel.isLoadInFlight)

        // 10 秒到期时上一轮仍未返回 → 跳过
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(1, repo.lineDetailTargetOrders.size)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(viewModel.isLoadInFlight)

        // 下一周期正常请求
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(2, repo.lineDetailTargetOrders.size)

        viewModel.pausePolling()
    }

    @Test
    fun testSelectStop_resetsCountdown() = runTest {
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            repository = fakeRepository,
            reminderRepository = FakeScheduledReminderRepository(),
            enableAutoPolling = true
        )
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.refreshCountdownSeconds < 10)

        viewModel.selectStop(stops[2])
        assertEquals(10, viewModel.uiState.value.refreshCountdownSeconds)

        viewModel.pausePolling()
    }

    @Test
    fun testScreenResume_refreshesOnlyWhenStale() = runTest {
        var now = 0L
        val repo = CountingLineRepository(fakeRepository)
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            repository = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            enableAutoPolling = true,
            clockMillis = { now }
        )
        testScheduler.runCurrent()
        assertEquals(1, repo.lineDetailTargetOrders.size)

        // 刚加载完就 resume：不额外请求
        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(1, repo.lineDetailTargetOrders.size)

        viewModel.onScreenPaused()
        assertFalse(viewModel.isPollingActive)

        // 后台超过一个周期后回到前台：立即刷新一次并恢复轮询
        now += 11_000L
        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(2, repo.lineDetailTargetOrders.size)
        assertTrue(viewModel.isPollingActive)

        viewModel.onScreenPaused()
    }

    @Test
    fun testScreenResume_noopWhenAutoPollingDisabled() = runTest {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        viewModel.onScreenResumed()
        assertFalse(viewModel.isPollingActive)
    }
}
