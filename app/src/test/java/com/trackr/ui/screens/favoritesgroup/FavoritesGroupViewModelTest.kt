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
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        assertEquals(2, viewModel.groups.value.size)

        // 切换过滤到 LEISURE
        viewModel.selectCategory(GroupCategory.LEISURE)
        testScheduler.runCurrent()
        assertEquals(1, viewModel.filteredGroups.value.size)
        assertEquals("周末西湖", viewModel.filteredGroups.value.first().name)

        // 切换回全部 (null)
        viewModel.selectCategory(null)
        testScheduler.runCurrent()
        assertEquals(2, viewModel.filteredGroups.value.size)
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

        assertEquals(2, viewModel.groups.value.size)

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
        assertEquals(2, viewModel.groups.value.size)
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
        assertEquals("test_key_123456", sharedPrefs.getString("custom_amap_web_key", null))
    }
}
