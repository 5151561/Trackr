package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.domain.model.BusLineDetail
import com.trackr.ui.components.common.DataSourceBadge
import com.trackr.ui.theme.busColors

/**
 * 顶部线路元信息头
 */
@Composable
fun TopLineHeader(
    lineDetail: BusLineDetail,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val line = lineDetail.line
    val busColors = MaterialTheme.busColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 线路名大标题
            Text(
                text = line.name,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 数据源标签（车来了 / 掌上公交）
            DataSourceBadge(source = line.source)
        }

        // 开往方向副标题
        Text(
            text = "开往  ${line.endStop} （始发：${line.startStop}）",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 标签胶囊行：首末班、票价、总站数、当前在途车数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapsuleBadge("首 ${line.firstTime.ifEmpty { "—" }}")
            CapsuleBadge("末 ${line.lastTime.ifEmpty { "—" }}")
            if (line.price.isNotEmpty()) CapsuleBadge("票价 ${line.price}")
            CapsuleBadge("共 ${lineDetail.stops.size} 站")

            // 实时在途车辆数胶囊
            val busCount = lineDetail.buses.size
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (busCount > 0) busColors.busChipContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsBus,
                        contentDescription = null,
                        tint = if (busCount > 0) busColors.busChipContent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (busCount > 0) "当前 $busCount 辆车在途" else "暂无在途车辆",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (busCount > 0) busColors.busChipContent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun CapsuleBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
