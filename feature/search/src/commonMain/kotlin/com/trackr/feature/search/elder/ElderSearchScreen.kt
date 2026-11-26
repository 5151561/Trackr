package com.trackr.feature.search.elder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_clear
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.resources.ic_close
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.platform.SpeechInput
import com.trackr.feature.search.resources.Res as SearchRes
import com.trackr.feature.search.resources.search_elder_hint
import com.trackr.feature.search.resources.search_elder_lines
import com.trackr.feature.search.resources.search_elder_speak
import com.trackr.feature.search.resources.search_elder_stops
import com.trackr.feature.search.resources.search_elder_title
import com.trackr.feature.search.resources.search_empty
import com.trackr.feature.search.resources.search_empty_hint
import com.trackr.feature.search.resources.search_recent
import com.trackr.ui.components.elder.ElderButton
import com.trackr.ui.components.elder.ElderButtonHeight
import com.trackr.ui.components.elder.ElderLineBadge
import com.trackr.ui.components.elder.ElderTopBar
import com.trackr.ui.components.elder.ShowTransitCodeButton
import com.trackr.ui.screens.search.SearchEvent
import com.trackr.ui.screens.search.SearchRow
import com.trackr.ui.screens.search.SearchUiState
import com.trackr.ui.screens.search.searchRows
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 测试用的 tag */
internal object ElderSearchTags {
    const val FIELD = "elder_search_field"
    const val SPEAK = "elder_search_speak"
    const val LIST = "elder_search_list"
    fun row(key: String) = "elder_search_row_$key"
    fun history(word: String) = "elder_search_history_$word"
}

/**
 * 长辈版搜索（设计稿「找线路」，§8.5）。和标准版共用 `SearchViewModel` / [SearchUiState] 和 `SearchRoute` 的事件处理。
 *
 * 大输入框 +「按这里说话」（系统语音输入，[speechInput] 为 null 即手机没有语音识别时不显示）；
 * 没输入时列最近搜过的词（一行一个，点了就搜；不放删除，没有长按）；
 * 有结果时「找到的线路」每个方向一行（「始发 → 终点」）、「找到的车站」每站一行；底部固定「出示乘车码」。
 */
@Composable
fun ElderSearchScreen(
    state: SearchUiState,
    /** 输入框文字，传 `SearchViewModel.queryText`（同标准版，见 `SearchScreen`） */
    queryText: String,
    onEvent: (SearchEvent) -> Unit,
    speechInput: SpeechInput?,
    onOpenTransitCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = TrackrTheme.dimens
    val scope = rememberCoroutineScope()
    val rows = remember(state.searchResult) { searchRows(state.searchResult) }
    val result = state.searchResult

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ElderTopBar(title = stringResource(SearchRes.string.search_elder_title), onBack = { onEvent(SearchEvent.Back) })
        },
        bottomBar = { ShowTransitCodeButton(onClick = onOpenTransitCode) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ElderSearchTags.LIST),
            contentPadding = PaddingValues(
                start = dimens.screenPadding,
                end = dimens.screenPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + dimens.spaceL
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceM)
        ) {
            item(key = "field", contentType = "field") {
                ElderSearchField(query = queryText, onEvent = onEvent)
            }
            if (speechInput != null) {
                item(key = "speak", contentType = "speak") {
                    ElderButton(
                        text = stringResource(SearchRes.string.search_elder_speak),
                        onClick = {
                            scope.launch {
                                val heard = speechInput.listen()?.trim()
                                if (!heard.isNullOrEmpty()) {
                                    onEvent(SearchEvent.QueryChanged(heard))
                                    onEvent(SearchEvent.Submit)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ElderSearchTags.SPEAK)
                    )
                }
            }
            if (state.isSearching) {
                item(key = "progress", contentType = "progress") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            when {
                queryText.isBlank() -> if (state.searchHistory.isNotEmpty()) {
                    item(key = "recent_title", contentType = "title") {
                        ElderSectionTitle(stringResource(SearchRes.string.search_recent))
                    }
                    items(items = state.searchHistory, key = { "recent_$it" }, contentType = { "recent" }) { word ->
                        ElderResultRow(
                            onClick = { onEvent(SearchEvent.HistoryClicked(word)) },
                            modifier = Modifier.testTag(ElderSearchTags.history(word))
                        ) {
                            Text(text = word, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }

                !state.isSearching && result != null && rows.isEmpty() -> item(key = "empty", contentType = "empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                        Text(
                            text = stringResource(SearchRes.string.search_empty, queryText.trim()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(SearchRes.string.search_empty_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> items(items = rows, key = { it.key }, contentType = { it.contentType }) { row ->
                    when (row) {
                        is SearchRow.Header -> ElderSectionTitle(
                            if (row.title == SearchRow.TITLE_LINES) {
                                stringResource(SearchRes.string.search_elder_lines)
                            } else {
                                stringResource(SearchRes.string.search_elder_stops)
                            }
                        )
                        is SearchRow.LineDirection -> ElderResultRow(
                            onClick = { onEvent(SearchEvent.LineClicked(row.direction)) },
                            modifier = Modifier.testTag(ElderSearchTags.row(row.key))
                        ) {
                            // 运营时间另起一行占满宽度：字放大到 2 倍时和线路号挤一行会把「06:30-22:30」拆开
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ElderLineBadge(name = row.lineName)
                                    Spacer(modifier = Modifier.width(dimens.spaceM))
                                    Text(
                                        text = row.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (row.detail.isNotBlank()) {
                                    Text(
                                        text = row.detail,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        is SearchRow.Stop -> ElderResultRow(
                            onClick = { onEvent(SearchEvent.StopClicked(row.stop)) },
                            modifier = Modifier.testTag(ElderSearchTags.row(row.key))
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = row.stop.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                val sub = listOf(row.distance, row.lineNames).filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotBlank()) {
                                    Text(text = sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 大输入框：字和主题一样大；有字时右边一个「清空」（64dp） */
@Composable
private fun ElderSearchField(query: String, onEvent: (SearchEvent) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = { onEvent(SearchEvent.QueryChanged(it)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ElderButtonHeight)
            .testTag(ElderSearchTags.FIELD),
        textStyle = MaterialTheme.typography.titleMedium,
        placeholder = { Text(text = stringResource(SearchRes.string.search_elder_hint), style = MaterialTheme.typography.titleMedium) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onEvent(SearchEvent.Submit) }),
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onEvent(SearchEvent.QueryChanged("")) }, modifier = Modifier.size(ElderButtonHeight)) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_close),
                        contentDescription = stringResource(Res.string.action_clear),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        } else {
            null
        }
    )
}

@Composable
private fun ElderSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = TrackrTheme.dimens.spaceS)
    )
}

/** 一行结果：整行能点、至少 64dp 高，右边一个箭头 */
@Composable
private fun ElderResultRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val dimens = TrackrTheme.dimens
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ElderButtonHeight)
                .clickable(onClick = onClick)
                .padding(horizontal = dimens.cardPadding, vertical = dimens.spaceS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
            Icon(
                imageVector = vectorResource(Res.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
