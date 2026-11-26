package com.trackr.app

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.UiMode
import com.trackr.core.testing.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 系统字体放大时的「试试长辈版」提示（第 30 步，重构计划 §8.2、§8.7）：
 * ≥ 1.3 倍且没点过才弹；点「先不用」后不再弹；点「打开长辈版」切到长辈版主题；1.0 不弹。
 * 用真的 [AppShellViewModel] + 内存设置，走和 [TrackrRoot] 一样的接法。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FontScalePromptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val title = "字太小看不清？"

    /** 和 TrackrRoot 一样：按壳状态换主题，提示在主题里面 */
    @Composable
    private fun Shell(viewModel: AppShellViewModel, fontScale: Float, onTouchTarget: (Dp) -> Unit = {}) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
            val shell by viewModel.shell.collectAsStateWithLifecycle()
            TrackrTheme(uiMode = shell.uiMode) {
                onTouchTarget(LocalMinimumInteractiveComponentSize.current)
                FontScalePrompt(
                    dismissed = shell.fontScalePromptDismissed,
                    onEnableElder = viewModel::enableElderFromPrompt,
                    onDismiss = viewModel::dismissFontScalePrompt
                )
            }
        }
    }

    @Test
    fun largeFont_showsPrompt_notNowHidesForGood() {
        val settings = FakeSettingsRepository()
        val viewModel = AppShellViewModel(settings)
        composeTestRule.setContent { Shell(viewModel, fontScale = 1.5f) }

        composeTestRule.onNodeWithText(title).assertExists()
        // 第 33 步有了朗读，文案加回「还能读给你听」（设计稿「开启入口」）
        composeTestRule.onNodeWithText("还能读给你听", substring = true).assertExists()
        composeTestRule.onNodeWithText("先不用").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(title).assertDoesNotExist()
        assertTrue(settings.fontScalePromptDismissed.value)
        assertEquals(UiMode.Standard, settings.uiMode.value)
    }

    @Test
    fun dismissedBefore_neverShowsAgain() {
        val settings = FakeSettingsRepository().apply { dismissFontScalePrompt() }
        composeTestRule.setContent { Shell(AppShellViewModel(settings), fontScale = 2.0f) }
        composeTestRule.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun normalFont_noPrompt() {
        val settings = FakeSettingsRepository()
        composeTestRule.setContent { Shell(AppShellViewModel(settings), fontScale = 1.0f) }
        composeTestRule.onNodeWithText(title).assertDoesNotExist()
        assertFalse(settings.fontScalePromptDismissed.value)
    }

    @Test
    fun threshold_isInclusive() {
        composeTestRule.setContent { Shell(AppShellViewModel(FakeSettingsRepository()), fontScale = FONT_SCALE_PROMPT_THRESHOLD) }
        composeTestRule.onNodeWithText(title).assertExists()
    }

    @Test
    fun enableElder_switchesThemeAndHidesPrompt() {
        val settings = FakeSettingsRepository()
        val viewModel = AppShellViewModel(settings)
        var touchTarget = 0.dp
        composeTestRule.setContent { Shell(viewModel, fontScale = 1.5f) { touchTarget = it } }
        composeTestRule.waitForIdle()
        assertEquals(48.dp, touchTarget)

        composeTestRule.onNodeWithText("打开长辈版").performClick()
        composeTestRule.waitForIdle()

        assertEquals(UiMode.Elder, settings.uiMode.value)
        assertTrue(settings.fontScalePromptDismissed.value)
        assertEquals(64.dp, touchTarget)
        composeTestRule.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun shell_followsSettings() {
        val settings = FakeSettingsRepository()
        val viewModel = AppShellViewModel(settings)
        assertEquals(AppShellState(UiMode.Standard, fontScalePromptDismissed = false), viewModel.shell.value)

        settings.setUiMode(UiMode.Elder)
        composeTestRule.waitForIdle()
        assertEquals(UiMode.Elder, viewModel.shell.value.uiMode)
    }
}
