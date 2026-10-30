package com.trackr.ui.screens.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.trackr.TrackrApp
import com.trackr.ui.components.ScheduledRemindersBottomSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.util.TransitCodeHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: DataSourceType, lineName: String) -> Unit,
    onNavigateToStopDetail: ((cityId: String, stopId: String, stopName: String, physicalStId: String, namesakeStId: String, lat: Double, lng: Double) -> Unit)? = null,
    onNavigateToSearch: () -> Unit,
    onNavigateToMore: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 向上滑动时标题栏平滑向上收起为单行栏，向下拉时展开
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            HomeTopBar(
                city = state.currentCity,
                scrollBehavior = scrollBehavior,
                onCityClick = { viewModel.setCityPickerOpen(true) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 80.dp + 28.dp
            )
        ) {
            // 1. 若当前有正在运行的通知栏实时追踪，置顶显示呼吸动效卡片
            if (state.activeTracking.isActive) {
                item(key = "active_tracking_banner", contentType = "active_tracking_banner") {
                    ActiveTrackingBanner(
                        tracking = state.activeTracking,
                        onCardClick = {
                            onNavigateToLineDetail(
                                state.activeTracking.cityId,
                                state.activeTracking.lineId,
                                state.activeTracking.targetOrder,
                                state.activeTracking.targetStopId,
                                DataSourceType.CHELAILE,
                                state.activeTracking.lineName
                            )
                        },
                        onStopTracking = { viewModel.stopTracking() }
                    )
                }
            }

            // 2. 搜索入口胶囊栏
            item(key = "search_entry_bar", contentType = "search_entry_bar") {
                SearchEntryBar(
                    cityName = state.currentCity.cityName,
                    onClick = onNavigateToSearch
                )
            }

            // 3. 一键唤醒乘车码双胶囊卡片
            item(key = "transit_code_card", contentType = "transit_code_card") {
                TransitCodeQuickCard(
                    onOpenAlipay = { TransitCodeHelper.openAlipayTransitCode(context) },
                    onOpenUnionPay = { TransitCodeHelper.openUnionPayTransitCode(context) }
                )
            }

            // 4. 权限或定位提示
            if (state.locationError != null) {
                item(key = "location_hint_bar", contentType = "location_hint_bar") {
                    LocationHintBar(
                        message = state.locationError ?: "",
                        onRefreshLocation = { viewModel.refreshLocationAndNearby() }
                    )
                }
            }

            // 5. 「常用候车站 / 我的收藏」横向滑动卡片区
            item(key = "favorites_section", contentType = "favorites_section") {
                FavoritesSection(
                    favorites = state.favorites,
                    favoritesRealtime = state.favoritesRealtime,
                    onFavoriteClick = { fav ->
                        onNavigateToLineDetail(
                            fav.cityId,
                            fav.lineId,
                            fav.targetOrder,
                            fav.targetStopId,
                            fav.source,
                            fav.lineName
                        )
                    }
                )
            }

            // 6. 「附近公交站点」手风琴卡片流
            item(key = "nearby_stops_header", contentType = "nearby_stops_header") {
                NearbyStopsHeader(
                    cityName = state.currentCity.cityName,
                    isLoading = state.isLoadingNearby || state.isRefreshing || state.isLocating,
                    onRefresh = { viewModel.refresh() }
                )
            }

            if (state.isLoadingNearby && state.nearbyStops.isEmpty()) {
                item(key = "nearby_loading", contentType = "nearby_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                }
            } else if (state.nearbyStops.isEmpty()) {
                item(key = "nearby_empty", contentType = "nearby_empty") {
                    EmptyNearbyView(onRetry = { viewModel.refreshLocationAndNearby() })
                }
            } else {
                items(
                    items = state.nearbyStops,
                    key = { "${it.source.name}_${it.stopId.ifBlank { "${it.name}_${it.wgsLat}_${it.wgsLng}" }}" },
                    contentType = { "nearby_stop" }
                ) { stop ->
                    NearbyStopCard(
                        stop = stop,
                        cityId = state.currentCity.cityId,
                        onStopClick = onNavigateToStopDetail?.let { nav ->
                            {
                                nav(
                                    state.currentCity.cityId,
                                    stop.stopId,
                                    stop.name,
                                    stop.physicalStId.orEmpty(),
                                    stop.namesakeStId.orEmpty(),
                                    stop.wgsLat,
                                    stop.wgsLng
                                )
                            }
                        },
                        onLineClick = { lineStatus ->
                            onNavigateToLineDetail(
                                state.currentCity.cityId,
                                lineStatus.line.lineId,
                                lineStatus.targetOrder,
                                stop.stopId,
                                lineStatus.line.source,
                                lineStatus.line.name
                            )
                        }
                    )
                }
            }
        }
    }

    // 城市切换选择器弹窗 BottomSheet
    if (state.isCityPickerOpen) {
        CityPickerBottomSheet(
            currentCity = state.currentCity,
            locatedCity = state.locatedCity,
            selectedProvince = state.selectedProvince,
            cities = state.cities,
            searchQuery = state.citySearchQuery,
            onSearchQueryChange = { viewModel.updateCitySearchQuery(it) },
            onSelectProvince = { viewModel.selectProvince(it) },
            onSelectCity = { viewModel.selectCity(it) },
            onRelocate = { viewModel.refreshLocationAndNearby() },
            onDismiss = { viewModel.setCityPickerOpen(false) }
        )
    }
}

