package com.trackr.data.remote.amap

import com.trackr.core.testing.FakeAmapKeyRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 第 11 步：在「更多」页改 Android Key 后由应用级收集器注入 SDK；启动时的值不重复注入，改 Web Key 不注入 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmapSdkKeySyncTest {

    @Test
    fun appliesOnlyAndroidKeyChanges_skippingStartupValue() = runTest {
        val keys = FakeAmapKeyRepository(androidKey = "startup_key")
        keys.setPrivacyConsent(true)
        val applied = mutableListOf<String?>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sync = AmapSdkKeySync(keys, { applied += it }, backgroundScope, dispatcher)

        sync.start()
        sync.start() // 重复调用不开第二个收集
        assertEquals(emptyList<String?>(), applied)

        keys.setWebKey("web_only")
        assertEquals(emptyList<String?>(), applied)

        keys.setAndroidKey("new_key")
        keys.setAndroidKey("")
        assertEquals(listOf("new_key", null), applied)
    }
}
