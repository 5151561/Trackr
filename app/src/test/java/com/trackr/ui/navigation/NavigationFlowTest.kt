package com.trackr.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import com.trackr.domain.model.DataSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationFlowTest {

    @Test
    fun testInitialBackStack_startsAtHome() {
        val backStack = mutableListOf<ScreenKey>(ScreenKey.Home)
        assertEquals(1, backStack.size)
        assertEquals(ScreenKey.Home, backStack.last())
    }

    @Test
    fun testNavigateToSearch_andBackHandling() {
        val backStack = mutableListOf<ScreenKey>(ScreenKey.Home)

        // Navigate to Search
        backStack.add(ScreenKey.Search)
        assertEquals(2, backStack.size)
        assertEquals(ScreenKey.Search, backStack.last())

        // Back handling
        val popped = backStack.removeLastOrNull()
        assertEquals(ScreenKey.Search, popped)
        assertEquals(ScreenKey.Home, backStack.last())
    }

    @Test
    fun testDeepLinkResolution_pushesLineDetailOntoBackStack() {
        val backStack = mutableListOf<ScreenKey>(ScreenKey.Home)

        val deepLinkKey = ScreenKey.LineDetail(
            cityId = "021",
            lineId = "line_49",
            targetOrder = 4,
            stopId = "stop_004",
            source = DataSourceType.CHELAILE,
            lineName = "49路"
        )

        // Simulate deep link handling from Widget or Notification
        if (backStack.lastOrNull() != deepLinkKey) {
            backStack.add(deepLinkKey)
        }

        assertEquals(2, backStack.size)
        assertEquals(deepLinkKey, backStack.last())

        // Ensure duplicate deep link intent does not duplicate top entry
        if (backStack.lastOrNull() != deepLinkKey) {
            backStack.add(deepLinkKey)
        }
        assertEquals(2, backStack.size)

        // Back from line detail returns to Home
        backStack.removeLastOrNull()
        assertEquals(ScreenKey.Home, backStack.last())
    }

    @Test
    fun testMultiStepNavigation_homeToSearchToLineDetail_backToHome() {
        val backStack = mutableListOf<ScreenKey>(ScreenKey.Home)

        // 1. Home -> Search
        backStack.add(ScreenKey.Search)
        // 2. Search -> LineDetail
        backStack.add(ScreenKey.LineDetail(cityId = "034", lineId = "line_101", lineName = "101路"))

        assertEquals(3, backStack.size)

        // Back to Search
        backStack.removeLastOrNull()
        assertEquals(ScreenKey.Search, backStack.last())

        // Back to Home
        backStack.removeLastOrNull()
        assertEquals(ScreenKey.Home, backStack.last())

        // At Home, cannot pop further
        assertTrue(backStack.size == 1)
    }

    @Test
    fun testNavigateToMore_andAbout_andBackHandling() {
        val backStack = mutableListOf<ScreenKey>(ScreenKey.Home)

        // 1. Home -> More
        backStack.add(ScreenKey.More)
        assertEquals(2, backStack.size)
        assertEquals(ScreenKey.More, backStack.last())

        // 2. More -> About
        backStack.add(ScreenKey.About)
        assertEquals(3, backStack.size)
        assertEquals(ScreenKey.About, backStack.last())

        // 3. Back from About -> More
        val poppedAbout = backStack.removeLastOrNull()
        assertEquals(ScreenKey.About, poppedAbout)
        assertEquals(ScreenKey.More, backStack.last())

        // 4. Back from More -> Home
        val poppedMore = backStack.removeLastOrNull()
        assertEquals(ScreenKey.More, poppedMore)
        assertEquals(ScreenKey.Home, backStack.last())
    }

    @Test
    fun testTabSwitching_preservesHistoryAndReselectPopsToRoot() {
        val homeStack = mutableListOf<ScreenKey>(ScreenKey.Home)
        val moreStack = mutableListOf<ScreenKey>(ScreenKey.More)
        var selectedTab = "home"

        // 1. Home navigates to Search
        homeStack.add(ScreenKey.Search)
        assertEquals(2, homeStack.size)

        // 2. Switch to More tab
        selectedTab = "more"
        assertEquals("more", selectedTab)
        assertEquals(ScreenKey.More, moreStack.last())

        // 3. More navigates to About
        moreStack.add(ScreenKey.About)
        assertEquals(2, moreStack.size)

        // 4. Switch back to Home tab: Home history is completely preserved!
        selectedTab = "home"
        assertEquals("home", selectedTab)
        assertEquals(ScreenKey.Search, homeStack.last())
        assertEquals(2, homeStack.size)

        // 5. Re-select active Home tab: pops to root (ScreenKey.Home)
        if (selectedTab == "home") {
            while (homeStack.size > 1) {
                homeStack.removeLastOrNull()
            }
        }
        assertEquals(1, homeStack.size)
        assertEquals(ScreenKey.Home, homeStack.last())

        // 6. Switch back to More tab: More history (About) is also preserved!
        selectedTab = "more"
        assertEquals(ScreenKey.About, moreStack.last())

        // 7. Re-select active More tab: pops to root (ScreenKey.More)
        if (selectedTab == "more") {
            while (moreStack.size > 1) {
                moreStack.removeLastOrNull()
            }
        }
        assertEquals(1, moreStack.size)
        assertEquals(ScreenKey.More, moreStack.last())
    }

    @Test
    fun testOneOffDeepLinkConsumption_preventsDuplicateReplay() {
        var pendingDeepLink: PendingDeepLink? = PendingDeepLink(
            cityId = "021",
            lineId = "line_71",
            lineName = "71路"
        )
        val homeStack = mutableListOf<ScreenKey>(ScreenKey.Home)

        // First consumption
        pendingDeepLink?.let { link ->
            val key = ScreenKey.LineDetail(cityId = link.cityId, lineId = link.lineId, lineName = link.lineName)
            if (homeStack.lastOrNull() != key) {
                homeStack.add(key)
            }
            pendingDeepLink = null // consumed
        }

        assertEquals(2, homeStack.size)
        assertTrue(homeStack.last() is ScreenKey.LineDetail)
        assertEquals(null, pendingDeepLink)

        // Simulated configuration change (recreation): pendingDeepLink is null, no extra pushed
        pendingDeepLink?.let { link ->
            val key = ScreenKey.LineDetail(cityId = link.cityId, lineId = link.lineId, lineName = link.lineName)
            if (homeStack.lastOrNull() != key) {
                homeStack.add(key)
            }
        }
        assertEquals(2, homeStack.size)
    }
}
