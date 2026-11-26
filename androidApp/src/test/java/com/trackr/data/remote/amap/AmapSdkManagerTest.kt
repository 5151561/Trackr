package com.trackr.data.remote.amap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 高德 Key 本地持久化（[AmapKeyStorage]）。
 * SDK 封装（[AmapSdkManager]）的用例第 17 步随代码搬到 `:feature:route` 的 androidHostTest。
 */
@RunWith(RobolectricTestRunner::class)
class AmapSdkManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun androidAndWebKeys_stayInSeparateSlots() {
        val storePrefs = AmapKeyStorage.prefs(context)
        AmapKeyStorage.writeAndroidKey(context, "android_key_abc")
        AmapKeyStorage.writeWebKey(context, "web_key_xyz")

        assertEquals("android_key_abc", AmapKeyStorage.readAndroidKey(context))
        assertEquals("web_key_xyz", AmapKeyStorage.readWebKey(context))

        AmapKeyStorage.writeAndroidKey(context, "")
        AmapKeyStorage.writeWebKey(context, "")
        storePrefs?.edit()?.clear()?.apply()
    }

    @Test
    fun readAndroidKey_doesNotFallBackToWebSlot() {
        val storePrefs = AmapKeyStorage.prefs(context)
        storePrefs?.edit()?.clear()?.commit()
        // 只配置了 Web 服务 Key（或旧版本单输入框留下的值）
        AmapKeyStorage.writeWebKey(context, "web_only_key")

        assertNull(AmapKeyStorage.readAndroidKey(context))
        assertEquals("web_only_key", AmapKeyStorage.readWebKey(context))

        storePrefs?.edit()?.clear()?.commit()
    }

    @Test
    fun prefs_movesLegacyKeysOutOfTrackrPrefsOnce() {
        val legacy = context.getSharedPreferences(AmapKeyStorage.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val keyFile = context.getSharedPreferences(AmapKeyStorage.PREFS_NAME, Context.MODE_PRIVATE)
        keyFile.edit().clear().commit()
        legacy.edit().clear()
            .putString(AmapKeyStorage.KEY_ANDROID, "legacy_android")
            .putString(AmapKeyStorage.KEY_WEB, "legacy_web")
            .putString("selected_city_id", "033")
            .commit()

        assertEquals("legacy_android", AmapKeyStorage.readAndroidKey(context))
        assertEquals("legacy_web", AmapKeyStorage.readWebKey(context))
        // Key 已从普通设置文件移除，其他设置原样保留
        assertNull(legacy.getString(AmapKeyStorage.KEY_ANDROID, null))
        assertNull(legacy.getString(AmapKeyStorage.KEY_WEB, null))
        assertEquals("033", legacy.getString("selected_city_id", null))

        // 只搬一次：之后旧文件里再出现的值不会覆盖专用文件
        legacy.edit().putString(AmapKeyStorage.KEY_ANDROID, "stale").commit()
        assertEquals("legacy_android", AmapKeyStorage.readAndroidKey(context))

        legacy.edit().clear().commit()
        keyFile.edit().clear().commit()
    }

    @Test
    fun migrateFromLegacy_keepsKeyAlreadyInNewFile() {
        val legacy = context.getSharedPreferences("migrate_legacy_test", Context.MODE_PRIVATE)
        val target = context.getSharedPreferences("migrate_target_test", Context.MODE_PRIVATE)
        legacy.edit().clear().putString(AmapKeyStorage.KEY_WEB, "old_web").commit()
        target.edit().clear().putString(AmapKeyStorage.KEY_WEB, "new_web").commit()

        AmapKeyStorage.migrateFromLegacy(legacy, target)

        assertEquals("new_web", target.getString(AmapKeyStorage.KEY_WEB, null))
        assertTrue(target.getBoolean(AmapKeyStorage.KEY_MIGRATED_FROM_LEGACY, false))
        assertNull(legacy.getString(AmapKeyStorage.KEY_WEB, null))
    }
}
