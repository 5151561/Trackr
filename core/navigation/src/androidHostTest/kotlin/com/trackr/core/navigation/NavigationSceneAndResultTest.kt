package com.trackr.core.navigation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.ui.NavDisplay
import com.trackr.core.navigation.scene.BottomSheetSceneStrategy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 第 18 步的导航基建（重构计划 §3.5、§3.7）：
 * - [BottomSheetSceneStrategy]：带 `bottomSheet()` 的 entry 叠在上一页上用弹层显示，返回关掉弹层；没带的照常单页显示。
 * - [NavigationState.toDecoratedEntries] 给所有 entry 共用一个 ResultEventBus：弹层发的结果，上一页的 `ResultEffect` 收到。
 *
 * 用真实的 [rememberNavigationState] + [Navigator] + NavDisplay，和 `TrackrRoot` 的接法一样。
 * 弹层借 [ScreenKey.About] 当测试目的地（本步还没有真正的弹层 Key：第 21、22 步加）。
 */
@RunWith(RobolectricTestRunner::class)
class NavigationSceneAndResultTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private data class Saved(val id: String)

    private lateinit var state: NavigationState
    private lateinit var navigator: Navigator
    private val received = mutableListOf<String>()

    @Composable
    private fun Host(sheetKey: ScreenKey = ScreenKey.About) {
        val navigationState = rememberNavigationState()
        val nav = remember(navigationState) { Navigator(navigationState) }
        SideEffect {
            state = navigationState
            navigator = nav
        }
        NavDisplay(
            entries = navigationState.toDecoratedEntries(startUnderneath = false) { key ->
                when (key) {
                    sheetKey -> NavEntry(key, metadata = BottomSheetSceneStrategy.bottomSheet()) {
                        val bus = LocalResultEventBus.current
                        Text("弹层内容")
                        Button(onClick = {
                            bus.sendResult(Saved("fav_1"))
                            nav.back()
                        }) { Text("保存") }
                    }
                    else -> NavEntry(key) {
                        ResultEffect<Saved> { received += it.id }
                        Text("页面 $key")
                    }
                }
            },
            sceneStrategies = listOf(BottomSheetSceneStrategy()),
            onBack = { nav.back() },
        )
    }

    @Test
    fun bottomSheetEntry_isShownOverPreviousPage_andBackDismissesIt() {
        composeTestRule.setContent { Host() }
        composeTestRule.runOnIdle { navigator.selectTab(ScreenKey.More) }
        composeTestRule.onNodeWithText("页面 More").assertExists()

        composeTestRule.runOnIdle { navigator.open(ScreenKey.About) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("弹层内容").assertExists()
        composeTestRule.onNodeWithText("页面 More").assertExists() // 弹层下面的页还在

        composeTestRule.runOnIdle { navigator.back() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("弹层内容").assertDoesNotExist()
        composeTestRule.onNodeWithText("页面 More").assertExists()
        assertEquals(listOf<NavKey>(ScreenKey.More), state.currentStack.toList())
    }

    @Test
    fun entriesWithoutBottomSheetMetadata_stillShowAsSinglePages() {
        composeTestRule.setContent { Host() }
        composeTestRule.onNodeWithText("页面 Home").assertExists()

        composeTestRule.runOnIdle { navigator.open(ScreenKey.Search) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("页面 Search").assertExists()
        composeTestRule.onNodeWithText("页面 Home").assertDoesNotExist()
    }

    @Test
    fun resultSentFromSheet_isReceivedByPageBelow() {
        composeTestRule.setContent { Host() }
        composeTestRule.runOnIdle {
            navigator.selectTab(ScreenKey.More)
            navigator.open(ScreenKey.About)
        }
        composeTestRule.waitForIdle()

        // 弹层在单独的窗口里、还在展开动画中，Robolectric 下触摸点不到：直接触发点击语义
        composeTestRule.onNodeWithText("保存").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals(listOf<NavKey>(ScreenKey.More), state.currentStack.toList())
        assertEquals(listOf("fav_1"), received)
        composeTestRule.onNodeWithText("弹层内容").assertDoesNotExist()
    }
}
