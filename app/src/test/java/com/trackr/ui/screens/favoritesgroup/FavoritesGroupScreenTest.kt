package com.trackr.ui.screens.favoritesgroup

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FavoritesGroupScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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
        groupRepository = RouteGroupRepository(context, Dispatchers.Unconfined)
        fakeTransitRepository = FakeTransitRepository(
            initialCity = TransitCity(cityId = "0755", cityName = "深圳")
        )
    }

    private fun createViewModel(): FavoritesGroupViewModel {
        return FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            coordinator = null,
            ioDispatcher = Dispatchers.Unconfined,
            enableAutoRefresh = false
        )
    }

    @Test
    fun multiLineDirectCard_rendersPureChipsWithoutVerboseText_andSwitchesSelectionOnClick() {
        val line1 = CandidateLine(
            cityId = "0755",
            lineId = "line_320",
            lineName = "320",
            source = DataSourceType.CHELAILE,
            fromStopOrder = 5,
            toStopOrder = 9,
            startStopName = "深大北门",
            endStopName = "恒丰工业城"
        )
        val line2 = CandidateLine(
            cityId = "0755",
            lineId = "line_m194",
            lineName = "M194",
            source = DataSourceType.CHELAILE,
            fromStopOrder = 5,
            toStopOrder = 10,
            startStopName = "深大北门",
            endStopName = "布吉慢城"
        )
        val directGroup = TransitGroup(
            id = "group_direct_1",
            name = "家 ➔ 科技园",
            category = GroupCategory.COMMUTE,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "深大北门",
                    toStopName = "高新园总站",
                    candidateLines = listOf(line1, line2)
                )
            )
        )

        // 配置假数据实时到站
        fakeTransitRepository.lineDetailsMap["line_320"] = BusLineDetail(
            line = BusLineDirection(lineId = "line_320", name = "320", startStop = "恒丰", endStop = "深大"),
            stops = emptyList(),
            buses = listOf(
                LiveBus(busId = "b1", plateOrFleetNo = "粤B·58921", order = 4, segmentProgress = 0.5f, etaSeconds = 120)
            )
        )
        fakeTransitRepository.lineDetailsMap["line_m194"] = BusLineDetail(
            line = BusLineDirection(lineId = "line_m194", name = "M194", startStop = "布吉", endStop = "深大"),
            stops = emptyList(),
            buses = listOf(
                LiveBus(busId = "b2", plateOrFleetNo = "粤B·33210", order = 1, segmentProgress = 0.2f, etaSeconds = 480)
            )
        )

        val viewModel = createViewModel()
        viewModel.saveGroup(directGroup)
        viewModel.refreshRealtimeEta(listOf(directGroup))

        composeTestRule.setContent {
            FavoritesGroupScreen(
                viewModel = viewModel,
                onNavigateToLineDetail = { _, _, _, _, _, _ -> },
                onNavigateToRoutePlan = {}
            )
        }
        composeTestRule.waitForIdle()

        // 1. 验证标题栏及副标题摘要
        composeTestRule.onNodeWithText("我的收藏").assertIsDisplayed()
        composeTestRule.onNodeWithText("已收纳 2 个同达方案与 0 个换乘组合").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_reverse_group_direct_1").assertIsDisplayed()

        // 2. 验证纯线路号码小图标胶囊展示 (无冗余“备选”字样)
        composeTestRule.onNodeWithTag("candidate_chip_320").assertIsDisplayed()
        composeTestRule.onNodeWithTag("candidate_chip_M194").assertIsDisplayed()

        // 验证绝对没有任何含有“备选”或“备选线路”的节点
        val verboseNodes = composeTestRule.onAllNodesWithText("备选", substring = true).fetchSemanticsNodes()
        assertEquals("Spec v2.3 要求彻底消除'备选'字样", 0, verboseNodes.size)

        // 3. 初始详情面板应高亮最快的 320 路
        composeTestRule.onNodeWithText("320 路").assertExists()
        composeTestRule.onNodeWithText("最快").assertExists()
        composeTestRule.onNodeWithText("下车站: 高新园总站 (乘 4 站)").assertExists()

        // 4. 用户点击 M194 纯号码胶囊切换展示
        composeTestRule.onNodeWithTag("candidate_chip_M194").performClick()
        composeTestRule.waitForIdle()

        // 详情面板无缝切换为 M194 路
        composeTestRule.onNodeWithText("M194 路").assertExists()
        composeTestRule.onNodeWithText("下车站: 高新园总站 (乘 5 站)").assertExists()
    }

    @Test
    fun multiLegTransferCard_rendersPureLegLineChips_andSwitchesLegActiveCandidateLine() {
        val leg0Line1 = CandidateLine(
            cityId = "0755",
            lineId = "line_320",
            lineName = "320",
            fromStopOrder = 1,
            toStopOrder = 5
        )
        val leg0Line2 = CandidateLine(
            cityId = "0755",
            lineId = "line_m375",
            lineName = "M375",
            fromStopOrder = 1,
            toStopOrder = 6
        )
        val leg1Line1 = CandidateLine(
            cityId = "0755",
            lineId = "line_19",
            lineName = "19",
            fromStopOrder = 1,
            toStopOrder = 4
        )

        val transferGroup = TransitGroup(
            id = "group_transfer_1",
            name = "晨间跨区换乘 (家 ➔ 科技园)",
            category = GroupCategory.COMMUTE,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "碧海湾站",
                    toStopName = "高新园地铁站",
                    candidateLines = listOf(leg0Line1, leg0Line2),
                    walkingDistanceMeters = 120
                ),
                TransitLeg(
                    legIndex = 1,
                    fromStopName = "高新园地铁站",
                    toStopName = "腾讯大厦",
                    candidateLines = listOf(leg1Line1),
                    walkingDistanceMeters = 50
                )
            )
        )

        val viewModel = createViewModel()
        viewModel.saveGroup(transferGroup)

        composeTestRule.setContent {
            FavoritesGroupScreen(
                viewModel = viewModel,
                onNavigateToLineDetail = { _, _, _, _, _, _ -> },
                onNavigateToRoutePlan = {}
            )
        }
        composeTestRule.waitForIdle()

        // 1. 验证标题与换乘统计
        composeTestRule.onNodeWithText("晨间跨区换乘 (家 ➔ 科技园)").assertIsDisplayed()
        composeTestRule.onNodeWithText("换乘 1 次 · 步行 170米").assertIsDisplayed()

        // 2. 验证纯程数胶囊与纯线路号码胶囊
        composeTestRule.onNodeWithText("1程").assertIsDisplayed()
        composeTestRule.onNodeWithTag("leg_chip_320").assertIsDisplayed()
        composeTestRule.onNodeWithTag("leg_chip_M375").assertIsDisplayed()
        composeTestRule.onNodeWithText("2程").assertExists()
        composeTestRule.onNodeWithTag("leg_chip_19").assertExists()

        // 验证绝对没有“备选”字样
        val verboseNodes = composeTestRule.onAllNodesWithText("备选", substring = true).fetchSemanticsNodes()
        assertEquals(0, verboseNodes.size)

        // 3. 点击 M375 胶囊切换第 1 程所选线路
        composeTestRule.onNodeWithTag("leg_chip_M375").performClick()
        composeTestRule.waitForIdle()

        val selected = viewModel.selectedLineKeys.value["group_transfer_1_0"]
        assertEquals("line_m375", selected)
    }

    @Test
    fun multiLegTransferCard_rendersSameStationTransfer_whenWalkingIsZero() {
        val sameStationTransferGroup = TransitGroup(
            id = "group_same_station",
            name = "万达金街 ➔ 西徐村",
            category = GroupCategory.CUSTOM,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "万达金街",
                    toStopName = "西社小区",
                    candidateLines = listOf(
                        CandidateLine(cityId = "0532", lineId = "59", lineName = "59")
                    ),
                    walkingDistanceMeters = 0
                ),
                TransitLeg(
                    legIndex = 1,
                    fromStopName = "西社小区",
                    toStopName = "西徐村",
                    candidateLines = listOf(
                        CandidateLine(cityId = "0532", lineId = "351", lineName = "351")
                    ),
                    walkingDistanceMeters = 0
                )
            )
        )

        val viewModel = createViewModel()
        viewModel.saveGroup(sameStationTransferGroup)

        composeTestRule.setContent {
            FavoritesGroupScreen(
                viewModel = viewModel,
                onNavigateToLineDetail = { _, _, _, _, _, _ -> },
                onNavigateToRoutePlan = {}
            )
        }
        composeTestRule.waitForIdle()

        // 1. 验证标题、同站换乘摘要及反向按钮
        composeTestRule.onNodeWithText("万达金街 ➔ 西徐村").assertIsDisplayed()
        composeTestRule.onNodeWithText("换乘 1 次 · 同站换乘").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_reverse_group_same_station").assertIsDisplayed()

        // 2. 验证中转节点显示 (同站换乘) 而不是 (步行 0米)
        composeTestRule.onNodeWithText("西社小区 (同站换乘)").assertExists()
    }

    @Test
    fun favoriteGroupManagement_createsAndDeletesGroup() {
        val viewModel = createViewModel()

        composeTestRule.setContent {
            FavoritesGroupScreen(
                viewModel = viewModel,
                onNavigateToLineDetail = { _, _, _, _, _, _ -> },
                onNavigateToRoutePlan = {}
            )
        }
        composeTestRule.waitForIdle()

        // 1. 点击“⚙️ 管理”呼起管理抽屉
        composeTestRule.onNodeWithTag("btn_manage_groups").performClick()
        composeTestRule.waitForIdle()

        // 验证抽屉标题与提示
        composeTestRule.onNodeWithText("管理收藏分组").assertIsDisplayed()
        composeTestRule.onNodeWithText("仅删除分组标签，组内收藏将归入全部").assertIsDisplayed()

        // 2. 输入新建分组名称并点击新建
        composeTestRule.onNodeWithTag("input_new_favorite_group").performTextInput("商圈购物")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_create_favorite_group").performClick()
        composeTestRule.waitForIdle()

        val created = viewModel.favoriteGroups.value.find { it.name == "商圈购物" }
        assertNotNull("新建的分组应持久化存在", created)
        val createdId = created!!.id

        composeTestRule.onNodeWithTag("item_favorite_group_$createdId").assertIsDisplayed()

        // 3. 点击删除该分组
        composeTestRule.onNodeWithTag("btn_delete_favorite_group_$createdId").performClick()
        composeTestRule.waitForIdle()

        // 验证分组已删除
        val deleted = viewModel.favoriteGroups.value.find { it.id == createdId }
        assertNull("被删除的分组不应再存在", deleted)

        // 点击完成关闭抽屉
        composeTestRule.onNodeWithTag("btn_dismiss_manage_groups").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun quickCreateGroupChip_createsNewFavoriteGroup() {
        val viewModel = createViewModel()

        composeTestRule.setContent {
            FavoritesGroupScreen(
                viewModel = viewModel,
                onNavigateToLineDetail = { _, _, _, _, _, _ -> },
                onNavigateToRoutePlan = {}
            )
        }
        composeTestRule.waitForIdle()

        // 点击顶部滑动栏末尾的“+ 新建”胶囊
        composeTestRule.onNodeWithTag("chip_quick_create_group").performClick()
        composeTestRule.waitForIdle()

        // 验证弹出新建分组弹窗
        composeTestRule.onNodeWithText("新建收藏分组").assertIsDisplayed()

        // 输入分组名并确认
        composeTestRule.onNodeWithTag("input_quick_group_name").performTextInput("周末西湖")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_confirm_quick_group").performClick()
        composeTestRule.waitForIdle()

        // 验证分组已成功创建并在筛选栏显示
        val created = viewModel.favoriteGroups.value.find { it.name == "周末西湖" }
        assertNotNull(created)
        composeTestRule.onNodeWithTag("chip_fav_group_${created!!.id}").assertIsDisplayed()
    }
}
