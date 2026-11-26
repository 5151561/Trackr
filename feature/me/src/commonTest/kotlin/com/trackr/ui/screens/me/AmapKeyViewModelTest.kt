package com.trackr.ui.screens.me

import com.trackr.core.data.repository.AmapKeyDefaults
import com.trackr.core.testing.FakeAmapKeyRepository
import com.trackr.core.testing.FakeAppSigningInfo
import com.trackr.core.testing.MainDispatcherHolder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmapKeyViewModelTest {

    private val main = MainDispatcherHolder()
    private val androidKey = "a".repeat(32)
    private val webKey = "w".repeat(32)

    private lateinit var amapKeys: FakeAmapKeyRepository
    private lateinit var notices: MeNotices

    @BeforeTest
    fun setup() {
        main.install()
        amapKeys = FakeAmapKeyRepository()
        notices = MeNotices()
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    private fun createViewModel(
        defaults: AmapKeyDefaults = AmapKeyDefaults.NONE,
        signing: FakeAppSigningInfo = FakeAppSigningInfo()
    ) = AmapKeyViewModel(amapKeys = amapKeys, signingInfo = signing, defaults = defaults, notices = notices)

    @Test
    fun showsPackageAndSha1_fromAppSigningInfo() {
        val state = createViewModel().uiState.value
        assertEquals("com.github.u5151561.trackr", state.appId)
        assertEquals("AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD", state.sha1)
        assertTrue(state.showAndroidSlot)
        assertFalse(state.canSave)
    }

    @Test
    fun iosHasNoAndroidSlotOrSha1() {
        val state = createViewModel(
            defaults = AmapKeyDefaults(hasAndroidMapSdk = false),
            signing = FakeAppSigningInfo(appId = "com.trackr.ios", sha1 = null)
        ).uiState.value
        assertFalse(state.showAndroidSlot)
        assertNull(state.sha1)
    }

    @Test
    fun onlyWebKeyChanged_writesOnlyWebSlot() {
        val viewModel = createViewModel()

        viewModel.onEvent(AmapKeyEvent.EditWebKey("  $webKey \n"))
        assertEquals(webKey, viewModel.uiState.value.webInput)
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.onEvent(AmapKeyEvent.Save)
        assertEquals(listOf<String?>(webKey), amapKeys.webKeyWrites)
        assertTrue(amapKeys.androidKeyWrites.isEmpty())
        assertTrue(amapKeys.hasMapKey.value)
        assertFalse(amapKeys.canShowMap.value)
        assertTrue(viewModel.uiState.value.closed)
        assertEquals("高德 Key 已保存：只有 Web 服务 Key：能算路线，不显示地图", notices.messages.value.single().text)
    }

    @Test
    fun clearingAndroidSlot_keepsWebSlot() {
        amapKeys = FakeAmapKeyRepository(androidKey = androidKey, webKey = webKey)
        val viewModel = createViewModel()
        assertEquals(androidKey, viewModel.uiState.value.androidInput)

        viewModel.onEvent(AmapKeyEvent.EditAndroidKey(""))
        viewModel.onEvent(AmapKeyEvent.Save)

        assertNull(amapKeys.androidKey.value)
        assertEquals(webKey, amapKeys.webKey.value)
        assertTrue(amapKeys.webKeyWrites.isEmpty())
    }

    @Test
    fun wrongLength_blocksSave_withHint() {
        val viewModel = createViewModel()
        viewModel.onEvent(AmapKeyEvent.EditAndroidKey("abc123"))

        val state = viewModel.uiState.value
        assertFalse(state.androidKeyValid)
        assertFalse(state.canSave)
        assertEquals("Key 是 32 位字母和数字，检查一下有没有粘全", state.androidHint)

        viewModel.onEvent(AmapKeyEvent.Save)
        assertTrue(amapKeys.androidKeyWrites.isEmpty())
        assertFalse(viewModel.uiState.value.closed)
    }

    @Test
    fun hints_followInputAndBuiltInKeys() {
        val viewModel = createViewModel(defaults = AmapKeyDefaults(webKey = webKey))
        assertEquals("不填就不显示地图", viewModel.uiState.value.androidHint)
        // 构建时带了 Web Key：没填也算有
        assertEquals("填了，用它搜地点、算路线", viewModel.uiState.value.webHint)

        viewModel.onEvent(AmapKeyEvent.EditAndroidKey(androidKey))
        assertEquals("填了，路线页显示地图", viewModel.uiState.value.androidHint)
    }

    @Test
    fun copyAndConsoleFailure_showMessages() {
        val viewModel = createViewModel()
        viewModel.onEvent(AmapKeyEvent.Copied("包名和 SHA-1"))
        viewModel.onEvent(AmapKeyEvent.OpenConsoleFailed)

        val texts = viewModel.uiState.value.userMessages.map { it.text }
        assertEquals(listOf("包名和 SHA-1已复制", "没找到浏览器，请手动打开 console.amap.com"), texts)

        viewModel.onEvent(AmapKeyEvent.MessageShown(viewModel.uiState.value.userMessages.first().id))
        assertEquals(1, viewModel.uiState.value.userMessages.size)
    }
}
