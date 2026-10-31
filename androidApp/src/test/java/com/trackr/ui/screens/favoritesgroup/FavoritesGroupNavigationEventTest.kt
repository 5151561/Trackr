package com.trackr.ui.screens.favoritesgroup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.domain.model.TransitCity
import com.trackr.ui.screens.favoritesgroup.components.GroupEditDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FavoritesGroupNavigationEventTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun groupEditDialog_whenClean_dismissesDirectlyOnCancel() {
        var dismissed = false
        composeTestRule.setContent {
            GroupEditDialog(
                initialGroup = null,
                onDismiss = { dismissed = true },
                onSave = {}
            )
        }

        composeTestRule.onNodeWithText("取消").performClick()
        assertTrue("Clean form should dismiss directly on cancel", dismissed)
    }

    @Test
    fun groupEditDialog_whenDirty_showsDiscardConfirmationDialogOnCancel() {
        var dismissed = false
        composeTestRule.setContent {
            GroupEditDialog(
                initialGroup = null,
                onDismiss = { dismissed = true },
                onSave = {}
            )
        }

        // 修改起点站
        composeTestRule.onNodeWithText("起点站").performTextInput("武林广场")

        // 点击取消
        composeTestRule.onNodeWithText("取消").performClick()

        // 应弹出确认对话框，而不是直接 dismiss
        assertFalse("Dirty form should not dismiss immediately", dismissed)
        composeTestRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()

        // 点击继续编辑，对话框消失，仍留在表单
        composeTestRule.onNodeWithText("继续编辑").performClick()
        assertFalse(dismissed)
        composeTestRule.onNodeWithText("武林广场").assertIsDisplayed()

        // 再次点击取消并确认放弃修改
        composeTestRule.onNodeWithText("取消").performClick()
        composeTestRule.onNodeWithText("放弃修改").performClick()
        assertTrue("Dismiss callback should be called after confirming discard", dismissed)
    }

    @Test
    fun groupEditDialog_whenDirty_backKeyTriggersDiscardConfirmation() {
        var dismissed = false
        composeTestRule.setContent {
            GroupEditDialog(
                initialGroup = null,
                onDismiss = { dismissed = true },
                onSave = {}
            )
        }

        // 修改起点站
        composeTestRule.onNodeWithText("起点站").performTextInput("武林广场")
        composeTestRule.waitForIdle()

        // 模拟物理返回按键/手势
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // 应拦截并弹出确认框
        assertFalse(dismissed)
        composeTestRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()

        // 确认放弃修改
        composeTestRule.onNodeWithText("放弃修改").performClick()
        assertTrue(dismissed)
    }
}
