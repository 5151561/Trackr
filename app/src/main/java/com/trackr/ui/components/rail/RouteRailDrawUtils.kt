package com.trackr.ui.components.rail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.trackr.core.designsystem.rail.RouteRailArrowArm as CoreRouteRailArrowArm
import com.trackr.core.designsystem.rail.RouteRailArrowGap as CoreRouteRailArrowGap
import com.trackr.core.designsystem.rail.RouteRailArrowSpread as CoreRouteRailArrowSpread
import com.trackr.core.designsystem.rail.RouteRailArrowStrokeWidth as CoreRouteRailArrowStrokeWidth
import com.trackr.core.designsystem.rail.RouteRailStrokeWidth as CoreRouteRailStrokeWidth
import com.trackr.core.designsystem.rail.drawHorizontalTrackSegment as coreDrawHorizontalTrackSegment
import com.trackr.core.designsystem.rail.drawVerticalTrackSegment as coreDrawVerticalTrackSegment

/**
 * 实时公交走向条 (RouteRail) 轨道与行车方向箭头 Canvas 绘制尺寸与画笔规范常量
 */
val RouteRailArrowGap = CoreRouteRailArrowGap
val RouteRailArrowArm = CoreRouteRailArrowArm
val RouteRailArrowSpread = CoreRouteRailArrowSpread
val RouteRailStrokeWidth = CoreRouteRailStrokeWidth
val RouteRailArrowStrokeWidth = CoreRouteRailArrowStrokeWidth

/**
 * 在水平轨道区间绘制线段与行车方向折线箭头（>）。
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
) = coreDrawHorizontalTrackSegment(
    startX = startX,
    endX = endX,
    y = y,
    color = color,
    strokeWidth = strokeWidth,
    hasArrow = hasArrow,
    arrowPath = arrowPath,
    arrowStroke = arrowStroke,
    arrowGap = arrowGap,
    arrowArm = arrowArm,
    arrowSpread = arrowSpread,
    cap = cap
)

/**
 * 在纵向时间轴轨道区间绘制线段与行车方向折线箭头（v）。
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
) = coreDrawVerticalTrackSegment(
    x = x,
    startY = startY,
    endY = endY,
    color = color,
    strokeWidth = strokeWidth,
    hasArrow = hasArrow,
    arrowPath = arrowPath,
    arrowStroke = arrowStroke,
    arrowGap = arrowGap,
    arrowArm = arrowArm,
    arrowSpread = arrowSpread,
    cap = cap
)
