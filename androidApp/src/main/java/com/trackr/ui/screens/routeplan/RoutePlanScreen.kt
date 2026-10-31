package com.trackr.ui.screens.routeplan

import android.os.Build
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.snapshotFlow
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.LegPhase
import com.trackr.ui.screens.favoritesgroup.boardStopOrderForDetail
import com.trackr.ui.screens.favoritesgroup.components.etaMinutesOf
import com.trackr.ui.screens.favoritesgroup.components.formatClock
import com.trackr.ui.screens.favoritesgroup.components.legLineColor
import com.trackr.ui.screens.favoritesgroup.components.reliableRideStops
import com.trackr.ui.screens.favoritesgroup.components.trackingPhaseLabel
import java.time.ZoneId
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.trackr.BuildConfig
import com.trackr.data.remote.amap.AmapSdkManager
import com.trackr.data.remote.amap.AmapTipItem
import com.trackr.data.remote.amap.AmapTransitSchemeResult
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.ProvinceCityCatalog
import com.trackr.domain.model.TransitGroup
import com.trackr.ui.screens.favoritesgroup.FavoritesGroupViewModel
import com.trackr.ui.theme.extendedColors
import com.trackr.util.CoordTransform
import com.trackr.util.SignatureUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 是否运行在 Robolectric/无高德 SDK 的测试环境，用于决定地图是否走矢量降级实现
 */
internal fun isTestEnvironment(): Boolean =
    Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

/**
 * 路线规划全屏界面 (RoutePlanScreen - 路线规划 Tab)
 * 采用 全屏矢量地图底座 + 顶部悬浮搜索岛 + 底部方案抽屉 架构
 */
