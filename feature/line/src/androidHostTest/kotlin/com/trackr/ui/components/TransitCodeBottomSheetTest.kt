package com.trackr.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.TransitCodeProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransitCodeBottomSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testTransitCodeBottomSheet_showsProvidersAndSelectsAlipay() {
        var selected: Pair<TransitCodeProvider, Boolean>? = null

        composeTestRule.setContent {
            TrackrTheme {
                TransitCodeBottomSheet(
                    onDismiss = {},
                    onSelectProvider = { provider, saveAsDefault -> selected = provider to saveAsDefault }
                )
            }
        }

        composeTestRule.onNodeWithText("支付宝乘车码").assertIsDisplayed()
        composeTestRule.onNodeWithText("云闪付乘车码").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(1_000L)
        composeTestRule.waitForIdle()
        // Robolectric 里弹层在单独窗口，坐标点击偶尔落不到；直接触发选项卡的点击动作
        composeTestRule.onNodeWithText("支付宝乘车码").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals(TransitCodeProvider.ALIPAY to false, selected)
    }
}
