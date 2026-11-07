package com.trackr.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.ThemeMode
import com.trackr.core.model.UiMode
import com.trackr.core.testing.FakeAmapKeyRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.ui.screens.me.MeNotices
import com.trackr.ui.screens.me.MeScreen
import com.trackr.ui.screens.me.MeViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 真实设置事件 → 仓库 → 根主题，以及主题切换时系统栏图标的对比度。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight")
class ThemeModeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settings = FakeSettingsRepository()
    private var background = Color.Unspecified
    private var isDark = false

    private fun show() {
        val shellViewModel = AppShellViewModel(settings)
        val meViewModel = MeViewModel(settings, FakeScheduledReminderRepository(), FakeAmapKeyRepository(), MeNotices())
        rule.setContent {
            val shell by shellViewModel.shell.collectAsStateWithLifecycle()
            val me by meViewModel.uiState.collectAsStateWithLifecycle()
            TrackrTheme(uiMode = shell.uiMode, themeMode = shell.themeMode) {
                val currentBackground = MaterialTheme.colorScheme.background
                val currentDark = TrackrTheme.isDark
                SideEffect {
                    background = currentBackground
                    isDark = currentDark
                }
                PlatformHost(enabled = false) {
                    MeScreen(state = me, onEvent = meViewModel::onEvent)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun choose(label: String) {
        rule.onNodeWithText("深浅色模式").performClick()
        rule.onNode(hasText(label) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    private fun assertTheme(dark: Boolean, expectedBackground: Color) {
        assertEquals(dark, isDark)
        assertEquals(expectedBackground, background)
        val window = rule.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(!dark, controller.isAppearanceLightStatusBars)
        assertEquals(!dark, controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun choicesUpdateWholeThemeAndSystemBars_withoutLeavingSettings() {
        show()
        assertTheme(dark = false, Color(0xFFF5F6F8))
        choose("深色")
        assertEquals(ThemeMode.Dark, settings.themeMode.value)
        assertTheme(dark = true, Color(0xFF0F1115))

        rule.onNodeWithText("深浅色模式").performClick()
        rule.onNode(hasText("深色") and hasAnyAncestor(isDialog())).assertIsSelected()
        rule.onNodeWithText("取消").performClick()
        assertEquals(ThemeMode.Dark, settings.themeMode.value)

        choose("浅色")
        assertTheme(dark = false, Color(0xFFF5F6F8))
        choose("跟随系统")
        assertEquals(ThemeMode.System, settings.themeMode.value)
        assertTheme(dark = false, Color(0xFFF5F6F8))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-night")
    fun systemDark_canBeOverriddenAndRestored() {
        show()
        assertTheme(dark = true, Color(0xFF0F1115))
        choose("浅色")
        assertTheme(dark = false, Color(0xFFF5F6F8))
        choose("跟随系统")
        assertTheme(dark = true, Color(0xFF0F1115))
    }

    @Test
    fun savedDarkChoice_appliesImmediatelyInElderMode() {
        settings.setUiMode(UiMode.Elder)
        settings.setThemeMode(ThemeMode.Dark)
        show()
        assertTheme(dark = true, Color.Black)
        choose("浅色")
        assertTheme(dark = false, Color(0xFFF2F3F5))
    }
}
