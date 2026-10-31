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
import com.trackr.domain.arrival.ArrivalText
import com.trackr.domain.arrival.ArrivalTone
import com.trackr.domain.model.LiveBus
import com.trackr.core.designsystem.theme.extendedColors

/**
 * 临近车辆类型别名（兼容性定义）
 */
typealias ApproachingBus = LiveBus

/**
 * 实时到站状态信息包装
 *
 * @param text 展示文案（由 [ArrivalText] 生成，如「到站了」「8 分钟 · 还有 4 站」「还有 2 站 · 暂无到站时间」）
 * @param isHighlighted 是否高亮显示（[ArrivalTone.Go]：到站了 / ≤ 1 站 / ≤ 3 分钟）
 * @param tone 到站文字色调；数据源直接给的状态文字为 null
 */
data class BusArrivalStatusInfo(
    val text: String,
    val isHighlighted: Boolean,
    val tone: ArrivalTone? = null
)

/**
 * 计算实时公交到站状态文案与高亮状态。
 * 文字和「快到 / 已到」的判断都走 [ArrivalText]（内部用 ArrivalRules），不再单独写区间。
 */
fun computeArrivalStatus(
    targetOrder: Int,
    approachingBus: ApproachingBus?,
    fallbackStatusText: String = "暂无在途车辆",
    statusText: String = ""
): BusArrivalStatusInfo = when {
    statusText.isNotBlank() -> BusArrivalStatusInfo(text = statusText, isHighlighted = false)
    approachingBus != null -> {
        val display = ArrivalText.format(ArrivalText.stateForBus(targetOrder, approachingBus))
        BusArrivalStatusInfo(
            text = display.short,
            isHighlighted = display.tone == ArrivalTone.Go,
            tone = display.tone
        )
    }
    else -> BusArrivalStatusInfo(text = fallbackStatusText, isHighlighted = false)
}

/**
 * 到站文字色调对应的颜色：绿 = 快到 / 已到，正文色 = 在路上，灰 = 车都过了 / 没发车 / 加载 / 没网，橙 = 末班已过 / 停运
 */
@Composable
fun arrivalToneColor(tone: ArrivalTone): Color = when (tone) {
    ArrivalTone.Go -> MaterialTheme.extendedColors.success
    ArrivalTone.Normal -> MaterialTheme.colorScheme.onSurface
    ArrivalTone.Mute -> MaterialTheme.colorScheme.onSurfaceVariant
    ArrivalTone.Warn -> MaterialTheme.extendedColors.warning
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
