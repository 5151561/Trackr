package com.trackr.ui.components.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.LiveBus

/**
 * 临近车辆类型别名（兼容性定义）
 */
typealias ApproachingBus = LiveBus

/**
 * 实时到站状态信息包装
 *
 * @param text 展示文案（如 "已到站"、"即将进站"、"还有 2 站 · 3 分钟"、"在途行驶中"、"暂无在途车辆" 等）
 * @param isHighlighted 是否高亮显示（如已到站、即将进站状态）
 */
data class BusArrivalStatusInfo(
    val text: String,
    val isHighlighted: Boolean
)

/**
 * 计算实时公交到站状态文案与高亮状态
 */
fun computeArrivalStatus(
    targetOrder: Int,
    approachingBus: ApproachingBus?,
    fallbackStatusText: String = "暂无在途车辆",
    statusText: String = ""
): BusArrivalStatusInfo {
    val text = when {
        statusText.isNotBlank() -> statusText
        approachingBus != null -> {
            val busOrder = approachingBus.order
            if (targetOrder > 0 && busOrder > 0) {
                val remaining = targetOrder - busOrder
                val etaPart = RouteProjectionMath.formatEtaText(approachingBus.etaSeconds)
                when {
                    remaining == 0 && approachingBus.isArrived -> "已到站"
                    remaining <= 0 -> "即将进站"
                    etaPart.isNotBlank() -> "还有 $remaining 站 · $etaPart"
                    else -> "还有 $remaining 站"
                }
            } else if (approachingBus.isArrived) {
                "已到站"
            } else if (approachingBus.etaSeconds > 0) {
                "即将进站 · ${RouteProjectionMath.formatEtaText(approachingBus.etaSeconds)}"
            } else {
                "在途行驶中"
            }
        }
        else -> fallbackStatusText
    }
    val isHighlighted = text.contains("已到站") || text.contains("即将进站")
    return BusArrivalStatusInfo(
        text = text,
        isHighlighted = isHighlighted
    )
}

/**
 * 统一的公交实时到站状态徽标
 */
@Composable
fun BusArrivalBadge(
    statusInfo: BusArrivalStatusInfo,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    containerColor: Color? = null,
    contentColor: Color? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val defaultContainerColor = if (statusInfo.isHighlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val defaultContentColor = if (statusInfo.isHighlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = shape,
        color = containerColor ?: defaultContainerColor,
        modifier = modifier
    ) {
        Text(
            text = statusInfo.text,
            style = textStyle,
            color = contentColor ?: defaultContentColor,
            modifier = Modifier.padding(contentPadding),
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

/**
 * 便捷重载：直接接收文案渲染到站徽标
 */
@Composable
fun BusArrivalBadge(
    text: String,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = text.contains("已到站") || text.contains("即将进站"),
    shape: Shape = RoundedCornerShape(8.dp),
    containerColor: Color? = null,
    contentColor: Color? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    BusArrivalBadge(
        statusInfo = BusArrivalStatusInfo(text = text, isHighlighted = isHighlighted),
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        textStyle = textStyle,
        contentPadding = contentPadding,
        maxLines = maxLines,
        overflow = overflow
    )
}
