package com.trackr.ui.screens.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

// 行按形状裁剪（首末行一边圆角一边直角）：点击判定要走 Path，Robolectric 默认图形模式算不对，得用原生图形
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val city = TransitCity(cityId = "034", cityName = "上海")
    private val events = mutableListOf<SearchEvent>()

    private val forward = BusLineDirection(
        lineId = "49-0", name = "49路", startStop = "汉口路", endStop = "上海体育馆",
        firstTime = "05:30", lastTime = "23:00"
    )
    private val backward = BusLineDirection(lineId = "49-1", name = "49路", startStop = "上海体育馆", endStop = "汉口路")
    private val stop = NearbyStop(
        stopId = "s1",
        name = "人民广场",
        distanceMeters = 350,
        lines = listOf(
            NearbyStopLineStatus(line = forward, targetOrder = 5, targetStopId = "s1", targetStopName = "人民广场"),
            NearbyStopLineStatus(
                line = BusLineDirection(lineId = "71-0", name = "71路", startStop = "a", endStop = "b"),
                targetOrder = 2, targetStopId = "s1", targetStopName = "人民广场"
            )
        )
    )
    private val result = TransitSearchResult(
        keyword = "49",
        lines = listOf(BusLineSummary(name = "49路", lineNo = "49", directions = listOf(forward, backward))),
        stops = listOf(stop)
    )

    /** [queryText] 是输入框文字（生产里来自 ViewModel 的 Compose 快照状态）；默认和 state 里的搜索词一致 */
    private fun show(state: SearchUiState, queryText: String = state.searchQuery) {
        composeTestRule.setContent {
            TrackrTheme {
                SearchScreen(state = state, queryText = queryText, onEvent = { events += it })
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun blankQuery_showsOnlyRecentSearches_noSuggestions() {
        show(SearchUiState(currentCity = city, searchHistory = listOf("71路", "人民广场")))

        composeTestRule.onNodeWithText("最近搜过").assertExists()
        composeTestRule.onNodeWithText("71路").assertExists()
        composeTestRule.onNodeWithText("人民广场").assertExists()
        // 写死的「常用搜索推荐」和「双引擎」说明卡都没了
        composeTestRule.onNodeWithText("常用搜索推荐").assertDoesNotExist()
        composeTestRule.onNodeWithText("快速公交").assertDoesNotExist()
        composeTestRule.onNodeWithText("双引擎", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("搜索 上海 公交线路、车站").assertExists()
    }

    @Test
    fun blankQuery_noHistory_showsNothingBelowTheField() {
        show(SearchUiState(currentCity = city))

        composeTestRule.onNodeWithText("最近搜过").assertDoesNotExist()
        composeTestRule.onNodeWithText("清空").assertDoesNotExist()
    }

    @Test
    fun historyChip_click_removeAndClearWithConfirm() {
        show(SearchUiState(currentCity = city, searchHistory = listOf("71路")))

        composeTestRule.onNodeWithText("71路").performClick()
        // 点在字的最右边也只是搜这个词，不会误删
        composeTestRule.onNodeWithText("71路").performTouchInput { click(centerRight - Offset(1f, 0f)) }
        composeTestRule.onNodeWithContentDescription("删除 71路").performClick()
        assertEquals(
            listOf(SearchEvent.HistoryClicked("71路"), SearchEvent.HistoryClicked("71路"), SearchEvent.RemoveHistory("71路")),
            events
        )

        events.clear()
        composeTestRule.onNodeWithText("清空").performClick()
        composeTestRule.onNodeWithText("清空最近搜过的词？").assertExists()
        assertTrue("点了「清空」先问，不直接删", events.isEmpty())
        composeTestRule.onNode(hasText("清空") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(SearchEvent.ClearHistory), events)
    }

    @Test
    fun results_oneRowPerDirection_clickOpensThatDirection() {
        show(SearchUiState(currentCity = city, searchQuery = "49", searchResult = result))

        composeTestRule.onNodeWithText("线路").assertExists()
        composeTestRule.onNodeWithText("车站").assertExists()
        composeTestRule.onNodeWithText("汉口路 → 上海体育馆").assertExists()
        composeTestRule.onNodeWithText("05:30-23:00").assertExists()
        composeTestRule.onNodeWithText("上海体育馆 → 汉口路").performClick()

        assertEquals(listOf<SearchEvent>(SearchEvent.LineClicked(backward)), events)
    }

    @Test
    fun stopRow_showsLinesAndDistance_clickOpensStop() {
        show(SearchUiState(currentCity = city, searchQuery = "人民", searchResult = result))

        composeTestRule.onNodeWithText("49路 · 71路").assertExists()
        composeTestRule.onNodeWithText("350米").assertExists()
        composeTestRule.onNodeWithText("人民广场").performClick()

        assertEquals(listOf<SearchEvent>(SearchEvent.StopClicked(stop)), events)
    }

    @Test
    fun noResults_saysWhatWasNotFound() {
        show(SearchUiState(currentCity = city, searchQuery = " 999 ", searchResult = TransitSearchResult(keyword = "999")))

        composeTestRule.onNodeWithText("没找到和「999」有关的线路或车站").assertExists()
        composeTestRule.onNodeWithTag(SEARCH_RESULT_LIST_TAG).assertDoesNotExist()
    }

    @Test
    fun noResults_whileSearching_doesNotShowEmptyMessageYet() {
        show(SearchUiState(currentCity = city, searchQuery = "999", isSearching = true, searchResult = TransitSearchResult(keyword = "99")))

        composeTestRule.onAllNodesWithText("没找到", substring = true).assertCountEquals(0)
    }

    @Test
    fun typing_clear_imeSearch_andBack_sendEvents() {
        var state by mutableStateOf(SearchUiState(currentCity = city))
        // 输入框文字单独一份，和 SearchRoute 接 ViewModel 的 queryText 一样
        var queryText by mutableStateOf("")
        composeTestRule.setContent {
            TrackrTheme {
                SearchScreen(state = state, queryText = queryText, onEvent = { event ->
                    events += event
                    if (event is SearchEvent.QueryChanged) {
                        queryText = event.query
                        state = state.copy(searchQuery = event.query)
                    }
                })
            }
        }

        composeTestRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("49")
        composeTestRule.onNodeWithTag(SEARCH_FIELD_TAG).performImeAction()
        composeTestRule.onNodeWithContentDescription("清空").performClick()
        composeTestRule.onNodeWithContentDescription("返回").performClick()

        assertEquals(
            listOf(SearchEvent.QueryChanged("49"), SearchEvent.Submit, SearchEvent.QueryChanged(""), SearchEvent.Back),
            events
        )
    }
}
