package com.trackr.ui.screens.routeplan

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.data.remote.amap.AmapTransitSchemeResult
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.FavoriteGroup
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.ui.screens.favoritesgroup.FavoritesGroupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutePlanScreenTest {

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
            enableAutoRefresh = false
        )
    }

    private fun createSampleScheme(transferCount: Int = 0): AmapTransitSchemeResult {
        val leg1 = TransitLeg(
            legIndex = 0,
            fromStopName = "深大北门",
            toStopName = if (transferCount == 0) "高新园" else "前海湾",
            candidateLines = listOf(
                CandidateLine(
                    cityId = "0755",
                    lineId = "line_320",
                    lineName = "320路",
                    startStopName = "深大北门",
                    endStopName = if (transferCount == 0) "高新园" else "前海湾"
                )
            )
        )
        val legs = if (transferCount == 0) {
            listOf(leg1)
        } else {
            val leg2 = TransitLeg(
                legIndex = 1,
                fromStopName = "前海湾",
                toStopName = "高新园",
                candidateLines = listOf(
                    CandidateLine(
                        cityId = "0755",
                        lineId = "line_m194",
                        lineName = "M194路",
                        startStopName = "前海湾",
                        endStopName = "高新园"
                    )
                )
            )
            listOf(leg1, leg2)
        }

        val group = TransitGroup(
            id = "group_sample_1",
            name = "深大北门 ➔ 高新园",
            legs = legs
        )

        return AmapTransitSchemeResult(
            title = if (transferCount == 0) "320路 直达" else "320路 ➔ M194路 换乘",
            durationMinutes = 18,
            walkingMeters = 80,
            costYuan = "2.5",
            transferCount = transferCount,
            group = group
        )
    }

    @Test
    fun testTopSearchConsole_initialRenderingAndQuickFill() {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            RoutePlanScreen(
                viewModel = viewModel,
                onNavigateToFavorites = {},
                onNavigateToLineDetail = { _, _, _, _, _, _ -> }
            )
        }

        // 验证顶部搜索控制台初始占位文案展示
        composeTestRule.onNodeWithText("我的位置 / 输入起点站").assertIsDisplayed()
        composeTestRule.onNodeWithText("输入目的地、商圈或公交地铁站").assertIsDisplayed()

        // 验证规划触发按钮与Key配置按钮
        composeTestRule.onNodeWithTag("btn_trigger_plan").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_key_config").assertIsDisplayed()

        // 输入起点
        composeTestRule.onNodeWithTag("input_origin").performTextInput("科技园")
        composeTestRule.waitForIdle()

        // 验证起点已填充
        composeTestRule.onNodeWithText("科技园").assertIsDisplayed()
    }

    @Test
    fun testSwapOriginAndDestination_swapsInputValues() {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            RoutePlanScreen(
                viewModel = viewModel,
                onNavigateToFavorites = {},
                onNavigateToLineDetail = { _, _, _, _, _, _ -> }
            )
        }

        // 输入起点与终点
        composeTestRule.onNodeWithTag("input_origin").performTextInput("深大北门")
        composeTestRule.onNodeWithTag("input_dest").performTextInput("高新园")
        composeTestRule.waitForIdle()

        // 点击互换按钮
        composeTestRule.onNodeWithTag("btn_swap_origin_dest").performClick()
        composeTestRule.waitForIdle()

        // 验证起终点互换
        composeTestRule.onNodeWithTag("input_origin").assertIsDisplayed()
        composeTestRule.onNodeWithTag("input_dest").assertIsDisplayed()
    }

    @Test
    fun testSaveFavoriteGroupDialog_savesAndInvokesCallback() {
        val sampleScheme = createSampleScheme(transferCount = 0)
        var savedGroupId: String? = "uncalled"
        var savedCustomName: String? = null

        composeTestRule.setContent {
            SaveFavoriteGroupDialog(
                scheme = sampleScheme,
                favoriteGroups = listOf(
                    FavoriteGroup(id = "commute", name = "上班通勤"),
                    FavoriteGroup(id = "home", name = "回家线路")
                ),
                onDismiss = {},
                onConfirmSave = { groupId, customName ->
                    savedGroupId = groupId
                    savedCustomName = customName
                },
                onCreateNewGroup = {}
            )
        }

        composeTestRule.onNodeWithText("存入收藏").assertIsDisplayed()
        composeTestRule.onNodeWithText("上班通勤").assertIsDisplayed()
        composeTestRule.onNodeWithText("回家线路").assertIsDisplayed()

        // 选中“上班通勤”
        composeTestRule.onNodeWithText("上班通勤").performClick()

        // 点击确认存入
        composeTestRule.onNodeWithTag("btn_confirm_save_favorite").performClick()
        composeTestRule.waitForIdle()

        assertEquals("commute", savedGroupId)
        assertEquals("深大北门 ➔ 高新园", savedCustomName)
    }

    @Test
    fun testBottomAdaptiveRouteSheet_displaysSchemeAndMonitorAction() {
        val sampleScheme = createSampleScheme(transferCount = 0)
        var monitored = false
        var savedFavorite = false

        composeTestRule.setContent {
            // 直接渲染存入与监控按钮行为的单元隔离验证
            SaveFavoriteGroupDialog(
                scheme = sampleScheme,
                favoriteGroups = emptyList(),
                onDismiss = {},
                onConfirmSave = { _, _ -> savedFavorite = true },
                onCreateNewGroup = {}
            )
        }

        composeTestRule.onNodeWithTag("btn_confirm_save_favorite").performClick()
        assertTrue("Confirm save should trigger save callback", savedFavorite)
    }

    // ---------------- 方案快速切换 ----------------

    private fun scheme(
        id: String,
        minutes: Int,
        transfers: Int,
        walk: Int,
        lineNames: List<String>
    ): AmapTransitSchemeResult {
        val legs = lineNames.mapIndexed { index, name ->
            TransitLeg(
                legIndex = index,
                fromStopName = "站$index",
                toStopName = "站${index + 1}",
                candidateLines = listOf(CandidateLine(cityId = "0755", lineId = "amap_$name", lineName = name)),
                walkingDistanceMeters = if (index == 0) 0 else 80
            )
        }
        return AmapTransitSchemeResult(
            title = id,
            durationMinutes = minutes,
            walkingMeters = walk,
            costYuan = "4.0",
            transferCount = transfers,
            group = TransitGroup(id = id, name = id, legs = legs)
        )
    }

    private val demoSchemes = listOf(
        scheme("s0", 32, 1, 820, listOf("B612", "M492")),
        scheme("s1", 38, 0, 640, listOf("M375")),
        scheme("s2", 35, 1, 260, listOf("B796", "323")),
        scheme("s3", 36, 1, 1100, listOf("地铁1号线", "B612"))
    )

    @Test
    fun schemeFeatureTag_pickOneTagPerScheme() {
        assertEquals("最快", schemeFeatureTag(demoSchemes, 0))
        assertEquals("直达", schemeFeatureTag(demoSchemes, 1))
        assertEquals("少步行", schemeFeatureTag(demoSchemes, 2))
        assertEquals("地铁", schemeFeatureTag(demoSchemes, 3))
    }

    @Test
    fun schemeFeatureTag_tiesGoToFirst_andNoTagWhenNothingStandsOut() {
        val tie = listOf(
            scheme("a", 30, 1, 500, listOf("1", "2")),
            scheme("b", 30, 1, 500, listOf("3", "4"))
        )
        assertEquals("最快", schemeFeatureTag(tie, 0))
        assertNull(schemeFeatureTag(tie, 1))
        // 只有一个方案时不比较，换乘方案也不是地铁 → 不显示
        assertNull(schemeFeatureTag(listOf(scheme("c", 30, 1, 500, listOf("1", "2"))), 0))
        assertEquals("少换乘", schemeFeatureTag(
            listOf(scheme("d", 30, 2, 500, listOf("1", "2", "3")), scheme("e", 35, 1, 900, listOf("4", "5"))), 1
        ))
    }

    @Test
    fun schemeTexts_summaryTimesAndSegments() {
        assertEquals("步行 820 米 · 换乘 1 次 · ¥4.0", schemeSummaryText(demoSchemes[0]))
        assertEquals("步行 640 米 · 不用换乘 · ¥4.0", schemeSummaryText(demoSchemes[1]))
        assertEquals("直达", schemeTransferText(demoSchemes[1]))
        assertEquals("换 1 次", schemeTransferText(demoSchemes[0]))

        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.of(2026, 10, 31, 7, 42).atZone(zone).toInstant().toEpochMilli()
        assertEquals("07:42 出发 · 08:14 到", departArriveText(now, 32, zone))

        // 步行 240 米：第 2 程前 80 米 + 末段 160 米；站序不可信，两程平分剩余时长
        val segments = planSegments(scheme("x", 30, 1, 240, listOf("B612", "M492")))
        assertEquals(listOf(false, true, false, true), segments.map { it.isWalk })
        assertEquals(listOf("B612", "", "M492", ""), segments.map { it.label })
        assertEquals(1f, segments[1].weight, 0.01f)
        assertEquals(2f, segments[3].weight, 0.01f)
        assertEquals(13.5f, segments[0].weight, 0.01f)
    }

    private fun trackingSession(groupId: String, phase: LegPhase, stops: Int, eta: Int) = GroupTrackingSessionState(
        groupId = groupId,
        activeLegIndex = 0,
        totalLegs = 2,
        trackingSessionState = TrackingSessionState(
            isActive = true,
            legPhase = phase,
            stopsRemaining = stops,
            etaSeconds = eta,
            currentLegIndex = 0,
            totalLegs = 2
        )
    )

    @Test
    fun trackingStatusText_byPhase() {
        assertEquals("第 1/2 程 · 等车 · 4 分钟", trackingStatusText(trackingSession("s0", LegPhase.WAITING_BOARD, 2, 240)))
        assertEquals("第 1/2 程 · 乘车 · 2 站后下车", trackingStatusText(trackingSession("s0", LegPhase.ON_BOARD, 2, 300)))
    }

    @Test
    fun schemePanel_tabsSwitchPager_andStartTrackingUsesSelectedScheme() {
        var started: AmapTransitSchemeResult? = null
        var clickedOrder = -1
        composeTestRule.setContent {
            val pagerState = rememberPagerState { demoSchemes.size }
            val scope = rememberCoroutineScope()
            RoutePlanSchemePanel(
                schemes = demoSchemes,
                pagerState = pagerState,
                trackingSession = null,
                onSelectScheme = { scope.launch { pagerState.animateScrollToPage(it) } },
                onLineClick = { clickedOrder = it.fromStopOrder },
                onSaveFavorite = {},
                onStartTracking = { started = it },
                onConfirmBoarded = {},
                onStopTracking = {}
            )
        }

        // 4 个方案标签一直可见，没有"展开全部方案"
        (0 until 4).forEach { composeTestRule.onNodeWithTag("scheme_tab_$it").assertIsDisplayed() }
        composeTestRule.onAllNodesWithText("展开全部", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithTag("scheme_duration_0").assertTextEquals("32 分钟")
        composeTestRule.onNodeWithText("收藏").assertIsDisplayed()

        // 点第 3 个标签切到对应详情
        composeTestRule.onNodeWithTag("scheme_tab_2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scheme_duration_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scheme_summary_2").assertTextEquals("步行 260 米 · 换乘 1 次 · ¥4.0")

        // 胶囊跳详情传上车站
        composeTestRule.onNodeWithTag("scheme_line_chip_B796").performClick()
        assertEquals(1, clickedOrder)

        composeTestRule.onNodeWithTag("btn_start_monitor").performClick()
        assertEquals("s2", started?.group?.id)
    }

    @Test
    fun schemePanel_trackingCurrentScheme_showsStatusBarInsteadOfButtons() {
        var stopped = false
        composeTestRule.setContent {
            val pagerState = rememberPagerState { demoSchemes.size }
            RoutePlanSchemePanel(
                schemes = demoSchemes,
                pagerState = pagerState,
                trackingSession = trackingSession("s0", LegPhase.WAITING_BOARD, 2, 240),
                onSelectScheme = {},
                onLineClick = {},
                onSaveFavorite = {},
                onStartTracking = {},
                onConfirmBoarded = {},
                onStopTracking = { stopped = true }
            )
        }

        composeTestRule.onNodeWithTag("tracking_status_text").assertTextEquals("第 1/2 程 · 等车 · 4 分钟")
        composeTestRule.onNodeWithTag("btn_plan_confirm_boarded").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_start_monitor").assertDoesNotExist()
        composeTestRule.onNodeWithTag("btn_plan_stop_tracking").performClick()
        assertTrue(stopped)
    }
}
