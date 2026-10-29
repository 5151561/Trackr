package com.trackr.ui.screens.linedetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.TrackrApp
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.service.BusTrackingService
import com.trackr.ui.components.ExpressiveRouteRail
import com.trackr.ui.components.RouteRailLayoutMode
import com.trackr.ui.components.TransitCodeBottomSheet
import com.trackr.ui.theme.busColors
import com.trackr.ui.theme.expressiveRotateSpringSpec
import com.trackr.ui.theme.expressiveSpringSpec
import com.trackr.util.TransitCodeHelper

/**
 * 线路详情页面（Material Design 3 Expressive 规范）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun LineDetailScreen(
    viewModel: LineDetailViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val repository = TrackrApp.instance.repository
    val activeTracking by repository.activeTrackingSession.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val busColors = MaterialTheme.busColors

    val lineDetail = uiState.lineDetail
    val selectedStop = uiState.selectedStop

    // 换向旋转弹簧动画
    val animatedFlipRotation by animateFloatAsState(
        targetValue = uiState.flipRotationDegrees,
        animationSpec = expressiveRotateSpringSpec(),
        label = "flip_rotation"
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 底部 M3E 悬浮胶囊工具栏 (Floating Pill Toolbar)
            BottomFloatingToolbar(
                isFavorite = uiState.isFavorite,
                flipRotation = animatedFlipRotation,
                hasReverseDirection = lineDetail?.reverseDirection != null,
                countdownSeconds = uiState.refreshCountdownSeconds,
                isRefreshing = uiState.isRefreshing,
                onToggleFavorite = { viewModel.toggleFavoriteStop() },
                onSwitchDirection = { viewModel.switchOppositeDirection() },
                onOpenTimetable = { viewModel.loadTimetable() },
                onOpenTransitCode = {
                    when (uiState.preferredTransitCodeProvider) {
                        TransitCodeProvider.ALIPAY -> TransitCodeHelper.openAlipayTransitCode(context)
                        TransitCodeProvider.UNIONPAY -> TransitCodeHelper.openUnionPayTransitCode(context)
                        TransitCodeProvider.ASK_ALWAYS -> viewModel.openTransitCodeSheet()
                    }
                },
                onLongClickTransitCode = { viewModel.openTransitCodeSheet() },
                onRefreshNow = { viewModel.refreshNow() }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading && lineDetail == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在连接实时公交数据…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
            return@Scaffold
        }

        if (lineDetail == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = uiState.errorMessage ?: "未获取到该线路信息",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.refreshNow() }) {
                        Text("重试")
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // 1. 顶部 Header：返回键、线路名徽标、数据源标签、首末站方向与运营胶囊
            TopLineHeader(
                lineDetail = lineDetail,
                onNavigateBack = onNavigateBack
            )

            // 错误/提醒提示条
            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.refreshNow() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "重试",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. 中部核心三态卡片（固定外层卡片容器与阴影，内部平滑尺寸过渡，杜绝阴影闪烁与裁切痕迹）
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = busColors.stationCardContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                AnimatedContent(
                    targetState = uiState.panelMode,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(durationMillis = 200, delayMillis = 60)
                        ) togetherWith fadeOut(
                            animationSpec = tween(durationMillis = 140)
                        ) using SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            }
                        )
                    },
                    contentAlignment = Alignment.TopCenter,
                    label = "panel_state_transition",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                ) { targetMode ->
                    when (targetMode) {
                        PanelMode.STATION_CARD -> {
                            StationArrivalContent(
                                lineDetail = lineDetail,
                                selectedStop = selectedStop,
                                isTrackingActive = activeTracking.isActive &&
                                        activeTracking.lineId == lineDetail.line.lineId &&
                                        activeTracking.targetOrder == selectedStop?.order,
                                alertThreshold = uiState.alertThresholdStops,
                                onThresholdChange = { viewModel.setAlertThreshold(it) },
                                onOpenBusList = { viewModel.openBusList() },
                                onSwitchOpposite = { viewModel.switchOppositeDirection() },
                                onToggleTracking = {
                                    val isTrackingThis = activeTracking.isActive &&
                                            activeTracking.lineId == lineDetail.line.lineId &&
                                            activeTracking.targetOrder == selectedStop?.order

                                    if (isTrackingThis) {
                                        BusTrackingService.stopTracking(context)
                                    } else if (selectedStop != null) {
                                        BusTrackingService.startTracking(
                                            context = context,
                                            cityId = viewModel.cityId,
                                            line = lineDetail.line,
                                            targetStop = selectedStop,
                                            totalStops = lineDetail.stops.size,
                                            alertThresholdStops = uiState.alertThresholdStops
                                        )
                                    }
                                }
                            )
                        }

                        PanelMode.BUS_LIST -> {
                            BusListContent(
                                lineDetail = lineDetail,
                                selectedStop = selectedStop,
                                onBack = { viewModel.backToStationCard() },
                                onSelectBus = { viewModel.selectBusToFollow(it) }
                            )
                        }

                        PanelMode.BUS_FOLLOW -> {
                            val followedBus = lineDetail.buses.find { it.busId == uiState.followedBusId }
                            BusFollowContent(
                                lineDetail = lineDetail,
                                followedBus = followedBus,
                                onBack = { viewModel.backToStationCard() }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. 下部走向条区域与布局切换栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "线路全景走向",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.layoutMode == RouteRailLayoutMode.Horizontal) "横向滚动" else "纵向时间轴",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.toggleLayoutMode() }
                ) {
                    Icon(
                        imageVector = if (uiState.layoutMode == RouteRailLayoutMode.Horizontal) {
                            Icons.Rounded.ViewAgenda
                        } else {
                            Icons.Rounded.ViewWeek
                        },
                        contentDescription = "切换走向条视图",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                ExpressiveRouteRail(
                    stops = lineDetail.stops,
                    buses = lineDetail.buses,
                    selectedStop = selectedStop,
                    selectedBusId = uiState.followedBusId,
                    layoutMode = uiState.layoutMode,
                    onSelectStop = { viewModel.selectStop(it) },
                    onSelectBus = { viewModel.selectBusToFollow(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(96.dp)) // 为底部悬浮工具栏留白
        }
    }

    // 4. 发车时刻表 BottomSheet 弹窗
    if (uiState.isTimetableSheetOpen && uiState.timetable != null) {
        TimetableBottomSheet(
            timetable = uiState.timetable!!,
            line = lineDetail?.line,
            onDismiss = { viewModel.dismissTimetable() }
        )
    }

    // 5. 乘车码选择 BottomSheet 弹窗
    if (uiState.isTransitCodeSheetOpen) {
        TransitCodeBottomSheet(
            onDismiss = { viewModel.dismissTransitCodeSheet() },
            onSelectProvider = { provider, saveAsDefault ->
                viewModel.dismissTransitCodeSheet()
                if (saveAsDefault) {
                    viewModel.setPreferredTransitCodeProvider(provider)
                }
                TransitCodeHelper.openTransitCode(context, provider)
            }
        )
    }
}

/**
 * 顶部线路元信息头
 */