@Composable
fun RoutePlanScreen(
    viewModel: FavoritesGroupViewModel,
    mapSession: AmapMapSession? = null,
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: DataSourceType, lineName: String) -> Unit = { _, _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
    useVectorMapFallback: Boolean = isTestEnvironment()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val originTips by viewModel.amapOriginTips.collectAsStateWithLifecycle()
    val destTips by viewModel.amapDestTips.collectAsStateWithLifecycle()
    val plannedSchemes by viewModel.plannedSchemes.collectAsStateWithLifecycle()
    val isPlanning by viewModel.isPlanningRoute.collectAsStateWithLifecycle()
    val planError by viewModel.planErrorMessage.collectAsStateWithLifecycle()
    val customApiKey by viewModel.customAmapApiKey.collectAsStateWithLifecycle()
    val customWebApiKey by viewModel.customAmapWebApiKey.collectAsStateWithLifecycle()
    val favoriteGroups by viewModel.favoriteGroups.collectAsStateWithLifecycle()
    val currentCity by viewModel.currentCity.collectAsStateWithLifecycle()
    val userLocationWgs by viewModel.userLocation.collectAsStateWithLifecycle()

    // 地图跟随应用主题：深色时切高德夜间样式，避免夜里一张白底图刺眼
    val isDarkTheme = isSystemInDarkTheme()

    // 系统定位是 WGS-84，高德地图需要 GCJ-02；两者在国内相差 300~600 米
    val myLocationGcj = remember(userLocationWgs) {
        userLocationWgs?.let { (lat, lng) ->
            val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(lat, lng)
            LatLng(gcjLat, gcjLng)
        }
    }
    // 定位不可用时的兜底视角：当前城市中心。未收录的城市宁可不动相机，
    // 也不能回落到某个内置城市（否则用户会被搬到毫不相干的地方）。
    // 城市中心表是 WGS-84，高德取景要 GCJ-02，先转换（否则偏 300~600 米）
    val cityCenterLatLng = remember(currentCity.cityName) {
        ProvinceCityCatalog.findCityCenterCoordinates(currentCity.cityName)
            ?.let { (lat, lng) ->
                val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(lat, lng)
                LatLng(gcjLat, gcjLng)
            }
    }

    var originText by rememberSaveable { mutableStateOf("") }
    var destText by rememberSaveable { mutableStateOf("") }
    var selectedOrigin by remember { mutableStateOf<AmapTipItem?>(null) }
    var selectedDest by remember { mutableStateOf<AmapTipItem?>(null) }

    var selectedSchemeIndex by rememberSaveable { mutableIntStateOf(0) }
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveGroupDialog by rememberSaveable { mutableStateOf(false) }

    var isOriginFocused by remember { mutableStateOf(false) }
    var isDestFocused by remember { mutableStateOf(false) }
    var dismissOriginTips by remember { mutableStateOf(false) }
    var dismissDestTips by remember { mutableStateOf(false) }

    var mapScale by remember { mutableFloatStateOf(1f) }
    var mapOffset by remember { mutableStateOf(Offset.Zero) }
    var recenterTrigger by rememberSaveable { mutableIntStateOf(0) }

    val triggerPlan: () -> Unit = {
        if (destText.isNotBlank()) {
            val orig = if (originText.isBlank()) {
                AmapTipItem(name = "我的位置", location = "")
            } else {
                selectedOrigin ?: AmapTipItem(name = originText)
            }
            val dst = selectedDest ?: AmapTipItem(name = destText)
            viewModel.planTransitRoute(orig, dst)
        }
    }

    // 当方案更新时校准选中索引
    LaunchedEffect(plannedSchemes) {
        if (selectedSchemeIndex >= plannedSchemes.size) {
            selectedSchemeIndex = 0
        }
        if (plannedSchemes.isNotEmpty()) {
            mapScale = 1f
            mapOffset = Offset.Zero
        }
    }

    // 当联想更新时恢复展示
    LaunchedEffect(originTips) {
        if (originTips.isNotEmpty()) dismissOriginTips = false
    }
    LaunchedEffect(destTips) {
        if (destTips.isNotEmpty()) dismissDestTips = false
    }

    val isDestTipsVisible = !dismissDestTips && selectedDest == null && destTips.isNotEmpty()
    val isOriginTipsVisible = !dismissOriginTips && selectedOrigin == null && originTips.isNotEmpty()
    val hasTipsVisible = isDestTipsVisible || isOriginTipsVisible

    // 遵循 LIFO 消费返回键：先收起终点候选 -> 再收起起点候选
    BackHandler(enabled = hasTipsVisible) {
        if (isDestTipsVisible) {
            dismissDestTips = true
            viewModel.searchDestTips("")
        } else if (isOriginTipsVisible) {
            dismissOriginTips = true
            viewModel.searchOriginTips("")
        }
    }

    // 面板顶部最多放 4 个方案；详情区 HorizontalPager 与标签联动
    val visibleSchemes = remember(plannedSchemes) { plannedSchemes.take(MAX_VISIBLE_SCHEMES) }
    val schemePagerState = rememberPagerState(initialPage = selectedSchemeIndex) { visibleSchemes.size }
    LaunchedEffect(schemePagerState) {
        snapshotFlow { schemePagerState.settledPage }.collect { selectedSchemeIndex = it }
    }
    // 新的规划结果回到第一个方案；配置变更（同一份结果）时保持用户当前选择
    var handledSchemes by remember { mutableStateOf(visibleSchemes) }
    LaunchedEffect(visibleSchemes) {
        if (visibleSchemes !== handledSchemes) {
            handledSchemes = visibleSchemes
            if (visibleSchemes.isNotEmpty()) schemePagerState.scrollToPage(0)
        }
    }
    val currentScheme = visibleSchemes.getOrNull(selectedSchemeIndex)

    val activeGroupSession by viewModel.activeGroupSession.collectAsStateWithLifecycle()
    // 追踪对齐失败等一次性提示
    val trackingMessage by viewModel.trackingMessage.collectAsStateWithLifecycle()
    LaunchedEffect(trackingMessage) {
        trackingMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearTrackingMessage()
        }
    }

    // 地图可用性判定：
    // - 硬失败（缺 Key / ABI 不支持 / 原生库异常）→ 直接降级为内置矢量示意图；
    // - 软失败（底图 15s 未就绪）→ 保留真实地图，浮层提示，避免把“加载慢”误判成“用不了”。
    val isSdkKeyConfigured = remember(customApiKey) {
        AmapSdkManager.isAndroidKeyConfigured(customApiKey)
    }
    val mapUnavailableReason = remember(customApiKey) {
        AmapSdkManager.mapUnavailableReason(customApiKey)
    }
    var mapRuntimeError by remember(customApiKey) { mutableStateOf<String?>(null) }
    var mapLoadTimeout by remember(customApiKey) { mutableStateOf<String?>(null) }
    var mapRetryToken by remember(customApiKey) { mutableIntStateOf(0) }
    var showMapDiagnostics by remember { mutableStateOf(false) }

    val fallbackReason = mapUnavailableReason ?: mapRuntimeError
    // mapSession 为 null（如测试环境）时直接走内置示意图，避免依赖真实地图实例
    val useFallbackMap = useVectorMapFallback || mapSession == null || fallbackReason != null

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 全屏矢量地图视窗 (真实高德 3D 矢量地图 / 降级 Canvas 示意图)
        if (useFallbackMap) {
            RoutePlanVectorMapCanvas(
                selectedScheme = currentScheme,
                scale = mapScale,
                offset = mapOffset,
                onTransform = { pan, zoom ->
                    mapScale = (mapScale * zoom).coerceIn(0.5f, 3.5f)
                    mapOffset += pan
                },
                modifier = Modifier.fillMaxSize()
            )
            if (!useVectorMapFallback && fallbackReason != null) {
                MapUnavailableNotice(
                    reason = fallbackReason,
                    showKeyAction = !isSdkKeyConfigured,
                    onConfigureKey = { showKeyDialog = true },
                    onRetry = { mapRuntimeError = null; mapRetryToken++ },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                )
            }
        } else {
            AmapRealVectorMapView(
                session = mapSession,
                schemes = visibleSchemes,
                selectedIndex = selectedSchemeIndex,
                customApiKey = customApiKey,
                darkMode = isDarkTheme,
                myLocation = myLocationGcj,
                cityCenter = cityCenterLatLng,
                recenterTrigger = recenterTrigger,
                retryToken = mapRetryToken,
                onMapLoaded = { mapLoadTimeout = null },
                onLoadTimeout = { mapLoadTimeout = it },
                onRuntimeError = { mapRuntimeError = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 底图迟迟未就绪：浮层提示但不销毁地图，用户可以直接重试或查看诊断信息
        if (!useFallbackMap && mapLoadTimeout != null) {
            MapLoadTimeoutNotice(
                reason = mapLoadTimeout.orEmpty(),
                onRetry = {
                    mapLoadTimeout = null
                    mapRetryToken++
                },
                onShowDiagnostics = { showMapDiagnostics = true },
                onDismiss = { mapLoadTimeout = null },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }

        // 地图浮动工具按键 (右侧：单一居中重置 FAB)
        FloatingActionButton(
            onClick = {
                recenterTrigger++
                mapScale = 1f
                mapOffset = Offset.Zero
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .size(42.dp)
                .testTag("btn_recenter"),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = "居中重置",
                modifier = Modifier.size(20.dp)
            )
        }

        // 2. 顶部悬浮搜索岛 (Top Floating Search Console)
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = statusBarPadding + 10.dp, start = 16.dp, end = 16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp))
                    .testTag("top_search_console"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 起点行
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.extendedColors.success, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            BasicTextField(
                                value = originText,
                                onValueChange = {
                                    originText = it
                                    selectedOrigin = null
                                    dismissOriginTips = false
                                    viewModel.searchOriginTips(it)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("input_origin"),
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    if (originText.isEmpty()) {
                                        Text(
                                            text = "我的位置 / 输入起点站",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (originText.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        originText = ""
                                        selectedOrigin = null
                                        viewModel.searchOriginTips("")
                                        viewModel.clearPlanError()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空起点",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // 微妙分割线
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp)
                                .height(0.8.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        )

                        // 终点行
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            BasicTextField(
                                value = destText,
                                onValueChange = {
                                    destText = it
                                    selectedDest = null
                                    dismissDestTips = false
                                    viewModel.searchDestTips(it)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("input_dest"),
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Search
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = { triggerPlan() }
                                ),
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    if (destText.isEmpty()) {
                                        Text(
                                            text = "输入目的地、商圈或公交地铁站",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (destText.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        destText = ""
                                        selectedDest = null
                                        viewModel.searchDestTips("")
                                        viewModel.clearPlanError()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空终点",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 右侧操作栏：互换与规划触发
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val tmpText = originText
                                originText = destText
                                destText = tmpText

                                val tmpTip = selectedOrigin
                                selectedOrigin = selectedDest
                                selectedDest = tmpTip

                                if (destText.isNotBlank()) {
                                    val orig = if (originText.isBlank()) {
                                        AmapTipItem(name = "我的位置", location = "")
                                    } else {
                                        selectedOrigin ?: AmapTipItem(name = originText)
                                    }
                                    val dst = selectedDest ?: AmapTipItem(name = destText)
                                    viewModel.planTransitRoute(orig, dst)
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .testTag("btn_swap_origin_dest")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = "互换起终点",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { triggerPlan() },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("btn_trigger_plan")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "规划",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 独立的小胶囊配置按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, end = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    onClick = { showKeyDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    shadowElevation = 2.dp,
                    modifier = Modifier.testTag("btn_key_config")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "高德Key配置",
                            tint = if (!isSdkKeyConfigured) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (!isSdkKeyConfigured) "配置Key" else "Key已生效",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = if (!isSdkKeyConfigured) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

            // 搜索联想下拉卡片 (POIs / Tips)
            AnimatedVisibility(
                visible = hasTipsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 240.dp)
                        .shadow(elevation = 6.dp, shape = RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    val activeTips = if (isDestTipsVisible) destTips else originTips
                    val isSelectingDest = isDestTipsVisible

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(activeTips) { tip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelectingDest) {
                                            destText = tip.name
                                            selectedDest = tip
                                            dismissDestTips = true
                                            viewModel.searchDestTips("")
                                            if (originText.isNotBlank()) {
                                                val orig = selectedOrigin ?: AmapTipItem(name = originText)
                                                viewModel.planTransitRoute(orig, tip)
                                            }
                                        } else {
                                            originText = tip.name
                                            selectedOrigin = tip
                                            dismissOriginTips = true
                                            viewModel.searchOriginTips("")
                                            if (destText.isNotBlank()) {
                                                val dst = selectedDest ?: AmapTipItem(name = destText)
                                                viewModel.planTransitRoute(tip, dst)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (tip.address.contains("地铁") || tip.name.contains("地铁") || tip.name.contains("站")) {
                                        Icons.Default.DirectionsBus
                                    } else {
                                        Icons.Default.Search
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tip.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val detail = listOfNotNull(tip.district.takeIf { it.isNotBlank() }, tip.address.takeIf { it.isNotBlank() }).joinToString(" · ")
                                    if (detail.isNotBlank()) {
                                        Text(
                                            text = detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 底部自适应方案抽屉 (Bottom Adaptive Route Sheet)
        val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .animateContentSize()
                .testTag("bottom_route_sheet"),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = navBarPadding + 80.dp + 12.dp
                    )
            ) {
                // 拖拽条 Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(36.dp, 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                Spacer(modifier = Modifier.height(10.dp))

                when {
                    isPlanning -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "正在规划最佳公交出行方案…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    planError != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = planError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    if (originText.isNotBlank() && destText.isNotBlank()) {
                                        val orig = selectedOrigin ?: AmapTipItem(name = originText)
                                        val dst = selectedDest ?: AmapTipItem(name = destText)
                                        viewModel.planTransitRoute(orig, dst)
                                    }
                                }
                            ) {
                                Text("重新规划")
                            }
                        }
                    }

                    currentScheme != null -> {
                        RoutePlanSchemePanel(
                            schemes = visibleSchemes,
                            pagerState = schemePagerState,
                            trackingSession = activeGroupSession,
                            onSelectScheme = { index ->
                                scope.launch { schemePagerState.animateScrollToPage(index) }
                            },
                            onLineClick = { line ->
                                // 线路详情定位到上车站
                                onNavigateToLineDetail(
                                    line.cityId,
                                    line.lineId,
                                    line.boardStopOrderForDetail(),
                                    "",
                                    line.source,
                                    line.lineName
                                )
                            },
                            onSaveFavorite = { showSaveGroupDialog = true },
                            onStartTracking = { scheme ->
                                // 只做一次临时追踪：不加入收藏 / 路线组，留在规划页
                                viewModel.startGroupTracking(context, scheme.group)
                            },
                            onConfirmBoarded = { viewModel.confirmBoarded(context) },
                            onStopTracking = { viewModel.stopGroupTracking(context) }
                        )
                    }

                    else -> {
                        // 初始引导提示
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "输入起点与终点，开启全城公交换乘与多线直达规划",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 4. 存入收藏分组选择对话框
    if (showSaveGroupDialog && currentScheme != null) {
        SaveFavoriteGroupDialog(
            scheme = currentScheme,
            favoriteGroups = favoriteGroups,
            onDismiss = { showSaveGroupDialog = false },
            onConfirmSave = { customGroupId, customName ->
                viewModel.addPlannedSchemeAsFavorite(
                    scheme = currentScheme,
                    customGroupId = customGroupId,
                    customName = customName
                )
                showSaveGroupDialog = false
                Toast.makeText(context, "已成功加入收藏！", Toast.LENGTH_SHORT).show()
                onNavigateToFavorites()
            },
            onCreateNewGroup = { newGroupName ->
                viewModel.createFavoriteGroup(newGroupName)
            }
        )
    }

    // 5. 高德 Key 与 SHA-1 配置对话框
    if (showKeyDialog) {
        AmapApiKeyConfigDialog(
            customApiKey = customApiKey,
            customWebApiKey = customWebApiKey,
            onSaveCustomApiKey = { viewModel.saveCustomAmapApiKey(it) },
            onSaveCustomWebApiKey = { viewModel.saveCustomAmapWebApiKey(it) },
            onDismiss = { showKeyDialog = false }
        )
    }

    // 6. 地图诊断信息（核对包名 / SHA-1 / 生效 Key 是否与控制台一致）
    if (showMapDiagnostics) {
        MapDiagnosticsDialog(
            androidKey = customApiKey,
            onDismiss = { showMapDiagnostics = false }
        )
    }
}

/**
 * 全屏矢量地图 Canvas 渲染器
 * 绘制底图路网道路网格、干道、公交折线与起点、换乘中转站、终点 Marker
 */
@Composable
fun RoutePlanVectorMapCanvas(
    selectedScheme: AmapTransitSchemeResult?,
    scale: Float,
    offset: Offset,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    // 调色盘：全部取自主题，随深浅色切换
    val colorScheme = MaterialTheme.colorScheme
    val extendedColors = MaterialTheme.extendedColors
    val bgColor = colorScheme.surfaceContainerHigh
    val gridColor = colorScheme.outlineVariant.copy(alpha = 0.5f)
    val arterialRoadColor = colorScheme.surface
    val arterialCasingColor = colorScheme.outlineVariant

    val transitLeg1Color = colorScheme.primary
    val transitLeg2Color = extendedColors.legSecondary
    val walkingColor = extendedColors.walk

    val originColor = extendedColors.success
    val transferColor = extendedColors.legSecondary
    val destColor = colorScheme.error

    Canvas(
        modifier = modifier
            .background(bgColor)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(pan, zoom)
                }
            }
    ) {
        val w = size.width
        val h = size.height

        withTransform({
            translate(offset.x, offset.y)
            scale(scaleX = scale, scaleY = scale, pivot = center)
        }) {
            // 1. 绘制网格线底纹 (Stylized Grid)
            val gridSize = 60.dp.toPx()
            var x = -gridSize * 4
            while (x < w + gridSize * 4) {
                drawLine(
                    color = gridColor,
                    start = Offset(x, -gridSize * 4),
                    end = Offset(x, h + gridSize * 4),
                    strokeWidth = 1.dp.toPx()
                )
                x += gridSize
            }
            var y = -gridSize * 4
            while (y < h + gridSize * 4) {
                drawLine(
                    color = gridColor,
                    start = Offset(-gridSize * 4, y),
                    end = Offset(w + gridSize * 4, y),
                    strokeWidth = 1.dp.toPx()
                )
                y += gridSize
            }

            // 2. 绘制城市主要干道弧线 (Arterial Roads)
            val roadPath1 = Path().apply {
                moveTo(-100f, h * 0.28f)
                quadraticTo(w * 0.4f, h * 0.38f, w + 120f, h * 0.22f)
            }
            val roadPath2 = Path().apply {
                moveTo(w * 0.28f, -100f)
                quadraticTo(w * 0.35f, h * 0.52f, w * 0.82f, h + 120f)
            }

            // 道路外边沿 (Casing)
            drawPath(roadPath1, arterialCasingColor, style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round))
            drawPath(roadPath2, arterialCasingColor, style = Stroke(width = 28.dp.toPx(), cap = StrokeCap.Round))

            // 道路实体 (Core)
            drawPath(roadPath1, arterialRoadColor, style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round))
            drawPath(roadPath2, arterialRoadColor, style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round))

            // 3. 规划轨迹折线与起终点投影
            if (selectedScheme != null) {
                val legs = selectedScheme.group.legs
                val originName = selectedScheme.group.originStopName.ifBlank { "起点" }
                val destName = selectedScheme.group.destinationStopName.ifBlank { "终点" }

                // 计算或采用自适应 Procedural 布局坐标
                val pOrigin = Offset(w * 0.22f, h * 0.36f)
                val pDest = Offset(w * 0.76f, h * 0.70f)

                if (legs.size <= 1) {
                    // 直达方案：一段公交实线
                    val transitPath = Path().apply {
                        moveTo(pOrigin.x, pOrigin.y)
                        cubicTo(w * 0.28f, h * 0.52f, w * 0.60f, h * 0.56f, pDest.x, pDest.y)
                    }
                    // 轨迹外描边抗锯齿
                    drawPath(transitPath, Color.White, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(transitPath, transitLeg1Color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))

                    // 起终点 Marker
                    drawMarkerBadge(textMeasurer, pOrigin, "🟢 起点: $originName", originColor)
                    drawMarkerBadge(textMeasurer, pDest, "🚩 终点: $destName", destColor)
                } else {
                    // 换乘方案：第 1 程 -> 步行接驳 -> 第 2 程
                    val pTransfer1 = Offset(w * 0.46f, h * 0.49f)
                    val pTransfer2 = Offset(w * 0.52f, h * 0.54f)
                    val transferStopName = legs.firstOrNull()?.toStopName ?: "中转站"

                    // 第 1 程 (公交实线)
                    val pathLeg1 = Path().apply {
                        moveTo(pOrigin.x, pOrigin.y)
                        quadraticTo(w * 0.30f, h * 0.44f, pTransfer1.x, pTransfer1.y)
                    }
                    drawPath(pathLeg1, Color.White, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(pathLeg1, transitLeg1Color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))

                    // 换乘步行接驳段 (点状虚线)
                    val pathWalk = Path().apply {
                        moveTo(pTransfer1.x, pTransfer1.y)
                        lineTo(pTransfer2.x, pTransfer2.y)
                    }
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    drawPath(pathWalk, walkingColor, style = Stroke(width = 3.5.dp.toPx(), pathEffect = dashEffect, cap = StrokeCap.Round))

                    // 第 2 程 (公交/地铁实线，紫罗兰色)
                    val pathLeg2 = Path().apply {
                        moveTo(pTransfer2.x, pTransfer2.y)
                        quadraticTo(w * 0.68f, h * 0.60f, pDest.x, pDest.y)
                    }
                    drawPath(pathLeg2, Color.White, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(pathLeg2, transitLeg2Color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))

                    // 起点 Marker
                    drawMarkerBadge(textMeasurer, pOrigin, "🟢 起点: $originName", originColor)

                    // 换乘站 Badge
                    val pTransferMid = Offset((pTransfer1.x + pTransfer2.x) / 2, (pTransfer1.y + pTransfer2.y) / 2)
                    drawTransferBadge(textMeasurer, pTransferMid, "🔄 $transferStopName", transferColor)

                    // 终点 Marker
                    drawMarkerBadge(textMeasurer, pDest, "🚩 终点: $destName", destColor)
                }
            }
        }
    }
}

/**
 * 在 Canvas 绘制高质感 Marker 胶囊徽标
 */
private fun DrawScope.drawMarkerBadge(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    pos: Offset,
    label: String,
    badgeColor: Color
) {
    val textLayout = textMeasurer.measure(
        text = AnnotatedString(label),
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    )

    val padH = 10.dp.toPx()
    val padV = 5.dp.toPx()
    val badgeWidth = textLayout.size.width + padH * 2
    val badgeHeight = textLayout.size.height + padV * 2

    val badgeTopLeft = Offset(pos.x - badgeWidth / 2, pos.y - badgeHeight - 8.dp.toPx())

    // 徽标阴影与实体背景
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.2f),
        topLeft = badgeTopLeft.copy(y = badgeTopLeft.y + 2.dp.toPx()),
        size = Size(badgeWidth, badgeHeight),
        cornerRadius = CornerRadius(badgeHeight / 2, badgeHeight / 2)
    )
    drawRoundRect(
        color = badgeColor,
        topLeft = badgeTopLeft,
        size = Size(badgeWidth, badgeHeight),
        cornerRadius = CornerRadius(badgeHeight / 2, badgeHeight / 2)
    )

    // 定位小尖角
    val arrowPath = Path().apply {
        moveTo(pos.x - 5.dp.toPx(), badgeTopLeft.y + badgeHeight)
        lineTo(pos.x + 5.dp.toPx(), badgeTopLeft.y + badgeHeight)
        lineTo(pos.x, pos.x * 0 + pos.y)
        close()
    }
    drawPath(arrowPath, badgeColor)

    // 文字
    drawText(
        textLayoutResult = textLayout,
        topLeft = Offset(badgeTopLeft.x + padH, badgeTopLeft.y + padV)
    )

    // 地面定位白心锚点
    drawCircle(Color.White, radius = 4.dp.toPx(), center = pos)
    drawCircle(badgeColor, radius = 2.dp.toPx(), center = pos)
}

/**
 * 在 Canvas 绘制换乘中转站双环 Badge
 */
private fun DrawScope.drawTransferBadge(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    pos: Offset,
    label: String,
    badgeColor: Color
) {
    // 双环圆圈
    drawCircle(Color.White, radius = 9.dp.toPx(), center = pos)
    drawCircle(badgeColor, radius = 7.dp.toPx(), center = pos)
    drawCircle(Color.White, radius = 3.dp.toPx(), center = pos)

    val textLayout = textMeasurer.measure(
        text = AnnotatedString(label),
        style = TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = badgeColor
        )
    )

    val padH = 6.dp.toPx()
    val padV = 2.dp.toPx()
    val badgeWidth = textLayout.size.width + padH * 2
    val badgeHeight = textLayout.size.height + padV * 2
    val badgeTopLeft = Offset(pos.x - badgeWidth / 2, pos.y + 11.dp.toPx())

    drawRoundRect(
        color = Color.White.copy(alpha = 0.95f),
        topLeft = badgeTopLeft,
        size = Size(badgeWidth, badgeHeight),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )
    drawRoundRect(
        color = badgeColor.copy(alpha = 0.3f),
        topLeft = badgeTopLeft,
        size = Size(badgeWidth, badgeHeight),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        style = Stroke(width = 1.dp.toPx())
    )
    drawText(
        textLayoutResult = textLayout,
        topLeft = Offset(badgeTopLeft.x + padH, badgeTopLeft.y + padV)
    )
}

/**
 * 存入收藏分组选择对话框
 */
@Composable
fun SaveFavoriteGroupDialog(
    scheme: AmapTransitSchemeResult,
    favoriteGroups: List<com.trackr.domain.model.FavoriteGroup>,
    onDismiss: () -> Unit,
    onConfirmSave: (customGroupId: String?, customName: String) -> Unit,
    onCreateNewGroup: (String) -> Unit
) {
    var groupName by remember(scheme) { mutableStateOf(scheme.group.name) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var showNewGroupInput by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "存入收藏",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "方案：${scheme.title} (约 ${scheme.durationMinutes} 分钟)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("路线名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_favorite_name")
                )

                Text(
                    text = "选择所属收藏分组：",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedGroupId == null,
                        onClick = { selectedGroupId = null },
                        label = { Text("全部") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    favoriteGroups.forEach { group ->
                        FilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { selectedGroupId = group.id },
                            label = { Text(group.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }

                if (!showNewGroupInput) {
                    TextButton(
                        onClick = { showNewGroupInput = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("新建自定义分组")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            placeholder = { Text("输入新分组名 (如: 周末出行)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newGroupName.isNotBlank()) {
                                    onCreateNewGroup(newGroupName.trim())
                                    newGroupName = ""
                                    showNewGroupInput = false
                                }
                            }
                        ) {
                            Text("添加")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSave(selectedGroupId, groupName.trim()) },
                modifier = Modifier.testTag("btn_confirm_save_favorite")
            ) {
                Text("确认存入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 高德 Key 与 SHA-1 签名配置对话框。
 *
 * 高德开放平台的「Android 平台 Key」与「Web 服务 Key」互不通用，这里分两个输入框分别保存：
 * 前者决定地图能否出图，后者决定云端公交算路能否走 HTTP 接口。
 */
@Composable
fun AmapApiKeyConfigDialog(
    customApiKey: String?,
    customWebApiKey: String?,
    onSaveCustomApiKey: (String) -> Unit,
    onSaveCustomWebApiKey: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var inputKey by remember(customApiKey) { mutableStateOf(customApiKey.orEmpty()) }
    var inputWebKey by remember(customWebApiKey) { mutableStateOf(customWebApiKey.orEmpty()) }
    val currentSha1 = remember { SignatureUtils.getSigningCertificateSha1(context) }

    // 高德 Key 为 32 位字母数字组合，长度不符时直接拦下，省得用户以为“填了就该出图”
    fun isKeyFormatAcceptable(key: String): Boolean =
        key.isBlank() || key.length == 32 && key.all { it.isLetterOrDigit() }

    val isAndroidKeyFormatValid = isKeyFormatAcceptable(inputKey.trim())
    val isWebKeyFormatValid = isKeyFormatAcceptable(inputWebKey.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "高德地图 Key 配置",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "本应用开源分发、不内置任何 Key，每位用户用自己的高德账号申请即可（个人开发者免费额度足够日常使用）。" +
                            "地图底图由「Android 平台 Key」鉴权，必须与下方包名 + SHA-1 完全一致；" +
                            "「Web 服务 Key」只用于云端公交算路，两者不能混填。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "申请步骤：① 复制包名 + SHA-1 → ② 控制台创建「Android 平台」Key，发布版 / 调试版 SHA-1 都填上 → ③ 把 Key 填到下面 → ④ 保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    val text = "包名：${context.packageName}\nSHA-1：$currentSha1"
                                    clipboardManager.setText(AnnotatedString(text))
                                    Toast.makeText(context, "包名与 SHA-1 已复制", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.testTag("btn_copy_pkg_sha1")
                            ) {
                                Text("复制包名 + SHA-1", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        uriHandler.openUri("https://console.amap.com/dev/key/app")
                                    }.onFailure {
                                        Toast.makeText(context, "未找到可用浏览器，请手动访问 console.amap.com", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.testTag("btn_open_amap_console")
                            ) {
                                Text("打开高德控制台", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Package Name",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = context.packageName,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(context.packageName))
                                        Toast.makeText(context, "包名已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "复制包名", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SHA-1 签名指纹",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = currentSha1.ifBlank { "正在读取…" },
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (currentSha1.isNotBlank()) {
                                            clipboardManager.setText(AnnotatedString(currentSha1))
                                            Toast.makeText(context, "SHA-1 已复制", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = currentSha1.isNotBlank(),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "复制 SHA-1", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it.trim() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_amap_android_key"),
                        label = { Text("高德 Android Key（地图）") },
                        placeholder = { Text("粘贴 32 位 Android 平台 Key") },
                        isError = !isAndroidKeyFormatValid,
                        supportingText = {
                            Text(
                                text = when {
                                    !isAndroidKeyFormatValid -> "Key 应为 32 位字母数字，请检查是否粘贴完整"
                                    AmapSdkManager.isAndroidKeyConfigured(inputKey) -> "已配置，地图将使用该 Key 出图"
                                    else -> "未配置时地图降级为内置示意图"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        trailingIcon = {
                            if (inputKey.isNotBlank()) {
                                IconButton(onClick = { inputKey = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清空 Android Key", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = inputWebKey,
                        onValueChange = { inputWebKey = it.trim() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_amap_web_key"),
                        label = { Text("高德 Web 服务 Key（可选）") },
                        placeholder = { Text("用于云端公交算路，不绑定包名") },
                        isError = !isWebKeyFormatValid,
                        supportingText = {
                            Text(
                                text = when {
                                    !isWebKeyFormatValid -> "Key 应为 32 位字母数字，请检查是否粘贴完整"
                                    AmapSdkManager.isWebServiceKeyConfigured(inputWebKey) -> "已配置"
                                    else -> "未配置时仅使用本地 + SDK 引擎算路"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        trailingIcon = {
                            if (inputWebKey.isNotBlank()) {
                                IconButton(onClick = { inputWebKey = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清空 Web Key", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isAndroidKeyFormatValid && isWebKeyFormatValid,
                onClick = {
                    onSaveCustomApiKey(inputKey.trim())
                    onSaveCustomWebApiKey(inputWebKey.trim())
                    Toast.makeText(context, "Key 已保存，正在重新加载地图", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) {
                Text("保存生效")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 地图不可用提示：说明原因并给出配置入口，避免空白底图让人摸不着头脑。
 */
@Composable
fun MapUnavailableNotice(
    reason: String,
    showKeyAction: Boolean,
    onConfigureKey: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("card_map_unavailable"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "底图未加载",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "当前展示的是内置矢量示意图，路线与站点信息仍然可用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "提示：刚填完 Key 仍不出图时，请彻底退出应用再打开一次（高德 SDK 会缓存首次初始化结果）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showKeyAction) {
                    Button(
                        onClick = onConfigureKey,
                        modifier = Modifier.testTag("btn_map_configure_key")
                    ) {
                        Text("配置 Key")
                    }
                }
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("btn_map_retry")
                ) {
                    Text("重试")
                }
            }
        }
    }
}

/**
 * 底图加载超时提示（软失败）：不销毁地图，只做浮层说明 + 重试 / 诊断入口。
 */
@Composable
fun MapLoadTimeoutNotice(
    reason: String,
    onRetry: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("card_map_timeout"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "底图还没加载出来",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("btn_map_timeout_retry")
                ) {
                    Text("重试")
                }
                OutlinedButton(
                    onClick = onShowDiagnostics,
                    modifier = Modifier.testTag("btn_map_diagnostics")
                ) {
                    Text("诊断信息")
                }
                TextButton(onClick = onDismiss) {
                    Text("先不用")
                }
            }
        }
    }
}

/**
 * 地图诊断信息：把包名 / SHA-1 / 实际生效的 Key 摆出来，方便对照高德控制台排查。
 */
@Composable
fun MapDiagnosticsDialog(
    androidKey: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val packageName = context.packageName
    val sha1 = remember { SignatureUtils.getSigningCertificateSha1(context) }
    val effectiveKey = remember(androidKey) { AmapSdkManager.getEffectiveApiKey(androidKey) }
    val keySource = when {
        !androidKey.isNullOrBlank() -> "应用内配置"
        effectiveKey.isNotEmpty() -> "编译期注入（local.properties / 环境变量）"
        else -> "未配置"
    }
    val maskedKey = if (effectiveKey.isEmpty()) {
        "未配置"
    } else {
        "${effectiveKey.take(4)}…${effectiveKey.takeLast(4)}（${effectiveKey.length} 位）"
    }
    val sdkVersion = remember { runCatching { com.amap.api.maps.MapsInitializer.getVersion() }.getOrDefault("未知") }

    val report = buildString {
        appendLine("包名：$packageName")
        appendLine("SHA-1：${sha1.ifBlank { "读取失败" }}")
        appendLine("生效 Key：$maskedKey")
        appendLine("Key 来源：$keySource")
        appendLine("设备 ABI：${Build.SUPPORTED_ABIS.joinToString("/")}")
        appendLine("地图 SDK：$sdkVersion")
        appendLine("鉴权失败常见原因：Key 未绑定当前包名或 SHA-1；把 Web 服务 Key 当地图 Key 用；Key 未通过审核/未勾选协议。")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("地图诊断信息", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
        text = {
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(report))
                    Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.testTag("btn_copy_diagnostics")
            ) {
                Text("复制")
            }
        }
    )
}

/**
 * 真实高德 3D 矢量地图视图（TextureMapView），实例由 [AmapMapSession] 托管。
 *
 * 地图实例不随本 composable 创建 / 销毁：切走 tab 只是把容器从窗口摘下（onPause），
 * 回来时同一实例重新挂上（onResume），所以不会重新初始化、也不会丢相机位置。
 *
 * @param session Activity 级地图会话，负责实例复用与生命周期
 * @param schemes 当前全部规划方案：选中的画粗彩线，其余画细灰线，方便对比
 * @param selectedIndex 选中方案下标
 * @param myLocation 当前设备定位，**必须已转换为 GCJ-02**；无方案时用于取景，null 表示暂无定位
 * @param cityCenter 当前城市中心（GCJ-02）；定位不可用时的取景兜底，null 时保持相机不动
 * @param darkMode 是否深色主题，决定地图是否使用夜间样式
 * @param retryToken 变化时丢弃当前实例并重建（用户点“重试”）
 * @param onMapLoaded 底图首次就绪回调
 * @param onLoadTimeout 底图超时未就绪回调（软失败，外层以浮层提示，不销毁地图）
 * @param onRuntimeError 地图实例创建失败（如设备 ABI 缺 arm 库、原生库加载失败）回调，外层切换为矢量示意图
 */
@Composable
fun AmapRealVectorMapView(
    session: AmapMapSession,
    schemes: List<AmapTransitSchemeResult>,
    selectedIndex: Int,
    customApiKey: String?,
    darkMode: Boolean = false,
    myLocation: LatLng? = null,
    cityCenter: LatLng? = null,
    recenterTrigger: Int = 0,
    retryToken: Int = 0,
    modifier: Modifier = Modifier,
    onMapLoaded: () -> Unit = {},
    onLoadTimeout: (String) -> Unit = {},
    onRuntimeError: (String) -> Unit = {}
) {
    val aMap = session.aMapState.value
    val isMapLoaded = session.isLoadedState.value
    val runtimeError = session.runtimeErrorState.value

    AndroidView(
        factory = { _ ->
            // 容器可能是从上一个页面"摘下"的，先确保它没有残留父节点，否则 addView 会抛异常
            (session.container.parent as? ViewGroup)?.removeView(session.container)
            // Key 必须在 SDK 创建地图实例前注入
            session.updateKey(customApiKey)
            session.container
        },
        modifier = modifier
    )

    LaunchedEffect(customApiKey) { session.updateKey(customApiKey) }
    LaunchedEffect(darkMode) { session.setDarkMode(darkMode) }

    // retryToken 来自 rememberSaveable，首次组合可能带着历史值，只在真正变化时重建
    var handledRetryToken by remember { mutableIntStateOf(retryToken) }
    LaunchedEffect(retryToken) {
        if (retryToken != handledRetryToken) {
            handledRetryToken = retryToken
            session.retry()
        }
    }

    LaunchedEffect(runtimeError) {
        runtimeError?.let { onRuntimeError(it) }
    }

    // 底图长时间未就绪：多为 Key 未绑定当前包名 / SHA-1（高德鉴权失败时不会抛异常，只是不出图）
    LaunchedEffect(aMap, isMapLoaded) {
        if (aMap == null) return@LaunchedEffect
        if (isMapLoaded) {
            onMapLoaded()
            return@LaunchedEffect
        }
        delay(15_000)
        if (!session.isLoadedState.value) {
            onLoadTimeout(
                "底图 15 秒内未加载完成。最常见原因是 Key 与当前运行环境不匹配：" +
                    "包名（${BuildConfig.APPLICATION_ID}）或签名 SHA-1 与高德控制台填写的不一致，" +
                    "或误把「Web 服务 Key」当成了地图 Key。可点「诊断信息」核对具体数值。"
            )
        }
    }

    // 折线颜色取自主题（高德 SDK 需要 ARGB Int）
    val leg1ColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val leg2ColorArgb = MaterialTheme.extendedColors.legSecondary.toArgb()
    val walkColorArgb = MaterialTheme.extendedColors.walk.toArgb()
    val otherSchemeColorArgb = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f).toArgb()
    val selectedScheme = schemes.getOrNull(selectedIndex)

    // 绘制层：方案列表 / 选中方案 / 主题色变化时重绘。只画线，不动相机：
    // 切换方案不重新缩放，用户可以直接对比。
    LaunchedEffect(schemes, selectedIndex, aMap, isMapLoaded, leg1ColorArgb, leg2ColorArgb, walkColorArgb, otherSchemeColorArgb) {
        if (aMap == null || !isMapLoaded) return@LaunchedEffect
        // clear(true)：保留定位蓝点，只清掉方案覆盖物
        aMap.clear(true)

        // 1. 其他方案：细灰半透明线
        schemes.forEachIndexed { index, scheme ->
            if (index == selectedIndex) return@forEachIndexed
            schemePolylines(scheme).forEach { (points, isWalk) ->
                val opts = PolylineOptions()
                    .color(otherSchemeColorArgb)
                    .width(if (isWalk) 6f else 8f)
                    .setDottedLine(isWalk)
                points.forEach { opts.add(it) }
                aMap.addPolyline(opts)
            }
        }

        // 2. 选中方案：粗彩线 + 换乘 / 起终点 marker
        if (selectedScheme != null) {
            val legs = selectedScheme.group.legs

            // 步行灰色虚线复用的绘制逻辑
            fun drawWalk(points: List<LatLng>) {
                if (points.size < 2) return
                val opts = PolylineOptions()
                    .color(walkColorArgb)
                    .width(12f)
                    .setDottedLine(true)
                points.forEach { opts.add(it) }
                aMap.addPolyline(opts)
            }

            var prevLegToLatLng: LatLng? = null

            legs.forEachIndexed { index, leg ->
                val hasFrom = leg.fromStopLat > 0.0 && leg.fromStopLng > 0.0
                val hasTo = leg.toStopLat > 0.0 && leg.toStopLng > 0.0

                val fromLatLng = if (hasFrom) LatLng(leg.fromStopLat, leg.fromStopLng) else null
                val toLatLng = if (hasTo) LatLng(leg.toStopLat, leg.toStopLng) else null

                // 上车前步行轨迹：优先用解析出的真实 walkPathBefore，否则回落到换乘两站直连虚线
                val walkBefore = leg.walkPathBefore.map { LatLng(it.lat, it.lng) }
                if (walkBefore.size >= 2) {
                    drawWalk(walkBefore)
                } else if (prevLegToLatLng != null && fromLatLng != null && prevLegToLatLng != fromLatLng) {
                    drawWalk(listOf(prevLegToLatLng!!, fromLatLng))
                }

                // 绘制当前公交段折线：优先用真实行驶轨迹 ridePath，为空时回落到起终两点直线
                val legColor = if (index == 0) leg1ColorArgb else leg2ColorArgb
                val ridePoints = leg.ridePath.map { LatLng(it.lat, it.lng) }
                if (ridePoints.size >= 2) {
                    val opts = PolylineOptions().color(legColor).width(18f)
                    ridePoints.forEach { opts.add(it) }
                    aMap.addPolyline(opts)
                } else if (fromLatLng != null && toLatLng != null) {
                    aMap.addPolyline(
                        PolylineOptions()
                            .add(fromLatLng, toLatLng)
                            .color(legColor)
                            .width(18f)
                    )
                }

                if (fromLatLng != null && index != 0) {
                    aMap.addMarker(
                        MarkerOptions()
                            .position(fromLatLng)
                            .title("换乘: ${leg.fromStopName}")
                    )
                }

                if (toLatLng != null) {
                    prevLegToLatLng = toLatLng
                }
            }

            // 末段步行（末站 -> 目的地 POI）
            val finalWalk = selectedScheme.finalWalkPath.map { LatLng(it.lat, it.lng) }
            if (finalWalk.size >= 2) {
                drawWalk(finalWalk)
            } else if (prevLegToLatLng != null && selectedScheme.destinationPoint != null) {
                val dest = LatLng(selectedScheme.destinationPoint!!.lat, selectedScheme.destinationPoint!!.lng)
                if (prevLegToLatLng != dest) drawWalk(listOf(prevLegToLatLng!!, dest))
            }

            // 起点 marker：优先用户选择的出发地 POI，否则回落首段上车站
            schemeOriginLatLng(selectedScheme)?.let { originLatLng ->
                aMap.addMarker(
                    MarkerOptions()
                        .position(originLatLng)
                        .title("起点")
                )
            }

            // 终点 marker：优先用户选择的目的地 POI，否则回落末段下车站
            schemeDestinationLatLng(selectedScheme)?.let { destLatLng ->
                aMap.addMarker(
                    MarkerOptions()
                        .position(destLatLng)
                        .title("终点")
                )
            }
        }
    }

    // 取景层（有方案时）：只在方案列表变化 / 用户点重置时 fit bounds 一次，范围覆盖全部方案。
    // 不能把 myLocation 或选中方案作为 key——否则用户拖动或切换方案时视角会被反复拉回去。
    LaunchedEffect(schemes, aMap, isMapLoaded, recenterTrigger) {
        if (aMap == null || !isMapLoaded || schemes.isEmpty()) return@LaunchedEffect
        val allPoints = schemes.flatMap { schemeBoundsPoints(it) }
        if (allPoints.isEmpty()) return@LaunchedEffect
        val boundsBuilder = LatLngBounds.builder()
        allPoints.forEach { boundsBuilder.include(it) }
        runCatching {
            aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 180))
        }.onFailure {
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(allPoints.first(), 14f))
        }
    }

    // 取景层：负责"首次进入 / 点击重置"时的相机位置。
    // 优先级：我的定位 > 当前城市中心 > 不动（绝不硬编码某个城市，否则外地用户会"被搬去"别的地方）。
    LaunchedEffect(aMap, isMapLoaded, schemes.isNotEmpty(), myLocation, cityCenter, recenterTrigger) {
        if (aMap == null || !isMapLoaded) return@LaunchedEffect
        // 有算路方案时由方案自己 fit bounds，不抢相机
        if (schemes.isNotEmpty()) {
            session.hasCenteredOnce = true
            session.handledRecenterTrigger = recenterTrigger
            return@LaunchedEffect
        }
        val isUserRecenter = recenterTrigger != session.handledRecenterTrigger
        // 定位迟到（先按城市中心取景，之后才拿到 GPS）时补一次跟随
        val isFirstUserFix = myLocation != null && !session.centeredWithMyLocation
        if (session.hasCenteredOnce && !isUserRecenter && !isFirstUserFix) return@LaunchedEffect

        val target = myLocation ?: cityCenter ?: return@LaunchedEffect
        session.hasCenteredOnce = true
        session.handledRecenterTrigger = recenterTrigger
        if (myLocation != null) session.centeredWithMyLocation = true
        aMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(target, if (myLocation != null) 15f else 12f)
        )
    }
}

/** 方案起点：优先用户选择的出发地 POI，否则回落首段上车站 */
private fun schemeOriginLatLng(scheme: AmapTransitSchemeResult): LatLng? =
    scheme.originPoint?.let { LatLng(it.lat, it.lng) }
        ?: scheme.group.legs.firstOrNull()?.takeIf { it.fromStopLat > 0.0 && it.fromStopLng > 0.0 }
            ?.let { LatLng(it.fromStopLat, it.fromStopLng) }

/** 方案终点：优先用户选择的目的地 POI，否则回落末段下车站 */
private fun schemeDestinationLatLng(scheme: AmapTransitSchemeResult): LatLng? =
    scheme.destinationPoint?.let { LatLng(it.lat, it.lng) }
        ?: scheme.group.legs.lastOrNull()?.takeIf { it.toStopLat > 0.0 && it.toStopLng > 0.0 }
            ?.let { LatLng(it.toStopLat, it.toStopLng) }

/**
 * 方案的折线（点序列 to 是否步行）：乘车优先用 ridePath，没有就用上下车站直线；
 * 步行用 walkPathBefore / finalWalkPath。用于画未选中方案的细灰线。
 */
private fun schemePolylines(scheme: AmapTransitSchemeResult): List<Pair<List<LatLng>, Boolean>> {
    val result = mutableListOf<Pair<List<LatLng>, Boolean>>()
    scheme.group.legs.forEach { leg ->
        val walk = leg.walkPathBefore.map { LatLng(it.lat, it.lng) }
        if (walk.size >= 2) result += walk to true
        val ride = leg.ridePath.map { LatLng(it.lat, it.lng) }
        if (ride.size >= 2) {
            result += ride to false
        } else if (leg.fromStopLat > 0.0 && leg.fromStopLng > 0.0 && leg.toStopLat > 0.0 && leg.toStopLng > 0.0) {
            result += listOf(LatLng(leg.fromStopLat, leg.fromStopLng), LatLng(leg.toStopLat, leg.toStopLng)) to false
        }
    }
    val finalWalk = scheme.finalWalkPath.map { LatLng(it.lat, it.lng) }
    if (finalWalk.size >= 2) result += finalWalk to true
    return result
}

/** 方案里所有用于取景的点（折线 + 站点 + 起终点） */
private fun schemeBoundsPoints(scheme: AmapTransitSchemeResult): List<LatLng> = buildList {
    schemePolylines(scheme).forEach { (points, _) -> addAll(points) }
    scheme.group.legs.forEach { leg ->
        if (leg.fromStopLat > 0.0 && leg.fromStopLng > 0.0) add(LatLng(leg.fromStopLat, leg.fromStopLng))
        if (leg.toStopLat > 0.0 && leg.toStopLng > 0.0) add(LatLng(leg.toStopLat, leg.toStopLng))
    }
    schemeOriginLatLng(scheme)?.let { add(it) }
    schemeDestinationLatLng(scheme)?.let { add(it) }
}

// =====================================================================================
// 底部方案面板：最多 4 个方案标签 + 可左右滑动的详情 + 收藏 / 开始追踪
// =====================================================================================

/** 面板顶部最多显示的方案数 */
internal const val MAX_VISIBLE_SCHEMES = 4

/** 是否地铁线路（按线路名粗略判断） */
private fun isSubwayLineName(name: String): Boolean = name.contains("地铁") || name.contains("号线")

/**
 * 方案的特点标签（每个方案最多一个，按 最快 → 直达 / 少换乘 → 少步行 → 地铁 的顺序取第一个符合的）：
 * - 最快：总时长最短（并列取靠前的方案），且方案多于 1 个
 * - 直达：不用换乘；少换乘：换乘次数最少且比其他方案少
 * - 少步行：步行最短（并列取靠前），且比其他方案少
 * - 地铁：含地铁线路
 * 都不符合时返回 null（不显示）。
 */
internal fun schemeFeatureTag(schemes: List<AmapTransitSchemeResult>, index: Int): String? {
    val scheme = schemes.getOrNull(index) ?: return null
    val compare = schemes.size > 1
    val fastestIndex = schemes.indices.minByOrNull { schemes[it].durationMinutes }
    val minTransfers = schemes.minOf { it.transferCount }
    val maxTransfers = schemes.maxOf { it.transferCount }
    val minWalk = schemes.minOf { it.walkingMeters }
    val maxWalk = schemes.maxOf { it.walkingMeters }
    val leastWalkIndex = schemes.indices.minByOrNull { schemes[it].walkingMeters }
    return when {
        compare && index == fastestIndex -> "最快"
        scheme.transferCount == 0 -> "直达"
        compare && scheme.transferCount == minTransfers && minTransfers < maxTransfers -> "少换乘"
        compare && index == leastWalkIndex && minWalk < maxWalk -> "少步行"
        scheme.group.legs.any { leg -> leg.candidateLines.any { isSubwayLineName(it.lineName) } } -> "地铁"
        else -> null
    }
}

/** 标签上的换乘文字："直达" / "换 1 次" */
internal fun schemeTransferText(scheme: AmapTransitSchemeResult): String =
    if (scheme.transferCount == 0) "直达" else "换 ${scheme.transferCount} 次"

/** "步行 820 米 · 换乘 1 次 · ¥4.0" */
internal fun schemeSummaryText(scheme: AmapTransitSchemeResult): String {
    val transfer = if (scheme.transferCount == 0) "不用换乘" else "换乘 ${scheme.transferCount} 次"
    val cost = scheme.costYuan.trim().takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }?.let { "¥$it" }
    return listOfNotNull("步行 ${scheme.walkingMeters} 米", transfer, cost).joinToString(" · ")
}

/** "07:42 出发 · 08:14 到"（按当前时间估算） */
internal fun departArriveText(
    nowMillis: Long,
    durationMinutes: Int,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val arrive = nowMillis + durationMinutes.coerceAtLeast(0) * 60_000L
    return "${formatClock(nowMillis, zoneId)} 出发 · ${formatClock(arrive, zoneId)} 到"
}

/** 分段条的一段 */
internal data class PlanSegment(
    val isWalk: Boolean,
    val legIndex: Int,
    val weight: Float,
    val label: String
)

/**
 * 分段条：步行 / 每程按时长比例分配。
 * 方案里没有分段时长，所以步行按 80 米 / 分钟估算，乘车按站数 × 2 分钟；
 * 站数不可信的程平分剩余时长。乘车段文字只写线路名，步行段不写字。
 */
internal fun planSegments(scheme: AmapTransitSchemeResult): List<PlanSegment> {
    val legs = scheme.group.legs
    if (legs.isEmpty()) return emptyList()
    fun walkMinutes(meters: Int): Float = if (meters <= 0) 0f else (meters / 80f).coerceAtLeast(1f)

    val walkBefore = legs.map { it.walkingDistanceMeters.coerceAtLeast(0) }
    val finalWalk = (scheme.walkingMeters - walkBefore.sum()).coerceAtLeast(0)
    val totalWalk = walkBefore.sumOf { walkMinutes(it).toDouble() }.toFloat() + walkMinutes(finalWalk)
    val knownRide = legs.map { leg -> reliableRideStops(leg.primaryLine)?.let { it * 2f } }
    val unknownCount = knownRide.count { it == null }
    val rideBudget = (scheme.durationMinutes - totalWalk - knownRide.sumOf { (it ?: 0f).toDouble() }.toFloat())
        .coerceAtLeast(0f)
    val fallbackRide = if (unknownCount > 0) (rideBudget / unknownCount).coerceAtLeast(1f) else 0f

    return buildList {
        legs.forEachIndexed { index, leg ->
            val walk = walkMinutes(walkBefore[index])
            if (walk > 0f) add(PlanSegment(isWalk = true, legIndex = index, weight = walk, label = ""))
            add(
                PlanSegment(
                    isWalk = false,
                    legIndex = index,
                    weight = (knownRide[index] ?: fallbackRide).coerceAtLeast(1f),
                    label = leg.primaryLine?.lineName.orEmpty()
                )
            )
        }
        val last = walkMinutes(finalWalk)
        if (last > 0f) add(PlanSegment(isWalk = true, legIndex = legs.lastIndex, weight = last, label = ""))
    }
}

/**
 * 规划页追踪状态条文字：
 * 等车 "第 1/2 程 · 等车 · 4 分钟"；乘车 "第 1/2 程 · 乘车 · 2 站后下车"。
 */
internal fun trackingStatusText(session: GroupTrackingSessionState): String {
    val legSession = session.trackingSessionState?.takeIf {
        it.legPhase != LegPhase.SINGLE && it.currentLegIndex == session.activeLegIndex
    }
    val phase = legSession?.legPhase ?: LegPhase.WAITING_BOARD
    val label = trackingPhaseLabel(session.activeLegIndex, session.totalLegs, phase)
    val detail = when {
        legSession == null || legSession.stopsRemaining < 0 -> null
        phase == LegPhase.ON_BOARD -> "${legSession.stopsRemaining} 站后下车"
        else -> etaMinutesOf(legSession.etaSeconds, legSession.stopsRemaining)?.let { "$it 分钟" }
    }
    return if (detail != null) "$label · $detail" else label
}

@Composable
internal fun RoutePlanSchemePanel(
    schemes: List<AmapTransitSchemeResult>,
    pagerState: PagerState,
    trackingSession: GroupTrackingSessionState?,
    onSelectScheme: (Int) -> Unit,
    onLineClick: (CandidateLine) -> Unit,
    onSaveFavorite: () -> Unit,
    onStartTracking: (AmapTransitSchemeResult) -> Unit,
    onConfirmBoarded: () -> Unit,
    onStopTracking: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nowMillis = remember(schemes) { System.currentTimeMillis() }
    Column(modifier = modifier.fillMaxWidth()) {
        // 1. 方案标签：一直可见，点一下切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            schemes.forEachIndexed { index, scheme ->
                SchemeTab(
                    scheme = scheme,
                    featureTag = schemeFeatureTag(schemes, index),
                    selected = pagerState.currentPage == index,
                    onClick = { onSelectScheme(index) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scheme_tab_$index")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. 详情：左右滑动切换，与标签联动
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scheme_pager"),
            verticalAlignment = Alignment.Top,
            pageSpacing = 16.dp
        ) { page ->
            schemes.getOrNull(page)?.let { scheme ->
                SchemeDetail(
                    page = page,
                    scheme = scheme,
                    nowMillis = nowMillis,
                    onLineClick = onLineClick
                )
            }
        }

        if (schemes.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
            ) {
                schemes.indices.forEach { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (selected) 16.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. 按钮：收藏 / 开始追踪；正在追踪当前方案时换成追踪状态条
        val current = schemes.getOrNull(pagerState.currentPage)
        val isTrackingCurrent = trackingSession != null && !trackingSession.isCompleted &&
            current != null && trackingSession.groupId == current.group.id
        if (isTrackingCurrent) {
            TrackingStatusBar(
                session = trackingSession,
                onConfirmBoarded = onConfirmBoarded,
                onStopTracking = onStopTracking
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveFavorite,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_save_favorite")
                ) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("收藏", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { current?.let(onStartTracking) },
                    enabled = current != null,
                    modifier = Modifier
                        .weight(2f)
                        .height(48.dp)
                        .testTag("btn_start_monitor")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("开始追踪", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SchemeTab(
    scheme: AmapTransitSchemeResult,
    featureTag: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${scheme.durationMinutes} 分钟",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = schemeTransferText(scheme),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
            if (featureTag != null) {
                Text(
                    text = featureTag,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchemeDetail(
    page: Int,
    scheme: AmapTransitSchemeResult,
    nowMillis: Long,
    onLineClick: (CandidateLine) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${scheme.durationMinutes} 分钟",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("scheme_duration_$page")
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = departArriveText(nowMillis, scheme.durationMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SegmentBar(segments = planSegments(scheme))

        Text(
            text = schemeSummaryText(scheme),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("scheme_summary_$page")
        )

        scheme.group.legs.forEachIndexed { legIndex, leg ->
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第 ${legIndex + 1} 程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                leg.candidateLines.forEachIndexed { lineIndex, line ->
                    val isMain = lineIndex == 0
                    Surface(
                        onClick = { onLineClick(line) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isMain) legLineColor(legIndex) else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.testTag("scheme_line_chip_${line.lineName}")
                    ) {
                        Text(
                            text = if (isMain) line.lineName else "或 ${line.lineName}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = when {
                                !isMain -> MaterialTheme.colorScheme.onSurfaceVariant
                                legIndex == 0 -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 分段条：步行灰、第 1 程 primary、第 2 程起 legSecondary，宽度按时长比例 */
@Composable
private fun SegmentBar(segments: List<PlanSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .testTag("scheme_segment_bar"),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        segments.forEach { segment ->
            val background = if (segment.isWalk) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                legLineColor(segment.legIndex)
            }
            Box(
                modifier = Modifier
                    .weight(segment.weight)
                    .fillMaxHeight()
                    .background(background),
                contentAlignment = Alignment.Center
            ) {
                if (segment.label.isNotEmpty()) {
                    Text(
                        text = segment.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (segment.legIndex == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}

/** 规划页追踪状态条："第 1/2 程 · 等车 · 4 分钟" + 我已上车 / 结束 */
@Composable
private fun TrackingStatusBar(
    session: GroupTrackingSessionState,
    onConfirmBoarded: () -> Unit,
    onStopTracking: () -> Unit
) {
    val phase = session.trackingSessionState
        ?.takeIf { it.legPhase != LegPhase.SINGLE && it.currentLegIndex == session.activeLegIndex }
        ?.legPhase ?: LegPhase.WAITING_BOARD
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tracking_status_bar"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = trackingStatusText(session),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .testTag("tracking_status_text")
            )
            if (phase == LegPhase.WAITING_BOARD) {
                FilledTonalButton(
                    onClick = onConfirmBoarded,
                    modifier = Modifier.testTag("btn_plan_confirm_boarded")
                ) {
                    Text("我已上车")
                }
            }
            Button(
                onClick = onStopTracking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.testTag("btn_plan_stop_tracking")
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("结束")
            }
        }
    }
}
