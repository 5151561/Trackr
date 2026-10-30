package com.trackr.core.designsystem.rail

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 实时公交走向条 (RouteRail) 轨道与行车方向箭头 Canvas 绘制尺寸与画笔规范常量
 */
val RouteRailArrowGap = 4.dp
val RouteRailArrowArm = 3.dp
val RouteRailArrowSpread = 3.5.dp
val RouteRailStrokeWidth = 3.dp
val RouteRailArrowStrokeWidth = 2.dp

/**
 * 在水平轨道区间绘制线段与行车方向折线箭头（>）。
 *
 * @param startX 轨道起始 X 坐标
 * @param endX 轨道终止 X 坐标
 * @param y 轨道中心 Y 坐标
 * @param color 轨道与箭头绘制颜色
 * @param strokeWidth 轨道线条宽度（px）
 * @param hasArrow 是否在区间中心绘制行车箭头
 * @param arrowPath 可复用的 Path 实例（避免每帧重复创建对象）
 * @param arrowStroke 箭头描边样式
 * @param arrowGap 箭头与两侧轨道线的空隙（px）
 * @param arrowArm 箭头纵深臂长（px）
 * @param arrowSpread 箭头两翼展开半宽（px）
 * @param cap 线段端点样式，默认为 [StrokeCap.Round]
 */
fun DrawScope.drawHorizontalTrackSegment(
    startX: Float,
    endX: Float,
    y: Float,
    color: Color,
    strokeWidth: Float,
    hasArrow: Boolean,
    arrowPath: Path,
    arrowStroke: Stroke,
    arrowGap: Float,
    arrowArm: Float,
    arrowSpread: Float,
    cap: StrokeCap = StrokeCap.Round
) {
    if (hasArrow) {
        val midX = (startX + endX) / 2f

        // 轨道前半段（站点到箭头间隙）
        drawLine(
            color = color,
            start = Offset(startX, y),
            end = Offset(midX - arrowGap, y),
            strokeWidth = strokeWidth,
            cap = cap
        )

        // 绘制向右行车折线箭头 '>'（复用外部传入的底层 Path 缓冲区）
        arrowPath.rewind()
        arrowPath.moveTo(midX - arrowArm, y - arrowSpread)
        arrowPath.lineTo(midX + arrowArm, y)
        arrowPath.lineTo(midX - arrowArm, y + arrowSpread)
        drawPath(
            path = arrowPath,
            color = color,
            style = arrowStroke
        )

        // 轨道后半段（箭头间隙到下一站）
        drawLine(
            color = color,
            start = Offset(midX + arrowGap, y),
            end = Offset(endX, y),
            strokeWidth = strokeWidth,
            cap = cap
        )
    } else {
        // 无箭头时，平整绘制贯穿整段的轨道线
        drawLine(
            color = color,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = strokeWidth,
            cap = cap
        )
    }
}

/**
 * 在纵向时间轴轨道区间绘制线段与行车方向折线箭头（v）。
 *
 * @param x 轨道中心 X 坐标
 * @param startY 轨道起始 Y 坐标（可包含 overlap 以消除接缝）
 * @param endY 轨道终止 Y 坐标（可包含 overlap 以消除接缝）
 * @param color 轨道与箭头绘制颜色
 * @param strokeWidth 轨道线条宽度（px）
 * @param hasArrow 是否在区间中心绘制行车箭头
 * @param arrowPath 可复用的 Path 实例（避免每帧重复创建对象）
 * @param arrowStroke 箭头描边样式
 * @param arrowGap 箭头与两侧轨道线的空隙（px）
 * @param arrowArm 箭头纵深臂长（px）
 * @param arrowSpread 箭头两翼展开半宽（px）
 * @param cap 线段端点样式，默认为 [StrokeCap.Round]
 */
fun DrawScope.drawVerticalTrackSegment(
    x: Float,
    startY: Float,
    endY: Float,
    color: Color,
    strokeWidth: Float,
    hasArrow: Boolean,
    arrowPath: Path,
    arrowStroke: Stroke,
    arrowGap: Float,
    arrowArm: Float,
    arrowSpread: Float,
    cap: StrokeCap = StrokeCap.Round
) {
    if (hasArrow) {
        val midY = (startY + endY) / 2f

        // 上半段轨道（顶端到箭头上方空隙）
        drawLine(
            color = color,
            start = Offset(x, startY),
            end = Offset(x, midY - arrowGap),
            strokeWidth = strokeWidth,
            cap = cap
        )

        // 绘制精准居中的向下行车折线箭头 'v'（复用外部传入的底层 Path 缓冲区）
        arrowPath.rewind()
        arrowPath.moveTo(x - arrowSpread, midY - arrowArm)
        arrowPath.lineTo(x, midY + arrowArm)
        arrowPath.lineTo(x + arrowSpread, midY - arrowArm)
        drawPath(
            path = arrowPath,
            color = color,
            style = arrowStroke
        )

        // 下半段轨道（箭头下方空隙到底部边界）
        drawLine(
            color = color,
            start = Offset(x, midY + arrowGap),
            end = Offset(x, endY),
            strokeWidth = strokeWidth,
            cap = cap
        )
    } else {
        // 无箭头时，平整绘制贯穿整段的连接轴线
        drawLine(
            color = color,
            start = Offset(x, startY),
            end = Offset(x, endY),
            strokeWidth = strokeWidth,
            cap = cap
        )
    }
}
