package com.trackr.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Subway
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.LiveBus
import com.trackr.ui.theme.BusSemanticColors
import com.trackr.ui.theme.busChipSpringSpec
import com.trackr.ui.theme.busColors
import com.trackr.ui.theme.expressiveSpringSpec
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 走向条展示模式：横向一站一列 vs 纵向时间轴
 */
enum class RouteRailLayoutMode {
    Horizontal,
    Vertical
}

/**
 * 车牌简写转换函数：
 * 车牌号（如“桂K03853D”）在窄列宽走向条上提取核心号码（如“3853”），自编号（如“430”）保留
 */
fun formatBusChipShortText(plateOrFleetNo: String): String {
    val hasChinese = plateOrFleetNo.any { it in '\u4e00'..'\u9fa5' }
    return if (hasChinese) {
        val alphanum = plateOrFleetNo.filter { it.isLetterOrDigit() }
        val suffix = alphanum.dropWhile { it.isLetter() }
        suffix.trimStart('0').ifEmpty { plateOrFleetNo }
    } else {
        plateOrFleetNo
    }
}

/**
 * Material Design 3 Expressive 实时公交走向条组件
 *
 * 核心特性：
 * 1. 横向/纵向双布局自适应切换
 * 2. 真实物理弹簧站间插值 (`segmentProgress`) 动态平滑推进公交徽标小芯片
 * 3. 候车站呼吸高亮胶囊与条件停靠站空心描边节点
 * 4. 自动视野居中对焦选中候车站
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExpressiveRouteRail(
    stops: List<BusStop>,
    buses: List<LiveBus>,
    selectedStop: BusStop?,
    selectedBusId: String? = null,
    layoutMode: RouteRailLayoutMode = RouteRailLayoutMode.Horizontal,
    onSelectStop: (BusStop) -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    if (stops.isEmpty()) return

    when (layoutMode) {
        RouteRailLayoutMode.Horizontal -> {
            HorizontalRouteRail(
                stops = stops,
                buses = buses,
                selectedStop = selectedStop,
                selectedBusId = selectedBusId,
                onSelectStop = onSelectStop,
                onSelectBus = onSelectBus,
                modifier = modifier
            )
        }
        RouteRailLayoutMode.Vertical -> {
            VerticalRouteRail(
                stops = stops,
                buses = buses,
                selectedStop = selectedStop,
                selectedBusId = selectedBusId,
                onSelectStop = onSelectStop,
                onSelectBus = onSelectBus,
                modifier = modifier
            )
        }
    }
}

/**
 * 横向滚动走向条：一站一列（宽 56.dp），上方为平滑插值车辆层，中为轨道节点层，下为竖排站名
 */
