package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.sanitizePlateOrFleetNo
import com.trackr.ui.components.common.BusArrivalBadge
import com.trackr.ui.theme.busColors

/**
 * 状态 2：车辆列表内容 (`BUS_LIST`)
 */
@Composable
fun BusListContent(
    lineDetail: BusLineDetail,
    selectedStop: BusStop?,
    onBack: () -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val currentOrder = selectedStop?.order ?: 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回站点卡",
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "到「${selectedStop?.name ?: "当前站"}」的全部车辆",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "共 ${lineDetail.buses.size} 辆",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (lineDetail.buses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "当前线路上暂无在途运营车辆",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            // 排序：未过本站（按距离升序）在前，已过本站沉底
            val sortedBuses = remember(lineDetail.buses, currentOrder) {
                lineDetail.buses.sortedWith(
                    compareBy<LiveBus> { it.order > currentOrder }
                        .thenBy { if (it.etaSeconds > 0) it.etaSeconds else Int.MAX_VALUE }
                        .thenBy { currentOrder - it.order }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sortedBuses.forEach { bus ->
                    val isPassed = bus.order > currentOrder
                    val isAtStop = bus.order == currentOrder && bus.isArrived
                    val stopsLeft = currentOrder - bus.order

                    Surface(
                        onClick = { onSelectBus(bus) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isPassed) busColors.busPassedGray.copy(alpha = 0.2f) else busColors.busChipContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.DirectionsBus,
                                        contentDescription = null,
                                        tint = if (isPassed) busColors.busPassedGray else busColors.busChipContent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                val cleanPlate = sanitizePlateOrFleetNo(bus.plateOrFleetNo)
                                val displayPlate = cleanPlate.ifBlank {
                                    when {
                                        isPassed -> "已过站车辆"
                                        isAtStop -> "已到站车辆"
                                        else -> "在途车辆"
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = displayPlate,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isPassed) busColors.busPassedGray else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (bus.speedKmh > 0 && !isPassed) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${bus.speedKmh.toInt()} km/h",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        isPassed -> "已过该站"
                                        isAtStop -> "已到站"
                                        stopsLeft > 0 -> "还有 $stopsLeft 站 · 正在接近第 ${bus.order} 站"
                                        else -> "正在进站"
                                    },
                                    fontSize = 12.sp,
                                    color = if (isPassed) busColors.busPassedGray else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val statusText = when {
                                    isPassed -> "已过站"
                                    isAtStop -> "已到站"
                                    bus.etaSeconds > 0 -> RouteProjectionMath.formatEtaText(bus.etaSeconds)
                                    else -> "无预测"
                                }
                                BusArrivalBadge(
                                    text = statusText,
                                    isHighlighted = isAtStop || (!isPassed && stopsLeft <= 1)
                                )
                                if (!isPassed && !bus.expectedArrivalTimeText.isNullOrEmpty()) {
                                    Text(
                                        text = "${bus.expectedArrivalTimeText} 到",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "查看单车跟随",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
