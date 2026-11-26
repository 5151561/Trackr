package com.trackr.ui.screens.routeplan

import com.trackr.core.data.local.AmapKeyStore
import com.trackr.core.data.local.SerialWriter
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.AmapRouteGateway
import com.trackr.core.data.repository.DefaultAmapKeyRepository
import com.trackr.core.data.route.DefaultRoutePlanRepository
import com.trackr.core.data.route.FavoriteGroupSelection
import com.trackr.core.data.route.RouteAlignmentService
import com.trackr.core.data.route.RouteGroupSaver
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.testing.FakeAmapRouteGateway
import com.trackr.core.testing.FakeLocationRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.core.testing.InMemoryRouteGroupRepository
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第 17 步：原 `FavoritesGroupViewModelTest`（:androidApp，Robolectric）里测输入提示、算路、「我的位置」、
 * 高德 Key 只读、方案存收藏的几条，搬到这里测 [RoutePlanViewModel]。
 * 算路缓存 / 时间桶另在 :core:data 的 RoutePlanRepositoryTest 直接测仓库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlanViewModelTest {

    /** 高德 Key 两槽的内存存储（原测试用 SharedPreferences，断言改成看这两个槽） */
    private class MemoryAmapKeyStore(var android: String? = null, var web: String? = null) : AmapKeyStore {
        override suspend fun loadAndroidKey(): String? = android
        override suspend fun loadWebKey(): String? = web
        override suspend fun saveAndroidKey(key: String?) { android = key }
        override suspend fun saveWebKey(key: String?) { web = key }
    }

    private val main = MainDispatcherHolder()

    private lateinit var settings: FakeSettingsRepository
    private lateinit var realtime: FakeRealtimeRepository
    private lateinit var groupRepository: InMemoryRouteGroupRepository
    private lateinit var selection: FavoriteGroupSelection
    private lateinit var alignment: RouteAlignmentService
    private lateinit var groupSaver: RouteGroupSaver

    @BeforeTest
    fun setup() {
        main.install()
        settings = FakeSettingsRepository(TransitCity(cityId = "033", cityName = "杭州"))
        realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
        groupRepository = InMemoryRouteGroupRepository(main.dispatcher)
        selection = FavoriteGroupSelection()
        alignment = RouteAlignmentService(
            settings = settings,
            realtime = realtime,
            coordinator = null,
            ioDispatcher = main.dispatcher,
            wallClockMillis = { 1_000_000_000L }
        )
        groupSaver = RouteGroupSaver(groupRepository, alignment, selection)
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    /** 原 createViewModelWithGateway：墙钟固定 1_000_000_000L，没有本地兜底（coordinator = null） */
    private fun createViewModelWithGateway(
        gateway: AmapRouteGateway,
        amapKeys: AmapKeyRepository? = null,
        userLocationStore: FakeUserLocationStore = FakeUserLocationStore()
    ): RoutePlanViewModel = RoutePlanViewModel(
        routePlan = DefaultRoutePlanRepository(
            gateway = gateway,
            alignment = alignment,
            coordinator = null,
            ioDispatcher = main.dispatcher,
            wallClockMillis = { 1_000_000_000L }
        ),
        settings = settings,
        realtime = realtime,
        userLocationStore = userLocationStore,
        groupRepository = groupRepository,
        groupSaver = groupSaver,
        amapKeys = amapKeys,
        locationRepository = FakeLocationRepository(lastKnownResult = null)
    )

    private fun sampleScheme(title: String): AmapTransitSchemeResult = AmapTransitSchemeResult(
        title = title,
        durationMinutes = 20,
        walkingMeters = 100,
        costYuan = "2.0",
        transferCount = 0,
        group = TransitGroup(
            id = "scheme_$title",
            name = title,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "甲站",
                    toStopName = "乙站",
                    candidateLines = listOf(
                        CandidateLine(cityId = "", lineId = "amap_1", lineName = "1路", fromStopOrder = 1, toStopOrder = 4)
                    )
                )
            )
        )
    )

    @Test
    fun searchTips_debouncesAndOnlyRequestsLatestKeyword() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        viewModel.searchDestTips("人")
        testScheduler.advanceTimeBy(100)
        viewModel.searchDestTips("人民")
        testScheduler.advanceTimeBy(100)
        viewModel.searchDestTips("人民广场")
        testScheduler.advanceTimeBy(RoutePlanViewModel.TIPS_DEBOUNCE_MS - 1)
        testScheduler.runCurrent()

        // 防抖窗口内不发请求
        assertTrue(gateway.tipQueries.isEmpty())

        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()

        // 连续输入只为最后一个关键字请求一次
        assertEquals(listOf("人民广场"), gateway.tipQueries)
        assertEquals("人民广场站", viewModel.amapDestTips.value.firstOrNull()?.name)
    }

    @Test
    fun searchTips_ignoresTooShortKeyword_andClearsTips() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        viewModel.searchOriginTips("a")
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        assertTrue(gateway.tipQueries.isEmpty())
        assertTrue(viewModel.amapOriginTips.value.isEmpty())
    }

    @Test
    fun searchTips_sameCityAndKeyword_hitsLruCache() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        viewModel.searchOriginTips("西湖")
        testScheduler.advanceTimeBy(400)
        testScheduler.runCurrent()
        viewModel.searchOriginTips("西湖文化")
        testScheduler.advanceTimeBy(400)
        testScheduler.runCurrent()
        viewModel.searchOriginTips("西湖")
        testScheduler.advanceTimeBy(400)
        testScheduler.runCurrent()

        assertEquals(listOf("西湖", "西湖文化"), gateway.tipQueries)
        assertEquals("西湖站", viewModel.amapOriginTips.value.firstOrNull()?.name)
    }

    @Test
    fun planTransitRoute_cancelsPreviousPlan_andCachesSameQuery() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway().apply {
            planResult = { call -> listOf(sampleScheme(call.destName)) }
            planDelayMs = 1_000
        }
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        val origin = AmapTipItem(name = "起点", location = "120.1234567,30.1234567")
        val destB = AmapTipItem(name = "B", location = "120.2,30.2")
        val destC = AmapTipItem(name = "C", location = "120.3,30.3")

        viewModel.planTransitRoute(origin, destB)
        testScheduler.advanceTimeBy(100)
        // 新的规划取消上一次，旧结果不会覆盖新结果
        viewModel.planTransitRoute(origin, destC)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("C"), viewModel.plannedSchemes.value.map { it.title })
        assertFalse(viewModel.isPlanningRoute.value)

        // 同城市、同坐标、同一 15 分钟时间桶：命中缓存，不再请求高德
        val callsBefore = gateway.planCalls.size
        viewModel.planTransitRoute(origin, destC.copy(location = "120.3000001,30.3000001"))
        testScheduler.advanceUntilIdle()

        assertEquals(callsBefore, gateway.planCalls.size)
        assertEquals(listOf("C"), viewModel.plannedSchemes.value.map { it.title })
        // 坐标按 6 位小数传给高德通道之前保持原样，由客户端负责格式化
        assertEquals("120.1234567,30.1234567", gateway.planCalls.first().originLngLat)
    }

    @Test
    fun planTransitRoute_myLocationUnavailable_reportsErrorWithoutPoiSearch() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        var completed: Boolean? = null
        viewModel.planTransitRoute(
            origin = AmapTipItem(name = RoutePlanViewModel.MY_LOCATION_NAME),
            destination = AmapTipItem(name = "B", location = "120.2,30.2"),
            onComplete = { completed = it }
        )
        testScheduler.advanceUntilIdle()

        assertEquals(false, completed)
        assertTrue(gateway.tipQueries.isEmpty())
        assertTrue(gateway.planCalls.isEmpty())
        assertTrue(viewModel.planErrorMessage.value.orEmpty().contains("定位不可用"))
        assertFalse(viewModel.isPlanningRoute.value)
    }

    @Test
    fun planTransitRoute_passesCitydOnlyWhenCrossCity() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        // 杭州 -> 上海：跨城，传终点城市 adcode
        viewModel.planTransitRoute(
            AmapTipItem(name = "西湖", location = "120.15,30.25", adcode = "330106"),
            AmapTipItem(name = "外滩", location = "121.49,31.24", district = "上海市黄浦区", adcode = "310101")
        )
        testScheduler.advanceUntilIdle()
        // 同城：不传 cityd
        viewModel.planTransitRoute(
            AmapTipItem(name = "西湖", location = "120.15,30.25", adcode = "330106"),
            AmapTipItem(name = "武林广场", location = "120.17,30.27", adcode = "330103")
        )
        testScheduler.advanceUntilIdle()
        // 起点无 adcode（如我的位置）：按终点行政区是否包含当前城市名判断
        viewModel.planTransitRoute(
            AmapTipItem(name = "某地", location = "120.15,30.25"),
            AmapTipItem(name = "外滩", location = "121.49,31.24", district = "上海市黄浦区", adcode = "310101")
        )
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("310100", null, "310100"), gateway.planCalls.map { it.cityd })
    }

    @Test
    fun amapKeys_followRepository_legacyWebSlotNotUsedAsAndroidKey() = runTest(main.dispatcher) {
        // 第 11 步：Key 在「更多」页改，这个 VM 只读、跟着仓库走。
        // 两槽规则本身（原 customAmapApiKey_doesNotFallBackToLegacyWebSlot）在 :core:data 的 AmapKeyRepositoryTest
        val store = MemoryAmapKeyStore(web = "legacy_single_key")
        val amapKeys = DefaultAmapKeyRepository.create(store, SerialWriter(CoroutineScope(Dispatchers.Unconfined)))

        val viewModel = createViewModelWithGateway(FakeAmapRouteGateway(), amapKeys = amapKeys)

        // 旧存储里 Web 槽的值不当作 Android Key；只有 Web Key 不画地图
        assertNull(viewModel.customAmapApiKey.value)
        assertEquals("legacy_single_key", viewModel.customAmapWebApiKey.value)
        assertFalse(viewModel.canShowMap.value)

        // 别处（「更多」页）改了 Key，这里马上是新值，并且各写各的槽
        amapKeys.setAndroidKey("test_key_123456")
        assertEquals("test_key_123456", viewModel.customAmapApiKey.value)
        assertTrue(viewModel.canShowMap.value)
        amapKeys.flush()
        assertEquals("test_key_123456", store.android)
        assertEquals("legacy_single_key", store.web)
    }

    @Test
    fun addPlannedSchemeAsFavorite_noGroupChosen_fallsBackToSelectedFavoriteGroup() = runTest(main.dispatcher) {
        val viewModel = createViewModelWithGateway(FakeAmapRouteGateway())
        testScheduler.runCurrent()

        // 收藏页选中了「上班通勤」（FavoritesEditViewModel.selectFavoriteGroup 写的就是这个共用的选择）
        selection.select("commute")
        viewModel.addPlannedSchemeAsFavorite(sampleScheme("B"))
        testScheduler.advanceUntilIdle()

        // 没选分组：回落到选中的 commute，分类跟着变成通勤，不再是临时方案
        val saved = groupRepository.getGroupById("scheme_B")
        assertNotNull(saved)
        assertEquals("commute", saved.customGroupId)
        assertEquals(GroupCategory.COMMUTE, saved.category)
        assertFalse(saved.isTemporary)

        // 存进通勤自动在「回家线路」生成返程
        assertEquals(2, groupRepository.groups.value.size)
        val reverse = groupRepository.groups.value.find { it.customGroupId == "home" }
        assertNotNull(reverse, "存进通勤应自动在'回家线路'中生成返程")
        assertEquals("乙站", reverse.originStopName)
        assertEquals("甲站", reverse.destinationStopName)
    }

    // ---------------- 第 24 步：页面状态与事件 ----------------

    private fun scheme(id: String, minutes: Int, transfers: Int, walk: Int): AmapTransitSchemeResult =
        sampleScheme(id).copy(durationMinutes = minutes, transferCount = transfers, walkingMeters = walk)

    @Test
    fun uiState_tabsAreFixedThree_andNewResultSelectsFirstTab() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway().apply {
            planResult = {
                listOf(
                    scheme("fast", 30, 2, 900),
                    scheme("direct", 40, 0, 700),
                    scheme("walk", 45, 1, 100),
                    scheme("other", 50, 1, 800)
                )
            }
        }
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        viewModel.planTransitRoute(AmapTipItem(name = "甲", location = "120.1,30.1"), AmapTipItem(name = "乙", location = "120.2,30.2"))
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("最快", "少换乘", "少走路"), state.schemeTabs.map { it.label })
        assertEquals(listOf("scheme_fast", "scheme_direct", "scheme_walk"), state.schemeTabs.map { it.scheme.group.id })
        // 地图只画标签里的方案，选中的和标签一致
        assertEquals(state.schemeTabs.map { it.scheme }, state.map.schemes)

        viewModel.onEvent(RoutePlanEvent.SelectScheme(2))
        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.selectedTab)
        assertEquals(2, viewModel.uiState.value.map.selectedIndex)

        // 再算一次（新结果）回到第一个标签
        viewModel.planTransitRoute(AmapTipItem(name = "甲", location = "120.1,30.1"), AmapTipItem(name = "丙", location = "120.3,30.3"))
        testScheduler.advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun saveScheme_staysOnPage_andPostsSavedMessageWithGoToFavorites() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway().apply { planResult = { listOf(sampleScheme("B")) } }
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()
        viewModel.planTransitRoute(AmapTipItem(name = "甲", location = "120.1,30.1"), AmapTipItem(name = "B", location = "120.2,30.2"))
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(RoutePlanEvent.OpenSaveDialog)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSaveDialog)

        viewModel.onEvent(RoutePlanEvent.ConfirmSave(groupId = null, name = "上班"))
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSaveDialog)
        assertEquals("上班", groupRepository.getGroupById("scheme_B")?.name)
        val message = viewModel.userMessages.value.single()
        assertEquals(RoutePlanViewModel.SAVED_MESSAGE, message.text)
        assertEquals(RoutePlanMessageAction.GoToFavorites, message.action)
        // 方案还在（留在本页）
        assertEquals(1, viewModel.uiState.value.schemeTabs.size)

        viewModel.onEvent(RoutePlanEvent.MessageShown(message.id))
        assertTrue(viewModel.userMessages.value.isEmpty())
    }

    @Test
    fun pickTip_fillsInputHidesTips_andPlansWhenOtherFieldFilled() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        viewModel.onEvent(RoutePlanEvent.DestChanged("西湖"))
        testScheduler.advanceUntilIdle()
        assertEquals(TipsField.Dest, viewModel.uiState.value.tipsField)
        assertEquals(listOf("西湖站"), viewModel.uiState.value.tips.map { it.name })

        // 起点空着：选终点不自动算（和原来一样）
        val tip = viewModel.uiState.value.tips.first()
        viewModel.onEvent(RoutePlanEvent.PickTip(TipsField.Dest, tip))
        testScheduler.advanceUntilIdle()
        assertEquals("西湖站", viewModel.destText)
        assertNull(viewModel.uiState.value.tipsField)
        assertTrue(gateway.planCalls.isEmpty())

        // 起点填了再选：直接用选中的提示坐标算
        viewModel.onEvent(RoutePlanEvent.OriginChanged("武林"))
        testScheduler.advanceUntilIdle()
        assertEquals(TipsField.Origin, viewModel.uiState.value.tipsField)
        viewModel.onEvent(RoutePlanEvent.PickTip(TipsField.Origin, viewModel.uiState.value.tips.first()))
        testScheduler.advanceUntilIdle()
        assertEquals(1, gateway.planCalls.size)
        assertEquals("120.100000,30.200000", gateway.planCalls.single().destLngLat)
    }

    @Test
    fun pickMyLocation_asOrigin_plansWithCurrentLocation() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(
            gateway,
            userLocationStore = FakeUserLocationStore(initial = 30.25 to 120.15)
        )
        testScheduler.runCurrent()

        // 终点有字：起点选「我的位置」直接算，起点用实时定位的 GCJ-02 坐标
        viewModel.onEvent(RoutePlanEvent.DestChanged("西湖"))
        testScheduler.advanceUntilIdle()
        viewModel.onEvent(RoutePlanEvent.PickMyLocation(TipsField.Origin))
        testScheduler.advanceUntilIdle()

        assertEquals(RoutePlanViewModel.MY_LOCATION_NAME, viewModel.originText)
        val call = gateway.planCalls.single()
        assertTrue(call.originLngLat.contains(","))
    }

    @Test
    fun pickMyLocation_asDest_plansToCurrentLocation() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(
            gateway,
            userLocationStore = FakeUserLocationStore(initial = 30.25 to 120.15)
        )
        testScheduler.runCurrent()

        // 起点先选好一条提示，再把终点设成「我的位置」：同样能算
        viewModel.onEvent(RoutePlanEvent.OriginChanged("武林"))
        testScheduler.advanceUntilIdle()
        viewModel.onEvent(RoutePlanEvent.PickTip(TipsField.Origin, viewModel.uiState.value.tips.first()))
        testScheduler.advanceUntilIdle()
        viewModel.onEvent(RoutePlanEvent.PickMyLocation(TipsField.Dest))
        testScheduler.advanceUntilIdle()

        assertEquals(RoutePlanViewModel.MY_LOCATION_NAME, viewModel.destText)
        val call = gateway.planCalls.single()
        assertTrue(call.destLngLat.contains(","))
    }

    @Test
    fun pickMyLocation_asDest_withoutLocation_reportsDestinationError() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        viewModel.onEvent(RoutePlanEvent.OriginChanged("武林"))
        testScheduler.advanceUntilIdle()
        viewModel.onEvent(RoutePlanEvent.PickTip(TipsField.Origin, viewModel.uiState.value.tips.first()))
        testScheduler.advanceUntilIdle()
        viewModel.onEvent(RoutePlanEvent.PickMyLocation(TipsField.Dest))
        testScheduler.advanceUntilIdle()

        val error = viewModel.uiState.value.planError.orEmpty()
        assertTrue(error.contains("定位不可用"))
        assertTrue(error.contains("目的地"))
        assertTrue(gateway.planCalls.isEmpty())
    }

    @Test
    fun dismissTips_hidesDestFirstThenOrigin() = runTest(main.dispatcher) {
        val viewModel = createViewModelWithGateway(FakeAmapRouteGateway())
        testScheduler.runCurrent()

        viewModel.onEvent(RoutePlanEvent.OriginChanged("武林"))
        viewModel.onEvent(RoutePlanEvent.DestChanged("西湖"))
        testScheduler.advanceUntilIdle()
        assertEquals(TipsField.Dest, viewModel.uiState.value.tipsField)

        viewModel.onEvent(RoutePlanEvent.DismissTips)
        testScheduler.advanceUntilIdle()
        assertEquals(TipsField.Origin, viewModel.uiState.value.tipsField)

        viewModel.onEvent(RoutePlanEvent.DismissTips)
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.tipsField)
        // 文字留着
        assertEquals("武林", viewModel.originText)
        assertEquals("西湖", viewModel.destText)
    }

    @Test
    fun submit_blankOrigin_startsFromMyLocation_andSwapReplans() = runTest(main.dispatcher) {
        val gateway = FakeAmapRouteGateway()
        val viewModel = createViewModelWithGateway(gateway)
        testScheduler.runCurrent()

        // 终点空着：不算
        viewModel.onEvent(RoutePlanEvent.Submit)
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.planError)

        // 起点空着 = 我的位置；没有定位时直接报错，不拿「我的位置」去搜
        viewModel.onEvent(RoutePlanEvent.DestChanged("西湖"))
        viewModel.onEvent(RoutePlanEvent.Submit)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.planError.orEmpty().contains("定位不可用"))
        assertTrue(gateway.planCalls.isEmpty())

        // 互换：文字对调后再算一次（起点「西湖」、终点空 → 不算）
        viewModel.onEvent(RoutePlanEvent.SwapOriginDest)
        testScheduler.advanceUntilIdle()
        assertEquals("西湖", viewModel.originText)
        assertEquals("", viewModel.destText)
        assertTrue(gateway.planCalls.isEmpty())
    }

    @Test
    fun uiState_mapPointsAreGcj02() = runTest(main.dispatcher) {
        // 301c124：城市中心和定位都是 WGS-84，给地图前先转 GCJ-02
        val location = FakeUserLocationStore(initial = 30.25 to 120.15)
        val viewModel = createViewModelWithGateway(FakeAmapRouteGateway(), userLocationStore = location)
        testScheduler.advanceUntilIdle()

        val map = viewModel.uiState.value.map
        val myLocation = assertNotNull(map.myLocation)
        assertTrue(myLocation.lat != 30.25 && myLocation.lng != 120.15)
        assertEquals(com.trackr.ui.screens.routeplan.map.wgs84ToGcj02Point(30.25, 120.15), myLocation)
        assertEquals(com.trackr.ui.screens.routeplan.map.cityCenterGcj02("杭州"), map.cityCenter)
        assertNotNull(map.cityCenter)
    }
}
