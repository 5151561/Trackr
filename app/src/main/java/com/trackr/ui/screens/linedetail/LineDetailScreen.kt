package com.trackr.ui.screens.linedetail

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.TrackrApp
import com.trackr.domain.model.ScheduledReminder
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.service.BusTrackingService
import com.trackr.service.vendor.XiaomiFocusAdapter
import com.trackr.ui.components.ExpressiveRouteRail
import com.trackr.ui.components.FocusPermissionGuideDialog
import com.trackr.ui.components.RouteRailLayoutMode
import com.trackr.ui.components.ScheduledReminderDialog
import com.trackr.ui.components.TransitCodeBottomSheet
import com.trackr.ui.components.common.FullscreenErrorView
import com.trackr.ui.components.common.FullscreenLoadingView
import com.trackr.ui.components.timetable.TimetableBottomSheet
import com.trackr.ui.screens.linedetail.components.BottomFloatingToolbar
import com.trackr.ui.screens.linedetail.components.BusFollowContent
import com.trackr.ui.screens.linedetail.components.BusListContent
import com.trackr.ui.screens.linedetail.components.StationArrivalContent
import com.trackr.ui.screens.linedetail.components.TopLineHeader
import com.trackr.ui.theme.busColors
import com.trackr.ui.theme.expressiveRotateSpringSpec
import com.trackr.util.TransitCodeHelper
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 线路详情页面（Material Design 3 Expressive 规范）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val coroutineScope = rememberCoroutineScope()
    var showReminderDialog by remember { mutableStateOf(false) }
    var showFocusGuideDialog by remember { mutableStateOf(false) }

    val isCurrentStationTracking = activeTracking.isActive &&
            activeTracking.lineId == lineDetail?.line?.lineId &&
            activeTracking.targetOrder == selectedStop?.order

    val startCurrentTracking: () -> Unit = {
        if (selectedStop != null && lineDetail != null) {
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

    // 换向旋转弹簧动画
    val animatedFlipRotation by animateFloatAsState(
        targetValue = uiState.flipRotationDegrees,
        animationSpec = expressiveRotateSpringSpec(),
        label = "flip_rotation"
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
        ) {
            if (uiState.isLoading && lineDetail == null) {
                FullscreenLoadingView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    message = "正在连接实时公交数据…"
                )
                return@Box
            }

            if (lineDetail == null) {
                FullscreenErrorView(
                    errorMessage = uiState.errorMessage ?: "未获取到该线路信息",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    onRetry = { viewModel.refreshNow() }
                )
                return@Box
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
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
                                    isTrackingActive = isCurrentStationTracking,
                                    alertThreshold = uiState.alertThresholdStops,
                                    onThresholdChange = { viewModel.setAlertThreshold(it) },
                                    onOpenBusList = { viewModel.openBusList() },
                                    onSwitchOpposite = { viewModel.switchOppositeDirection() },
                                    onToggleTracking = {
                                        if (isCurrentStationTracking) {
                                            BusTrackingService.stopTracking(context)
                                        } else if (selectedStop != null) {
                                            if (XiaomiFocusAdapter.isXiaomiRom() && !XiaomiFocusAdapter.canShowFocus(context)) {
                                                showFocusGuideDialog = true
                                            } else {
                                                startCurrentTracking()
                                            }
                                        }
                                    },
                                    onOpenScheduledReminder = { showReminderDialog = true }
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

                // 为底部悬浮工具栏留白（工具栏高度约 56dp + 悬浮间距 16dp + 导航栏高度 + 呼吸空间）
                Spacer(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(96.dp)
                )
            }

            // 底部 M3E 悬浮胶囊工具栏 (Floating Pill Toolbar) 浮动于内容之上，背景透明无遮挡
            BottomFloatingToolbar(
                modifier = Modifier.align(Alignment.BottomCenter),
                isFavorite = uiState.isFavorite,
                flipRotation = { animatedFlipRotation },
                hasReverseDirection = lineDetail.reverseDirection != null,
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

    // 6. 定时通勤提醒设置弹窗
    if (showReminderDialog && lineDetail != null && selectedStop != null) {
        ScheduledReminderDialog(
            lineName = lineDetail.line.name,
            endStopName = lineDetail.line.endStop,
            targetStopName = selectedStop.name,
            onDismissRequest = { showReminderDialog = false },
            onConfirm = { hour, minute, daysOfWeek, autoStartTracking ->
                showReminderDialog = false
                val reminder = ScheduledReminder(
                    id = UUID.randomUUID().toString(),
                    hour = hour,
                    minute = minute,
                    daysOfWeek = daysOfWeek,
                    cityId = viewModel.cityId,
                    cityName = TrackrApp.instance.repository.selectedCity.value.cityName,
                    lineId = lineDetail.line.lineId,
                    lineName = lineDetail.line.name,
                    direction = lineDetail.line.direction,
                    endStopName = lineDetail.line.endStop,
                    targetStopId = selectedStop.stopId,
                    targetStopName = selectedStop.name,
                    targetOrder = selectedStop.order,
                    totalStops = lineDetail.stops.size,
                    dataSourceType = lineDetail.line.source,
                    mygolbsCityCode = lineDetail.line.mygolbsCityCode,
                    autoStartLiveTracking = autoStartTracking
                )
                coroutineScope.launch {
                    TrackrApp.instance.reminderRepository.saveReminder(reminder)
                    Toast.makeText(
                        context,
                        "已设定通勤提醒：${reminder.formatTime()} (${reminder.formatRepeatSummary()})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    // 7. 小米澎湃OS 焦点通知权限未授权引导弹窗
    if (showFocusGuideDialog) {
        FocusPermissionGuideDialog(
            onDismissRequest = { showFocusGuideDialog = false },
            onContinueWithoutSettings = {
                showFocusGuideDialog = false
                startCurrentTracking()
            },
            onGoToSettings = {
                showFocusGuideDialog = false
                XiaomiFocusAdapter.openNotificationSettings(context)
                startCurrentTracking()
            }
        )
    }
}
