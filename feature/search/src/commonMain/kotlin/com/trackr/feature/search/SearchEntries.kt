package com.trackr.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.feature.search.elder.ElderSearchScreen
import com.trackr.core.navigation.ScreenKey
import com.trackr.ui.screens.search.SearchEvent
import com.trackr.ui.components.transitcode.TransitCodeChooserSheet
import com.trackr.ui.screens.search.SearchScreen
import com.trackr.ui.screens.search.SearchUiState
import com.trackr.ui.screens.search.SearchViewModel
import com.trackr.ui.screens.search.rememberSpeechInput
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** 搜索页的导航入口（`:composeApp` 组装） */
fun EntryProviderScope<NavKey>.searchEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.Search> { SearchRoute(navigator) }
}

/**
 * 长辈版搜索（§8.4、8.5，第 34 步）：同一个 Key、同一个 ViewModel 和事件处理，只换成 [ElderSearchScreen]。
 * 长辈版每页底部都有「出示乘车码」：选了默认平台就直接打开，「每次询问」才弹选择（和其他页一样）。
 */
fun EntryProviderScope<NavKey>.elderSearchEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.Search> {
        val transitCodeLauncher: TransitCodeLauncher = koinInject()
        val settings: SettingsRepository = koinInject()
        val speechInput = rememberSpeechInput()
        val scope = rememberCoroutineScope()
        var chooserOpen by rememberSaveable { mutableStateOf(false) }

        SearchRoute(navigator) { state, queryText, onEvent ->
            ElderSearchScreen(
                state = state,
                queryText = queryText,
                onEvent = onEvent,
                speechInput = speechInput,
                onOpenTransitCode = {
                    when (val provider = settings.preferredTransitCodeProvider.value) {
                        TransitCodeProvider.ASK_ALWAYS -> chooserOpen = true
                        else -> transitCodeLauncher.open(provider)
                    }
                }
            )
        }
        if (chooserOpen) {
            TransitCodeChooserSheet(
                onDismiss = { chooserOpen = false },
                onSelect = { provider, saveAsDefault ->
                    chooserOpen = false
                    if (provider != TransitCodeProvider.ASK_ALWAYS) {
                        if (saveAsDefault) scope.launch { settings.setPreferredTransitCodeProvider(provider) }
                        transitCodeLauncher.open(provider)
                    }
                }
            )
        }
    }
}

/** 搜索的 Koin 定义 */
val searchModule = module {
    viewModel {
        SearchViewModel(
            settings = get(),
            realtime = get(),
            searchHistoryRepository = get(),
            userLocationStore = get(),
            homeCoordinates = get(),
            savedStateHandle = get()
        )
    }
}

/**
 * 搜索页 Route：取 entry 级的 [SearchViewModel]（重构计划 §3.4），把 [SearchEvent] 翻成 VM 调用和导航。
 * 跳转只在页面 RESUMED 时做，防连点重复入栈（和原来的 dropUnlessResumed 一样）。
 */
@Composable
fun SearchRoute(
    navigator: TrackrNavigator,
    viewModel: SearchViewModel = koinViewModel(),
    /** 画哪个页面：标准版 [SearchScreen]，长辈版 [ElderSearchScreen]（§8.5：共用 ViewModel 和事件处理，只换页面） */
    screen: @Composable (state: SearchUiState, queryText: String, onEvent: (SearchEvent) -> Unit) -> Unit = { state, queryText, onEvent ->
        SearchScreen(state = state, queryText = queryText, onEvent = onEvent)
    }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    fun whenResumed(block: () -> Unit) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
    }
    /** 点进结果前把当前词记进最近搜过（原来就是这样） */
    fun rememberQuery() {
        val query = viewModel.uiState.value.searchQuery
        if (query.isNotBlank()) viewModel.addSearchHistory(query.trim())
    }

    screen(
        state,
        // 输入框直接读 ViewModel 的 Compose 快照，中文输入法组字不会被 Flow 重组打断。
        // 不再 `state.copy(searchQuery = …)` 覆盖状态：两份真值会让「输入框文字」和「上一次真正发起的搜索词」混成一个字段。
        viewModel.queryText,
        { event ->
            when (event) {
                is SearchEvent.QueryChanged -> viewModel.updateSearchQuery(event.query)
                SearchEvent.Submit -> rememberQuery()
                is SearchEvent.HistoryClicked -> {
                    viewModel.updateSearchQuery(event.query)
                    viewModel.addSearchHistory(event.query)
                }
                is SearchEvent.RemoveHistory -> viewModel.removeSearchHistory(event.query)
                SearchEvent.ClearHistory -> viewModel.clearSearchHistory()
                is SearchEvent.LineClicked -> {
                    rememberQuery()
                    val direction = event.direction
                    whenResumed {
                        navigator.open(
                            ScreenKey.LineDetail(
                                cityId = viewModel.uiState.value.currentCity.cityId,
                                lineId = direction.lineId,
                                targetOrder = 1,
                                stopId = "",
                                source = direction.source,
                                lineName = direction.name
                            )
                        )
                    }
                }
                is SearchEvent.StopClicked -> {
                    rememberQuery()
                    val stop = event.stop
                    whenResumed {
                        navigator.open(
                            ScreenKey.StopDetail(
                                cityId = viewModel.uiState.value.currentCity.cityId,
                                stopId = stop.stopId,
                                stopName = stop.name,
                                physicalStId = stop.physicalStId.orEmpty(),
                                namesakeStId = stop.namesakeStId.orEmpty(),
                                lat = stop.wgsLat,
                                lng = stop.wgsLng
                            )
                        )
                    }
                }
                SearchEvent.Back -> whenResumed { navigator.popChildPage() }
            }
        }
    )
}
