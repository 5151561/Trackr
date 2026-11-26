package com.trackr.ui.screens.routeplan.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_walk
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.ui.screens.favoritesgroup.components.legLineColor
import com.trackr.ui.screens.routeplan.PlanSegment
import org.jetbrains.compose.resources.vectorResource

/**
 * 分段条：步行段用 v3 的灰（`TrackrTheme.colors.walk`）+ 步行小图标，乘车段第 1 程 primary、第 2 程起 legSecondary，
 * 宽度按时长比例。
 *
 * 步行段以前用 surfaceContainerHighest，深色主题下和面板底（surface）只差一点亮度，看着像空位；
 * 换成中间调的灰之后，和浅蓝的乘车段、和深色面板底三边都拉得开。
 */
@Composable
internal fun SegmentBar(segments: List<PlanSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(MaterialTheme.shapes.small)
            .testTag("scheme_segment_bar"),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        segments.forEach { segment ->
            val background = if (segment.isWalk) {
                TrackrTheme.colors.walk
            } else {
                legLineColor(segment.legIndex)
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(segment.weight)
                    .fillMaxHeight()
                    .background(background),
                contentAlignment = Alignment.Center
            ) {
                when {
                    // 步行段没有文字，画个步行小图标说明这是走路；太窄放不下就只留底色
                    segment.isWalk -> if (maxWidth >= 14.dp) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_walk),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(11.dp)
                        )
                    }

                    segment.label.isNotEmpty() -> Text(
                        text = segment.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (segment.legIndex == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}
