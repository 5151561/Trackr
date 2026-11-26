package com.trackr.feature.search.elder

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.core.app.ActivityOptionsCompat
import org.robolectric.RuntimeEnvironment
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.platform.SpeechInput
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.UiMode
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeSpeechInput
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.core.testing.RecordingSearchRealtimeRepository
import com.trackr.ui.components.elder.ELDER_BACK_TAG
import com.trackr.ui.components.elder.SHOW_TRANSIT_CODE_TAG
import com.trackr.ui.screens.search.SearchEvent
import com.trackr.ui.screens.search.SearchUiState
import com.trackr.ui.screens.search.SearchViewModel
import com.trackr.ui.screens.search.rememberSpeechInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 长辈版搜索（§8.7）：系统语音输入返回的文字进 `SearchViewModel`（测试用 [ActivityResultRegistry] 代替识别界面）；
 * 手机没有语音识别时不显示话筒；结果每个方向一行「始发 → 终点」；浅 / 深 × 字号 1.0 / 1.5 / 2.0 尺寸检查并截图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElderSearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<SearchEvent>()
    private var transitCodeClicks = 0

    private val city = TransitCity(cityId = "028", cityName = "深圳")
    private val forward = BusLineDirection(
        lineId = "325-0", name = "325", startStop = "白石洲", endStop = "科技园", firstTime = "06:30", lastTime = "22:30"
    )
    private val backward = BusLineDirection(lineId = "325-1", name = "325", startStop = "科技园", endStop = "白石洲")
    private val stop = NearbyStop(
        stopId = "s1",
        name = "325公交总站",
        distanceMeters = 350,
        lines = listOf(NearbyStopLineStatus(line = forward, targetOrder = 1, targetStopId = "s1", targetStopName = "325公交总站"))
    )
    private val result = TransitSearchResult(
        keyword = "325",
        lines = listOf(BusLineSummary(name = "325", lineNo = "325", directions = listOf(forward, backward))),
        stops = listOf(stop)
    )

    private fun resultState() = SearchUiState(currentCity = city, searchQuery = "325", searchResult = result)

    @Composable
    private fun ElderSearch(
        state: SearchUiState,
        queryText: String,
        speechInput: SpeechInput?,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        onEvent: (SearchEvent) -> Unit = { events += it }
    ) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = darkTheme) {
                ElderSearchScreen(
                    state = state,
                    queryText = queryText,
                    onEvent = onEvent,
                    speechInput = speechInput,
                    onOpenTransitCode = { transitCodeClicks++ }
                )
            }
        }
    }

    // ---- 尺寸 + 截图 ----

    @Test
    fun light_fontScale1() = assertFits(1f, false)

    @Test
    fun light_fontScale15() = assertFits(1.5f, false)

    @Test
    fun light_fontScale2() = assertFits(2f, false)

    @Test
    fun dark_fontScale1() = assertFits(1f, true)

    @Test
    fun dark_fontScale15() = assertFits(1.5f, true)

    @Test
    fun dark_fontScale2() = assertFits(2f, true)

    private fun assertFits(scale: Float, darkTheme: Boolean) {
        composeTestRule.setContent {
            ElderSearch(resultState(), "325", FakeSpeechInput(), fontScale = scale, darkTheme = darkTheme)
        }
        ElderScreenshots.capture(composeTestRule, "search", scale, darkTheme)
        listOf(ElderSearchTags.SPEAK, rowTag("line:CUSTOM:325-1"), rowTag("stop:CUSTOM:s1")).forEach { tag ->
            composeTestRule.onNodeWithTag(ElderSearchTags.LIST).performScrollToNode(hasTestTag(tag))
            ElderLayoutChecks.assertClickablesAtLeast64dp(composeTestRule)
            ElderLayoutChecks.assertNoTextClipped(composeTestRule, scale)
            composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertIsDisplayed()
        }
    }

    private fun rowTag(key: String) = ElderSearchTags.row(key)

    // ---- 内容 ----

    @Test
    fun results_oneRowPerDirection_withArrow() {
        composeTestRule.setContent { ElderSearch(resultState(), "325", speechInput = null) }

        composeTestRule.onNodeWithText("找到的线路").assertIsDisplayed()
        composeTestRule.onNodeWithText("白石洲 → 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("科技园 → 白石洲").assertIsDisplayed()
        composeTestRule.onNodeWithText("找到的车站").assertExists()

        composeTestRule.onNodeWithText("科技园 → 白石洲").performClick()
        assertEquals(SearchEvent.LineClicked(backward), events.last())

        composeTestRule.onNodeWithTag(ElderSearchTags.LIST).performScrollToNode(hasTestTag(rowTag("stop:CUSTOM:s1")))
        composeTestRule.onNodeWithText("325公交总站").performClick()
        assertEquals(SearchEvent.StopClicked(stop), events.last())
    }

    @Test
    fun noQuery_recentWordsAreBigRows_noDelete() {
        composeTestRule.setContent {
            ElderSearch(SearchUiState(currentCity = city, searchHistory = listOf("325", "科技园")), "", FakeSpeechInput())
        }
        composeTestRule.onNodeWithText("最近搜过").assertIsDisplayed()
        composeTestRule.onNodeWithText("删除 325").assertDoesNotExist()
        composeTestRule.onNodeWithText("清空").assertDoesNotExist()
        ElderLayoutChecks.assertClickablesAtLeast64dp(composeTestRule)

        composeTestRule.onNodeWithTag(ElderSearchTags.history("科技园")).performClick()
        assertEquals(SearchEvent.HistoryClicked("科技园"), events.last())
    }

    @Test
    fun emptyResult_saysWhatWasNotFound() {
        composeTestRule.setContent {
            ElderSearch(SearchUiState(currentCity = city, searchQuery = "999", searchResult = TransitSearchResult("999")), "999", null)
        }
        composeTestRule.onNodeWithText("没找到和「999」有关的线路或车站").assertIsDisplayed()
        composeTestRule.onNodeWithText("试试只输路号数字（比如 20），或站名里的几个字").assertIsDisplayed()
    }

    @Test
    fun backAndTransitCode() {
        composeTestRule.setContent { ElderSearch(SearchUiState(currentCity = city), "", null) }
        composeTestRule.onNodeWithTag(ELDER_BACK_TAG).performClick()
        assertEquals(SearchEvent.Back, events.last())
        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).performClick()
        assertEquals(1, transitCodeClicks)
    }

    // ---- 语音 ----

    @Test
    fun speak_heardTextIsSearchedAndRemembered() {
        val speech = FakeSpeechInput(listOf("325"))
        composeTestRule.setContent { ElderSearch(SearchUiState(currentCity = city), "", speech) }

        composeTestRule.onNodeWithText("按这里说话").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, speech.listenCount)
        assertEquals(listOf(SearchEvent.QueryChanged("325"), SearchEvent.Submit), events)
    }

    @Test
    fun speak_cancelled_nothingHappens() {
        val speech = FakeSpeechInput(listOf(null))
        composeTestRule.setContent { ElderSearch(SearchUiState(currentCity = city), "", speech) }

        composeTestRule.onNodeWithText("按这里说话").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, speech.listenCount)
        assertTrue(events.isEmpty())
    }

    @Test
    fun noSpeechInput_noMicButton() {
        composeTestRule.setContent { ElderSearch(SearchUiState(currentCity = city), "", speechInput = null) }
        composeTestRule.onNodeWithText("按这里说话").assertDoesNotExist()
        composeTestRule.onNodeWithTag(ElderSearchTags.FIELD).assertIsDisplayed()
    }

    /** §8.7：识别界面（这里是测试用的 ActivityResultRegistry）返回「325路。」，查询词进了 SearchViewModel，也记进最近搜过 */
    @Test
    fun recognizerResult_reachesSearchViewModel() {
        installRecognizer()
        val registry = FakeRecognizerRegistry(Activity.RESULT_OK, arrayListOf("325路。"))
        val history = FakeSearchHistoryRepository()
        val viewModel = SearchViewModel(
            settings = FakeSettingsRepository(initialCity = city),
            realtime = RecordingSearchRealtimeRepository(),
            searchHistoryRepository = history,
            userLocationStore = FakeUserLocationStore()
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                ElderSearch(
                    state = SearchUiState(currentCity = city),
                    queryText = viewModel.queryText,
                    speechInput = rememberSpeechInput(),
                    // 和 SearchRoute 一样：改词交给 ViewModel，Submit 记进最近搜过
                    onEvent = { event ->
                        when (event) {
                            is SearchEvent.QueryChanged -> viewModel.updateSearchQuery(event.query)
                            SearchEvent.Submit -> viewModel.addSearchHistory(viewModel.uiState.value.searchQuery)
                            else -> events += event
                        }
                    }
                )
            }
        }

        composeTestRule.onNodeWithText("按这里说话").performClick()
        composeTestRule.waitForIdle()

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, registry.launched.single().action)
        assertEquals("zh-CN", registry.launched.single().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("325路", viewModel.queryText)
        assertEquals("325路", viewModel.uiState.value.searchQuery)
        composeTestRule.waitUntil { history.searchHistory.value == listOf("325路") }
    }

    @Test
    fun recognizerCancelled_queryUnchanged() {
        installRecognizer()
        val registry = FakeRecognizerRegistry(Activity.RESULT_CANCELED, null)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                ElderSearch(SearchUiState(currentCity = city), "", rememberSpeechInput())
            }
        }
        composeTestRule.onNodeWithText("按这里说话").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, registry.launched.size)
        assertTrue(events.isEmpty())
    }

    /** 手机上没有语音识别（没装或停用了系统语音输入）：不显示话筒，照样能打字 */
    @Test
    fun noRecognizerOnPhone_micHidden() {
        val registry = FakeRecognizerRegistry(Activity.RESULT_OK, arrayListOf("325"))
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                ElderSearch(SearchUiState(currentCity = city), "", rememberSpeechInput())
            }
        }
        composeTestRule.onNodeWithText("按这里说话").assertDoesNotExist()
        composeTestRule.onNodeWithTag(ElderSearchTags.FIELD).assertIsDisplayed()
    }

    /** 查的时候有、点的时候识别界面没了（被停用）：这次什么都不做，话筒藏起来 */
    @Test
    fun recognizerGoneWhenLaunching_micHidden() {
        installRecognizer()
        val registry = FakeRecognizerRegistry(Activity.RESULT_OK, arrayListOf("325"), throwOnLaunch = true)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                ElderSearch(SearchUiState(currentCity = city), "", rememberSpeechInput())
            }
        }
        composeTestRule.onNodeWithText("按这里说话").performClick()
        composeTestRule.waitForIdle()
        assertTrue(events.isEmpty())
        composeTestRule.onNodeWithText("按这里说话").assertDoesNotExist()
    }

    private fun installRecognizer() {
        val context = RuntimeEnvironment.getApplication()
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.recognizer"
                name = "com.example.recognizer.VoiceActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), info)
    }

    /** 代替系统识别界面：一打开就按给定结果返回 */
    private class FakeRecognizerRegistry(
        private val resultCode: Int,
        private val results: ArrayList<String>?,
        private val throwOnLaunch: Boolean = false
    ) : ActivityResultRegistry() {
        val launched = mutableListOf<Intent>()

        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry get() = this@FakeRecognizerRegistry
        }

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            if (throwOnLaunch) throw android.content.ActivityNotFoundException("no recognizer")
            launched += input as Intent
            val data = results?.let { Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, it) }
            dispatchResult(requestCode, resultCode, data)
        }
    }
}
