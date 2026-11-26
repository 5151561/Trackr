package com.trackr.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import com.trackr.core.data.repository.HomeCoordinates
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.CustomSourceConfig
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.core.testing.MainDispatcherHolder
import com.trackr.core.testing.RecordingSearchRealtimeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val main = MainDispatcherHolder()

    private val shanghai = TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", province = "直辖市")
    private val beijing = TransitCity(cityId = "001", cityName = "北京", pinyin = "beijing", province = "直辖市")

    private lateinit var settings: FakeSettingsRepository
    private lateinit var realtime: RecordingSearchRealtimeRepository
    private lateinit var history: FakeSearchHistoryRepository
    private lateinit var userLocation: FakeUserLocationStore

    @BeforeTest
    fun setup() {
        main.install()
        settings = FakeSettingsRepository(initialCity = shanghai)
        realtime = RecordingSearchRealtimeRepository()
        history = FakeSearchHistoryRepository()
        userLocation = FakeUserLocationStore()
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    private fun TestScope.createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): SearchViewModel {
        val vm = SearchViewModel(
            settings = settings,
            realtime = realtime,
            searchHistoryRepository = history,
            userLocationStore = userLocation,
            savedStateHandle = savedStateHandle
        )
        testScheduler.runCurrent()
        return vm
    }

    /** 输入关键词并等防抖结束、请求跑完 */
    private fun TestScope.searchAndWait(viewModel: SearchViewModel, query: String) {
        viewModel.updateSearchQuery(query)
        testScheduler.advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()
    }

    // ---- 从 HomeViewModelTest 搬来的 5 条 ----

    @Test
    fun testSearchQuery_triggersSearchInRepository() = runTest(main.dispatcher) {
        realtime.searchResultFor = { _, _ ->
            Result.success(
                TransitSearchResult(
                    keyword = "49",
                    lines = listOf(
                        BusLineSummary(
                            name = "49路",
                            lineNo = "49",
                            directions = listOf(
                                BusLineDirection(
                                    lineId = "l1",
                                    name = "49路",
                                    startStop = "汉口路",
                                    endStop = "上海体育馆"
                                )
                            )
                        )
                    )
                )
            )
        }

        val viewModel = createViewModel()

        viewModel.updateSearchQuery("49")
        testScheduler.advanceTimeBy(350L)
        testScheduler.runCurrent()

        val searchResult = viewModel.uiState.value.searchResult
        assertEquals("49", searchResult?.keyword)
        assertEquals(1, searchResult?.lines?.size)
        assertEquals("49路", searchResult?.lines?.first()?.name)
    }

    @Test
    fun testSearchHistory_addDeduplicateAndOrder() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        assertEquals(0, viewModel.uiState.value.searchHistory.size)

        viewModel.addSearchHistory("71路")
        testScheduler.runCurrent()
        assertEquals(listOf("71路"), viewModel.uiState.value.searchHistory)

        viewModel.addSearchHistory("人民广场")
        testScheduler.runCurrent()
        assertEquals(listOf("人民广场", "71路"), viewModel.uiState.value.searchHistory)

        // 已有的词再加一次会排到最前
        viewModel.addSearchHistory("71路")
        testScheduler.runCurrent()
        assertEquals(listOf("71路", "人民广场"), viewModel.uiState.value.searchHistory)

        // 空白词忽略
        viewModel.addSearchHistory("   ")
        testScheduler.runCurrent()
        assertEquals(listOf("71路", "人民广场"), viewModel.uiState.value.searchHistory)
    }

    @Test
    fun testSearchHistory_removeItem() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.addSearchHistory("71路")
        viewModel.addSearchHistory("2号线")
        testScheduler.runCurrent()

        assertEquals(listOf("2号线", "71路"), viewModel.uiState.value.searchHistory)

        viewModel.removeSearchHistory("2号线")
        testScheduler.runCurrent()

        assertEquals(listOf("71路"), viewModel.uiState.value.searchHistory)
    }

    @Test
    fun testSearchHistory_clearAll() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.addSearchHistory("1路")
        viewModel.addSearchHistory("2路")
        testScheduler.runCurrent()
        assertEquals(2, viewModel.uiState.value.searchHistory.size)

        viewModel.clearSearchHistory()
        testScheduler.runCurrent()
        assertEquals(0, viewModel.uiState.value.searchHistory.size)
    }

    @Test
    fun testSearchHistory_maxCapacityLimit() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        for (i in 1..25) {
            viewModel.addSearchHistory("线路$i")
        }
        testScheduler.runCurrent()

        assertEquals(20, viewModel.uiState.value.searchHistory.size)
        assertEquals("线路25", viewModel.uiState.value.searchHistory.first())
        assertEquals("线路6", viewModel.uiState.value.searchHistory.last())
    }

    // ---- 新增（bus-search.md §5.1） ----

    @Test
    fun query_debounces300ms_thenSearchesOnce() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.updateSearchQuery("49")
        assertEquals("49", viewModel.uiState.value.searchQuery)

        testScheduler.advanceTimeBy(299L)
        testScheduler.runCurrent()
        assertTrue(realtime.searchCalls.isEmpty(), "299 ms 时还不该搜")

        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertEquals(listOf("49"), realtime.searchCalls.map { it.keyword })
        assertEquals("49", viewModel.uiState.value.searchResult?.keyword)

        // 之后不会再搜
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(1, realtime.searchCalls.size)
    }

    @Test
    fun query_trimsKeyword() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        searchAndWait(viewModel, "  49 ")

        assertEquals(listOf("49"), realtime.searchCalls.map { it.keyword })
        assertEquals("  49 ", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun query_newKeywordWithinDebounce_cancelsPrevious() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.updateSearchQuery("4")
        testScheduler.advanceTimeBy(100L)
        viewModel.updateSearchQuery("49")
        testScheduler.advanceTimeBy(350L)
        testScheduler.runCurrent()

        assertEquals(listOf("49"), realtime.searchCalls.map { it.keyword })
        assertEquals("49", viewModel.uiState.value.searchResult?.keyword)
    }

    @Test
    fun query_newKeyword_cancelsInFlightRequest() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        val firstRequest = CompletableDeferred<Unit>()
        realtime.pendingSearch = firstRequest

        searchAndWait(viewModel, "4")
        assertEquals(listOf("4"), realtime.searchCalls.map { it.keyword })
        assertTrue(viewModel.uiState.value.isSearching)

        // 旧请求还没回来就换词：旧请求被取消，结果只来自新词
        realtime.pendingSearch = null
        searchAndWait(viewModel, "49")
        firstRequest.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("4", "49"), realtime.searchCalls.map { it.keyword })
        assertEquals("49", viewModel.uiState.value.searchResult?.keyword)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun isSearching_trueDuringRequest_falseAfter() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        val request = CompletableDeferred<Unit>()
        realtime.pendingSearch = request

        viewModel.updateSearchQuery("49")
        testScheduler.advanceTimeBy(299L)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isSearching, "防抖期间不算搜索中")

        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isSearching)

        request.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals("49", viewModel.uiState.value.searchResult?.keyword)
    }

    @Test
    fun blankQuery_clearsResultAndStopsSearching() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        searchAndWait(viewModel, "49")
        assertNotNull(viewModel.uiState.value.searchResult)

        viewModel.updateSearchQuery(" ")
        assertNull(viewModel.uiState.value.searchResult)
        assertFalse(viewModel.uiState.value.isSearching)

        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertEquals(1, realtime.searchCalls.size, "空白词不发请求")
    }

    @Test
    fun blankQuery_duringDebounce_cancelsPendingSearch() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.updateSearchQuery("49")
        testScheduler.advanceTimeBy(100L)
        viewModel.updateSearchQuery("")
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        assertTrue(realtime.searchCalls.isEmpty())
        assertNull(viewModel.uiState.value.searchResult)
    }

    @Test
    fun selectedCityChange_clearsResult_keepsQuery() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        searchAndWait(viewModel, "49")
        assertNotNull(viewModel.uiState.value.searchResult)

        settings.selectCity(beijing)
        testScheduler.runCurrent()

        assertEquals(beijing, viewModel.uiState.value.currentCity)
        assertNull(viewModel.uiState.value.searchResult)
        assertEquals("49", viewModel.uiState.value.searchQuery)
        // 和原来一样，换城市本身不触发重搜
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertEquals(1, realtime.searchCalls.size)
    }

    @Test
    fun customSourceChange_withQuery_researches() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        searchAndWait(viewModel, "49")
        assertEquals(1, realtime.searchCalls.size)

        settings.setCustomSource(CustomSourceConfig(baseUrl = "https://other.test"))
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()

        assertEquals(listOf("49", "49"), realtime.searchCalls.map { it.keyword })
    }

    @Test
    fun customSourceSwitchedBack_researchesAgain() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        searchAndWait(viewModel, "49")

        // 切过去再切回建 VM 时的数据源：两次都算「变了」
        val original = settings.customSource.value
        for (source in listOf(CustomSourceConfig(baseUrl = "https://other.test"), original)) {
            settings.setCustomSource(source)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS)
            testScheduler.runCurrent()
        }

        assertEquals(3, realtime.searchCalls.size)
    }

    @Test
    fun customSourceChange_withoutQuery_doesNotSearch() = runTest(main.dispatcher) {
        createViewModel()

        settings.setCustomSource(CustomSourceConfig(baseUrl = "https://other.test"))
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        assertTrue(realtime.searchCalls.isEmpty())
    }

    @Test
    fun search_usesSelectedCityAndUserLocation() = runTest(main.dispatcher) {
        settings.selectCity(beijing)
        userLocation.updateUserLocation(39.9, 116.4)
        val viewModel = createViewModel()

        searchAndWait(viewModel, "1路")

        val call = realtime.searchCalls.single()
        assertEquals(beijing, call.city)
        assertEquals(39.9, call.userLat)
        assertEquals(116.4, call.userLng)
    }

    @Test
    fun search_prefersHomeCoordinatesOverGps() = runTest(main.dispatcher) {
        // 拆分前搜索读首页的 currentLat/currentLng：人在上海、选了北京时首页用的是北京中心点，不是 GPS
        settings.selectCity(beijing)
        userLocation.updateUserLocation(31.2, 121.4)
        val homeCoordinates = HomeCoordinates().apply { update(39.9042, 116.4074) }
        val viewModel = SearchViewModel(
            settings = settings,
            realtime = realtime,
            searchHistoryRepository = history,
            userLocationStore = userLocation,
            homeCoordinates = homeCoordinates
        )
        testScheduler.runCurrent()

        searchAndWait(viewModel, "1路")

        val call = realtime.searchCalls.single()
        assertEquals(39.9042, call.userLat)
        assertEquals(116.4074, call.userLng)
    }

    @Test
    fun search_withoutUserLocation_usesSelectedCityCenter() = runTest(main.dispatcher) {
        settings.selectCity(beijing)
        val viewModel = createViewModel()

        searchAndWait(viewModel, "1路")

        val center = ProvinceCityCatalog.getCityCenterCoordinates("北京")
        val call = realtime.searchCalls.single()
        assertEquals(beijing, call.city)
        assertEquals(center.first, call.userLat)
        assertEquals(center.second, call.userLng)
    }

    // ---- 第 23 步：输入存 SavedStateHandle（重构计划 §3.4、§3.8） ----

    @Test
    fun query_isWrittenToSavedStateHandle() = runTest(main.dispatcher) {
        val handle = SavedStateHandle()
        val viewModel = createViewModel(handle)

        viewModel.updateSearchQuery("49")
        assertEquals("49", handle.get<String>(SearchViewModel.KEY_QUERY))

        viewModel.updateSearchQuery("")
        assertEquals("", handle.get<String>(SearchViewModel.KEY_QUERY))
    }

    @Test
    fun restoredQuery_isShownAndSearchedOnce() = runTest(main.dispatcher) {
        // 进程重建：新 VM 拿到存着「49」的 SavedStateHandle
        val viewModel = createViewModel(SavedStateHandle(mapOf(SearchViewModel.KEY_QUERY to "49")))

        assertEquals("49", viewModel.uiState.value.searchQuery)
        testScheduler.advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()

        assertEquals(listOf("49"), realtime.searchCalls.map { it.keyword }, "按恢复的词搜一次，不重复")
        assertEquals("49", viewModel.uiState.value.searchResult?.keyword)
    }

    @Test
    fun noSavedQuery_startsEmptyWithoutSearching() = runTest(main.dispatcher) {
        val viewModel = createViewModel(SavedStateHandle())

        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        assertEquals("", viewModel.uiState.value.searchQuery)
        assertTrue(realtime.searchCalls.isEmpty())
    }

    @Test
    fun search_afterCityChange_usesNewCity() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        searchAndWait(viewModel, "49")

        settings.selectCity(beijing)
        testScheduler.runCurrent()
        searchAndWait(viewModel, "49路")

        assertEquals(listOf(shanghai, beijing), realtime.searchCalls.map { it.city })
    }
}
