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
}
