package com.trackr.ui.screens.favoritesgroup

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackr.data.remote.amap.AmapTipItem
import com.trackr.data.remote.amap.AmapTransitSchemeResult
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesGroupScreen(
    viewModel: FavoritesGroupViewModel,
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: com.trackr.domain.model.DataSourceType, lineName: String) -> Unit = { _, _, _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val groups by viewModel.filteredGroups.collectAsStateWithLifecycle()
    val allGroupsCount by viewModel.groups.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeGroupSession.collectAsStateWithLifecycle()
    val realtimeSummaries by viewModel.legRealtimeSummary.collectAsStateWithLifecycle()
    val clipboardPrompt by viewModel.clipboardSharePrompt.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importError by viewModel.importErrorMessage.collectAsStateWithLifecycle()

    val amapOriginTips by viewModel.amapOriginTips.collectAsStateWithLifecycle()
    val amapDestTips by viewModel.amapDestTips.collectAsStateWithLifecycle()
    val plannedSchemes by viewModel.plannedSchemes.collectAsStateWithLifecycle()
    val isPlanningRoute by viewModel.isPlanningRoute.collectAsStateWithLifecycle()
    val planError by viewModel.planErrorMessage.collectAsStateWithLifecycle()

    var showPasteImportDialog by remember { mutableStateOf(false) }
    var showAmapPlanDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<TransitGroup?>(null) }

    // 页面进入或恢复到前台时，自动检测剪贴板是否含有高德/百度分享文本
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null && clipboard.hasPrimaryClip()) {
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
                            viewModel.checkClipboardForShareContent(text)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportError()
        }
    }

    LaunchedEffect(planError) {
        planError?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "路线组与换乘",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "多段智能换乘 · 实时动态流转",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAmapPlanDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.AltRoute,
                            contentDescription = "高德智能规划",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showPasteImportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "导入地图路线",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showCreateGroupDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "新建分组",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. 剪贴板地图路线识别条
            AnimatedVisibility(
                visible = clipboardPrompt != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                clipboardPrompt?.let { text ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.AltRoute,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "检测到地图分享路线",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = text.take(60) + if (text.length > 60) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.importMapShare(text) { success ->
                                        if (success) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("路线方案已成功导入！")
                                            }
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                if (isImporting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("一键导入", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            IconButton(
                                onClick = { viewModel.dismissClipboardPrompt() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "忽略",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // 2. 场景分类过滤器 (Filter Chips)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { viewModel.selectCategory(null) },
                    label = { Text("全部 (${allGroupsCount.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                GroupCategory.entries.forEach { category ->
                    val count = allGroupsCount.count { it.category == category }
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { viewModel.selectCategory(category) },
                        label = { Text("${category.displayName} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            // 3. 路线组展示列表
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.AltRoute,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无匹配的路线分组",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "支持直接复制高德/百度地图方案粘贴导入，或点击右上角新建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { showAmapPlanDialog = true }) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.AltRoute, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("高德智能规划")
                            }
                            OutlinedButton(onClick = { showPasteImportDialog = true }) {
                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("粘贴方案导入")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(items = groups, key = { it.id }) { group ->
                        TransitGroupCard(
                            group = group,
                            activeSession = activeSession,
                            realtimeSummaries = realtimeSummaries,
                            onStartTracking = { viewModel.startGroupTracking(context, group) },
                            onStopTracking = { viewModel.stopGroupTracking(context) },
                            onSaveTemporary = { viewModel.saveOrConvertTemporaryGroup(group.id) },
                            onEdit = { editingGroup = group },
                            onDelete = { viewModel.deleteGroup(group.id) },
                            onLineClick = { candidate ->
                                onNavigateToLineDetail(
                                    candidate.cityId,
                                    candidate.lineId,
                                    candidate.toStopOrder,
                                    "",
                                    candidate.source,
                                    candidate.lineName
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // 4. 手工粘贴导入方案弹窗
    if (showPasteImportDialog) {
        var importInputText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteImportDialog = false },
            title = { Text("导入地图路线方案") },
            text = {
                Column {
                    Text(
                        text = "支持直接粘贴高德地图或百度地图 App 复制的分享文本、路线链接（如 surl.amap.com / j.map.baidu.com），系统将自动为您提取站点并对齐实时公交数据：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importInputText,
                        onValueChange = { importInputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("在此粘贴分享文案或短链接…") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val textToImport = importInputText.trim()
                        if (textToImport.isNotEmpty()) {
                            showPasteImportDialog = false
                            viewModel.importMapShare(textToImport) { success ->
                                if (success) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("路线方案已成功导入！")
                                    }
                                }
                            }
                        }
                    },
                    enabled = importInputText.isNotBlank() && !isImporting
                ) {
                    Text("开始解析导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteImportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 5. 高德智能路线规划弹窗
    if (showAmapPlanDialog) {
        AmapRoutePlanDialog(
            originTips = amapOriginTips,
            destTips = amapDestTips,
            plannedSchemes = plannedSchemes,
            isPlanning = isPlanningRoute,
            onSearchOrigin = { viewModel.searchOriginTips(it) },
            onSearchDest = { viewModel.searchDestTips(it) },
            onPlanRoute = { origin, dest ->
                viewModel.planTransitRoute(origin, dest)
            },
            onAddScheme = { scheme ->
                viewModel.addPlannedSchemeToGroups(scheme)
                showAmapPlanDialog = false
                viewModel.clearPlanning()
                scope.launch {
                    snackbarHostState.showSnackbar("路线方案已成功加入常用路线组！")
                }
            },
            onStartTrackingScheme = { scheme ->
                viewModel.addPlannedSchemeToGroups(scheme)
                viewModel.startGroupTracking(context, scheme.group)
                showAmapPlanDialog = false
                viewModel.clearPlanning()
                scope.launch {
                    snackbarHostState.showSnackbar("已加入路线组并开启全程换乘追踪！")
                }
            },
            onDismiss = {
                showAmapPlanDialog = false
                viewModel.clearPlanning()
            }
        )
    }

    // 6. 新建 / 编辑分组弹窗
    if (showCreateGroupDialog || editingGroup != null) {
        val target = editingGroup
        GroupEditDialog(
            initialGroup = target,
            onDismiss = {
                showCreateGroupDialog = false
                editingGroup = null
            },
            onSave = { savedGroup ->
                viewModel.saveGroup(savedGroup)
                showCreateGroupDialog = false
                editingGroup = null
            }
        )
    }
}

/**
 * 路线分组卡片（时间线/走向图样式）
 */
@Composable
private fun TransitGroupCard(
    group: TransitGroup,
    activeSession: com.trackr.domain.model.GroupTrackingSessionState?,
    realtimeSummaries: Map<String, String>,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onSaveTemporary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLineClick: (CandidateLine) -> Unit
) {
    val isTrackingThisGroup = activeSession != null && activeSession.groupId == group.id && !activeSession.isCompleted
    val currentLegIndex = activeSession?.activeLegIndex ?: 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTrackingThisGroup) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = if (isTrackingThisGroup) {
            borderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 头部：分类标签、标题、操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 分类 Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (group.category) {
                        GroupCategory.COMMUTE -> MaterialTheme.colorScheme.primary
                        GroupCategory.LEISURE -> Color(0xFF00897B)
                        GroupCategory.TEMPORARY -> Color(0xFFF57C00)
                        GroupCategory.CUSTOM -> MaterialTheme.colorScheme.secondary
                    }
                ) {
                    Text(
                        text = group.category.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (group.isTemporary) {
                    TextButton(
                        onClick = onSaveTemporary,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("转存常用", style = MaterialTheme.typography.labelSmall)
                    }
                }

                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 时间线走向展示 (Timeline Flow)
            group.legs.forEachIndexed { index, leg ->
                val isLegActive = isTrackingThisGroup && currentLegIndex == index

                LegTimelineItem(
                    legIndex = index,
                    totalLegs = group.legs.size,
                    leg = leg,
                    isLegActive = isLegActive,
                    realtimeSummaries = realtimeSummaries,
                    onLineClick = onLineClick
                )

                if (index < group.legs.size - 1) {
                    // 换乘站连接处
                    TransferConnector(
                        walkingMeters = leg.walkingDistanceMeters,
                        transferStationName = leg.toStopName
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 底部控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isTrackingThisGroup) {
                    OutlinedButton(
                        onClick = onStopTracking,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("结束换乘追踪")
                    }
                } else {
                    Button(
                        onClick = onStartTracking,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开始全程换乘追踪")
                    }
                }
            }
        }
    }
}

/**
 * 单程段展示走向
 */
@Composable
private fun LegTimelineItem(
    legIndex: Int,
    totalLegs: Int,
    leg: TransitLeg,
    isLegActive: Boolean,
    realtimeSummaries: Map<String, String>,
    onLineClick: (CandidateLine) -> Unit
) {
    val primaryCandidate = leg.primaryLine
    val realtimeKey = if (primaryCandidate != null) "${primaryCandidate.lineId}_${primaryCandidate.toStopOrder}" else ""
    val etaSummary = realtimeSummaries[realtimeKey] ?: "查询车辆中…"

    Column(modifier = Modifier.fillMaxWidth()) {
        // 起始站点
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isLegActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = leg.fromStopName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (legIndex == 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "出发",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }

        // 线路与实时到站信息卡片
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 5.dp)
        ) {
            // 左侧时间线导轨
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(58.dp)
                    .background(
                        if (isLegActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
            )

            Spacer(modifier = Modifier.width(15.dp))

            // 线路与实时卡片
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = primaryCandidate != null) {
                        primaryCandidate?.let { onLineClick(it) }
                    },
                shape = RoundedCornerShape(10.dp),
                color = if (isLegActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsBus,
                        contentDescription = null,
                        tint = if (isLegActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = primaryCandidate?.let { "${it.lineName}（开往 ${it.endStopName.ifEmpty { leg.toStopName }}）" } ?: "待配置乘坐线路",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = etaSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLegActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isLegActive) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "正在追踪",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // 到达/终点站点
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (legIndex == totalLegs - 1) Color(0xFFE91E63) else MaterialTheme.colorScheme.outline
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = leg.toStopName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (legIndex == totalLegs - 1) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFE91E63).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "终点",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE91E63),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/**
 * 换乘节点连接指示
 */
@Composable
private fun TransferConnector(
    walkingMeters: Int,
    transferStationName: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 5.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(28.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(modifier = Modifier.width(15.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (walkingMeters > 0) "同站换乘 · 步行约 ${walkingMeters}m" else "在 $transferStationName 换乘下一程",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

/**
 * 创建/编辑路线组弹窗
 */
@Composable
private fun GroupEditDialog(
    initialGroup: TransitGroup?,
    onDismiss: () -> Unit,
    onSave: (TransitGroup) -> Unit
) {
    var groupName by remember { mutableStateOf(initialGroup?.name.orEmpty()) }
    var selectedCategory by remember { mutableStateOf(initialGroup?.category ?: GroupCategory.COMMUTE) }

    // 简易两段换乘站名与路线名输入
    val leg1 = initialGroup?.legs?.getOrNull(0)
    val leg2 = initialGroup?.legs?.getOrNull(1)

    var from1 by remember { mutableStateOf(leg1?.fromStopName.orEmpty()) }
    var to1 by remember { mutableStateOf(leg1?.toStopName.orEmpty()) }
    var line1 by remember { mutableStateOf(leg1?.primaryLine?.lineName.orEmpty()) }

    var from2 by remember { mutableStateOf(leg2?.fromStopName.orEmpty().ifEmpty { to1 }) }
    var to2 by remember { mutableStateOf(leg2?.toStopName.orEmpty()) }
    var line2 by remember { mutableStateOf(leg2?.primaryLine?.lineName.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialGroup == null) "新建路线分组" else "编辑路线分组") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("分组名称") },
                    placeholder = { Text("例如：早晚高峰上下班通勤") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text("所属场景", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GroupCategory.entries.filter { it != GroupCategory.TEMPORARY }.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.displayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("第 1 程路线", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = from1,
                        onValueChange = {
                            from1 = it
                            if (from2.isEmpty()) from2 = to1
                        },
                        label = { Text("起点站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = to1,
                        onValueChange = {
                            to1 = it
                            from2 = it // 自动联动第二程起点
                        },
                        label = { Text("换乘站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = line1,
                    onValueChange = { line1 = it },
                    label = { Text("乘坐线路（如 156路）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("第 2 程路线（可选）", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = from2,
                        onValueChange = { from2 = it },
                        label = { Text("换乘站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = to2,
                        onValueChange = { to2 = it },
                        label = { Text("终点站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = line2,
                    onValueChange = { line2 = it },
                    label = { Text("换乘线路（如 地铁4号线）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = groupName.trim().ifEmpty { "${from1} ➔ ${to2.ifEmpty { to1 }}" }
                    val legs = mutableListOf<TransitLeg>()
                    if (from1.isNotBlank() && to1.isNotBlank()) {
                        legs.add(
                            TransitLeg(
                                legIndex = 0,
                                fromStopName = from1.trim(),
                                toStopName = to1.trim(),
                                candidateLines = listOf(
                                    CandidateLine(
                                        cityId = leg1?.primaryLine?.cityId ?: "033",
                                        cityName = leg1?.primaryLine?.cityName ?: "杭州",
                                        lineId = leg1?.primaryLine?.lineId ?: "line_1_${UUID.randomUUID().toString().take(6)}",
                                        lineName = line1.trim().ifEmpty { "156路" },
                                        fromStopOrder = 1,
                                        toStopOrder = 5
                                    )
                                )
                            )
                        )
                    }
                    if (from2.isNotBlank() && to2.isNotBlank()) {
                        legs.add(
                            TransitLeg(
                                legIndex = 1,
                                fromStopName = from2.trim(),
                                toStopName = to2.trim(),
                                candidateLines = listOf(
                                    CandidateLine(
                                        cityId = leg2?.primaryLine?.cityId ?: "033",
                                        cityName = leg2?.primaryLine?.cityName ?: "杭州",
                                        lineId = leg2?.primaryLine?.lineId ?: "line_2_${UUID.randomUUID().toString().take(6)}",
                                        lineName = line2.trim().ifEmpty { "地铁4号线" },
                                        fromStopOrder = 1,
                                        toStopOrder = 6
                                    )
                                ),
                                walkingDistanceMeters = 60
                            )
                        )
                    }

                    val updated = initialGroup?.copy(
                        name = finalName,
                        category = selectedCategory,
                        legs = if (legs.isNotEmpty()) legs else initialGroup.legs,
                        updatedAt = System.currentTimeMillis()
                    ) ?: TransitGroup(
                        id = UUID.randomUUID().toString(),
                        name = finalName,
                        category = selectedCategory,
                        legs = legs
                    )
                    onSave(updated)
                },
                enabled = from1.isNotBlank() && to1.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun borderStroke(width: androidx.compose.ui.unit.Dp, color: Color): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(width, color)
}

/**
 * 高德智能公交路径规划弹窗
 */
@Composable
private fun AmapRoutePlanDialog(
    originTips: List<AmapTipItem>,
    destTips: List<AmapTipItem>,
    plannedSchemes: List<AmapTransitSchemeResult>,
    isPlanning: Boolean,
    onSearchOrigin: (String) -> Unit,
    onSearchDest: (String) -> Unit,
    onPlanRoute: (origin: AmapTipItem, dest: AmapTipItem) -> Unit,
    onAddScheme: (AmapTransitSchemeResult) -> Unit,
    onStartTrackingScheme: (AmapTransitSchemeResult) -> Unit,
    onDismiss: () -> Unit
) {
    var originText by remember { mutableStateOf("") }
    var destText by remember { mutableStateOf("") }
    var selectedOrigin by remember { mutableStateOf<AmapTipItem?>(null) }
    var selectedDest by remember { mutableStateOf<AmapTipItem?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.AltRoute,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "高德智能公交换乘规划",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
            ) {
                Text(
                    text = "基于高德开放平台路径规划，输入起终点一键生成换乘路线组：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                // 起点输入
                OutlinedTextField(
                    value = originText,
                    onValueChange = {
                        originText = it
                        selectedOrigin = null
                        onSearchOrigin(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("出发地 / 站点") },
                    placeholder = { Text("输入起点站名或地名…") },
                    singleLine = true,
                    trailingIcon = {
                        if (selectedOrigin != null) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )

                // 起点搜索候选
                if (selectedOrigin == null && originTips.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    ) {
                        items(originTips.take(4)) { tip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedOrigin = tip
                                        originText = tip.name
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tip.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (tip.district.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tip.district,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 终点输入
                OutlinedTextField(
                    value = destText,
                    onValueChange = {
                        destText = it
                        selectedDest = null
                        onSearchDest(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目的地 / 站点") },
                    placeholder = { Text("输入终点站名或商圈…") },
                    singleLine = true,
                    trailingIcon = {
                        if (selectedDest != null) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )

                // 终点搜索候选
                if (selectedDest == null && destTips.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    ) {
                        items(destTips.take(4)) { tip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDest = tip
                                        destText = tip.name
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tip.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (tip.district.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tip.district,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 规划按钮
                Button(
                    onClick = {
                        val org = selectedOrigin ?: AmapTipItem(name = originText.trim(), location = "")
                        val dst = selectedDest ?: AmapTipItem(name = destText.trim(), location = "")
                        onPlanRoute(org, dst)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = originText.isNotBlank() && destText.isNotBlank() && !isPlanning
                ) {
                    if (isPlanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("高德智能计算换乘方案中…")
                    } else {
                        Icon(imageVector = Icons.AutoMirrored.Filled.AltRoute, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开始换乘规划")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 方案结果列表
                if (plannedSchemes.isNotEmpty()) {
                    Text(
                        text = "推荐换乘路线方案 (${plannedSchemes.size}条)：",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(plannedSchemes) { scheme ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = scheme.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "¥${scheme.costYuan}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "约 ${scheme.durationMinutes} 分钟 · 步行 ${scheme.walkingMeters}米 · ${scheme.group.legs.size}段换乘",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { onAddScheme(scheme) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("加入路线组", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { onStartTrackingScheme(scheme) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("立即追踪", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
