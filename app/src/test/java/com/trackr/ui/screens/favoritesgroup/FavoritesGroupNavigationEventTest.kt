package com.trackr.ui.screens.favoritesgroup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.data.remote.amap.AmapTipItem
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.ui.screens.favoritesgroup.components.AmapRoutePlanDialog
import com.trackr.ui.screens.favoritesgroup.components.GroupEditDialog
import kotlinx.coroutines.Dispatchers
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

    @Test
    fun amapRoutePlanDialog_backDismissesTipsBeforeClosingDialog() {
        var dismissed = false
        var clearedOriginTips = false
        var clearedDestTips = false

        composeTestRule.setContent {
            AmapRoutePlanDialog(
                originTips = listOf(AmapTipItem(name = "杭州东站", location = "120.2,30.3")),
                destTips = emptyList(),
                plannedSchemes = emptyList(),
                isPlanning = false,
                onSearchOrigin = {},
                onSearchDest = {},
                onPlanRoute = { _, _ -> },
                onAddScheme = {},
                onStartTrackingScheme = {},
                onDismiss = { dismissed = true },
                onClearOriginTips = { clearedOriginTips = true },
                onClearDestTips = { clearedDestTips = true }
            )
        }

        // 此时由于 selectedOrigin == null && originTips 不为空，originTips 候选展示
        composeTestRule.onNodeWithText("杭州东站").assertIsDisplayed()

        // 触发一次返回事件
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // 验证：不应该关闭整个弹窗，而是应该先收起/清空候选建议
        assertFalse("Dialog should not be dismissed when suggestions are visible", dismissed)
        assertTrue("Origin tips should be cleared first", clearedOriginTips)
    }

    @Test
    fun amapRoutePlanDialog_lifoOrder_destTipsThenOriginTipsThenDismiss() {
        var dismissed = false
        var clearedOriginTips = false
        var clearedDestTips = false

        // 模拟起点和终点联想列表均存在
        composeTestRule.setContent {
            AmapRoutePlanDialog(
                originTips = listOf(AmapTipItem(name = "杭州东站", location = "120.2,30.3")),
                destTips = listOf(AmapTipItem(name = "西湖断桥", location = "120.1,30.2")),
                plannedSchemes = emptyList(),
                isPlanning = false,
                onSearchOrigin = {},
                onSearchDest = {},
                onPlanRoute = { _, _ -> },
                onAddScheme = {},
                onStartTrackingScheme = {},
                onDismiss = { dismissed = true },
                onClearOriginTips = { clearedOriginTips = true },
                onClearDestTips = { clearedDestTips = true }
            )
        }

        // 第 1 次返回：LIFO 优先收起终点联想
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertTrue("Dest tips must be cleared first", clearedDestTips)
        assertFalse("Origin tips should not be cleared yet", clearedOriginTips)
        assertFalse("Dialog must not be dismissed", dismissed)

        // 第 2 次返回：收起起点联想
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertTrue("Origin tips must now be cleared", clearedOriginTips)
        assertFalse("Dialog must not be dismissed yet", dismissed)

        // 第 3 次：没有任何联想展示，点击关闭按键可正常关闭弹窗
        composeTestRule.onNodeWithText("关闭").performClick()
        assertTrue("Dialog must be dismissed on close", dismissed)
    }

    @Test
    fun favoritesGroupScreen_onResume_checksClipboardForShareContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("share", "【高德地图】从杭州东站到西湖 https://surl.amap.com/abc")
        clipboard.setPrimaryClip(clipData)

        val groupRepository = RouteGroupRepository(context, Dispatchers.Main)
        val fakeTransitRepository = FakeTransitRepository()
        val viewModel = FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            enableAutoRefresh = false
        )

        composeTestRule.setContent {
            FavoritesGroupScreen(viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        // 验证 LifecycleEventEffect(ON_RESUME) 正确触发剪贴板识别并展示卡片
        composeTestRule.onNodeWithText("检测到地图分享路线").assertIsDisplayed()
    }
}

