package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.TransitGroup

/**
 * 路线分组卡片（时间线/走向图样式）
 */
@Composable
fun TransitGroupCard(
    group: TransitGroup,
    activeSession: GroupTrackingSessionState?,
    realtimeSummaries: Map<String, String>,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onSaveTemporary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLineClick: (CandidateLine) -> Unit,
    modifier: Modifier = Modifier
) {
    val isTrackingThisGroup = activeSession != null && activeSession.groupId == group.id && !activeSession.isCompleted
    val currentLegIndex = activeSession?.activeLegIndex ?: 0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTrackingThisGroup) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = if (isTrackingThisGroup) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
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
