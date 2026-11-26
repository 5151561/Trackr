package com.trackr.ui.screens.routedetail

import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.data.route.DefaultRoutePlanRepository
import com.trackr.core.data.route.FavoriteGroupSelection
import com.trackr.core.data.route.RouteAlignmentService
import com.trackr.core.data.route.RouteGroupSaver
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.testing.FakeAmapRouteGateway
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.core.testing.InMemoryRouteGroupRepository
import com.trackr.core.testing.MainDispatcherHolder
import com.trackr.ui.screens.routeplan.RoutePlanViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第 17 步：原 `FavoritesGroupViewModelTest`（:androidApp，Robolectric）里测候选线、分组 ETA、追踪的几条，
 * 搬到这里测 [RouteDetailViewModel]。临时追踪转收藏那条和规划页 [RoutePlanViewModel] 共用一个仓库，和应用里一样。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteDetailViewModelTest {

    private val main = MainDispatcherHolder()

    private lateinit var settings: FakeSettingsRepository
    private lateinit var realtime: FakeRealtimeRepository
    private lateinit var groupRepository: InMemoryRouteGroupRepository
    private lateinit var selection: FavoriteGroupSelection
    private lateinit var alignment: RouteAlignmentService
    private lateinit var groupSaver: RouteGroupSaver
    private lateinit var trackingRepository: DefaultTrackingRepository
    private lateinit var trackingController: FakeTrackingController

    @BeforeTest
    fun setup() {
        main.install()
        settings = FakeSettingsRepository(TransitCity(cityId = "033", cityName = "杭州"))
        realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
        groupRepository = InMemoryRouteGroupRepository(main.dispatcher)
        selection = FavoriteGroupSelection()
        alignment = RouteAlignmentService(settings, realtime, coordinator = null, ioDispatcher = main.dispatcher)
        groupSaver = RouteGroupSaver(groupRepository, alignment, selection)
        // 每个测试自己一份追踪会话，不会串状态
        trackingRepository = DefaultTrackingRepository(
            InMemoryTrackingSessionStore(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        trackingController = FakeTrackingController(trackingRepository)
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    private fun createViewModel(): RouteDetailViewModel = RouteDetailViewModel(
        groupRepository = groupRepository,
        settings = settings,
        realtime = realtime,
        alignment = alignment,
        trackingRepository = trackingRepository,
        trackingController = trackingController,
        ioDispatcher = main.dispatcher,
        enableAutoRefresh = false
    )

    private fun createPlanViewModel(): RoutePlanViewModel = RoutePlanViewModel(
        routePlan = DefaultRoutePlanRepository(FakeAmapRouteGateway(), alignment, ioDispatcher = main.dispatcher),
        settings = settings,
        realtime = realtime,
        userLocationStore = FakeUserLocationStore(),
        groupRepository = groupRepository,
        groupSaver = groupSaver
    )

    /** 两路车的实时状态：[nearLineId] 在第 4 站（距候车站 5 还有 1 站），[farLineId] 在第 1 站（还有 4 站） */
    private fun stubTwoLines(nearLineId: String, farLineId: String) {
        realtime.lineDetailsMap[nearLineId] = BusLineDetail(
            line = BusLineDirection(lineId = nearLineId, name = "320路", startStop = "恒丰", endStop = "高新园"),
            stops = emptyList(),
            buses = listOf(
                LiveBus(busId = "b1", plateOrFleetNo = "粤B·58921", order = 4, segmentProgress = 0.5f, etaSeconds = 120)
            )
        )
        realtime.lineDetailsMap[farLineId] = BusLineDetail(
            line = BusLineDirection(lineId = farLineId, name = "M194路", startStop = "布吉", endStop = "高新园"),
            stops = emptyList(),
            buses = listOf(
                LiveBus(busId = "b2", plateOrFleetNo = "粤B·33210", order = 1, segmentProgress = 0.2f, etaSeconds = 480)
            )
        )
    }

    @Test
    fun testAddCandidateLineToLeg() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val group = TransitGroup(
            id = "tg_single_line",
            name = "科技园",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "深大北门",
                    toStopName = "高新园",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "033",
                            lineId = "320",
                            lineName = "320路",
                            fromStopOrder = 2,
                            toStopOrder = 6
                        )
                    )
                )
            )
        )
        // 原来是 viewModel.saveGroup(group)，现在由 RouteGroupSaver 存（同一份逻辑）
        groupSaver.saveGroup(group)
        testScheduler.runCurrent()

        val newCandidate = CandidateLine(
            cityId = "033",
            lineId = "m194",
            lineName = "M194路",
            fromStopOrder = 3,
            toStopOrder = 7
        )
        viewModel.addCandidateLineToLeg(group.id, 0, newCandidate)
        testScheduler.runCurrent()

        val updated = groupRepository.getGroupById(group.id)
        assertNotNull(updated)
        assertEquals(2, updated.legs[0].candidateLines.size)
        assertEquals("m194", updated.legs[0].candidateLines[1].lineId)
    }

    @Test
    fun testMultiLineEtaAggregationAndFastestLineSelection() = runTest(main.dispatcher) {
        val line1 = CandidateLine(
            cityId = "033",
            lineId = "line_320",
            lineName = "320路",
            source = DataSourceType.CUSTOM,
            fromStopOrder = 5,
            toStopOrder = 9
        )
        val line2 = CandidateLine(
            cityId = "033",
            lineId = "line_m194",
            lineName = "M194路",
            source = DataSourceType.CUSTOM,
            fromStopOrder = 5,
            toStopOrder = 10
        )
        val testGroup = TransitGroup(
            id = "tg_multi_line",
            name = "深大到高新园",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "深大北门",
                    toStopName = "高新园",
                    candidateLines = listOf(line1, line2)
                )
            )
        )

        // 两路车实时状态：320路 还有 1 站，M194路 还有 4 站
        stubTwoLines(nearLineId = "line_320", farLineId = "line_m194")

        val viewModel = createViewModel()
        groupSaver.saveGroup(testGroup)
        testScheduler.runCurrent()

        // 刷新实时 ETA
        val job = viewModel.refreshRealtimeEta(listOf(testGroup))
        job.join()
        testScheduler.advanceUntilIdle()

        val realtimeMap = viewModel.candidateRealtimeMap.value
        val info320 = realtimeMap["tg_multi_line_0_line_320"]
        val infoM194 = realtimeMap["tg_multi_line_0_line_m194"]

        assertNotNull(info320)
        assertNotNull(infoM194)

        assertEquals(1, info320.stopsAway)
        assertTrue(info320.isFastest)
        assertEquals("粤B·58921", info320.vehiclePlate)
        assertEquals("1站", info320.shortChipEta)

        assertEquals(4, infoM194.stopsAway)
        assertFalse(infoM194.isFastest)
        assertEquals("4站", infoM194.shortChipEta)

        // 验证默认自动选择最快到达的 320 路
        val selected = viewModel.selectedLineKeys.value["tg_multi_line_0"]
        assertEquals("line_320", selected)

        // 用户手动点击切换胶囊选择 M194 路
        viewModel.selectCandidateLine("tg_multi_line", 0, "line_m194")
        testScheduler.runCurrent()

        assertEquals("line_m194", viewModel.selectedLineKeys.value["tg_multi_line_0"])
    }

    @Test
    fun startGroupTracking_usesCandidateLineSelectedOnCard() = runTest(main.dispatcher) {
        // 不是合成线（lineId 不以 line_ 开头、站序正常），开始追踪时不用对齐
        val near = CandidateLine(cityId = "033", lineId = "b320", lineName = "320路", fromStopOrder = 5, toStopOrder = 9)
        val far = CandidateLine(cityId = "033", lineId = "m194", lineName = "M194路", fromStopOrder = 5, toStopOrder = 10)
        val group = TransitGroup(
            id = "tg_pick",
            name = "深大北门 ➔ 高新园",
            legs = listOf(
                TransitLeg(legIndex = 0, fromStopName = "深大北门", toStopName = "高新园", candidateLines = listOf(near, far))
            )
        )
        stubTwoLines(nearLineId = "b320", farLineId = "m194")

        val viewModel = createViewModel()
        groupRepository.addGroup(group)
        testScheduler.runCurrent()
        viewModel.refreshRealtimeEta().join()
        testScheduler.advanceUntilIdle()

        // 默认选最快的 320 路；用户在卡片上改选 M194 路
        assertEquals("b320", viewModel.selectedLineKeys.value["tg_pick_0"])
        viewModel.selectCandidateLine("tg_pick", 0, "m194")
        testScheduler.runCurrent()

        viewModel.startGroupTracking(group).join()
        testScheduler.runCurrent()

        val target = assertIs<TrackingTarget.Route>(trackingController.started.single())
        assertEquals("tg_pick", target.group.id)
        assertEquals(0, target.startLeg)
        assertEquals(mapOf(0 to "m194"), target.selectedLineIds)
        assertNull(viewModel.trackingMessage.value)
        assertEquals("tg_pick", viewModel.activeGroupSession.value?.groupId)
    }

    @Test
    fun startGroupTracking_temporaryScheme_isNotSaved_andShowsAsTemporaryUntilFavorited() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        // 原来同一个 FavoritesGroupViewModel 上的「收藏」，现在是规划页的（共用一个仓库）
        val planViewModel = createPlanViewModel()
        testScheduler.runCurrent()

        val plannedGroup = TransitGroup(
            id = "planned_temp",
            name = "科技园 → 车公庙",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "科技园",
                    toStopName = "深大北门",
                    candidateLines = listOf(
                        CandidateLine(cityId = "033", lineId = "b612", lineName = "B612", fromStopOrder = 5, toStopOrder = 9)
                    )
                )
            )
        )

        viewModel.startGroupTracking(plannedGroup)
        testScheduler.runCurrent()

        // 只做临时追踪：仓库里没有这条路线，收藏页显示为"临时追踪"
        assertTrue(viewModel.groups.value.none { it.id == "planned_temp" })
        assertEquals("planned_temp", viewModel.activeGroupSession.value?.groupId)
        assertEquals("planned_temp", viewModel.temporaryTrackingGroup.value?.id)

        // 点"收藏"后存进仓库，不再算临时
        planViewModel.addPlannedSchemeAsFavorite(group = plannedGroup)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.groups.value.any { it.id == "planned_temp" })
        assertNull(viewModel.temporaryTrackingGroup.value)
    }
}
