package com.trackr.ui.components.transitcode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 乘车码悬浮按钮（v3）：往下滑缩小、往上滑展开，点一下就打开 */
@RunWith(RobolectricTestRunner::class)
class TransitCodeFabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var state: TransitCodeFabState
    private var clicks = 0

    private fun setContent() {
        composeTestRule.setContent {
            TrackrTheme {
                state = rememberTransitCodeFabState()
                Box(modifier = Modifier.fillMaxSize().nestedScroll(state.nestedScrollConnection)) {
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("list")) {
                        items((1..100).toList()) { Text("第 $it 行", modifier = Modifier.height(48.dp)) }
                    }
                    TransitCodeFab(
                        expanded = state.expanded,
                        onClick = { clicks++ },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
        }
    }

    @Test
    fun startsExpanded_withLabel_andOneTapOpens() {
        setContent()

        // Robolectric 里字宽近似为 0，只断言文字在、按钮可见
        assertTrue(state.expanded)
        composeTestRule.onNodeWithText("乘车码", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(TRANSIT_CODE_FAB_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TRANSIT_CODE_FAB_TAG).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun scrollDown_collapsesToIcon_scrollUp_expandsAgain() {
        setContent()

        // 手指上推 = 内容往下翻：缩成图标
        composeTestRule.onNodeWithTag("list").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        assertFalse(state.expanded)
        // 缩小后读屏仍读「乘车码」，还能点
        composeTestRule.onNodeWithContentDescription("乘车码").performClick()
        assertEquals(1, clicks)

        // 手指下拉 = 往回翻：展开
        composeTestRule.onNodeWithTag("list").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        assertTrue(state.expanded)
    }

    @Test
    fun smallJitter_doesNotToggle() {
        val fab = TransitCodeFabState()
        fab.onScrolled(consumedY = -3f)
        assertTrue(fab.expanded)
        fab.onScrolled(consumedY = -20f)
        assertFalse(fab.expanded)
        fab.onScrolled(consumedY = 5f)
        assertFalse(fab.expanded)
        // 在顶部继续往下拉（没滚动，只有剩余量）也展开
        fab.onScrolled(consumedY = 0f, availableY = 30f)
        assertTrue(fab.expanded)
    }
}