@Composable
private fun HorizontalRouteRail(
    stops: List<BusStop>,
    buses: List<LiveBus>,
    selectedStop: BusStop?,
    selectedBusId: String?,
    onSelectStop: (BusStop) -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val columnWidth = 56.dp
    val columnWidthPx = with(density) { columnWidth.toPx() }
    val busLaneHeight = 44.dp
    val trackNodeHeight = 28.dp

    // 选中站点变化或换线路时，平滑将选中站滚动到视野中央
    LaunchedEffect(selectedStop?.order, stops.size) {
        if (selectedStop != null) {
            val stopIndex = (selectedStop.order - 1).coerceIn(0, stops.size - 1)
            val targetScrollPx = (stopIndex * columnWidthPx - columnWidthPx * 2.2f).coerceAtLeast(0f)
            scrollState.animateScrollTo(targetScrollPx.roundToInt(), expressiveSpringSpec())
        }
    }

    val totalWidth = columnWidth * stops.size + 32.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.width(totalWidth)) {
            // 1. 车辆在途插值动画层
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(busLaneHeight)
            ) {
                buses.forEach { bus ->
                    val isSelectedBus = bus.busId == selectedBusId

                    // 计算车辆在走向条上的 0-based 浮点索引位置
                    // bus.order 为正在接近（或已停靠）的 1-based 目标站序
                    // 当 bus.order == 1 时，处于第 1 站（索引 0）
                    // 当 bus.order >= 2 时，车辆处于 (order-2) 与 (order-1) 站之间，比例为 segmentProgress
                    val rawTargetPos = if (bus.order <= 1) {
                        0f
                    } else {
                        (bus.order - 2).toFloat() + bus.segmentProgress.coerceIn(0f, 1f)
                    }

                    // 弹性物理曲线平滑推进
                    val animatedPos by animateFloatAsState(
                        targetValue = rawTargetPos,
                        animationSpec = busChipSpringSpec(),
                        label = "bus_chip_${bus.busId}"
                    )

                    BusChip(
                        bus = bus,
                        isSelected = isSelectedBus,
                        onClick = { onSelectBus(bus) },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset {
                                // 转换为相对左侧起点的 X 偏移像素（中心对齐每列 56.dp 的中心：28.dp），延迟到 Layout 阶段读取
                                val xOffsetDp = columnWidth * animatedPos + (columnWidth / 2)
                                IntOffset(
                                    x = (xOffsetDp - 26.dp).roundToPx(),
                                    y = 0
                                )
                            }
                    )
                }
            }

            // 2. 连续轨道轴与站点节点
            val currentSelectedOrder = selectedStop?.order ?: 1
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackNodeHeight)
            ) {
                // 绘制连续贯穿轨道线与方向箭头
                val busColors = MaterialTheme.busColors

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackNodeHeight)
                ) {
                    val halfHeight = size.height / 2f
                    val arrowGap = 5.dp.toPx()
                    val arrowArm = 3.dp.toPx()
                    val arrowSpread = 3.5.dp.toPx()
                    val strokeWidthPx = 3.dp.toPx()
                    val arrowStrokeWidthPx = 2.dp.toPx()

                    for (i in 0 until stops.size - 1) {
                        val stopFrom = stops[i]
                        val stopTo = stops[i + 1]
                        val segStartX = (columnWidthPx / 2f) + i * columnWidthPx
                        val segEndX = (columnWidthPx / 2f) + (i + 1) * columnWidthPx
                        val midX = (segStartX + segEndX) / 2f
                        val segmentColor = getRouteSegmentColor(
                            fromOrder = stopFrom.order,
                            toOrder = stopTo.order,
                            currentSelectedOrder = currentSelectedOrder,
                            busColors = busColors
                        )

                        val hasArrow = (i % 3 == 0)

                        if (hasArrow) {
                            // 轨道前半段（站点到箭头间隙）
                            drawLine(
                                color = segmentColor,
                                start = Offset(segStartX, halfHeight),
                                end = Offset(midX - arrowGap, halfHeight),
                                strokeWidth = strokeWidthPx,
                                cap = StrokeCap.Round
                            )

                            // 绘制向右行车折线箭头 '>'
                            val arrowPath = Path().apply {
                                moveTo(midX - arrowArm, halfHeight - arrowSpread)
                                lineTo(midX + arrowArm, halfHeight)
                                lineTo(midX - arrowArm, halfHeight + arrowSpread)
                            }
                            drawPath(
                                path = arrowPath,
                                color = segmentColor,
                                style = Stroke(
                                    width = arrowStrokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )

                            // 轨道后半段（箭头间隙到下一站）
                            drawLine(
                                color = segmentColor,
                                start = Offset(midX + arrowGap, halfHeight),
                                end = Offset(segEndX, halfHeight),
                                strokeWidth = strokeWidthPx,
                                cap = StrokeCap.Round
                            )
                        } else {
                            // 无箭头时，平整绘制贯穿站间的轨道线
                            drawLine(
                                color = segmentColor,
                                start = Offset(segStartX, halfHeight),
                                end = Offset(segEndX, halfHeight),
                                strokeWidth = strokeWidthPx,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }

                // 节点行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    stops.forEach { stop ->
                        val isSelected = stop.order == selectedStop?.order
                        val isPassed = stop.order < currentSelectedOrder
                        val isConditionStop = !stop.statusNote.isNullOrEmpty()

                        Box(
                            modifier = Modifier
                                .width(columnWidth)
                                .height(trackNodeHeight),
                            contentAlignment = Alignment.Center
                        ) {
                            StationNode(
                                isSelected = isSelected,
                                isPassed = isPassed,
                                isConditionStop = isConditionStop,
                                onClick = { onSelectStop(stop) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3. 竖排站名（每字一行，经典公交走向条排版）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                stops.forEach { stop ->
                    val isSelected = stop.order == selectedStop?.order
                    val isPassed = stop.order < currentSelectedOrder

                    StationNameColumn(
                        stop = stop,
                        isSelected = isSelected,
                        isPassed = isPassed,
                        modifier = Modifier
                            .width(columnWidth)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = false, radius = 24.dp)
                            ) { onSelectStop(stop) }
                    )
                }
            }
        }
    }
}

/**
 * 走向条上的车辆徽标小芯片（Bus Chip）
 */
@Composable
private fun BusChip(
    bus: LiveBus,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            bus.isArrived -> busColors.busArrivingAmber
            else -> busColors.busChipContainer
        },
        label = "chip_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
            bus.isArrived -> Color.White
            else -> busColors.busChipContent
        },
        label = "chip_fg"
    )

    val shortPlate = remember(bus.plateOrFleetNo) {
        formatBusChipShortText(bus.plateOrFleetNo)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = if (isSelected) 4.dp else 1.5.dp,
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .wrapContentHeight()
            .width(52.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DirectionsBus,
                contentDescription = "在途车辆",
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = shortPlate,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 走向条站点节点（支持呼吸光晕高亮胶囊与条件停靠站空心描边）
 */
@Composable
private fun StationNode(
    isSelected: Boolean,
    isPassed: Boolean,
    isConditionStop: Boolean,
    onClick: () -> Unit
) {
    val busColors = MaterialTheme.busColors

    // 选中状态呼吸脉冲动画
    val pulseTransition = rememberInfiniteTransition(label = "node_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            // 外层呼吸光晕（通过 graphicsLayer 延迟到 Draw 阶段）
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        shape = CircleShape
                    )
            )
            // 内层高亮核心胶囊
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                    .border(2.dp, Color.White, CircleShape)
            )
        } else if (isConditionStop) {
            // 条件启用站：醒目描边空心节点
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(2.dp, busColors.conditionStopBorder, CircleShape)
            )
        } else {
            // 普通站：实心节点
            val dotColor = if (isPassed) busColors.busPassedGray else busColors.routeTrackActive
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

/**
 * 竖排站名列（每字一行）
 */
@Composable
private fun StationNameColumn(
    stop: BusStop,
    isSelected: Boolean,
    isPassed: Boolean,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isPassed -> busColors.busPassedGray
        else -> MaterialTheme.colorScheme.onSurface
    }
    val fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal

    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 站名字符竖排展示
        stop.name.forEach { char ->
            Text(
                text = char.toString(),
                fontSize = if (isSelected) 13.sp else 12.sp,
                fontWeight = fontWeight,
                color = textColor,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center
            )
        }

        // 若有地铁换乘标，底部展示地铁图标与线路
        if (stop.metroTransfers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Subway,
                    contentDescription = "地铁换乘",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .size(14.dp)
                        .padding(1.dp)
                )
            }
        }

        // 条件停靠站小标签
        if (!stop.statusNote.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "响应",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = busColors.conditionStopBorder,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 纵向时间轴走向条（适合大屏或喜欢列表化查阅全线站点的用户）
 */
@Composable
private fun VerticalRouteRail(
    stops: List<BusStop>,
    buses: List<LiveBus>,
    selectedStop: BusStop?,
    selectedBusId: String?,
    onSelectStop: (BusStop) -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSelectedOrder = selectedStop?.order ?: 1
    val busColors = MaterialTheme.busColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        stops.forEachIndexed { index, stop ->
            val isSelected = stop.order == selectedStop?.order
            val isPassed = stop.order < currentSelectedOrder
            val isFirst = index == 0
            val isLast = index == stops.size - 1

            // 查找停靠或正在接近本站的在途车辆
            val stationBuses = remember(buses, stop.order) {
                buses.filter { it.order == stop.order }
            }

            // 计算该站点上方和下方轨道的连贯颜色，保证相邻站点接缝处颜色严格一致
            val topTrackColor = if (index > 0) {
                val prevStop = stops[index - 1]
                getRouteSegmentColor(prevStop.order, stop.order, currentSelectedOrder, busColors)
            } else {
                busColors.routeTrackActive
            }

            val bottomTrackColor = if (index < stops.size - 1) {
                val nextStop = stops[index + 1]
                getRouteSegmentColor(stop.order, nextStop.order, currentSelectedOrder, busColors)
            } else {
                busColors.routeTrackActive
            }

            VerticalTimelineRow(
                stop = stop,
                isSelected = isSelected,
                isPassed = isPassed,
                isFirst = isFirst,
                isLast = isLast,
                topTrackColor = topTrackColor,
                bottomTrackColor = bottomTrackColor,
                stationBuses = stationBuses,
                selectedBusId = selectedBusId,
                onSelectStop = { onSelectStop(stop) },
                onSelectBus = onSelectBus
            )

            // 站点之间的轨道连接段（含精准居中的方向指示箭头）
            if (index < stops.size - 1) {
                val nextStop = stops[index + 1]
                val segmentColor = getRouteSegmentColor(stop.order, nextStop.order, currentSelectedOrder, busColors)
                VerticalTrackSegment(
                    segmentColor = segmentColor,
                    hasArrow = (index % 3 == 0)
                )
            }
        }
    }
}

