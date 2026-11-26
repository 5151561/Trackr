package com.trackr.feature.bus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_edit
import com.trackr.core.designsystem.resources.ic_arrow_drop_down
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.resources.ic_place
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.groupAccentColor
import com.trackr.core.designsystem.theme.item
import com.trackr.feature.bus.resources.Res as BusRes
import com.trackr.feature.bus.resources.bus_favorites_all
import com.trackr.feature.bus.resources.bus_favorites_all_cities
import com.trackr.feature.bus.resources.bus_favorites_city_empty
import com.trackr.feature.bus.resources.bus_favorites_collapse
import com.trackr.feature.bus.resources.bus_favorites_current_city_only
import com.trackr.feature.bus.resources.bus_favorites_empty
import com.trackr.feature.bus.resources.bus_favorites_expand
import com.trackr.feature.bus.resources.bus_favorites_group_city_empty
import com.trackr.feature.bus.resources.bus_favorites_group_empty
import com.trackr.feature.bus.resources.bus_favorites_show_all_cities
import com.trackr.feature.bus.resources.bus_favorites_title
import com.trackr.feature.bus.resources.bus_station_icon
import com.trackr.feature.bus.resources.bus_station_loading
import com.trackr.feature.bus.resources.bus_station_not_found
import com.trackr.ui.components.arrival.ArrivalTrailing
import com.trackr.ui.components.arrival.LineBadge
import com.trackr.ui.icons.TrackrIcons
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 收藏栏折叠时默认显示的行数（issue #61）；超过此数量才折叠并显示底部「展开全部 / 收起」 */
internal const val COLLAPSED_FAVORITES_LIMIT = 4

/**
 * 「收藏」：车和路线一个列表（§4.3：key = `stop_x` / `route_x`，contentType 两种）；
 * 有分组时上面一排 chips（全部 + 各分组），右上角「全部城市 / 仅本市」筛选（有跨城收藏或已开启时出现）+「编辑」；
 * 超过 [COLLAPSED_FAVORITES_LIMIT] 条时默认折叠，底部点「展开全部 / 收起」（issue #61）。
 */
internal fun LazyListScope.favoritesSection(state: BusHomeUiState, onEvent: (BusHomeEvent) -> Unit) {
    val hasAny = state.favoriteItems.isNotEmpty() || state.favoriteGroups.isNotEmpty()
    val showCityFilter = state.favoritesCurrentCityOnly || state.hasOtherCityFavorites

    item(key = "fav_header", contentType = "section_header") {
        SectionHeader(
            title = stringResource(BusRes.string.bus_favorites_title),
            trailing = if (hasAny || showCityFilter) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showCityFilter) {
                            FavoritesCityFilterToggle(
                                currentCityOnly = state.favoritesCurrentCityOnly,
                                onChange = { onEvent(BusHomeEvent.SetFavoritesCurrentCityOnly(it)) }
                            )
                        }
                        if (hasAny) {
                            TextButton(
                                onClick = { onEvent(BusHomeEvent.EditFavorites) },
                                modifier = Modifier.testTag(BusHomeTags.EDIT_FAVORITES)
                            ) { Text(stringResource(Res.string.action_edit)) }
                        }
                    }
                }
            } else null
        )
    }

    if (state.favoriteGroups.isNotEmpty()) {
        item(key = "fav_chips", contentType = "fav_chips") {
            GroupChips(state = state, onSelect = { onEvent(BusHomeEvent.SelectGroup(it)) })
        }
    }

    if (state.favoriteRows.isEmpty()) {
        item(key = "fav_empty", contentType = "empty_hint") {
            val filteredOutByCity = state.favoritesCurrentCityOnly && state.favoriteItems.isNotEmpty() && (
                state.selectedGroupId == null || state.favoriteItems.any { it.groupId == state.selectedGroupId }
                )
            val text = when {
                state.favoriteItems.isEmpty() -> stringResource(BusRes.string.bus_favorites_empty)
                filteredOutByCity && state.selectedGroupId == null -> stringResource(BusRes.string.bus_favorites_city_empty)
                filteredOutByCity -> stringResource(BusRes.string.bus_favorites_group_city_empty)
                else -> stringResource(BusRes.string.bus_favorites_group_empty)
            }
            EmptyHint(
                text = text,
                action = if (filteredOutByCity) {
                    {
                        TextButton(
                            onClick = { onEvent(BusHomeEvent.SetFavoritesCurrentCityOnly(false)) },
                            modifier = Modifier.testTag(BusHomeTags.FAVORITES_SHOW_ALL_CITIES)
                        ) {
                            Text(stringResource(BusRes.string.bus_favorites_show_all_cities))
                        }
                    }
                } else null
            )
        }
    } else {
        val totalCount = state.favoriteRows.size
        val canCollapse = totalCount > COLLAPSED_FAVORITES_LIMIT
        val visibleRows = if (canCollapse && !state.isFavoritesExpanded) {
            state.favoriteRows.take(COLLAPSED_FAVORITES_LIMIT)
        } else {
            state.favoriteRows
        }

        items(
            items = visibleRows,
            key = { it.key },
            contentType = {
                when (it) {
                    is FavoriteRowUi.Stop -> "fav_stop"
                    is FavoriteRowUi.Route -> "fav_route"
                    is FavoriteRowUi.Station -> "fav_station"
                }
            }
        ) { row ->
            when (row) {
                is FavoriteRowUi.Stop -> FavoriteStopRow(row, onClick = { onEvent(BusHomeEvent.OpenLine(row.open)) })
                is FavoriteRowUi.Route -> FavoriteRouteRow(row, onClick = { onEvent(BusHomeEvent.OpenRoute(row.routeId)) })
                is FavoriteRowUi.Station -> FavoriteStationRow(row, onClick = { onEvent(BusHomeEvent.OpenStop(row.open)) })
            }
        }

        if (canCollapse) {
            item(key = "fav_expand_toggle", contentType = "fav_expand_toggle") {
                FavoritesExpandToggle(
                    expanded = state.isFavoritesExpanded,
                    hiddenCount = totalCount - COLLAPSED_FAVORITES_LIMIT,
                    onClick = { onEvent(BusHomeEvent.ToggleFavoritesExpanded) }
                )
            }
        }
    }
}

