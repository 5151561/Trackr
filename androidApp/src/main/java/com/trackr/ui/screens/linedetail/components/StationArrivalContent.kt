package com.trackr.ui.screens.linedetail.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.domain.arrival.ArrivalDisplay
import com.trackr.domain.arrival.ArrivalText
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.sanitizePlateOrFleetNo
import com.trackr.ui.components.common.arrivalToneColor
import com.trackr.core.designsystem.theme.busColors
import com.trackr.core.designsystem.theme.extendedColors
import kotlinx.datetime.LocalTime

private val ALERT_THRESHOLD_OPTIONS = listOf(1, 2, 3)

/**
 * 状态 1：站点到站内容 (`STATION_CARD`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationArrivalContent(
    lineDetail: BusLineDetail,
    selectedStop: BusStop?,
    isTrackingActive: Boolean,
    alertThreshold: Int,
    onThresholdChange: (Int) -> Unit,
    onOpenBusList: () -> Unit,
    onToggleTracking: () -> Unit,
    onOpenScheduledReminder: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackNextDeparture: String? = null,
    /** 判「已过末班」用的本地时刻；默认取这次数据的刷新时刻 */
    now: LocalTime? = null
) {
    val busColors = MaterialTheme.busColors
    val currentOrder = selectedStop?.order ?: 1

    // 寻找最近一辆未过本站的在途车
    val upcomingBus = remember(lineDetail.buses, currentOrder) {
        ArrivalText.nearestBus(currentOrder, lineDetail.buses)
    }

    val stopsRemaining = if (upcomingBus != null) {
        currentOrder - upcomingBus.order
    } else null

    // 到站大字：8 种状态统一由 ArrivalText 给出；全部车过站时只给下一班始发发车时间，不估到站
    val nowTime = now ?: remember(lineDetail.updatedAtMillis) { ArrivalText.localTimeOf(lineDetail.updatedAtMillis) }
    val arrival = remember(lineDetail, currentOrder, fallbackNextDeparture, nowTime) {
        val line = lineDetail.line
        ArrivalText.format(
            ArrivalText.stateFor(
                targetOrder = currentOrder,
                buses = lineDetail.buses,
                operatingState = lineDetail.operatingState,
                startStop = line.startStop,
                firstTime = line.firstTime,
                lastTime = line.lastTime,
                nextDeparture = lineDetail.nextDepartureFromStart?.takeIf { it.isNotBlank() }
                    ?: fallbackNextDeparture,
                statusDescription = lineDetail.statusDescription,
                now = nowTime
            ),
            lineName = line.name,
            stopName = selectedStop?.name.orEmpty()
        )
    }
    val arrivalColor = arrivalToneColor(arrival.tone)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // 候车站名与状态标
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selectedStop?.name ?: "选择候车站",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!selectedStop?.statusNote.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = busColors.conditionStopBorder.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = selectedStop.statusNote,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = busColors.conditionStopBorder,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "第 $currentOrder 站",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 醒目到站预报大字：「8 分钟」+ 小字「还有 4 站」，或「到站了」+「快去上车 · 下一辆 9 分钟」
        if (upcomingBus != null) {
            ArrivalHeadline(arrival = arrival, color = arrivalColor)

            Spacer(modifier = Modifier.height(8.dp))

            // 车辆信息行：车牌、预计到达钟点、速度（分钟数已在大字里，不再重复）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val plateClean = sanitizePlateOrFleetNo(upcomingBus.plateOrFleetNo)
                val plateDisplay = plateClean.ifBlank {
                    if (upcomingBus.isArrived) "已到站车辆" else "在途车辆"
                }
                Text(
                    text = plateDisplay,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!upcomingBus.expectedArrivalTimeText.isNullOrEmpty()) {
                        Text(
                            text = "${upcomingBus.expectedArrivalTimeText} 到",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    if (upcomingBus.speedKmh > 0) {
                        Text(
                            text = "${upcomingBus.speedKmh.toInt()} km/h",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // M3E 进度条：计算此车到本站的相对进度
            val progress = remember(stopsRemaining, upcomingBus.segmentProgress) {
                if (stopsRemaining == null || stopsRemaining <= 0) 1.0f
                else (1.0f - (stopsRemaining.toFloat() / (currentOrder.coerceAtLeast(1)))).coerceIn(0.05f, 0.95f)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = busColors.busRunningGreen,
                trackColor = busColors.routeTrackInactive,
                strokeCap = StrokeCap.Round
            )
        } else {
            // 无可用在途车：车都过了 / 还没发车 / 末班已过 / 停运，给出下一班始发时间等有效信息
            ArrivalHeadline(arrival = arrival, color = arrivalColor)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 快捷入口按钮行（换向功能已在底部悬浮栏提供，此处不再重复）
        OutlinedButton(
            onClick = onOpenBusList,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = lineDetail.buses.size.let { count ->
                    if (count > 0) "全线 $count 辆车在途 · 查看" else "全线暂无在途车辆"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 提醒阈值单选芯片（1 站 / 2 站 / 3 站）：未选中仅显示数字，选中展开为“提前 x 站”
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "提前到站震动预警：",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ALERT_THRESHOLD_OPTIONS.forEach { stops ->
                        val isSelected = alertThreshold == stops
                        FilterChip(
                            selected = isSelected,
                            onClick = { onThresholdChange(stops) },
                            label = {
                                AnimatedContent(
                                    targetState = isSelected,
                                    transitionSpec = {
                                        (fadeIn(animationSpec = tween(180, delayMillis = 40)) +
                                            scaleIn(initialScale = 0.88f, animationSpec = tween(180, delayMillis = 40)))
                                            .togetherWith(
                                                fadeOut(animationSpec = tween(90))
                                            )
                                    },
                                    label = "AlertThresholdChipLabel_$stops"
                                ) { selected ->
                                    Text(
                                        text = if (selected) "提前 ${stops} 站" else "$stops",
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 32.dp)
                                .height(28.dp)
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 操作按钮区：开启实时追踪与设定定时通勤
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onToggleTracking,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTrackingActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = if (isTrackingActive) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isTrackingActive) "结束实时追踪" else "开启实时追踪",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onOpenScheduledReminder,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccessTime,
                    contentDescription = "定时通勤",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "定时通勤",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 到站大字 + 小字（[ArrivalText] 的输出） */
@Composable
private fun ArrivalHeadline(arrival: ArrivalDisplay, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = arrival.value,
                fontSize = if (arrival.unit != null) 34.sp else 26.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
            if (arrival.unit != null) {
                Text(
                    text = arrival.unit,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
        }
        if (arrival.detail.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = arrival.detail,
                fontSize = 13.sp,
                color = if (arrival.detailIsWarning) {
                    MaterialTheme.extendedColors.warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
