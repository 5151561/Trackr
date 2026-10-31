package com.trackr.ui.screens.favoritesgroup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.service.TransferAutoSwitchManager
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FavoritesGroupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var groupRepository: RouteGroupRepository
    private lateinit var fakeTransitRepository: FakeTransitRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        groupRepository = RouteGroupRepository(context, mainDispatcherRule.testDispatcher)
        fakeTransitRepository = FakeTransitRepository(
            initialCity = TransitCity(cityId = "033", cityName = "杭州")
        )
    }

    @After
    fun tearDown() {
        TransferAutoSwitchManager.stopGroupTracking(context)
    }

    private fun createViewModel(): FavoritesGroupViewModel {
        return FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            coordinator = null,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            enableAutoRefresh = false
        )
    }

    @Test
    fun testInitialGroupsAndCategoryFiltering() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val initialList = viewModel.groups.value
        assertEquals(0, initialList.size)

        // 添加一个通勤分组与一个休闲分组
        val commuteGroup = TransitGroup(
            id = "group_commute",
            name = "早间通勤",
            category = GroupCategory.COMMUTE,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "X", toStopName = "Y"))
        )
        viewModel.saveGroup(commuteGroup)

        val leisureGroup = TransitGroup(
            id = "group_leisure",
            name = "周末西湖",
            category = GroupCategory.LEISURE,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "A", toStopName = "B"))
        )
        viewModel.saveGroup(leisureGroup)
        testScheduler.runCurrent()

        // 早间通勤会自动在 home 分组生成返程路线，因此总数为 3
        assertEquals(3, viewModel.groups.value.size)

        // 切换过滤到 LEISURE
        viewModel.selectCategory(GroupCategory.LEISURE)
        testScheduler.runCurrent()
        assertEquals(1, viewModel.filteredGroups.value.size)
        assertEquals("周末西湖", viewModel.filteredGroups.value.first().name)

        // 切换回全部 (null)
        viewModel.selectCategory(null)
        testScheduler.runCurrent()
        assertEquals(3, viewModel.filteredGroups.value.size)
    }

    @Test
    fun testCustomFavoriteGroupManagementAndFiltering() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        // 默认种子分组存在
        val defaultFavGroups = viewModel.favoriteGroups.value
        assertEquals(2, defaultFavGroups.size)

        // 创建自定义收藏分组
        viewModel.createFavoriteGroup("商圈购物")
        testScheduler.runCurrent()

        val favGroups = viewModel.favoriteGroups.value
        assertEquals(3, favGroups.size)
        val shoppingGroup = favGroups.find { it.name == "商圈购物" }
        assertNotNull(shoppingGroup)
        val shoppingGroupId = shoppingGroup!!.id

        // 添加属于该自定义分组的路线
        val route1 = TransitGroup(
            id = "route_mall",
            name = "去万象城",
            category = GroupCategory.CUSTOM,
            customGroupId = shoppingGroupId,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "家", toStopName = "万象城"))
        )
        val route2 = TransitGroup(
            id = "route_office",
            name = "上班日常",
            category = GroupCategory.COMMUTE,
            customGroupId = "commute",
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "家", toStopName = "公司"))
        )
        viewModel.saveGroup(route1)
        viewModel.saveGroup(route2)
        testScheduler.runCurrent()

        // 上班日常自动在 home 生成返程，因此总数为 3
        assertEquals(3, viewModel.groups.value.size)

        // 按照自定义分组筛选
        viewModel.selectFavoriteGroup(shoppingGroupId)
        testScheduler.runCurrent()
        assertEquals(1, viewModel.filteredGroups.value.size)
        assertEquals("去万象城", viewModel.filteredGroups.value.first().name)

        // 重命名分组
        viewModel.renameFavoriteGroup(shoppingGroupId, "大型商业广场")
        testScheduler.runCurrent()
        assertEquals("大型商业广场", viewModel.favoriteGroups.value.find { it.id == shoppingGroupId }?.name)

        // 删除自定义分组
        viewModel.deleteFavoriteGroup(shoppingGroupId)
        testScheduler.runCurrent()

        // 验证分组已删除，但组内路线仍保留在全部路线中
        assertNull(viewModel.favoriteGroups.value.find { it.id == shoppingGroupId })
        viewModel.selectFavoriteGroup(null)
        testScheduler.runCurrent()
        assertEquals(3, viewModel.groups.value.size)
        val kept = viewModel.groups.value.find { it.id == "route_mall" }
        assertNotNull(kept)
        assertNull(kept?.customGroupId)
    }

    @Test
    fun testAddCandidateLineToLeg() = runTest {
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
        viewModel.saveGroup(group)
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
        assertEquals(2, updated!!.legs[0].candidateLines.size)
        assertEquals("m194", updated.legs[0].candidateLines[1].lineId)
    }

    @Test
    fun testMultiLineEtaAggregationAndFastestLineSelection() = runTest {
        val line1 = CandidateLine(
            cityId = "033",
            lineId = "line_320",
            lineName = "320路",
            source = DataSourceType.CHELAILE,
            fromStopOrder = 5,
            toStopOrder = 9
        )
        val line2 = CandidateLine(
            cityId = "033",
            lineId = "line_m194",
            lineName = "M194路",
            source = DataSourceType.CHELAILE,
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

        // 配置 FakeTransitRepository 中的两路车实时状态
        // 320路：在途车在第 4 站 (距离候车目标站 5 还有 1 站)
        fakeTransitRepository.lineDetailsMap["line_320"] = BusLineDetail(
            line = BusLineDirection(lineId = "line_320", name = "320路", startStop = "恒丰", endStop = "高新园"),
            stops = emptyList(),
            buses = listOf(
                LiveBus(busId = "b1", plateOrFleetNo = "粤B·58921", order = 4, segmentProgress = 0.5f, etaSeconds = 120)
            )
        )
        // M194路：在途车在第 1 站 (距离候车目标站 5 还有 4 站)
        fakeTransitRepository.lineDetailsMap["line_m194"] = BusLineDetail(
            line = BusLineDirection(lineId = "line_m194", name = "M194路", startStop = "布吉", endStop = "高新园"),
            stops = emptyList(),
            buses = listOf(
                LiveBus(busId = "b2", plateOrFleetNo = "粤B·33210", order = 1, segmentProgress = 0.2f, etaSeconds = 480)
            )
        )

        val viewModel = createViewModel()
        viewModel.saveGroup(testGroup)
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

        assertEquals(1, info320?.stopsAway)
        assertTrue(info320!!.isFastest)
        assertEquals("粤B·58921", info320.vehiclePlate)
        assertEquals("1站", info320.shortChipEta)

        assertEquals(4, infoM194?.stopsAway)
        assertFalse(infoM194!!.isFastest)
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
    fun testDeleteAndSaveTemporaryGroup() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val tempGroup = TransitGroup(
            id = "temp_1",
            name = "地图临时方案",
            category = GroupCategory.TEMPORARY,
            isTemporary = true,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "起点", toStopName = "终点"))
        )
        viewModel.saveGroup(tempGroup)
        testScheduler.runCurrent()

        viewModel.saveOrConvertTemporaryGroup(
            groupId = "temp_1",
            newName = "已保存的路线",
            newCategory = GroupCategory.CUSTOM
        )
        testScheduler.runCurrent()

        val saved = groupRepository.getGroupById("temp_1")
        assertNotNull(saved)
        assertFalse(saved!!.isTemporary)
        assertEquals("已保存的路线", saved.name)
        assertEquals(GroupCategory.CUSTOM, saved.category)

        viewModel.deleteGroup("temp_1")
        testScheduler.runCurrent()
        assertNull(groupRepository.getGroupById("temp_1"))
    }

    @Test
    fun testCustomAmapApiKeyPersistence() {
        val sharedPrefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        val viewModel = FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            prefs = sharedPrefs,
            enableAutoRefresh = false
        )

        assertNull(viewModel.customAmapApiKey.value)
        viewModel.saveCustomAmapApiKey("test_key_123456")
        assertEquals("test_key_123456", viewModel.customAmapApiKey.value)
        // Android 平台 Key 与 Web 服务 Key 分槽存储，避免两类 Key 混用
        assertEquals("test_key_123456", sharedPrefs.getString("custom_amap_android_key", null))
        assertNull(sharedPrefs.getString("custom_amap_web_key", null))
    }

    @Test
    fun testCustomAmapWebApiKeyStoredIndependently() {
        val sharedPrefs = context.getSharedPreferences("test_prefs_web", Context.MODE_PRIVATE)
        val viewModel = FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            prefs = sharedPrefs,
            enableAutoRefresh = false
        )

        assertNull(viewModel.customAmapWebApiKey.value)
        viewModel.saveCustomAmapWebApiKey("web_key_abcdef")
        assertEquals("web_key_abcdef", viewModel.customAmapWebApiKey.value)
        assertEquals("web_key_abcdef", sharedPrefs.getString("custom_amap_web_key", null))
        // 保存 Web Key 不应污染 Android 平台 Key
        assertNull(viewModel.customAmapApiKey.value)
    }

    @Test
    fun testSaveCommuteRoute_automaticallyBuildsReverseRouteInHome() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val forwardCommute = TransitGroup(
            id = "group_commute_transfer",
            name = "万达金街 ➔ 西徐村",
            category = GroupCategory.COMMUTE,
            customGroupId = "commute",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "万达金街",
                    toStopName = "西社小区",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "0532",
                            lineId = "59",
                            lineName = "59路",
                            direction = 0,
                            fromStopOrder = 1,
                            toStopOrder = 5,
                            startStopName = "万达金街",
                            endStopName = "西社小区"
                        )
                    ),
                    walkingDistanceMeters = 0
                ),
                TransitLeg(
                    legIndex = 1,
                    fromStopName = "西社小区",
                    toStopName = "西徐村",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "0532",
                            lineId = "351",
                            lineName = "351路",
                            direction = 0,
                            fromStopOrder = 1,
                            toStopOrder = 6,
                            startStopName = "西社小区",
                            endStopName = "西徐村"
                        )
                    ),
                    walkingDistanceMeters = 0
                )
            )
        )

        viewModel.saveGroup(forwardCommute)
        testScheduler.runCurrent()

        val allGroups = viewModel.groups.value
        assertEquals(2, allGroups.size)

        // 验证正向路线
        val forward = allGroups.find { it.id == "group_commute_transfer" }
        assertNotNull(forward)
        assertEquals("commute", forward?.customGroupId)
        assertEquals("万达金街 ➔ 西徐村", forward?.name)

        // 验证自动生成的反向路线
        val reverse = allGroups.find { it.customGroupId == "home" }
        assertNotNull("保存通勤路线应自动在'回家线路'中生成反向路线", reverse)
        assertEquals("西徐村 ➔ 万达金街", reverse?.name)
        assertEquals(2, reverse?.legs?.size)

        // 第 1 程：351路 西徐村 ➔ 西社小区
        val leg0 = reverse!!.legs[0]
        assertEquals(0, leg0.legIndex)
        assertEquals("西徐村", leg0.fromStopName)
        assertEquals("西社小区", leg0.toStopName)
        assertEquals("351路", leg0.candidateLines[0].lineName)
        assertEquals(1, leg0.candidateLines[0].direction)

        // 第 2 程：59路 西社小区 ➔ 万达金街
        val leg1 = reverse.legs[1]
        assertEquals(1, leg1.legIndex)
        assertEquals("西社小区", leg1.fromStopName)
        assertEquals("万达金街", leg1.toStopName)
        assertEquals("59路", leg1.candidateLines[0].lineName)
        assertEquals(1, leg1.candidateLines[0].direction)
    }

    @Test
    fun testFilteredGroups_properlySeparatesCommuteAndHome() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val forwardCommute = TransitGroup(
            id = "group_commute_1",
            name = "万达金街 ➔ 西徐村",
            category = GroupCategory.COMMUTE,
            customGroupId = "commute",
            legs = listOf(
                TransitLeg(legIndex = 0, fromStopName = "万达金街", toStopName = "西社小区"),
                TransitLeg(legIndex = 1, fromStopName = "西社小区", toStopName = "西徐村")
            )
        )
        viewModel.saveGroup(forwardCommute)
        testScheduler.runCurrent()

        // 1. 筛选“上班通勤”
        viewModel.selectFavoriteGroup("commute")
        testScheduler.runCurrent()
        assertEquals(1, viewModel.filteredGroups.value.size)
        assertEquals("万达金街 ➔ 西徐村", viewModel.filteredGroups.value.first().name)

        // 2. 筛选“回家线路”
        viewModel.selectFavoriteGroup("home")
        testScheduler.runCurrent()
        assertEquals(1, viewModel.filteredGroups.value.size)
        assertEquals("西徐村 ➔ 万达金街", viewModel.filteredGroups.value.first().name)

        // 3. 筛选“全部”
        viewModel.selectFavoriteGroup(null)
        testScheduler.runCurrent()
        assertEquals(2, viewModel.filteredGroups.value.size)
    }

    @Test
    fun testCreateReverseGroup_manual() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val testRoute = TransitGroup(
            id = "custom_route_1",
            name = "青岛火车站 ➔ 五四广场",
            category = GroupCategory.CUSTOM,
            customGroupId = "custom_fav",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "青岛火车站",
                    toStopName = "五四广场",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "0532",
                            lineId = "317",
                            lineName = "317路",
                            direction = 0,
                            fromStopOrder = 1,
                            toStopOrder = 8
                        )
                    )
                )
            )
        )
        viewModel.saveGroup(testRoute)
        testScheduler.runCurrent()

        // 点击生成返程
        viewModel.createReverseGroup(testRoute)
        testScheduler.runCurrent()

        val allGroups = viewModel.groups.value
        assertEquals(2, allGroups.size)
        val reversed = allGroups.find { it.id != "custom_route_1" }
        assertNotNull(reversed)
        assertEquals("五四广场 ➔ 青岛火车站", reversed?.name)
        assertEquals("五四广场", reversed?.legs?.first()?.fromStopName)
        assertEquals("青岛火车站", reversed?.legs?.first()?.toStopName)
        assertEquals(1, reversed?.legs?.first()?.candidateLines?.first()?.direction)
    }

    @Test
    fun testRunDataMigration_migratesLegacyCommuteOnceAndRecordsVersion() = runTest {
        val migrationPrefs = context.getSharedPreferences("migration_prefs", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()

        // 遗留数据：通勤分类但没有 customGroupId
        val legacyCommute = TransitGroup(
            id = "legacy_commute",
            name = "家 ➔ 公司",
            category = GroupCategory.COMMUTE,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "家", toStopName = "公司"))
        )
        groupRepository.addGroup(legacyCommute)
        testScheduler.runCurrent()

        fun newViewModel() = FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            coordinator = null,
            prefs = migrationPrefs,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            enableAutoRefresh = false,
            enableAutoMigration = false
        )

        val viewModel = newViewModel()
        viewModel.runDataMigrationIfNeeded().join()
        testScheduler.advanceUntilIdle()

        // 迁移补齐 customGroupId 并生成反向回家路线
        val migrated = groupRepository.getGroupById("legacy_commute")
        assertNotNull(migrated)
        assertEquals("commute", migrated?.customGroupId)
        assertEquals(2, viewModel.groups.value.size)
        assertEquals(GroupCategory.COMMUTE, migrated?.category)
        assertEquals(
            FavoritesGroupViewModel.CURRENT_MIGRATION_VERSION,
            migrationPrefs.getInt(FavoritesGroupViewModel.PREF_KEY_MIGRATION_VERSION, 0)
        )

        // 再写入一条新的遗留数据，已记录版本后不再重复迁移
        groupRepository.addGroup(legacyCommute.copy(id = "legacy_commute_2"))
        testScheduler.runCurrent()
        val secondViewModel = newViewModel()
        secondViewModel.runDataMigrationIfNeeded().join()
        testScheduler.advanceUntilIdle()

        assertNull(groupRepository.getGroupById("legacy_commute_2")?.customGroupId)
        assertEquals(3, secondViewModel.groups.value.size)
    }

    @Test
    fun startGroupTracking_temporaryScheme_isNotSaved_andShowsAsTemporaryUntilFavorited() = runTest {
        val viewModel = createViewModel()
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

        viewModel.startGroupTracking(context, plannedGroup)
        testScheduler.runCurrent()

        // 只做临时追踪：仓库里没有这条路线，收藏页显示为"临时追踪"
        assertTrue(viewModel.groups.value.none { it.id == "planned_temp" })
        assertEquals("planned_temp", viewModel.activeGroupSession.value?.groupId)
        assertEquals("planned_temp", viewModel.temporaryTrackingGroup.value?.id)

        // 点"收藏"后存进仓库，不再算临时
        viewModel.addPlannedSchemeAsFavorite(group = plannedGroup)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.groups.value.any { it.id == "planned_temp" })
        assertNull(viewModel.temporaryTrackingGroup.value)
    }
}
