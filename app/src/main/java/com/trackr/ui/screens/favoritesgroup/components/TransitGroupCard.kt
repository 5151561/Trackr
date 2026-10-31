package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.TransitGroup

/**
 * 路线分组卡片（支持单段多线同达卡片与多段换乘时间轴卡片）
 * 严格遵循 Spec v2.3 规范：绝不展示冗余“备选”文字，采用纯号码小胶囊即时联动
 */
@Composable
fun TransitGroupCard(
    group: TransitGroup,
    activeSession: GroupTrackingSessionState?,
    realtimeSummaries: Map<String, String>,
    candidateRealtimeMap: Map<String, CandidateLineRealtimeInfo> = emptyMap(),
    selectedLineKeys: Map<String, String> = emptyMap(),
    onSelectCandidateLine: (legIndex: Int, lineId: String) -> Unit = { _, _ -> },
    onAddCandidateLine: (legIndex: Int, lineName: String) -> Unit = { _, _ -> },
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConfirmBoarded: () -> Unit = {},
    onReverseClick: () -> Unit = {},
    onSaveTemporary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLineClick: (CandidateLine) -> Unit,
    modifier: Modifier = Modifier
) {
    val isTrackingThisGroup = activeSession != null && activeSession.groupId == group.id && !activeSession.isCompleted
    val currentLegIndex = activeSession?.activeLegIndex ?: 0
    // 正在等车阶段（WAITING_BOARD）时允许用户手动“我已上车”
    val isWaitingBoard = isTrackingThisGroup &&
        activeSession?.trackingSessionState?.legPhase == com.trackr.domain.model.LegPhase.WAITING_BOARD

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackingBorder = remember(primaryColor) {
        BorderStroke(2.dp, primaryColor)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("transit_group_card_${group.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTrackingThisGroup) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isTrackingThisGroup) trackingBorder else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (group.legs.size <= 1) {
                // =========================================================================
                // Mode A: 单段多线同达卡片 (group.legs.size == 1)
                // =========================================================================
                val leg = group.legs.firstOrNull()
                if (leg != null) {
                    // 1. 头部标题与紧凑操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("group_title_${group.id}")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (group.isTemporary) {
                                IconButton(
                                    onClick = onSaveTemporary,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("btn_save_temp_${group.id}")
                                ) {
                                    Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = "转存", modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(
                                onClick = onReverseClick,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("btn_reverse_${group.id}")
                            ) {
                                Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = "生成返程", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = onEdit,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("btn_edit_${group.id}")
                            ) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("btn_delete_${group.id}")
                            ) {
                                Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // 行 2：行程摘要与追踪按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = "直达组合",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            val totalWalk = leg.walkingDistanceMeters
                            val walkSuffix = if (totalWalk > 0) " · 步行 ${totalWalk}米" else ""
                            Text(
                                text = "${leg.fromStopName} ➔ ${leg.toStopName}$walkSuffix",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable {
                                    if (isTrackingThisGroup) onStopTracking() else onStartTracking()
                                }
                                .testTag("btn_track_${group.id}"),
                            shape = RoundedCornerShape(20.dp),
                            color = if (isTrackingThisGroup) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isTrackingThisGroup) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isTrackingThisGroup) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isTrackingThisGroup) "■ 结束追踪" else "▷ 开启追踪",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isTrackingThisGroup) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (isWaitingBoard) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onConfirmBoarded() }
                                    .testTag("btn_confirm_boarded_${group.id}"),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "我已上车",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    // 2. 纯线路号码小图标胶囊群 (横向滚动，点击即联动切换，含添加按钮)
                    val selectedLineId = selectedLineKeys["${group.id}_0"] ?: leg.primaryLine?.lineId
                    var showAddCandidateDialog by remember { mutableStateOf(false) }
                    var newCandidateLineName by remember { mutableStateOf("") }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        leg.candidateLines.forEach { candidate ->
                            val candidateKey = "${group.id}_0_${candidate.lineId}"
                            val info = candidateRealtimeMap[candidateKey]
                                ?: candidateRealtimeMap["${candidate.lineId}_${candidate.toStopOrder}"]

                            val isSelected = candidate.lineId == selectedLineId
                            val shortEta = info?.shortChipEta?.takeIf { it.isNotBlank() }

                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onSelectCandidateLine(0, candidate.lineId) }
                                    .testTag("candidate_chip_${candidate.lineName}"),
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                border = if (!isSelected) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                } else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "🚌 ${candidate.lineName}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (shortEta != null) {
                                        Text(
                                            text = "· $shortEta",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else if (info.stopsAway <= 2) {
                                                Color(0xFF00875A)
                                            } else {
                                                Color(0xFFD97706)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 添加同达线路小按钮
                        Surface(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { showAddCandidateDialog = true }
                                .testTag("btn_add_candidate_direct"),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "添加同达线路",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (showAddCandidateDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                showAddCandidateDialog = false
                                newCandidateLineName = ""
                            },
                            title = { Text("添加同达线路") },
                            text = {
                                OutlinedTextField(
                                    value = newCandidateLineName,
                                    onValueChange = { newCandidateLineName = it },
                                    label = { Text("线路名称") },
                                    placeholder = { Text("例如：311、M194") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (newCandidateLineName.isNotBlank()) {
                                            onAddCandidateLine(0, newCandidateLineName.trim())
                                            showAddCandidateDialog = false
                                            newCandidateLineName = ""
                                        }
                                    },
                                    enabled = newCandidateLineName.isNotBlank()
                                ) {
                                    Text("添加")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showAddCandidateDialog = false
                                    newCandidateLineName = ""
                                }) {
                                    Text("取消")
                                }
                            }
                        )
                    }

                    // 3. 联动切换的实时信息展示区 (Linked Real-Time Detail Panel)
                    val selectedCandidate = leg.candidateLines.find { it.lineId == selectedLineId }
                        ?: leg.candidateLines.firstOrNull()

                    val candidateKey = if (selectedCandidate != null) "${group.id}_0_${selectedCandidate.lineId}" else ""
                    val info = candidateRealtimeMap[candidateKey]
                        ?: (selectedCandidate?.let { candidateRealtimeMap["${it.lineId}_${it.toStopOrder}"] })

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail_panel_direct"),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val lineDisplayName = selectedCandidate?.lineName?.let {
                                            if (it.endsWith("路") || it.contains("线")) it else "$it 路"
                                        } ?: "公交线路"
                                        Text(
                                            text = lineDisplayName,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.testTag("selected_candidate_name")
                                        )
                                        if (info?.isFastest == true) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF00875A)
                                            ) {
                                                Text(
                                                    text = "最快",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val dir = info?.directionText?.ifEmpty { null }
                                        ?: selectedCandidate?.endStopName?.takeIf { it.isNotEmpty() }?.let { "$it 方向" }
                                        ?: "${leg.toStopName} 方向"
                                    Text(
                                        text = "开往 $dir",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    val etaText = info?.etaText?.ifEmpty { null }
                                        ?: (selectedCandidate?.let { realtimeSummaries["${it.lineId}_${it.toStopOrder}"] })
                                        ?: "查询车辆中…"
                                    Text(
                                        text = etaText,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                                        color = if (info != null && info.stopsAway <= 2) Color(0xFF00875A) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("selected_candidate_eta")
                                    )
                                    if (!info?.vehiclePlate.isNullOrBlank()) {
                                        Text(
                                            text = info.vehiclePlate,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val stopsCount = (selectedCandidate?.let { it.toStopOrder - it.fromStopOrder } ?: 1).coerceAtLeast(1)
                                Text(
                                    text = "下车站: ${leg.toStopName} (乘 $stopsCount 站)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable(enabled = selectedCandidate != null) {
                                            selectedCandidate?.let { onLineClick(it) }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .testTag("btn_candidate_trajectory"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "实时轨迹 >",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                }
            } else {
                // =========================================================================
                // Mode B: 多段换乘时间轴卡片 (group.legs.size > 1)
                // =========================================================================
                // 1. 头部标题与紧凑操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("group_title_${group.id}")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (group.isTemporary) {
                            IconButton(
                                onClick = onSaveTemporary,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("btn_save_temp_${group.id}")
                            ) {
                                Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = "转存", modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(
                            onClick = onReverseClick,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("btn_reverse_${group.id}")
                        ) {
                            Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = "生成返程", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("btn_edit_${group.id}")
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("btn_delete_${group.id}")
                        ) {
                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // 行 2：换乘及步行摘要与追踪按钮
                val transferCount = group.legs.size - 1
                val totalWalk = group.legs.sumOf { it.walkingDistanceMeters }
                val summaryText = if (totalWalk > 0) {
                    "换乘 $transferCount 次 · 步行 ${totalWalk}米"
                } else {
                    "换乘 $transferCount 次 · 同站换乘"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                if (isTrackingThisGroup) onStopTracking() else onStartTracking()
                            }
                            .testTag("btn_track_${group.id}"),
                        shape = RoundedCornerShape(20.dp),
                        color = if (isTrackingThisGroup) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isTrackingThisGroup) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isTrackingThisGroup) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isTrackingThisGroup) "■ 结束追踪" else "▷ 开启追踪",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isTrackingThisGroup) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // 2. 竖向时间轴走向
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 起点
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00875A))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = group.legs.firstOrNull()?.fromStopName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 各行程段
                    group.legs.forEachIndexed { index, leg ->
                        val isLegActive = isTrackingThisGroup && currentLegIndex == index
                        val activeLineId = selectedLineKeys["${group.id}_$index"] ?: leg.primaryLine?.lineId
                        val activeCandidate = leg.candidateLines.find { it.lineId == activeLineId } ?: leg.primaryLine
                        val realtimeKey = if (activeCandidate != null) "${activeCandidate.lineId}_${activeCandidate.toStopOrder}" else ""
                        val legEta = candidateRealtimeMap["${group.id}_${index}_${activeCandidate?.lineId}"]?.etaText
                            ?: realtimeSummaries[realtimeKey]
                            ?: "查询中…"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp)
                        ) {
                            // 导轨
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(if (isLegActive) 92.dp else 84.dp)
                                    .background(if (isLegActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            LegTimelineItem(
                                legIndex = index,
                                totalLegs = group.legs.size,
                                leg = leg,
                                isLegActive = isLegActive,
                                candidateRealtimeMap = candidateRealtimeMap,
                                selectedLineId = activeLineId,
                                etaSummary = legEta,
                                onSelectCandidateLine = { lineId -> onSelectCandidateLine(index, lineId) },
                                onAddCandidateLine = { lineName -> onAddCandidateLine(index, lineName) },
                                onLineClick = onLineClick,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // 换乘中转节点
                        if (index < group.legs.size - 1) {
                            val nextLeg = group.legs.getOrNull(index + 1)
                            val walkMeters = nextLeg?.walkingDistanceMeters ?: leg.walkingDistanceMeters
                            val transferLabel = if (walkMeters > 0) {
                                "${leg.toStopName} (步行 ${walkMeters}米)"
                            } else {
                                "${leg.toStopName} (同站换乘)"
                            }
                            Row(
                                modifier = Modifier.padding(start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF7C4DFF))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = transferLabel,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF512DA8)
                                )
                            }
                        }
                    }

                    // 终点
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE91E63))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = group.legs.lastOrNull()?.toStopName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