/**
 * 顶部动态大标题栏（支持滚动折叠与展开，包含城市选择胶囊入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    city: TransitCity,
    scrollBehavior: TopAppBarScrollBehavior,
    onCityClick: () -> Unit
) {
    LargeTopAppBar(
        title = {
            Text(
                text = "车在路上",
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            // 城市选择胶囊按钮
            Surface(
                onClick = onCityClick,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "城市",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = city.cityName,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "选择城市",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

/**
 * 实时追踪呼吸卡片（当后台有正在运行的通知栏追踪时置顶展示）
 */
@Composable
private fun ActiveTrackingBanner(
    tracking: TrackingSessionState,
    onCardClick: () -> Unit,
    onStopTracking: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        onClick = onCardClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 脉冲呼吸指示图标
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { alpha = alphaAnim }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "追踪中",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "正在实时追踪",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "【${tracking.lineName}】",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (tracking.stopsRemaining >= 0) {
                        val eta = RouteProjectionMath.formatEtaText(tracking.etaSeconds, tracking.isBusArrivedAtStop)
                        "候车站：${tracking.targetStopName} · 还有 ${tracking.stopsRemaining} 站 ($eta)"
                    } else {
                        "目标候车站：${tracking.targetStopName} · ${tracking.statusSummary}"
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onStopTracking,
                modifier = Modifier.clip(CircleShape),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "结束追踪",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * 首页混合搜索胶囊栏
 */
@Composable
private fun SearchEntryBar(
    cityName: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "搜索 $cityName 公交线路、车站（如 1路、人民广场）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 定位提示胶囊
 */
@Composable
private fun LocationHintBar(
    message: String,
    onRefreshLocation: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.NearMe,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "重试定位",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onRefreshLocation() }
                    .padding(4.dp)
            )
        }
    }
}

/**
 * 「常用候车站 / 我的收藏」区
 */
@Composable
private fun FavoritesSection(
    favorites: List<FavoriteStationLine>,
    favoritesRealtime: Map<String, NearbyStop>,
    onFavoriteClick: (FavoriteStationLine) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "收藏",
                    tint = Color(0xFFE91E63),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "常用候车站",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (favorites.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${favorites.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (favorites.isEmpty()) {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsBus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "在公交线路详情中点击爱心，可将常用通勤站固定于此并推送到桌面小组件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = favorites,
                    key = { it.id },
                    contentType = { "favorite_stop" }
                ) { fav ->
                    val realtimeStop = favoritesRealtime[fav.id]
                    FavoriteCard(
                        favorite = fav,
                        realtimeStop = realtimeStop,
                        onClick = { onFavoriteClick(fav) }
                    )
                }
            }
        }
    }
}

/**
 * 单个收藏候车站卡片
 */
