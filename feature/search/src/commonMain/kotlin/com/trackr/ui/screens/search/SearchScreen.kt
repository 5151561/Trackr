package com.trackr.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.component.EmptyStateView
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.action_clear
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.resources.ic_close
import com.trackr.core.designsystem.resources.ic_search
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.item
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitSearchResult
import com.trackr.feature.search.resources.Res as SearchRes
import com.trackr.feature.search.resources.search_clear_history_title
import com.trackr.feature.search.resources.search_empty
import com.trackr.feature.search.resources.search_empty_hint
import com.trackr.feature.search.resources.search_hint
import com.trackr.feature.search.resources.search_recent
import com.trackr.ui.components.arrival.LineBadge
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 搜索页发给 Route 的事件（重构计划阶段 D：无状态 `XxxScreen(state, onEvent)`） */
sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    /** 键盘上的「搜索」：把当前词记进最近搜过 */
    data object Submit : SearchEvent
    /** 点了一个最近搜过的词：填进输入框并搜 */
    data class HistoryClicked(val query: String) : SearchEvent
    data class RemoveHistory(val query: String) : SearchEvent
    data object ClearHistory : SearchEvent
    data class LineClicked(val direction: BusLineDirection) : SearchEvent
    data class StopClicked(val stop: NearbyStop) : SearchEvent
    data object Back : SearchEvent
}

internal const val SEARCH_FIELD_TAG = "search_field"
internal const val SEARCH_RESULT_LIST_TAG = "search_result_list"

/**
 * v3 搜索页（redesign_v3.html「搜索」）：
 * - 没输入：只显示最近搜过的词（可单删、全清，全清要确认）。
 * - 有结果：「线路」段每个方向一行（「始发 → 终点」），点了直接进那个方向；「车站」段每站一行。
 * - 没结果：说清楚没找到什么，给一句怎么改。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    /**
     * 输入框现在显示的文字。必填，页面内部不再从 [SearchUiState.searchQuery] 取值——那是两份真值。
     *
     * 必须传 ViewModel 的 Compose 快照状态：`searchQuery` 是 StateFlow 的快照，中文输入法组字期间回写会晚一拍，
     * 正在组的字会被覆盖掉（5dd5472）。
     */
    queryText: String,
    onEvent: (SearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            SearchTopBar(
                query = queryText,
                cityName = state.currentCity.cityName,
                isSearching = state.isSearching,
                onEvent = onEvent
            )
        }
    ) { innerPadding ->
        val result = state.searchResult
        when {
            queryText.isBlank() -> SearchHistoryView(
                history = state.searchHistory,
                onEvent = onEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding)
            )

            !state.isSearching && result != null && result.lines.isEmpty() && result.stops.isEmpty() -> EmptyStateView(
                message = stringResource(SearchRes.string.search_empty, queryText.trim()),
                subMessage = stringResource(SearchRes.string.search_empty_hint),
                icon = vectorResource(Res.drawable.ic_search),
                modifier = Modifier
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding)
            )

            else -> SearchResultList(
                result = result,
                innerPadding = innerPadding,
                onEvent = onEvent
            )
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    cityName: String,
    isSearching: Boolean,
    onEvent: (SearchEvent) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val dimens = TrackrTheme.dimens

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = dimens.spaceXs, end = dimens.spaceL, top = dimens.spaceS, bottom = dimens.spaceS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onEvent(SearchEvent.Back) }) {
                Icon(imageVector = vectorResource(Res.drawable.ic_back), contentDescription = stringResource(Res.string.action_back))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { onEvent(SearchEvent.QueryChanged(it)) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag(SEARCH_FIELD_TAG),
                placeholder = {
                    Text(
                        text = stringResource(SearchRes.string.search_hint, cityName),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onEvent(SearchEvent.Submit) }),
                leadingIcon = {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onEvent(SearchEvent.QueryChanged("")) }) {
                            Icon(imageVector = vectorResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.action_clear))
                        }
                    }
                }
            )
        }
        if (isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 没输入时：最近搜过的词 */
@Composable
private fun SearchHistoryView(
    history: List<String>,
    onEvent: (SearchEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    if (history.isEmpty()) return
    val dimens = TrackrTheme.dimens
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(SearchRes.string.search_clear_history_title)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onEvent(SearchEvent.ClearHistory)
                }) {
                    Text(stringResource(Res.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.spaceL, vertical = dimens.spaceS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(SearchRes.string.search_recent),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showClearDialog = true }) { Text(stringResource(Res.string.action_clear)) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceS)
        ) {
            history.forEach { word ->
                HistoryChip(
                    word = word,
                    onClick = { onEvent(SearchEvent.HistoryClicked(word)) },
                    onRemove = { onEvent(SearchEvent.RemoveHistory(word)) }
                )
            }
        }
    }
}

