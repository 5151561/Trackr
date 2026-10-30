package com.trackr.ui.screens.favoritesgroup

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.ui.screens.favoritesgroup.components.GroupEditDialog
import com.trackr.ui.screens.favoritesgroup.components.ManageFavoriteGroupsBottomSheet
import com.trackr.ui.screens.favoritesgroup.components.TransitGroupCard
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesGroupScreen(
    viewModel: FavoritesGroupViewModel,
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: DataSourceType, lineName: String) -> Unit = { _, _, _, _, _, _ -> },
    onNavigateToRoutePlan: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val groups by viewModel.filteredGroups.collectAsStateWithLifecycle()
    val allGroups by viewModel.groups.collectAsStateWithLifecycle()
    val favoriteGroups by viewModel.favoriteGroups.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedFavoriteGroupId by viewModel.selectedFavoriteGroupId.collectAsStateWithLifecycle()

    val activeSession by viewModel.activeGroupSession.collectAsStateWithLifecycle()
    val realtimeSummaries by viewModel.legRealtimeSummary.collectAsStateWithLifecycle()
    val candidateRealtimeMap by viewModel.candidateRealtimeMap.collectAsStateWithLifecycle()
    val selectedLineKeys by viewModel.selectedLineKeys.collectAsStateWithLifecycle()

    val directCount = remember(allGroups) { allGroups.count { it.legs.size == 1 } }
    val transferCount = remember(allGroups) { allGroups.count { it.legs.size > 1 } }

    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showCreateFavGroupQuickDialog by remember { mutableStateOf(false) }
    var showManageGroupsSheet by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<TransitGroup?>(null) }

    // 向上滑动时大标题平滑收起为单行标题栏，向下拉时展开，统一首页与更多页规范
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "我的收藏",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.testTag("top_bar_title")
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "已收纳 ${directCount} 个同达方案与 ${transferCount} 个换乘组合",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("top_bar_subtitle")
                        )
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showManageGroupsSheet = true },
                        modifier = Modifier.testTag("btn_manage_groups")
                    ) {
                        Text("⚙️ 管理", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    IconButton(
                        onClick = onNavigateToRoutePlan,
                        modifier = Modifier.testTag("btn_route_plan_action")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.AltRoute,
                            contentDescription = "路线规划",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { showCreateGroupDialog = true },
                        modifier = Modifier.testTag("btn_add_group_action")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "新建分组",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 80.dp + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 顶部智能分组滑动栏 (Filter Chips)
            item(key = "favorite_group_chips", contentType = "chips") {
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 全部
                    FilterChip(
                        selected = selectedFavoriteGroupId == null && selectedCategory == null,
                        onClick = {
                            viewModel.selectFavoriteGroup(null)
                            viewModel.selectCategory(null)
                        },
                        label = { Text("全部 (${allGroups.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("chip_all_groups")
                    )

                    // 自定义收藏分组
                    favoriteGroups.forEach { favGroup ->
                        val count = allGroups.count {
                            it.customGroupId == favGroup.id ||
                                (favGroup.id == "commute" && it.category == GroupCategory.COMMUTE) ||
                                (favGroup.id == "home" && (it.category == GroupCategory.COMMUTE || it.name.contains("家")))
                        }
                        FilterChip(
                            selected = selectedFavoriteGroupId == favGroup.id,
                            onClick = { viewModel.selectFavoriteGroup(favGroup.id) },
                            label = { Text("${favGroup.name} ($count)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("chip_fav_group_${favGroup.id}")
                        )
                    }

                    // + 新建分组 Chip
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showCreateFavGroupQuickDialog = true }
                            .testTag("chip_quick_create_group"),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "+ 新建",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // 2. 路线分组列表或空状态
            if (groups.isEmpty()) {
                item(key = "empty_groups", contentType = "empty_state") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.AltRoute,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
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
                                text = "点击右上角「新建」或「智能规划」添加常用路线与换乘方案",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onNavigateToRoutePlan) {
                                    Icon(imageVector = Icons.AutoMirrored.Filled.AltRoute, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("路线规划")
                                }
                                OutlinedButton(onClick = { showCreateGroupDialog = true }) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("新建路线")
                                }
                            }
                        }
                    }
                }
            } else {
                items(
                    items = groups,
                    key = { it.id },
                    contentType = { it.category }
                ) { group ->
                    TransitGroupCard(
                        group = group,
                        activeSession = activeSession,
                        realtimeSummaries = realtimeSummaries,
                        candidateRealtimeMap = candidateRealtimeMap,
                        selectedLineKeys = selectedLineKeys,
                        onSelectCandidateLine = { legIndex, lineId ->
                            viewModel.selectCandidateLine(group.id, legIndex, lineId)
                        },
                        onAddCandidateLine = { legIndex, lineName ->
                            val leg = group.legs.getOrNull(legIndex)
                            val newCandidate = CandidateLine(
                                cityId = leg?.primaryLine?.cityId ?: "033",
                                cityName = leg?.primaryLine?.cityName ?: "",
                                lineId = "line_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
                                lineName = lineName.trim(),
                                source = leg?.primaryLine?.source ?: DataSourceType.CHELAILE,
                                direction = leg?.primaryLine?.direction ?: 0,
                                fromStopOrder = leg?.primaryLine?.fromStopOrder ?: 1,
                                toStopOrder = (leg?.primaryLine?.fromStopOrder ?: 1) + 4,
                                startStopName = leg?.fromStopName.orEmpty(),
                                endStopName = leg?.toStopName.orEmpty()
                            )
                            viewModel.addCandidateLineToLeg(group.id, legIndex, newCandidate)
                        },
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

    // 3. 抽屉管理收藏分组
    if (showManageGroupsSheet) {
        ManageFavoriteGroupsBottomSheet(
            favoriteGroups = favoriteGroups,
            onDismiss = { showManageGroupsSheet = false },
            onCreateGroup = { name -> viewModel.createFavoriteGroup(name) },
            onRenameGroup = { groupId, newName -> viewModel.renameFavoriteGroup(groupId, newName) },
            onDeleteGroup = { groupId -> viewModel.deleteFavoriteGroup(groupId) },
            onReorderGroups = { groupIds -> viewModel.reorderFavoriteGroups(groupIds) }
        )
    }

    // 4. 快速新建分组弹窗 (+ 新建 chip 唤起)
    if (showCreateFavGroupQuickDialog) {
        var quickName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFavGroupQuickDialog = false },
            title = { Text("新建收藏分组") },
            text = {
                OutlinedTextField(
                    value = quickName,
                    onValueChange = { quickName = it },
                    label = { Text("分组名称") },
                    placeholder = { Text("例如：健身房、常用路线") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_quick_group_name")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (quickName.isNotBlank()) {
                            viewModel.createFavoriteGroup(quickName.trim())
                            showCreateFavGroupQuickDialog = false
                            quickName = ""
                        }
                    },
                    enabled = quickName.isNotBlank(),
                    modifier = Modifier.testTag("btn_confirm_quick_group")
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateFavGroupQuickDialog = false
                    quickName = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // 5. 新建 / 编辑路线分组弹窗
    if (showCreateGroupDialog || editingGroup != null) {
        val target = editingGroup
        GroupEditDialog(
            initialGroup = target,
            favoriteGroups = favoriteGroups,
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