/** 收藏栏按城市筛选下拉（issue #61）：和下方「附近」的 `LineSortToggle` 同款轻量小字 + 下拉菜单 */
@Composable
private fun FavoritesCityFilterToggle(
    currentCityOnly: Boolean,
    onChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val allCitiesLabel = stringResource(BusRes.string.bus_favorites_all_cities)
    val currentCityLabel = stringResource(BusRes.string.bus_favorites_current_city_only)
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(start = 8.dp, end = 2.dp),
            modifier = Modifier.testTag(BusHomeTags.FAVORITES_CITY_FILTER_TRIGGER)
        ) {
            Text(
                text = if (currentCityOnly) currentCityLabel else allCitiesLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (currentCityOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = if (currentCityOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CityFilterOption(
                label = allCitiesLabel,
                selected = !currentCityOnly,
                tag = BusHomeTags.FAVORITES_CITY_FILTER_ALL
            ) {
                expanded = false
                if (currentCityOnly) onChange(false)
            }
            CityFilterOption(
                label = currentCityLabel,
                selected = currentCityOnly,
                tag = BusHomeTags.FAVORITES_CITY_FILTER_CURRENT
            ) {
                expanded = false
                if (!currentCityOnly) onChange(true)
            }
        }
    }
}

@Composable
private fun CityFilterOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(TrackrIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        modifier = Modifier.testTag(tag)
    )
}

/** 收藏栏底部「展开全部（还有 N 条） ⌄」/「收起 ⌃」轻按钮 */
@Composable
private fun FavoritesExpandToggle(
    expanded: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TrackrTheme.dimens.screenPadding),
        contentAlignment = Alignment.Center
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.testTag(BusHomeTags.FAVORITES_EXPAND_TOGGLE)
        ) {
            Text(
                text = if (expanded) {
                    stringResource(BusRes.string.bus_favorites_collapse)
                } else {
                    stringResource(BusRes.string.bus_favorites_expand, hiddenCount)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

@Composable
private fun GroupChips(state: BusHomeUiState, onSelect: (String?) -> Unit) {
    val dimens = TrackrTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
    ) {
        FilterChip(
            selected = state.selectedGroupId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(BusRes.string.bus_favorites_all)) },
            modifier = Modifier.testTag(BusHomeTags.groupChip(null))
        )
        state.favoriteGroups.forEach { group ->
            FilterChip(
                selected = state.selectedGroupId == group.id,
                onClick = { onSelect(group.id) },
                label = { Text(group.name) },
                // 设了颜色的分组前面一个色点，和下面卡片左边的色条对得上
                leadingIcon = group.color?.let { color ->
                    {
                        Box(
                            modifier = Modifier
                                .size(GroupDotSize)
                                .clip(CircleShape)
                                .background(groupAccentColor(color))
                                .testTag(BusHomeTags.groupChipDot(group.id))
                        )
                    }
                },
                modifier = Modifier.testTag(BusHomeTags.groupChip(group.id))
            )
        }
    }
}

