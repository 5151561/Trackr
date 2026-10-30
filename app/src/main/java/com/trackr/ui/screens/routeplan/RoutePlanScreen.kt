package com.trackr.ui.screens.routeplan

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
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
import com.trackr.domain.model.TransitGroup
import com.trackr.ui.screens.favoritesgroup.FavoritesGroupViewModel
import com.trackr.util.SignatureUtils
import kotlinx.coroutines.launch

/**
 * 路线规划全屏界面 (RoutePlanScreen - 路线规划 Tab)
 * 采用 全屏矢量地图底座 + 顶部悬浮搜索岛 + 底部方案抽屉 架构
 */
@Composable
fun RoutePlanScreen(
    viewModel: FavoritesGroupViewModel,
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: DataSourceType, lineName: String) -> Unit = { _, _, _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val originTips by viewModel.amapOriginTips.collectAsStateWithLifecycle()
    val destTips by viewModel.amapDestTips.collectAsStateWithLifecycle()
    val plannedSchemes by viewModel.plannedSchemes.collectAsStateWithLifecycle()
    val isPlanning by viewModel.isPlanningRoute.collectAsStateWithLifecycle()
    val planError by viewModel.planErrorMessage.collectAsStateWithLifecycle()
    val customApiKey by viewModel.customAmapApiKey.collectAsStateWithLifecycle()
    val favoriteGroups by viewModel.favoriteGroups.collectAsStateWithLifecycle()

    var originText by rememberSaveable { mutableStateOf("") }
    var destText by rememberSaveable { mutableStateOf("") }
    var selectedOrigin by remember { mutableStateOf<AmapTipItem?>(null) }
    var selectedDest by remember { mutableStateOf<AmapTipItem?>(null) }

    var selectedSchemeIndex by rememberSaveable { mutableIntStateOf(0) }
    var isSheetExpanded by rememberSaveable { mutableStateOf(false) }
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveGroupDialog by rememberSaveable { mutableStateOf(false) }

    var isOriginFocused by remember { mutableStateOf(false) }
    var isDestFocused by remember { mutableStateOf(false) }
    var dismissOriginTips by remember { mutableStateOf(false) }
    var dismissDestTips by remember { mutableStateOf(false) }

    var mapScale by remember { mutableFloatStateOf(1f) }
    var mapOffset by remember { mutableStateOf(Offset.Zero) }
    var recenterTrigger by rememberSaveable { mutableIntStateOf(0) }

    val isRobolectric = remember {
        android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
    }

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

    val currentScheme = plannedSchemes.getOrNull(selectedSchemeIndex)

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 全屏矢量地图视窗 (真实高德 3D 矢量地图 / Robolectric Canvas 测试降级)
        if (isRobolectric) {
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
        } else {
            AmapRealVectorMapView(
                selectedScheme = currentScheme,
                customApiKey = customApiKey,
                recenterTrigger = recenterTrigger,
                modifier = Modifier.fillMaxSize()
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
                                    .background(Color(0xFF00875A), CircleShape)
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
                                    .background(Color(0xFFE53935), CircleShape)
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
                            tint = if (customApiKey.isNullOrBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (customApiKey.isNullOrBlank()) "配置Key" else "Key已生效",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = if (customApiKey.isNullOrBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
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
                        .clickable { isSheetExpanded = !isSheetExpanded }
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
                        if (!isSheetExpanded) {
                            // --- Peek 状态 (简洁信息概览) ---
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val badge = if (currentScheme.transferCount == 0) "直达多线推荐" else "推荐方案"
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = badge,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "约 ${currentScheme.durationMinutes} 分钟",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (plannedSchemes.size > 1) {
                                    Text(
                                        text = "展开全部方案 (${plannedSchemes.size}) ▾",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { isSheetExpanded = true }
                                            .padding(4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "步行 ${currentScheme.walkingMeters}米 · 换乘 ${currentScheme.transferCount} 次 · 预估费用 ¥${currentScheme.costYuan}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 线路号码小胶囊群
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                currentScheme.group.legs.forEachIndexed { legIdx, leg ->
                                    if (legIdx > 0) {
                                        Text(
                                            text = "➔",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    leg.candidateLines.forEach { line ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.clickable {
                                                onNavigateToLineDetail(
                                                    line.cityId,
                                                    line.lineId,
                                                    line.toStopOrder,
                                                    "",
                                                    line.source,
                                                    line.lineName
                                                )
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DirectionsBus,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(13.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = line.lineName,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 底部双主行动按钮 (存入收藏 & 开启监控)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showSaveGroupDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("btn_save_favorite"),
                                    shape = RoundedCornerShape(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("★ 存入收藏", fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        viewModel.addPlannedSchemeToGroups(currentScheme)
                                        viewModel.startGroupTracking(context, currentScheme.group)
                                        Toast.makeText(context, "已加入路线组并开启全程换乘追踪", Toast.LENGTH_SHORT).show()
                                        onNavigateToFavorites()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("btn_start_monitor"),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("▷ 开启监控", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            // --- Expanded 状态 (对比全部备选方案列表) ---
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "全部规划方案 (${plannedSchemes.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "收起 ▴",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { isSheetExpanded = false }
                                        .padding(4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(plannedSchemes) { index, scheme ->
                                    val isSelected = (index == selectedSchemeIndex)
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedSchemeIndex = index }
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(14.dp)
                                            ),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "方案 ${index + 1}",
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "约 ${scheme.durationMinutes} 分钟",
                                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                                    )
                                                }
                                                Text(
                                                    text = "¥${scheme.costYuan}",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "步行 ${scheme.walkingMeters}米 · 换乘 ${scheme.transferCount} 次",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = scheme.title,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showSaveGroupDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(22.dp)
                                ) {
                                    Text("★ 存入收藏", fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        viewModel.addPlannedSchemeToGroups(currentScheme)
                                        viewModel.startGroupTracking(context, currentScheme.group)
                                        Toast.makeText(context, "已加入路线组并开启全程换乘追踪", Toast.LENGTH_SHORT).show()
                                        onNavigateToFavorites()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(22.dp)
                                ) {
                                    Text("▷ 开启监控", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
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
            onSaveCustomApiKey = { viewModel.saveCustomAmapApiKey(it) },
            onDismiss = { showKeyDialog = false }
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
    val isDark = isSystemInDarkTheme()
    val textMeasurer = rememberTextMeasurer()

    // 调色盘规范
    val bgColor = if (isDark) Color(0xFF1B1E22) else Color(0xFFE8ECEF)
    val gridColor = if (isDark) Color(0xFF282C34) else Color(0xFFD3D9DE)
    val arterialRoadColor = if (isDark) Color(0xFF242830) else Color(0xFFFFFFFF)
    val arterialCasingColor = if (isDark) Color(0xFF1E2126) else Color(0xFFD8DEE4)

    val transitLeg1Color = Color(0xFF0061A4)
    val transitLeg2Color = Color(0xFF7C4DFF)
    val walkingColor = Color(0xFF78909C)

    val originColor = Color(0xFF00875A)
    val transferColor = Color(0xFF7C4DFF)
    val destColor = Color(0xFFE53935)

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
        color = Color(0x33000000),
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
 * 高德 Android Key 与 SHA-1 签名配置对话框
 */
@Composable
fun AmapApiKeyConfigDialog(
    customApiKey: String?,
    onSaveCustomApiKey: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var inputKey by remember(customApiKey) { mutableStateOf(customApiKey.orEmpty()) }
    val currentSha1 = remember { SignatureUtils.getSigningCertificateSha1(context) }

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
                    text = "高德地图 Key 与签名配置",
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
                        text = "本应用默认启用本地站点直达规划引擎。若需使用高德云端规划与地铁换乘算路，可在高德开放平台创建 Android Key：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        onValueChange = { inputKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("高德 Android Key") },
                        placeholder = { Text("粘贴 32 位 Android Key") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveCustomApiKey(inputKey.trim())
                    onDismiss()
                }
            ) {
                Text("保存生效")
            }
        },
        dismissButton = {
            Row {
                if (inputKey.isNotBlank()) {
                    TextButton(
                        onClick = {
                            inputKey = ""
                            onSaveCustomApiKey("")
                            onDismiss()
                        }
                    ) {
                        Text("清空")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

/**
 * 真实高德 3D 矢量地图视图 (TextureMapView)
 */
@Composable
fun AmapRealVectorMapView(
    selectedScheme: AmapTransitSchemeResult?,
    customApiKey: String?,
    recenterTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    var aMapInstance by remember { mutableStateOf<AMap?>(null) }
    var mapViewInstance by remember { mutableStateOf<TextureMapView?>(null) }

    key(customApiKey) {
        AndroidView(
            factory = { ctx ->
                AmapSdkManager.applyApiKey(ctx, customApiKey)
                TextureMapView(ctx).apply {
                    onCreate(null)
                    mapViewInstance = this
                    val aMap = this.map
                    aMapInstance = aMap

                    aMap.uiSettings.apply {
                        isZoomControlsEnabled = false
                        isCompassEnabled = false
                        isScaleControlsEnabled = true
                        isMyLocationButtonEnabled = false
                    }
                }
            },
            modifier = modifier
        )
    }

    DisposableEffect(lifecycleOwner, mapViewInstance) {
        val mapView = mapViewInstance ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(selectedScheme, recenterTrigger, aMapInstance) {
        val aMap = aMapInstance ?: return@LaunchedEffect
        aMap.clear()

        if (selectedScheme != null) {
            val legs = selectedScheme.group.legs
            val boundsBuilder = LatLngBounds.builder()
            var validPointsCount = 0

            var prevLegToLatLng: LatLng? = null

            legs.forEachIndexed { index, leg ->
                val hasFrom = leg.fromStopLat > 0.0 && leg.fromStopLng > 0.0
                val hasTo = leg.toStopLat > 0.0 && leg.toStopLng > 0.0

                val fromLatLng = if (hasFrom) LatLng(leg.fromStopLat, leg.fromStopLng) else null
                val toLatLng = if (hasTo) LatLng(leg.toStopLat, leg.toStopLng) else null

                // 若与上一段换乘站坐标不同，绘制步行换乘虚线
                if (prevLegToLatLng != null && fromLatLng != null && prevLegToLatLng != fromLatLng) {
                    aMap.addPolyline(
                        PolylineOptions()
                            .add(prevLegToLatLng, fromLatLng)
                            .color(0xFF78909C.toInt())
                            .width(12f)
                            .setDottedLine(true)
                    )
                }

                // 绘制当前公交段折线
                if (fromLatLng != null && toLatLng != null) {
                    val legColor = if (index == 0) 0xFF0061A4.toInt() else 0xFF7C4DFF.toInt()
                    aMap.addPolyline(
                        PolylineOptions()
                            .add(fromLatLng, toLatLng)
                            .color(legColor)
                            .width(18f)
                    )
                }

                if (fromLatLng != null) {
                    boundsBuilder.include(fromLatLng)
                    validPointsCount++
                    if (index == 0) {
                        aMap.addMarker(
                            MarkerOptions()
                                .position(fromLatLng)
                                .title("起点: ${leg.fromStopName}")
                        )
                    } else {
                        aMap.addMarker(
                            MarkerOptions()
                                .position(fromLatLng)
                                .title("换乘: ${leg.fromStopName}")
                        )
                    }
                }

                if (toLatLng != null) {
                    boundsBuilder.include(toLatLng)
                    validPointsCount++
                    prevLegToLatLng = toLatLng
                    if (index == legs.lastIndex) {
                        aMap.addMarker(
                            MarkerOptions()
                                .position(toLatLng)
                                .title("终点: ${leg.toStopName}")
                        )
                    }
                }
            }

            if (validPointsCount > 0) {
                runCatching {
                    val bounds = boundsBuilder.build()
                    aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 180))
                }.onFailure {
                    val firstLeg = legs.firstOrNull()
                    if (firstLeg != null && firstLeg.fromStopLat > 0.0 && firstLeg.fromStopLng > 0.0) {
                        aMap.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(firstLeg.fromStopLat, firstLeg.fromStopLng),
                                14f
                            )
                        )
                    }
                }
            }
        } else {
            aMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(22.5431, 114.0579),
                    14f
                )
            )
        }
    }
}
