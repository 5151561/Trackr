package com.trackr.ui.components.message

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.UiMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8.7：标准版 Snackbar 到时自己消失；长辈版推进 10 秒后还在，点「知道了」才消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class UserMessageHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val results = mutableListOf<Pair<Any, Boolean>>()

    /** 一个最简单的页面：消息放在列表里，宿主显示第一条，回报后删掉 */
    @Composable
    private fun Page(uiMode: UiMode, initial: List<HostMessage>) {
        TrackrTheme(uiMode = uiMode, darkTheme = false) {
            val messages = remember { mutableStateListOf<HostMessage>().apply { addAll(initial) } }
            Scaffold(
                snackbarHost = {
                    UserMessageHost(
                        message = messages.firstOrNull(),
                        onResult = { key, action ->
                            results += key to action
                            messages.removeAll { it.key == key }
                        }
                    )
                }
            ) { Text("页面") }
        }
    }

    @Test
    fun standard_snackbarGoesAwayByItself() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { Page(UiMode.Standard, listOf(HostMessage(1L, "已收藏"))) }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onNodeWithText("已收藏").assertIsDisplayed()
        // 不是对话框
        composeTestRule.onNode(isDialog()).assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(6_000)
        composeTestRule.onNodeWithText("已收藏").assertDoesNotExist()
        assertEquals(listOf<Pair<Any, Boolean>>(1L to false), results)
    }

    @Test
    fun standard_showsOneAfterAnother() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Page(UiMode.Standard, listOf(HostMessage(1L, "第一条"), HostMessage(2L, "第二条")))
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onNodeWithText("第一条").assertIsDisplayed()
        composeTestRule.onNodeWithText("第二条").assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(6_000)
        composeTestRule.onNodeWithText("第二条").assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(6_000)
        assertEquals(listOf<Any>(1L, 2L), results.map { it.first })
    }

    @Test
    fun standard_actionReported() {
        composeTestRule.setContent {
            Page(UiMode.Standard, listOf(HostMessage(7L, "已收藏", actionLabel = "换分组")))
        }
        composeTestRule.onNodeWithText("换分组").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf<Pair<Any, Boolean>>(7L to true), results)
    }

    @Test
    fun elder_staysAfter10Seconds_untilGotIt() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { Page(UiMode.Elder, listOf(HostMessage("k", "这台手机读不了"))) }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onNodeWithText("这台手机读不了").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(10_000)
        composeTestRule.onNodeWithText("这台手机读不了").assertIsDisplayed()
        assertEquals(emptyList<Pair<Any, Boolean>>(), results)

        composeTestRule.onNodeWithText("知道了").assertHeightIsAtLeast(64.dp).performClick()
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onNodeWithText("这台手机读不了").assertDoesNotExist()
        assertEquals(listOf<Pair<Any, Boolean>>("k" to false), results)
    }

    @Test
    fun elder_noActionButton_nextMessageAfterGotIt() {
        composeTestRule.setContent {
            Page(UiMode.Elder, listOf(HostMessage(1L, "已收藏", actionLabel = "换分组"), HostMessage(2L, "第二条")))
        }
        composeTestRule.onNodeWithText("换分组").assertDoesNotExist()
        composeTestRule.onNodeWithText("第二条").assertDoesNotExist()
        composeTestRule.onNodeWithText("知道了").performClick()
        composeTestRule.onNodeWithText("第二条").assertIsDisplayed()
    }
}