/**
 * 最近搜过的一个词：点字填进去搜，点 × 删掉这个词。
 * 字和 × 是两个并排的点击区（不用 InputChip 里套可点图标：小图标的点击区会被放大到 48dp，
 * 点在字的右半边也会删掉词）。
 */
@Composable
private fun HistoryChip(word: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val dimens = TrackrTheme.dimens
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = onClick)
                .padding(start = dimens.spaceM, end = dimens.spaceXs),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = word,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(40.dp)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "删除 $word" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchResultList(
    result: TransitSearchResult?,
    innerPadding: PaddingValues,
    onEvent: (SearchEvent) -> Unit
) {
    val rows = remember(result) { searchRows(result) }
    val dimens = TrackrTheme.dimens
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .testTag(SEARCH_RESULT_LIST_TAG),
        contentPadding = PaddingValues(
            start = dimens.spaceL,
            end = dimens.spaceL,
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + dimens.spaceXl
        )
    ) {
        items(items = rows, key = { it.key }, contentType = { it.contentType }) { row ->
            when (row) {
                is SearchRow.Header -> SectionHeader(row.title)
                is SearchRow.LineDirection -> LineDirectionRow(row, onClick = { onEvent(SearchEvent.LineClicked(row.direction)) })
                is SearchRow.Stop -> StopRow(row, onClick = { onEvent(SearchEvent.StopClicked(row.stop)) })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val dimens = TrackrTheme.dimens
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = dimens.spaceXs, top = dimens.spaceL, bottom = dimens.spaceS)
    )
}

@Composable
private fun LineDirectionRow(row: SearchRow.LineDirection, onClick: () -> Unit) {
    ResultRow(position = row.position, onClick = onClick) {
        LineBadge(row.lineName)
        TwoLineText(title = row.title, subtitle = row.detail, modifier = Modifier.weight(1f))
        Chevron()
    }
}

@Composable
private fun StopRow(row: SearchRow.Stop, onClick: () -> Unit) {
    ResultRow(position = row.position, onClick = onClick) {
        TwoLineText(title = row.stop.name, subtitle = row.lineNames, modifier = Modifier.weight(1f))
        if (row.distance.isNotEmpty()) {
            Text(
                text = row.distance,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Chevron()
    }
}

/** 一段里的一行：同一段的行连成一张卡（首行上圆角、末行下圆角，中间分隔线） */
@Composable
private fun ResultRow(
    position: RowPosition,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val dimens = TrackrTheme.dimens
    val shape = rowShape(MaterialTheme.shapes.medium, position)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
    ) {
        if (position == RowPosition.Middle || position == RowPosition.Last) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = dimens.spaceL),
                color = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = dimens.spaceL, vertical = dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
            content = content
        )
    }
}

private fun rowShape(card: CornerBasedShape, position: RowPosition): Shape = when (position) {
    RowPosition.Single -> card
    RowPosition.First -> card.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    RowPosition.Last -> card.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
    RowPosition.Middle -> RectangleShape
}

@Composable
private fun TwoLineText(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.item,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = vectorResource(Res.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