/**
 * 计算两个连续站点之间的轨道颜色：
 * 保证整段轨道在相邻 Station 之间颜色统一，不会在中间出现截断或突变
 */
internal fun getRouteSegmentColor(
    fromOrder: Int,
    toOrder: Int,
    currentSelectedOrder: Int,
    busColors: BusSemanticColors
): Color {
    // 初始状态（选中第一站或默认全览），整条轨道均为连续的活跃翡翠绿
    if (currentSelectedOrder <= 1) {
        return busColors.routeTrackActive
    }
    // 候车站之前的区间（发车站到候车站之间的路段）渲染为已过站灰色，候车站出发及后续乘车区间为活跃绿
    return if (fromOrder < currentSelectedOrder) {
        busColors.busPassedGray
    } else {
        busColors.routeTrackActive
    }
}

/**
 * 纵向时间轴单站行
 */
@Composable
private fun VerticalTimelineRow(
    stop: BusStop,
    isSelected: Boolean,
    isPassed: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    topTrackColor: Color,
    bottomTrackColor: Color,
    stationBuses: List<LiveBus>,
    selectedBusId: String?,
    onSelectStop: () -> Unit,
    onSelectBus: (LiveBus) -> Unit
) {
    val busColors = MaterialTheme.busColors
    val rowBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(rowBg, shape = RoundedCornerShape(8.dp))
            .clickable { onSelectStop() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 站序号
        Text(
            text = String.format("%02d", stop.order),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else busColors.busPassedGray,
            modifier = Modifier.width(26.dp)
        )

        // 2. 纵向轴线与节点 (Canvas 占满整行高度，上下出界 1.5px 消除浮点裁切误差，实现 100% 连续无断点)
        Box(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val overlap = 1.5f // 防微细浮点裂隙
                val strokeWidthPx = 3.dp.toPx()

                if (!isFirst) {
                    drawLine(
                        color = topTrackColor,
                        start = Offset(centerX, -overlap),
                        end = Offset(centerX, centerY),
                        strokeWidth = strokeWidthPx
                    )
                }
                if (!isLast) {
                    drawLine(
                        color = bottomTrackColor,
                        start = Offset(centerX, centerY),
                        end = Offset(centerX, size.height + overlap),
                        strokeWidth = strokeWidthPx
                    )
                }
            }

            StationNode(
                isSelected = isSelected,
                isPassed = isPassed,
                isConditionStop = !stop.statusNote.isNullOrEmpty(),
                onClick = onSelectStop
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 3. 站名与备注
        Column(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 44.dp)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stop.name,
                    fontSize = if (isSelected) 15.sp else 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isPassed -> busColors.busPassedGray
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "候车中",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // 状态附注（如“响应式停靠”）或地铁换乘
            if (!stop.statusNote.isNullOrEmpty() || stop.metroTransfers.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!stop.statusNote.isNullOrEmpty()) {
                        Text(
                            text = stop.statusNote,
                            fontSize = 11.sp,
                            color = busColors.conditionStopBorder
                        )
                    }
                    stop.metroTransfers.forEach { transfer ->
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = transfer,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // 4. 到站车辆小标签
        stationBuses.forEach { bus ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (bus.isArrived) busColors.busArrivingAmber else busColors.busChipContainer,
                onClick = { onSelectBus(bus) },
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsBus,
                        contentDescription = "车辆",
                        tint = if (bus.isArrived) Color.White else busColors.busChipContent,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (bus.isArrived) "已到站" else formatBusChipShortText(bus.plateOrFleetNo),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (bus.isArrived) Color.White else busColors.busChipContent
                    )
                }
            }
        }
    }
}

