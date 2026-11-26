package com.trackr.core.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.trackr.core.model.DataSourceType
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.deeplink.DeepLinkParser
import com.trackr.core.navigation.deeplink.DeepLinkUris
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 长辈版单栈导航（第 31 步，§8.4、§8.7）：返回、不支持的页面落首页、deep link 落栈。 */
class ElderNavigatorTest {

    private val line49 = ScreenKey.LineDetail(
        cityId = "021",
        lineId = "line_49",
        targetOrder = 4,
        stopId = "stop_004",
        source = DataSourceType.CUSTOM,
        lineName = "49路",
    )
    private val stop = ScreenKey.StopDetail(cityId = "021", stopId = "stop_555", stopName = "陕西南路")

    private fun newStack() = NavBackStack<NavKey>(ScreenKey.Home)

    @Test
    fun startsAtHome_backAtRootReturnsFalse() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList())
        assertFalse(nav.back(), "首页按返回交给系统退出")
        assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList())
    }

    @Test
    fun open_pushesAndDedupsTop_backPopsOneByOneThenFalse() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.open(ScreenKey.Search)
        nav.open(line49)
        nav.open(line49)
        assertEquals(listOf(ScreenKey.Home, ScreenKey.Search, line49), stack.toList())

        assertTrue(nav.back())
        assertTrue(nav.back())
        assertFalse(nav.back())
        assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList())
    }

    @Test
    fun meIsAChildPage_notATab() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.open(ScreenKey.More)
        nav.open(ScreenKey.About)
        assertEquals(listOf(ScreenKey.Home, ScreenKey.More, ScreenKey.About), stack.toList())
        assertTrue(nav.back())
        assertTrue(nav.back(), "「我的」在长辈版是首页上的子页，返回回到首页")
        assertEquals(ScreenKey.Home, nav.currentKey)
    }

    @Test
    fun unsupportedKeys_fallBackToHome_withNotice() {
        listOf(ScreenKey.RoutePlan, ScreenKey.FavoritesEdit, ScreenKey.ReminderEdit("r1"), ScreenKey.FavoritesGroup).forEach { key ->
            val stack = newStack()
            val nav = ElderNavigator(stack)
            nav.open(ScreenKey.More)
            nav.open(key)
            assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList(), "$key 落到首页")
            assertTrue(nav.unsupportedNotice, "$key 要提示长辈版不支持")
            nav.dismissUnsupportedNotice()
            assertFalse(nav.unsupportedNotice)
        }
    }

    @Test
    fun openHome_popsToRoot_withoutDuplicatingHome() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.open(ScreenKey.Search)
        nav.open(ScreenKey.Home)
        assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList())
        assertFalse(nav.unsupportedNotice)
    }

    @Test
    fun deepLink_line_landsAsHomeThenLine_andReplaysIdempotently() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.open(ScreenKey.More)
        nav.open(ScreenKey.About)

        val link = DeepLinkParser.parse(
            DeepLinkUris.line("021", "line_49", DataSourceType.CUSTOM, stopId = "stop_004", targetOrder = 4, lineName = "49路")
        )
        assertIs<DeepLink.Stack>(link)
        nav.applyDeepLink(link)
        assertEquals(listOf(ScreenKey.Home, line49), stack.toList())
        nav.applyDeepLink(link)
        assertEquals(listOf(ScreenKey.Home, line49), stack.toList(), "重放结果不变")

        assertTrue(nav.back())
        assertFalse(nav.back(), "deep link 进来按返回回到首页，再返回才退出")
        assertFalse(nav.unsupportedNotice)
    }

    @Test
    fun deepLink_stopAndRoute_land() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.applyDeepLink(assertIs<DeepLink.Stack>(DeepLinkParser.parse(DeepLinkUris.stop("021", "stop_555", "陕西南路"))))
        assertEquals(listOf(ScreenKey.Home, stop), stack.toList())

        nav.applyDeepLink(assertIs<DeepLink.Stack>(DeepLinkParser.parse(DeepLinkUris.route("g1"))))
        assertEquals(listOf(ScreenKey.Home, ScreenKey.RouteDetail("g1")), stack.toList())
    }

    @Test
    fun deepLink_ignoresTab_dropsUnsupportedKeys() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        // 标准版会切到「路线」页签；长辈版没有页签，只留支持的页面
        nav.applyDeepLink(DeepLink.Stack(ScreenKey.RoutePlan, listOf(ScreenKey.RoutePlan, line49, ScreenKey.FavoritesEdit)))
        assertEquals(listOf(ScreenKey.Home, line49), stack.toList())
        assertTrue(nav.unsupportedNotice)
    }

    @Test
    fun chooseBoardedBus_overlayOpensOnTop_andDismissRemovesOnlyIt() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.open(stop)
        val sheet = ScreenKey.ChooseBoardedBus("s1")
        nav.open(sheet)
        nav.open(sheet)
        assertEquals(listOf(ScreenKey.Home, stop, sheet), stack.toList())
        nav.dismiss(sheet)
        nav.dismiss(sheet)
        assertEquals(listOf(ScreenKey.Home, stop), stack.toList())
    }

    @Test
    fun popChildPage_stopsAtRoot() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.open(ScreenKey.Search)
        nav.popChildPage()
        nav.popChildPage()
        assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList())
    }

    @Test
    fun showInTab_singleStack_popsToExistingOrPushesFromRoot() {
        val stack = newStack()
        val nav = ElderNavigator(stack)
        nav.open(ScreenKey.RouteDetail("g1"))
        nav.open(line49)
        nav.showInTab(ScreenKey.Home, ScreenKey.RouteDetail("g1"))
        assertEquals(listOf(ScreenKey.Home, ScreenKey.RouteDetail("g1")), stack.toList())

        nav.showInTab(ScreenKey.More, ScreenKey.About)
        assertEquals(listOf(ScreenKey.Home, ScreenKey.About), stack.toList())

        nav.showInTab(ScreenKey.Home, ScreenKey.FavoritesEdit)
        assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList())
        assertTrue(nav.unsupportedNotice)
    }

    @Test
    fun badStack_isResetToHome() {
        val stack = NavBackStack<NavKey>(ScreenKey.More, ScreenKey.About)
        ElderNavigator(stack)
        assertEquals(listOf<NavKey>(ScreenKey.Home), stack.toList())
    }

    @Test
    fun supportedKeys() {
        assertTrue(ElderRoutes.isSupported(ScreenKey.Home))
        assertTrue(ElderRoutes.isSupported(ScreenKey.Search))
        assertTrue(ElderRoutes.isSupported(line49))
        assertTrue(ElderRoutes.isSupported(stop))
        assertTrue(ElderRoutes.isSupported(ScreenKey.RouteDetail("g1")))
        assertTrue(ElderRoutes.isSupported(ScreenKey.More))
        assertTrue(ElderRoutes.isSupported(ScreenKey.About))
        assertFalse(ElderRoutes.isSupported(ScreenKey.RoutePlan))
        assertFalse(ElderRoutes.isSupported(ScreenKey.FavoritesEdit))
        assertFalse(ElderRoutes.isSupported(ScreenKey.ReminderEdit("r1")))
        assertEquals(ScreenKey.Home, ElderRoutes.map(ScreenKey.RoutePlan))
        assertEquals(line49, ElderRoutes.map(line49))
    }
}
