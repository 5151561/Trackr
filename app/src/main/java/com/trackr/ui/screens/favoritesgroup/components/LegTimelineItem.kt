package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.TransitLeg

/**
 * 单程段展示走向
 */
@Composable
fun LegTimelineItem(
    legIndex: Int,
    totalLegs: Int,
    leg: TransitLeg,
    isLegActive: Boolean,
    etaSummary: String,
    onLineClick: (CandidateLine) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryCandidate = leg.primaryLine

    Column(modifier = modifier.fillMaxWidth()) {
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
fun TransferConnector(
    walkingMeters: Int,
    transferStationName: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