/**
 * 纵向时间轴站点之间的轨道连接段（含精准居中于两站正中间的行车方向箭头）
 */
@Composable
private fun VerticalTrackSegment(
    segmentColor: Color,
    hasArrow: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 与站序号 26.dp 严格对齐
        Spacer(modifier = Modifier.width(26.dp))

        // 纵向轨道段 (宽度 28.dp，严格对齐上下站点的轨道中心)
        Box(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val midY = size.height / 2f
                val overlap = 1.5f // 消除浮点接缝
                val strokeWidthPx = 3.dp.toPx()
                val arrowStrokeWidthPx = 2.dp.toPx()
                val arrowGap = 4.dp.toPx()
                val arrowArm = 3.dp.toPx()
                val arrowSpread = 3.5.dp.toPx()

                if (hasArrow) {
                    // 上半段轨道（顶端到箭头间隙）
                    drawLine(
                        color = segmentColor,
                        start = Offset(centerX, -overlap),
                        end = Offset(centerX, midY - arrowGap),
                        strokeWidth = strokeWidthPx
                    )

                    // 绘制精准居中的向下行车折线箭头 'v'
                    val arrowPath = Path().apply {
                        moveTo(centerX - arrowSpread, midY - arrowArm)
                        lineTo(centerX, midY + arrowArm)
                        lineTo(centerX + arrowSpread, midY - arrowArm)
                    }
                    drawPath(
                        path = arrowPath,
                        color = segmentColor,
                        style = Stroke(
                            width = arrowStrokeWidthPx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    // 下半段轨道（箭头间隙到底部边界）
                    drawLine(
                        color = segmentColor,
                        start = Offset(centerX, midY + arrowGap),
                        end = Offset(centerX, size.height + overlap),
                        strokeWidth = strokeWidthPx
                    )
                } else {
                    // 无箭头时，平整绘制贯穿整段的连接轴线
                    drawLine(
                        color = segmentColor,
                        start = Offset(centerX, -overlap),
                        end = Offset(centerX, size.height + overlap),
                        strokeWidth = strokeWidthPx
                    )
                }
            }
        }
    }
}
