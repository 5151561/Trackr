package com.trackr.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.designsystem.theme.TrackrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 首次打开时字号提示不能叠在定位说明上（第 30 步遗留）：定位说明、以及它发出的系统权限申请都结束后，
 * 「字太小看不清？」才弹出来。和 [TrackrRoot] 一样，提示放在 [LocationPermissionOnboarding] 的 afterOnboarding 里。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnboardingDialogOrderTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fontPrompt = "字太小看不清？"
    private val disclosure = "位置信息权限说明"
    private var locationGrantedCalls = 0

    private fun grant(vararg permissions: String) {
        shadowOf(composeTestRule.activity.application).grantPermissions(*permissions)
    }

    private fun setContent() {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                TrackrTheme {
                    LocationPermissionOnboarding(onLocationGranted = { locationGrantedCalls++ }) {
                        FontScalePrompt(dismissed = false, onEnableElder = {}, onDismiss = {})
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** 把最近一次系统权限申请的结果交回去（Robolectric 不会自己弹系统窗） */
    private fun answerPermissionRequest(granted: Boolean) {
        val request = shadowOf(composeTestRule.activity).lastRequestedPermission
        assertNotNull("应该已经发出系统权限申请", request)
        val results = IntArray(request.requestedPermissions.size) {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        composeTestRule.runOnIdle {
            composeTestRule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, results)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun locationDisclosureFirst_fontPromptAfterItCloses() {
        grant(Manifest.permission.POST_NOTIFICATIONS)
        setContent()

        composeTestRule.onNodeWithText(disclosure).assertExists()
        composeTestRule.onNodeWithText(fontPrompt).assertDoesNotExist()

        composeTestRule.onNodeWithText("暂不授权").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(disclosure).assertDoesNotExist()
        composeTestRule.onNodeWithText(fontPrompt).assertExists()
    }

    @Test
    fun systemPermissionRequestPending_fontPromptWaitsForResult() {
        // 没给通知权限：点「暂不授权」后还会申请通知权限，系统弹窗回来之前不弹字号提示
        setContent()
        composeTestRule.onNodeWithText("暂不授权").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(fontPrompt).assertDoesNotExist()

        answerPermissionRequest(granted = false)
        composeTestRule.onNodeWithText(fontPrompt).assertExists()
    }

    @Test
    fun acceptLocation_fontPromptAfterGrantResult() {
        grant(Manifest.permission.POST_NOTIFICATIONS)
        setContent()
        composeTestRule.onNodeWithText("同意并授权").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(disclosure).assertDoesNotExist()
        composeTestRule.onNodeWithText(fontPrompt).assertDoesNotExist()

        answerPermissionRequest(granted = true)
        assertEquals(1, locationGrantedCalls)
        composeTestRule.onNodeWithText(fontPrompt).assertExists()
    }

    @Test
    fun allPermissionsGranted_fontPromptRightAway() {
        grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        setContent()
        composeTestRule.onNodeWithText(disclosure).assertDoesNotExist()
        composeTestRule.onNodeWithText(fontPrompt).assertExists()
    }
}
