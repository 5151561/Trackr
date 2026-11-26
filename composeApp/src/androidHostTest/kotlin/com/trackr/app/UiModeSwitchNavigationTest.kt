package com.trackr.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.UiMode
import com.trackr.core.navigation.ElderNavigator
import com.trackr.core.navigation.NavigationState
import com.trackr.core.navigation.Navigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.TopLevel
import com.trackr.core.navigation.rememberElderBackStack
import com.trackr.core.navigation.rememberNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 切换界面模式时的导航（第 31 步，§8.4、§8.7，Q9：直接回首页）：不论停在哪一页，新模式的栈都是 `[首页]`；
 * 切回标准版时 3 条页签栈都在各自根页、当前页签是首页。旧模式的页面 ViewModel 跟着清掉。
 * 走的是 [TrackrRoot] 用的同一个 [UiModeNavHost]，两边放真实的导航状态，页面内容省掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UiModeSwitchNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    class PageViewModel : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private val line49 = ScreenKey.LineDetail(
        cityId = "021", lineId = "line_49", targetOrder = 4, stopId = "stop_004",
        source = DataSourceType.CUSTOM, lineName = "49路",
    )
    private val stop = ScreenKey.StopDetail(cityId = "021", stopId = "stop_555", stopName = "陕西南路")

    private val mode = mutableStateOf(UiMode.Standard)

    private var standardState: NavigationState? = null
    private var standardNav: Navigator? = null
    private var standardVm: PageViewModel? = null
    private var elderStack: NavBackStack<NavKey>? = null
    private var elderNav: ElderNavigator? = null
    private var elderVm: PageViewModel? = null

    private fun setContent(restorationTester: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            UiModeNavHost(
                uiMode = mode.value,
                standard = {
                    val state = rememberNavigationState()
                    val nav = remember(state) { Navigator(state) }
                    val vm = viewModel { PageViewModel() }
                    SideEffect {
                        standardState = state
                        standardNav = nav
                        standardVm = vm
                    }
                },
                elder = {
                    val stack = rememberElderBackStack()
                    val nav = remember(stack) { ElderNavigator(stack) }
                    val vm = viewModel { PageViewModel() }
                    SideEffect {
                        elderStack = stack
                        elderNav = nav
                        elderVm = vm
                    }
                }
            )
        }
        if (restorationTester != null) restorationTester.setContent(content) else composeTestRule.setContent(content)
        composeTestRule.waitForIdle()
    }

    private fun switchTo(newMode: UiMode) {
        composeTestRule.runOnIdle { mode.value = newMode }
        composeTestRule.waitForIdle()
    }

    private fun standard() = requireNotNull(standardState)
    private fun elder() = requireNotNull(elderStack)

    private fun assertStandardAtRoots() {
        val state = standard()
        assertEquals(ScreenKey.Home, state.topLevel)
        TopLevel.tabs.forEach { tab ->
            assertEquals("$tab 栈回到根页", listOf<NavKey>(tab), state.stacks.getValue(tab).toList())
        }
    }

    /** 标准版里的各种停留位置：每个都先在别的页签留点东西，确认切换后也一起清掉 */
    private val standardPlaces: List<Pair<String, Navigator.() -> Unit>> = listOf(
        "首页根页" to {},
        "搜索" to { open(ScreenKey.Search) },
        "线路页" to { open(ScreenKey.Search); open(line49) },
        "站点页 + 上了哪辆弹层" to { open(stop); open(ScreenKey.ChooseBoardedBus("s1")) },
        "路线详情" to { open(ScreenKey.RouteDetail("g1")) },
        "编辑收藏" to { open(ScreenKey.FavoritesEdit) },
        "路线规划页签" to { open(ScreenKey.Search); selectTab(ScreenKey.RoutePlan); open(line49) },
        "我的 · 关于" to { open(line49); selectTab(ScreenKey.More); open(ScreenKey.About) },
        "我的 · 编辑提醒" to { selectTab(ScreenKey.More); open(ScreenKey.ReminderEdit("r1")) },
    )

    private val elderPlaces: List<Pair<String, ElderNavigator.() -> Unit>> = listOf(
        "首页" to {},
        "搜索 · 线路" to { open(ScreenKey.Search); open(line49) },
        "站点页 + 上了哪辆弹层" to { open(stop); open(ScreenKey.ChooseBoardedBus("s1")) },
        "路线详情" to { open(ScreenKey.RouteDetail("g1")) },
        "设置 · 关于" to { open(ScreenKey.More); open(ScreenKey.About) },
        "设置 · 高德 Key" to { open(ScreenKey.More); open(ScreenKey.AmapKey) },
    )

    @Test
    fun standardAnyPage_toElder_stackIsHome_andBackToStandardAllTabsAtRoot() {
        setContent()
        standardPlaces.forEach { (name, go) ->
            composeTestRule.runOnIdle { requireNotNull(standardNav).go() }
            composeTestRule.waitForIdle()

            switchTo(UiMode.Elder)
            assertEquals("从标准版「$name」切到长辈版", listOf<NavKey>(ScreenKey.Home), elder().toList())

            switchTo(UiMode.Standard)
            assertStandardAtRoots()
        }
    }

    @Test
    fun elderAnyPage_toStandard_homeTabAtRoot_andBackToElderStackIsHome() {
        mode.value = UiMode.Elder
        setContent()
        elderPlaces.forEach { (name, go) ->
            composeTestRule.runOnIdle { requireNotNull(elderNav).go() }
            composeTestRule.waitForIdle()

            switchTo(UiMode.Standard)
            assertStandardAtRoots()
            assertEquals(listOf<NavKey>(ScreenKey.Home), standard().keysShown())

            switchTo(UiMode.Elder)
            assertEquals("长辈版「$name」切走再切回来", listOf<NavKey>(ScreenKey.Home), elder().toList())
        }
    }

    @Test
    fun switching_clearsOldModeViewModels_keepsCurrentOnes() {
        setContent()
        val standardBefore = requireNotNull(standardVm)
        composeTestRule.runOnIdle { requireNotNull(standardNav).open(ScreenKey.Search) }
        composeTestRule.waitForIdle()
        assertSame("同一模式里重组不换 ViewModel", standardBefore, standardVm)

        switchTo(UiMode.Elder)
        assertTrue("切到长辈版后标准版的 ViewModel 清掉", standardBefore.cleared)
        val elderBefore = requireNotNull(elderVm)
        assertFalse(elderBefore.cleared)

        switchTo(UiMode.Standard)
        assertTrue("切回标准版后长辈版的 ViewModel 清掉", elderBefore.cleared)
        assertNotSame("标准版是新的 ViewModel，不复用切走前的", standardBefore, standardVm)
        assertFalse(requireNotNull(standardVm).cleared)
    }

    @Test
    fun elderStack_survivesStateRestoration() {
        // 进程重建（§3.8）：模式没变，长辈版那条栈原样恢复
        mode.value = UiMode.Elder
        val tester = StateRestorationTester(composeTestRule)
        setContent(tester)
        composeTestRule.runOnIdle {
            requireNotNull(elderNav).run {
                open(ScreenKey.Search)
                open(line49)
            }
        }
        composeTestRule.waitForIdle()
        val before = elder()

        tester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertNotSame(before, elder())
        assertEquals(listOf(ScreenKey.Home, ScreenKey.Search, line49), elder().toList())
        assertTrue(requireNotNull(elderNav).back())
        assertTrue(requireNotNull(elderNav).back())
        assertFalse(requireNotNull(elderNav).back())
    }
}
