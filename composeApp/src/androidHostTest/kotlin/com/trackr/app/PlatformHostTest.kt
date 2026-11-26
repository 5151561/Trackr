package com.trackr.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.navigation.NavigationState
import com.trackr.core.navigation.Navigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.rememberNavigationState
import com.trackr.ui.screens.routeplan.AmapMapSession
import com.trackr.ui.screens.routeplan.LocalAmapMapSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 同意高德隐私说明后导航不能丢（5dd5472 之后引入的回归）。
 *
 * consent 从 null 翻成 true 时，`PlatformHost` 以前是 `if (enabled) ProvideAmapMapSession(content) else content()`：
 * `content` 在两个分支之间换了调用位置，整棵 `TrackrNavApp` 子树被销毁重建，`rememberSaveable` 的返回栈和
 * 弹层回调里闭包持有的 [Navigator] 都变成旧实例，`navigator.selectTab(RoutePlan)` 落在丢弃的栈上。
 *
 * 这里断言的就是那个行为：翻转前后导航子树不重建，且翻转前拿到的 [Navigator] 仍然改得动正在显示的导航状态。
 * 走的是 `PlatformHost` 的 Android 实现（`:composeApp` androidHostTest 只编译 androidMain），
 * 内部就是 [com.trackr.ui.screens.routeplan.ProvideAmapMapSession] + `LocalAmapMapSession` 的真实现。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlatformHostTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** 每次组合后记下来的东西（Compose 里读不到测试类的普通字段，用 SideEffect 带出来） */
    private class Seen {
        /** 最近一次组合时的导航状态 */
        var state: NavigationState? = null

        /** 导航子树被销毁了几次（正常应始终为 0） */
        var subtreeDisposals = 0

        /** 最近一次组合时 `LocalAmapMapSession` 的值 */
        var session: AmapMapSession? = null
    }

    @Test
    fun consentFlip_keepsNavigationSubtreeAndNavigator() {
        val seen = Seen()
        val consentAccepted = mutableStateOf(false)

        composeTestRule.setContent {
            PlatformHost(enabled = consentAccepted.value) {
                val navigationState = rememberNavigationState()
                val navigator = remember(navigationState) { Navigator(navigationState) }
                val session = LocalAmapMapSession.current
                SideEffect {
                    seen.state = navigationState
                    seen.session = session
                    capturedNavigator = navigator
                }
                // 子树被销毁时会走这里：这就是「返回栈丢了」的现场
                DisposableEffect(Unit) { onDispose { seen.subtreeDisposals++ } }
                Text("导航子树")
            }
        }
        composeTestRule.waitForIdle()

        // 还没同意：不建地图会话，页面走矢量降级（RouteMap 的 canDrawMap = session != null）
        assertNull("没同意前不该有地图会话", seen.session)
        assertEquals(ScreenKey.Home, seen.state?.topLevel)
        val navigatorFromDialog = requireNotNull(capturedNavigator) { "导航子树没有组合出来" }

        // 用户在弹层里点「同意并继续」：先落 consent，再在同一个回调里切页签（用的是翻转前拿到的 navigator）
        composeTestRule.runOnIdle { consentAccepted.value = true }
        composeTestRule.waitForIdle()
        assertNotNull("同意后应该有地图会话", seen.session)

        composeTestRule.runOnIdle { navigatorFromDialog.selectTab(ScreenKey.RoutePlan) }
        composeTestRule.waitForIdle()

        assertEquals("导航子树不能被重建", 0, seen.subtreeDisposals)
        assertEquals(
            "「同意并继续」里的 selectTab 必须落到正在显示的导航状态上",
            ScreenKey.RoutePlan,
            seen.state?.topLevel
        )
    }

    /** 最近一次组合里拿到的 navigator（和弹层回调闭包里的是同一个） */
    private var capturedNavigator: Navigator? = null
}