/** 收藏的车：线路号 | 「科技园站上车」「→ 科技园」 | 到站 */
@Composable
private fun FavoriteStopRow(row: FavoriteRowUi.Stop, onClick: () -> Unit) {
    RowCard(onClick = onClick, testTag = BusHomeTags.favoriteRow(row.key), accent = row.groupColor?.let { groupAccentColor(it) }) {
        LineBadge(name = row.lineName)
        Spacer(modifier = Modifier.width(TrackrTheme.dimens.spaceM))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.boardText, style = MaterialTheme.typography.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = row.direction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(TrackrTheme.dimens.spaceS))
        ArrivalTrailing(arrival = row.arrival)
    }
}

/**
 * 收藏的站（issue #47，设计稿方案 A「一行」）：绿色图钉 |「科技园站 · 往北」「19 2 分 · 325 4 分 · M194 9 分」| 最先到那辆。
 * 整行点开站点页（停在默认站台）。
 */
@Composable
private fun FavoriteStationRow(row: FavoriteRowUi.Station, onClick: () -> Unit) {
    RowCard(onClick = onClick, testTag = BusHomeTags.favoriteRow(row.key), accent = row.groupColor?.let { groupAccentColor(it) }) {
        StationBadge(muted = row.status == StationStatus.NotFound)
        Spacer(modifier = Modifier.width(TrackrTheme.dimens.spaceM))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.item,
                color = if (row.status == StationStatus.NotFound) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when (row.status) {
                StationStatus.Ready -> StationLinesText(row.lines)
                StationStatus.Loading -> SubText(stringResource(BusRes.string.bus_station_loading))
                StationStatus.NotFound -> SubText(stringResource(BusRes.string.bus_station_not_found))
            }
        }
        row.arrival?.let { arrival ->
            Spacer(modifier = Modifier.width(TrackrTheme.dimens.spaceS))
            ArrivalTrailing(arrival = arrival)
        }
        if (row.status == StationStatus.NotFound) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 「19 2 分 · 325 4 分 · M194 9 分」：线路号加粗，放不下就截掉 */
@Composable
private fun StationLinesText(lines: List<StationLineUi>) {
    val emphasis = MaterialTheme.colorScheme.onSurface
    val text = buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append("  ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = emphasis)) { append(line.lineName) }
            if (line.short.isNotBlank()) {
                append(" ")
                append(line.short)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SubText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 收藏的站的徽标：绿底图钉，和蓝色线路号、路线图标区分开；站查不到时灰色 */
@Composable
internal fun StationBadge(muted: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (muted) MaterialTheme.colorScheme.surfaceVariant else TrackrTheme.colors.goContainer,
        contentColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else TrackrTheme.colors.go,
        modifier = modifier
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_place),
                contentDescription = stringResource(BusRes.string.bus_station_icon),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 收藏的路线：「家 → 公司」「325 › 19 · 换乘 1 次」 */
@Composable
private fun FavoriteRouteRow(row: FavoriteRowUi.Route, onClick: () -> Unit) {
    RowCard(onClick = onClick, testTag = BusHomeTags.favoriteRow(row.key), accent = row.groupColor?.let { groupAccentColor(it) }) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, style = MaterialTheme.typography.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.linesText.isNotBlank()) {
                Text(
                    text = row.linesText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = vectorResource(Res.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 分组颜色的色点（chip 前） */
private val GroupDotSize = 10.dp

/** 卡片左边分组颜色色条的宽度 */
private val AccentStripeWidth = 4.dp

/**
 * 一行一张卡（圆角 16，页面左右 12）。
 * @param accent 收藏所在分组的颜色（issue #38）：卡片左边画一条色条（跟着圆角裁），null 不画
 */
@Composable
internal fun RowCard(
    onClick: () -> Unit,
    testTag: String,
    accent: Color? = null,
    content: @Composable RowScope.() -> Unit
) {
    val dimens = TrackrTheme.dimens
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (accent != null) {
                        Modifier
                            .drawBehind { drawRect(color = accent, size = Size(AccentStripeWidth.toPx(), size.height)) }
                            .testTag(BusHomeTags.accentStripe(testTag))
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = dimens.cardPadding, vertical = dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** 空状态一句话（v3） */
@Composable
internal fun EmptyHint(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TrackrTheme.dimens.screenPadding + 4.dp, vertical = TrackrTheme.dimens.spaceS)
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}
