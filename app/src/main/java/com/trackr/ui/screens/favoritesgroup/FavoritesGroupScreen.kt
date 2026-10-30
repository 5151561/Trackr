package com.trackr.ui.screens.favoritesgroup

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.ui.screens.favoritesgroup.components.AmapRoutePlanDialog
import com.trackr.ui.screens.favoritesgroup.components.GroupEditDialog
import com.trackr.ui.screens.favoritesgroup.components.TransitGroupCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesGroupScreen(
    viewModel: FavoritesGroupViewModel,
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: DataSourceType, lineName: String) -> Unit = { _, _, _, _, _, _ -> },
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrBlank()) {
                        viewModel.checkClipboardForShareContent(text)
                    }
                }
            }
        } catch (_: Exception) {
            // 安全捕获剪贴板跨进程读取或安全沙箱权限异常
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
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
        PasteImportDialog(
            isImporting = isImporting,
            onDismiss = { showPasteImportDialog = false },
            onConfirm = { textToImport ->
                showPasteImportDialog = false
                viewModel.importMapShare(textToImport) { success ->
                    if (success) {
                        scope.launch {
                            snackbarHostState.showSnackbar("路线方案已成功导入！")
                        }
                    }
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
            onClearOriginTips = { viewModel.searchOriginTips("") },
            onClearDestTips = { viewModel.searchDestTips("") },
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
 * 手工粘贴导入方案弹窗
 */
@Composable
private fun PasteImportDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var importInputText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
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
                        onConfirm(textToImport)
                    }
                },
                enabled = importInputText.isNotBlank() && !isImporting
            ) {
                Text("开始解析导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
