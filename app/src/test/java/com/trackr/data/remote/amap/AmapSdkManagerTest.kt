package com.trackr.data.remote.amap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun getEffectiveWebServiceKey_prefersUserCustomKey() {
        assertEquals(
            "web_key_123",
            AmapSdkManager.getEffectiveWebServiceKey("web_key_123")
        )
    }

    @Test
    fun getEffectiveWebServiceKey_fallsBackToBuildConfig() {
        assertEquals(
            BuildConfig.AMAP_WEB_KEY.trim(),
            AmapSdkManager.getEffectiveWebServiceKey(null)
        )
    }

    @Test
    fun androidAndWebKeys_stayInSeparateSlots() {
        val storePrefs = AmapKeyStore.prefs(context)
        AmapKeyStore.writeAndroidKey(context, "android_key_abc")
        AmapKeyStore.writeWebKey(context, "web_key_xyz")

        assertEquals("android_key_abc", AmapKeyStore.readAndroidKey(context))
        assertEquals("web_key_xyz", AmapKeyStore.readWebKey(context))

        AmapKeyStore.writeAndroidKey(context, "")
        AmapKeyStore.writeWebKey(context, "")
        storePrefs?.edit()?.clear()?.apply()
    }

    @Test
    fun readAndroidKey_doesNotFallBackToWebSlot() {
        val storePrefs = AmapKeyStore.prefs(context)
        storePrefs?.edit()?.clear()?.commit()
        // 只配置了 Web 服务 Key（或旧版本单输入框留下的值）
        AmapKeyStore.writeWebKey(context, "web_only_key")

        assertNull(AmapKeyStore.readAndroidKey(context))
        assertEquals("web_only_key", AmapKeyStore.readWebKey(context))

        storePrefs?.edit()?.clear()?.commit()
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
        if (AmapSdkManager.isRuntimeAbiSupported() && BuildConfig.AMAP_ANDROID_KEY.isBlank()) {
            val reason = AmapSdkManager.mapUnavailableReason("")
            assertTrue(reason != null && reason.contains("Android Key"))
        }
    }
}