@Composable
private fun TopLineHeader(
    lineDetail: BusLineDetail,
    onNavigateBack: () -> Unit
) {
    val line = lineDetail.line
    val busColors = MaterialTheme.busColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 线路名大标题
            Text(
                text = line.name,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 数据源标签（车来了 / 掌上公交）
            val isChelaile = line.source == DataSourceType.CHELAILE
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isChelaile) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = line.source.displayName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isChelaile) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // 开往方向副标题
        Text(
            text = "开往  ${line.endStop} （始发：${line.startStop}）",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 标签胶囊行：首末班、票价、总站数、当前在途车数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapsuleBadge("首 ${line.firstTime.ifEmpty { "—" }}")
            CapsuleBadge("末 ${line.lastTime.ifEmpty { "—" }}")
            if (line.price.isNotEmpty()) CapsuleBadge("票价 ${line.price}")
            CapsuleBadge("共 ${lineDetail.stops.size} 站")

            // 实时在途车辆数胶囊
            val busCount = lineDetail.buses.size
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (busCount > 0) busColors.busChipContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsBus,
                        contentDescription = null,
                        tint = if (busCount > 0) busColors.busChipContent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (busCount > 0) "当前 $busCount 辆车在途" else "暂无在途车辆",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (busCount > 0) busColors.busChipContent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CapsuleBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * 状态 1：站点到站内容 (`STATION_CARD`)
 */
@Composable
private fun StationArrivalContent(
    lineDetail: BusLineDetail,
    selectedStop: BusStop?,
    isTrackingActive: Boolean,
    alertThreshold: Int,
    onThresholdChange: (Int) -> Unit,
    onOpenBusList: () -> Unit,
    onSwitchOpposite: () -> Unit,
    onToggleTracking: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val currentOrder = selectedStop?.order ?: 1

    // 寻找最近一辆未过本站的在途车
    val upcomingBus = remember(lineDetail.buses, currentOrder) {
        lineDetail.buses
            .filter { it.order <= currentOrder }
            .maxByOrNull { it.order.toFloat() + it.segmentProgress }
    }

    val stopsRemaining = if (upcomingBus != null) {
        currentOrder - upcomingBus.order
    } else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
            // 候车站名与状态标
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedStop?.name ?: "选择候车站",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!selectedStop?.statusNote.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = busColors.conditionStopBorder.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = selectedStop!!.statusNote!!,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = busColors.conditionStopBorder,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "第 $currentOrder 站",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 醒目到站预报大字
            if (upcomingBus != null) {
                if (stopsRemaining != null && stopsRemaining > 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "下一辆还有 ",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$stopsRemaining",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Black,
                            color = busColors.busArrivingAmber
                        )
                        Text(
                            text = " 个站，到达该站！",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Text(
                        text = if (upcomingBus.isArrived) "车辆已到站！" else "车辆正在进站！",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = if (upcomingBus.isArrived) busColors.busRunningGreen else busColors.busArrivingAmber
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 车辆信息行：车牌、倒计时、预计到达钟点、速度
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = upcomingBus.plateOrFleetNo,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = RouteProjectionMath.formatEtaText(upcomingBus.etaSeconds, upcomingBus.isArrived),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = busColors.busArrivingAmber
                    )

                    if (!upcomingBus.expectedArrivalTimeText.isNullOrEmpty()) {
                        Text(
                            text = "${upcomingBus.expectedArrivalTimeText} 到",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (upcomingBus.speedKmh > 0) {
                        Text(
                            text = "${upcomingBus.speedKmh.toInt()} km/h",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // M3E 进度条：计算此车到本站的相对进度
                val progress = remember(stopsRemaining, upcomingBus.segmentProgress) {
                    if (stopsRemaining == null || stopsRemaining <= 0) 1.0f
                    else (1.0f - (stopsRemaining.toFloat() / (currentOrder.coerceAtLeast(1)))).coerceIn(0.05f, 0.95f)
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = busColors.busRunningGreen,
                    trackColor = busColors.routeTrackInactive,
                    strokeCap = StrokeCap.Round
                )
            } else {
                Text(
                    text = if (lineDetail.buses.isNotEmpty()) "当前车辆均已过该站" else "当前暂无在途车辆",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "运营状态：${lineDetail.operatingState.defaultLabel}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 快捷入口按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenBusList,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "查看全线车辆 (${lineDetail.buses.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onSwitchOpposite,
                    enabled = lineDetail.reverseDirection != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "切换反向同名站",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 提醒阈值单选芯片（1 站 / 2 站 / 3 站）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "提前到站震动预警：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3).forEach { stops ->
                        FilterChip(
                            selected = alertThreshold == stops,
                            onClick = { onThresholdChange(stops) },
                            label = { Text("提前 ${stops} 站", fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 主操作大按钮：开启/停止通知栏实时追踪
            Button(
                onClick = onToggleTracking,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTrackingActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = if (isTrackingActive) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTrackingActive) "正在通知栏追踪中 · 点击结束追踪" else "开启上车/到站通知栏实时追踪",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
}

/**
 * 状态 2：车辆列表内容 (`BUS_LIST`)
 */
@Composable
private fun BusListContent(
    lineDetail: BusLineDetail,
    selectedStop: BusStop?,
    onBack: () -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val currentOrder = selectedStop?.order ?: 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回站点卡",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "到「${selectedStop?.name ?: "当前站"}」的全部车辆",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "共 ${lineDetail.buses.size} 辆",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (lineDetail.buses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前线路上暂无在途运营车辆",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                // 排序：未过本站（按距离升序）在前，已过本站沉底
                val sortedBuses = remember(lineDetail.buses, currentOrder) {
                    lineDetail.buses.sortedWith(
                        compareBy<LiveBus> { it.order > currentOrder }
                            .thenBy { if (it.etaSeconds > 0) it.etaSeconds else Int.MAX_VALUE }
                            .thenBy { currentOrder - it.order }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sortedBuses.forEach { bus ->
                        val isPassed = bus.order > currentOrder
                        val isAtStop = bus.order == currentOrder && bus.isArrived
                        val stopsLeft = currentOrder - bus.order

                        Surface(
                            onClick = { onSelectBus(bus) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isPassed) busColors.busPassedGray.copy(alpha = 0.2f) else busColors.busChipContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.DirectionsBus,
                                            contentDescription = null,
                                            tint = if (isPassed) busColors.busPassedGray else busColors.busChipContent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = bus.plateOrFleetNo,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPassed) busColors.busPassedGray else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (bus.speedKmh > 0 && !isPassed) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "${bus.speedKmh.toInt()} km/h",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = when {
                                            isPassed -> "已过该站"
                                            isAtStop -> "已到站"
                                            stopsLeft > 0 -> "还有 $stopsLeft 站 · 正在接近第 ${bus.order} 站"
                                            else -> "正在进站"
                                        },
                                        fontSize = 12.sp,
                                        color = if (isPassed) busColors.busPassedGray else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = when {
                                            isPassed -> "已过站"
                                            isAtStop -> "已到站"
                                            bus.etaSeconds > 0 -> RouteProjectionMath.formatEtaText(bus.etaSeconds)
                                            else -> "无预测"
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            isPassed -> busColors.busPassedGray
                                            isAtStop -> busColors.busRunningGreen
                                            else -> busColors.busArrivingAmber
                                        }
                                    )
                                    if (!isPassed && !bus.expectedArrivalTimeText.isNullOrEmpty()) {
                                        Text(
                                            text = "${bus.expectedArrivalTimeText} 到",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "查看单车跟随",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
}

/**
 * 状态 3：单车跟随行程轴内容 (`BUS_FOLLOW`)
 */
@Composable
private fun BusFollowContent(
    lineDetail: BusLineDetail,
    followedBus: LiveBus?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
            // 顶栏：返回按钮与车牌号
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "单车跟随 · ${followedBus?.plateOrFleetNo ?: "指定车辆"}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "开往  ${lineDetail.line.endStop}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (followedBus == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "这辆车已不在线上（可能已收班或已到达终点）。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            } else {
                val stopsLeftToTerminus = (lineDetail.stops.size - followedBus.order).coerceAtLeast(0)

                // 统计概要卡
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("距终点", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$stopsLeftToTerminus 站", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("时速", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${followedBus.speedKmh.toInt()} km/h", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("到终点预测", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (followedBus.etaSeconds > 0) RouteProjectionMath.formatEtaText(followedBus.etaSeconds) else "无预测",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = busColors.busArrivingAmber
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "全线途经站行程流：",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                val scrollState = rememberScrollState()

                // 当选中的车辆位置发生变化时，平滑滚动使当前车辆所在站点居中可见
                LaunchedEffect(followedBus.order) {
                    val currentIndex = (followedBus.order - 1).coerceAtLeast(0)
                    val scrollOffset = ((currentIndex - 2).coerceAtLeast(0) * 110)
                    scrollState.animateScrollTo(scrollOffset)
                }

                // 纵向行程节点流（展示已过站、当前接近站、未到站）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .verticalScroll(scrollState)
                ) {
                    lineDetail.stops.forEachIndexed { index, stop ->
                        val isPassed = stop.order < followedBus.order
                        val isCurrent = stop.order == followedBus.order
                        val isFirst = index == 0
                        val isLast = index == lineDetail.stops.size - 1
                        val isNextStop = stop.order == followedBus.order + 1

                        // 计算相邻站点轨道的严格连贯颜色，保证接缝处颜色严格一致无突变
                        val topTrackColor = if (stop.order <= followedBus.order) {
                            busColors.busPassedGray.copy(alpha = 0.35f)
                        } else {
                            busColors.routeTrackActive
                        }
                        val bottomTrackColor = if (stop.order < followedBus.order) {
                            busColors.busPassedGray.copy(alpha = 0.35f)
                        } else {
                            busColors.routeTrackActive
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. 左侧时间轴轨道与节点 (Canvas 占满整行高度，上下出界 1.5px 消除浮点裁切误差，实现 100% 连续无断点)
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val centerX = size.width / 2f
                                    val centerY = size.height / 2f
                                    val overlap = 1.5f // 消除浮点缝隙
                                    val strokeWidthPx = 3.dp.toPx()

                                    // 上半段轨道连线
                                    if (!isFirst) {
                                        drawLine(
                                            color = topTrackColor,
                                            start = Offset(centerX, -overlap),
                                            end = Offset(centerX, centerY),
                                            strokeWidth = strokeWidthPx
                                        )
                                    }

                                    // 下半段轨道连线
                                    if (!isLast) {
                                        drawLine(
                                            color = bottomTrackColor,
                                            start = Offset(centerX, centerY),
                                            end = Offset(centerX, size.height + overlap),
                                            strokeWidth = strokeWidthPx
                                        )
                                    }
                                }

                                // 节点圆点呈现
                                when {
                                    isCurrent -> {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .background(busColors.busArrivingAmber, CircleShape)
                                                .border(2.dp, Color.White, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DirectionsBus,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                    isPassed -> {
                                        // 已过站：柔和静音小灰点，与未到站形成鲜明体积与色彩对比
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(busColors.busPassedGray.copy(alpha = 0.45f), CircleShape)
                                        )
                                    }
                                    else -> {
                                        // 未到站（前方活跃路线）：高对比度翡翠绿，鲜亮饱满
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(busColors.routeTrackActive, CircleShape)
                                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 2. 站点右侧信息内容区（将内边距放在内容区，确保左侧轨道整行无缝相接）
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 36.dp)
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 站点序号
                                Text(
                                    text = String.format("%02d", stop.order),
                                    fontSize = 11.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        isCurrent -> busColors.busArrivingAmber
                                        isPassed -> busColors.busPassedGray.copy(alpha = 0.6f)
                                        else -> busColors.routeTrackActive
                                    },
                                    modifier = Modifier.width(22.dp)
                                )

                                // 站点名称
                                Text(
                                    text = stop.name,
                                    fontSize = if (isCurrent) 14.sp else 13.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else if (!isPassed) FontWeight.Medium else FontWeight.Normal,
                                    color = when {
                                        isCurrent -> busColors.busArrivingAmber
                                        isPassed -> busColors.busPassedGray
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                // 右侧状态指示与徽标
                                when {
                                    isCurrent -> {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = busColors.busArrivingAmber.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = if (followedBus.isArrived) "已到站停靠" else "正在接近",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = busColors.busArrivingAmber,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    isNextStop -> {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = busColors.routeTrackActive.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = "下一站",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = busColors.routeTrackActive,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    isPassed -> {
                                        Text(
                                            text = "已过",
                                            fontSize = 11.sp,
                                            color = busColors.busPassedGray.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(end = 4.dp)
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

/**
 * 底部 M3E 悬浮胶囊工具栏
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomFloatingToolbar(
    isFavorite: Boolean,
    flipRotation: Float,
    hasReverseDirection: Boolean,
    countdownSeconds: Int,
    isRefreshing: Boolean,
    onToggleFavorite: () -> Unit,
    onSwitchDirection: () -> Unit,
    onOpenTimetable: () -> Unit,
    onOpenTransitCode: () -> Unit,
    onLongClickTransitCode: () -> Unit,
    onRefreshNow: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 收藏候车站
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "收藏候车站",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 2. 换向按钮（带 180° 旋转弹簧动画）
                IconButton(
                    onClick = onSwitchDirection,
                    enabled = hasReverseDirection
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = "换向",
                        modifier = Modifier.graphicsLayer { rotationZ = flipRotation },
                        tint = if (hasReverseDirection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }

                // 3. 全天发车时刻表
                IconButton(onClick = onOpenTimetable) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = "时刻表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 4. 一键乘车码（单击秒开 / 长按呼出弹窗切换平台）
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = onOpenTransitCode,
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongClickTransitCode()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = "乘车码（长按切换平台）",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // 5. 立即刷新（带倒计时或旋转进度）
                IconButton(onClick = onRefreshNow) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "立即刷新",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${countdownSeconds}s",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 发车时刻表 BottomSheet 弹窗
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TimetableBottomSheet(
    timetable: LineTimetable,
    line: com.trackr.domain.model.BusLineDirection?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "${line?.name ?: timetable.lineName} 发车时刻表",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "始发站：${line?.startStop ?: ""} → 终点：${line?.endStop ?: ""}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 下一班发车提示
            if (!timetable.nextDeparture.isNullOrEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "预计下一班发车：${timetable.nextDeparture} （${timetable.nextDepartureSource ?: "预测班次"}）",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 发车分块间隔（如高峰 8 分钟，平峰 15 分钟）
            if (timetable.segments.isNotEmpty()) {
                Text(
                    text = "运营时段发车间隔：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                timetable.segments.forEach { seg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${seg.fromTime} – ${seg.toTime}", fontSize = 13.sp)
                        Text(
                            text = if (seg.intervalTips.isNotEmpty()) seg.intervalTips else if (seg.intervalMinutes > 0) "${seg.intervalMinutes} 分钟/班" else "计划发车",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 今日计划班次
            if (timetable.todayDepartures.isNotEmpty()) {
                Text(
                    text = "今日预估班次表（共 ${timetable.todayDepartures.size} 班）：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    timetable.todayDepartures.forEach { time ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = time,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 昨日实际班次回落
            if (timetable.yesterdayDepartures.isNotEmpty() && timetable.todayDepartures.isEmpty()) {
                Text(
                    text = "昨日实际发车时刻（参考）：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    timetable.yesterdayDepartures.forEach { time ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = time,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (timetable.depDesc.isNotEmpty()) {
                Text(
                    text = timetable.depDesc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