@Composable
private fun FavoriteCard(
    favorite: FavoriteStationLine,
    realtimeStop: NearbyStop?,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 2.dp
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = favorite.lineName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (favorite.source == DataSourceType.CHELAILE) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                ) {
                    Text(
                        text = favorite.source.displayName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (favorite.source == DataSourceType.CHELAILE) Color(0xFF2E7D32) else Color(0xFFE65100),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "开往 ${favorite.endStop}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "候车站: ${favorite.targetStopName}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 实时倒计时文案
            val lineStatus = realtimeStop?.lines?.firstOrNull { it.line.lineId == favorite.lineId }
            val approachingBus = lineStatus?.approachingBuses?.firstOrNull()
            val etaText = when {
                lineStatus != null && lineStatus.statusText.isNotBlank() -> lineStatus.statusText
                approachingBus != null -> {
                    val targetOrder = favorite.targetOrder
                    val busOrder = approachingBus.order
                    if (targetOrder > 0 && busOrder > 0) {
                        val remaining = targetOrder - busOrder
                        val etaPart = RouteProjectionMath.formatEtaText(approachingBus.etaSeconds)
                        when {
                            remaining == 0 && approachingBus.isArrived -> "已到站"
                            remaining <= 0 -> "即将进站"
                            etaPart.isNotBlank() -> "还有 $remaining 站 · $etaPart"
                            else -> "还有 $remaining 站"
                        }
                    } else if (approachingBus.isArrived) {
                        "已到站"
                    } else if (approachingBus.etaSeconds > 0) {
                        "即将进站 · ${RouteProjectionMath.formatEtaText(approachingBus.etaSeconds)}"
                    } else {
                        "在途行驶中"
                    }
                }
                else -> "实时连接中…"
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = etaText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 附近公交站区域头部
 */
@Composable
private fun NearbyStopsHeader(
    cityName: String,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "附近公交站",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        IconButton(
            onClick = onRefresh,
            enabled = !isLoading,
            modifier = Modifier.clip(CircleShape),
            shape = CircleShape
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新附近站点与定位",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 单个附近公交站手风琴卡片
 */
@Composable
private fun NearbyStopCard(
    stop: NearbyStop,
    cityId: String,
    onStopClick: (() -> Unit)? = null,
    onLineClick: (NearbyStopLineStatus) -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 站点名、距离与地铁换乘标识（支持点击进入站点详情）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onStopClick != null) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onStopClick() }
                        } else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stop.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (stop.subwayLineNames.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Subway,
                                    contentDescription = "地铁换乘",
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = stop.subwayLineNames.first(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val distText = RouteProjectionMath.formatDistanceText(stop.distanceMeters)
                    if (distText.isNotBlank()) {
                        Text(
                            text = distText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onStopClick != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "进入站点详情",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 途经该站的各条公交线路实时到站流
            if (stop.lines.isEmpty()) {
                Text(
                    text = "正在查询途经公交实时数据…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stop.lines.forEach { lineStatus ->
                        NearbyLineItemRow(
                            lineStatus = lineStatus,
                            onClick = { onLineClick(lineStatus) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 站点卡片内的单条线路行
 */
@Composable
private fun NearbyLineItemRow(
    lineStatus: NearbyStopLineStatus,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lineStatus.line.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "往 ${lineStatus.line.endStop}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 倒计时文案徽标
            val approachingBus = lineStatus.approachingBuses.firstOrNull()
            val countdownText = when {
                lineStatus.statusText.isNotBlank() -> lineStatus.statusText
                approachingBus != null -> {
                    val targetOrder = lineStatus.targetOrder
                    val busOrder = approachingBus.order
                    if (targetOrder > 0 && busOrder > 0) {
                        val remainingStops = targetOrder - busOrder
                        val etaPart = RouteProjectionMath.formatEtaText(approachingBus.etaSeconds)
                        when {
                            remainingStops == 0 && approachingBus.isArrived -> "已到站"
                            remainingStops <= 0 -> "即将进站"
                            etaPart.isNotBlank() -> "还有 $remainingStops 站 · $etaPart"
                            else -> "还有 $remainingStops 站"
                        }
                    } else if (approachingBus.isArrived) {
                        "已到站"
                    } else if (approachingBus.etaSeconds > 0) {
                        "即将进站 · ${RouteProjectionMath.formatEtaText(approachingBus.etaSeconds)}"
                    } else {
                        "在途行驶中"
                    }
                }
                else -> "暂无在途车辆"
            }

            val isArrivedOrNear = countdownText.contains("已到站") || countdownText.contains("即将进站")
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isArrivedOrNear) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = countdownText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isArrivedOrNear) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 附近无站点时的空占位
 */
@Composable
private fun EmptyNearbyView(onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.NearMe,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "附近 1 公里内暂未检索到公交站点",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onRetry() }
            ) {
                Text(
                    text = "重新获取定位",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * 全国 480+ 城市两级（省份 → 城市）选择器 BottomSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CityPickerBottomSheet(
    currentCity: TransitCity,
    locatedCity: TransitCity?,
    selectedProvince: String,
    cities: List<TransitCity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelectProvince: (String) -> Unit,
    onSelectCity: (TransitCity) -> Unit,
    onRelocate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            // 标题与关闭按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "切换城市",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "按 省 / 直辖市 分级快速查找全国 480+ 城市",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 搜索过滤框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索城市中文名或拼音（如：玉林 / yulin）") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "搜索")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (searchQuery.isNotBlank()) {
                // 搜索结果直接展示
                val matchingCities = remember(cities, searchQuery) {
                    val q = searchQuery.trim().lowercase()
                    cities.filter {
                        it.cityName.lowercase().contains(q) ||
                        it.pinyin.lowercase().contains(q) ||
                        (it.province.ifBlank { ProvinceCityCatalog.inferProvince(it.cityName) }).lowercase().contains(q)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "找到 ${matchingCities.size} 个相关城市",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = matchingCities,
                        key = { it.cityId },
                        contentType = { "matching_city" }
                    ) { city ->
                        val prov = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
                        val isCurrent = city.cityId == currentCity.cityId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectCity(city) },
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = city.cityName,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = prov,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isCurrent) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = "当前",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    if (city.cityId == locatedCity?.cityId) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = "定位",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    if (city.mygolbsCityCode != null) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFFFF3E0)
                                        ) {
                                            Text(
                                                text = "双源",
                                                fontSize = 10.sp,
                                                color = Color(0xFFE65100),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 非搜索状态：顶部定位与热门城市
                val hotCities = remember(cities) {
                    val hot = cities.filter { it.isHot }
                    if (hot.isNotEmpty()) hot.take(8) else cities.take(8)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (locatedCity != null) "定位: ${locatedCity.cityName}" else "已选: ${currentCity.cityName}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRelocate() },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "重新定位",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 热门城市 Chip 行
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 6.dp)
                ) {
                    items(
                        items = hotCities,
                        key = { "hot_${it.cityId}" },
                        contentType = { "hot_city" }
                    ) { city ->
                        FilterChip(
                            selected = city.cityId == currentCity.cityId,
                            onClick = { onSelectCity(city) },
                            label = { Text(city.cityName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 省份-城市双列联动选择器
                val provinceMap = remember(cities) {
                    val map = linkedMapOf<String, MutableList<TransitCity>>()
                    for (p in ProvinceCityCatalog.PROVINCE_ORDER) {
                        map[p] = mutableListOf()
                    }
                    for (city in cities) {
                        val prov = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
                        val list = map.getOrPut(prov) { mutableListOf() }
                        list.add(city)
                    }
                    map.filterValues { it.isNotEmpty() }
                }

                val availableProvinces = remember(provinceMap) { provinceMap.keys.toList() }
                val activeProvince = if (selectedProvince in provinceMap.keys) {
                    selectedProvince
                } else {
                    availableProvinces.firstOrNull() ?: "直辖市"
                }
                val provinceCities = provinceMap[activeProvince].orEmpty()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    // 左侧省份列表 (width 116.dp)
                    Surface(
                        modifier = Modifier
                            .width(116.dp)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = availableProvinces,
                                key = { it },
                                contentType = { "available_province" }
                            ) { prov ->
                                val isSelected = prov == activeProvince
                                val count = provinceMap[prov]?.size ?: 0
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectProvince(prov) },
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        Color.Transparent
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .width(4.dp)
                                                    .height(18.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        } else {
                                            Spacer(modifier = Modifier.width(10.dp))
                                        }

                                        Text(
                                            text = prov,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.weight(1f)
                                        )

                                        Text(
                                            text = "$count",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 右侧城市列表 (weight 1f, padding(start = 10.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 10.dp)
                    ) {
                        Text(
                            text = "$activeProvince · 共 ${provinceCities.size} 城",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                items = provinceCities,
                                key = { it.cityId },
                                contentType = { "province_city" }
                            ) { city ->
                                val isCurrent = city.cityId == currentCity.cityId
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onSelectCity(city) },
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = city.cityName,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = if (isCurrent) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (isCurrent) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.primary
                                                ) {
                                                    Text(
                                                        text = "当前",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            if (city.cityId == locatedCity?.cityId) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                                ) {
                                                    Text(
                                                        text = "定位",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            if (city.mygolbsCityCode != null) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFFFF3E0)
                                                ) {
                                                    Text(
                                                        text = "双源",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFFE65100),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 首页一键唤醒乘车码快捷双胶囊卡片
 */
@Composable
private fun TransitCodeQuickCard(
    onOpenAlipay: () -> Unit,
    onOpenUnionPay: () -> Unit
) {
    val alipayColor = Color(0xFF1677FF)
    val unionpayColor = Color(0xFFE60012)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 支付宝乘车码胶囊卡片
        Surface(
            onClick = onOpenAlipay,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = alipayColor.copy(alpha = 0.08f),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = alipayColor,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(5.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "支付宝乘车码",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "一键秒开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 云闪付乘车码胶囊卡片
        Surface(
            onClick = onOpenUnionPay,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = unionpayColor.copy(alpha = 0.08f),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = unionpayColor,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(5.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "云闪付乘车码",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "银联优惠直达",
                        style = MaterialTheme.typography.labelSmall,
                        color = unionpayColor
                    )
                }
            }
        }
    }
}

