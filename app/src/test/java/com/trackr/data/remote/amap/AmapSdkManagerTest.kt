package com.trackr.data.remote.amap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AmapSdkManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun getEffectiveApiKey_prioritizesUserCustomKey() {
        val userKey = "user_custom_test_key_12345"
        val effective = AmapSdkManager.getEffectiveApiKey(userKey)
        assertEquals(userKey, effective)
    }

    @Test
    fun getEffectiveApiKey_fallbacksToBuildConfig_whenUserKeyEmpty() {
        val effective = AmapSdkManager.getEffectiveApiKey("")
        assertEquals(BuildConfig.AMAP_ANDROID_KEY.trim(), effective)
    }

    @Test
    fun ensurePrivacyCompliance_executesWithoutCrash() {
        AmapSdkManager.ensurePrivacyCompliance(context)
    }

    @Test
    fun applyApiKey_withValidKey_returnsTrueOrFalseSafely() {
        // Robolectric 模拟环境下验证调用不抛异常
        val res = AmapSdkManager.applyApiKey(context, "dummy_key_1234567890abcdef")
        // 若 SDK 在无 native 库环境下成功设置即为 true
        assertTrue(res)
    }

    @Test
    fun applyApiKey_withEmptyKey_whenNoBuildConfig_returnsFalse() {
        if (BuildConfig.AMAP_ANDROID_KEY.isBlank()) {
            val res = AmapSdkManager.applyApiKey(context, "")
            assertFalse(res)
        }
    }
}
