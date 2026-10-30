package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.TransitLeg

/**
 * 换乘卡片中单程段走向展示
 * 遵循 Spec v2.3 极简化纯线路号码胶囊规范，彻底去除啰嗦文本
 */
@Composable
fun LegTimelineItem(
    legIndex: Int,
    totalLegs: Int,
    leg: TransitLeg,
    isLegActive: Boolean,
    candidateRealtimeMap: Map<String, CandidateLineRealtimeInfo> = emptyMap(),
    selectedLineId: String? = null,
    etaSummary: String = "",
    onSelectCandidateLine: ((lineId: String) -> Unit)? = null,
    onAddCandidateLine: ((lineName: String) -> Unit)? = null,
    onLineClick: (CandidateLine) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddLineDialog by remember { mutableStateOf(false) }
    var newLineName by remember { mutableStateOf("") }

    val activeLineId = selectedLineId ?: leg.primaryLine?.lineId
    val selectedCandidate = leg.candidateLines.find { it.lineId == activeLineId } ?: leg.primaryLine

    val candidateInfo = selectedCandidate?.let { candidateRealtimeMap["${it.lineId}_${it.toStopOrder}"] }
    val displayedEta = candidateInfo?.etaText?.ifEmpty { null }
        ?: if (etaSummary.isNotBlank()) etaSummary else "查询中…"

    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("leg_timeline_item_$legIndex"),
        shape = RoundedCornerShape(12.dp),
        color = if (isLegActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (isLegActive) BorderStroke(1.5.dp, primaryColor) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 顶部行：[第N程] 胶囊 + 纯线路号码胶囊群 + [ + ] 按钮，右侧显示实时 ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // 程数胶囊
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${legIndex + 1}程",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    // 纯线路号码胶囊列表 (例如 320, M375)
                    leg.candidateLines.forEach { candidate ->
                        val isSelected = candidate.lineId == activeLineId
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onSelectCandidateLine?.invoke(candidate.lineId) }
                                .testTag("leg_chip_${candidate.lineName}"),
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = if (!isSelected) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            } else null
                        ) {
                            Text(
                                text = candidate.lineName,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // 添加候选线路小按钮
                    Surface(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showAddLineDialog = true }
                            .testTag("btn_add_line_leg_$legIndex"),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "添加换乘线路",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // 右侧实时到站概况
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = displayedEta,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (candidateInfo?.stopsAway != null && candidateInfo.stopsAway <= 2) {
                            Color(0xFF00875A)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag("leg_eta_text_$legIndex")
                    )

                    if (isLegActive) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "正在追踪",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // 底部行：候车站 ➔ 下车站指引及实时轨迹入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "候车: ${leg.fromStopName} ➔ ${leg.toStopName} 下车",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = selectedCandidate != null) {
                            selectedCandidate?.let { onLineClick(it) }
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("btn_track_trajectory_$legIndex"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "实时轨迹 >",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        softWrap = false
                    )
                }
            }
        }
    }

    if (showAddLineDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddLineDialog = false
                newLineName = ""
            },
            title = { Text("添加换乘线路") },
            text = {
                OutlinedTextField(
                    value = newLineName,
                    onValueChange = { newLineName = it },
                    label = { Text("线路名称") },
                    placeholder = { Text("例如：M375、204") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newLineName.isNotBlank()) {
                            onAddCandidateLine?.invoke(newLineName.trim())
                            showAddLineDialog = false
                            newLineName = ""
                        }
                    },
                    enabled = newLineName.isNotBlank()
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddLineDialog = false
                    newLineName = ""
                }) {
                    Text("取消")
                }
            }
        )
    }
}
