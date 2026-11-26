package com.trackr.data.remote.amap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 高德 SDK 封装（第 17 步从 `:androidApp` 搬来）。内置 Key 不再读 `BuildConfig`，
 * 由测试经 [AmapSdkManager.setBuiltInKeys] 设定；每条用例前后清空，不受本机 `local.properties` 影响。
 */
@RunWith(RobolectricTestRunner::class)
class AmapSdkManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AmapSdkManager.setBuiltInKeys(androidKey = "", webKey = "")
    }

    @After
    fun tearDown() {
        AmapSdkManager.setBuiltInKeys(androidKey = "", webKey = "")
    }

    @Test
    fun getEffectiveApiKey_prioritizesUserCustomKey() {
        AmapSdkManager.setBuiltInKeys(androidKey = "built_in_android_key", webKey = "built_in_web_key")
        val userKey = "user_custom_test_key_12345"
        val effective = AmapSdkManager.getEffectiveApiKey(userKey)
        assertEquals(userKey, effective)
    }

    @Test
    fun getEffectiveApiKey_fallbacksToBuiltInKey_whenUserKeyEmpty() {
        AmapSdkManager.setBuiltInKeys(androidKey = "  built_in_android_key  ", webKey = "built_in_web_key")
        val effective = AmapSdkManager.getEffectiveApiKey("")
        assertEquals("built_in_android_key", effective)
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
    fun applyApiKey_withEmptyKey_whenNoBuiltInKey_returnsFalse() {
        val res = AmapSdkManager.applyApiKey(context, "")
        assertFalse(res)
    }

    @Test
    fun getEffectiveWebServiceKey_prefersUserCustomKey() {
        AmapSdkManager.setBuiltInKeys(androidKey = "built_in_android_key", webKey = "built_in_web_key")
        assertEquals(
            "web_key_123",
            AmapSdkManager.getEffectiveWebServiceKey("web_key_123")
        )
    }

    @Test
    fun getEffectiveWebServiceKey_fallsBackToBuiltInKey() {
        AmapSdkManager.setBuiltInKeys(androidKey = "built_in_android_key", webKey = " built_in_web_key ")
        assertEquals(
            "built_in_web_key",
            AmapSdkManager.getEffectiveWebServiceKey(null)
        )
    }

    @Test
    fun mapUnavailableReason_isNullWhenKeyAndAbiAvailable() {
        if (AmapSdkManager.isRuntimeAbiSupported()) {
            assertNull(AmapSdkManager.mapUnavailableReason("dummy_key_1234567890abcdef"))
        }
    }

    @Test
    fun mapUnavailableReason_mentionsAbiOnUnsupportedEmulator() {
        if (!AmapSdkManager.isRuntimeAbiSupported()) {
            val reason = AmapSdkManager.mapUnavailableReason("dummy_key_1234567890abcdef")
            assertTrue(reason != null && reason.contains("ABI"))
        }
    }

    @Test
    fun mapUnavailableReason_mentionsKeyWhenKeyMissing() {
        // 内置 Key 已在 setUp 清空
        if (AmapSdkManager.isRuntimeAbiSupported()) {
            val reason = AmapSdkManager.mapUnavailableReason("")
            assertTrue(reason != null && reason.contains("Android Key"))
        }
    }
}
