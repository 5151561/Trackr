package com.trackr.ui.screens.routeplan.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_favorite
import com.trackr.core.designsystem.resources.action_start_tracking
import com.trackr.core.designsystem.resources.ic_play
import com.trackr.core.designsystem.resources.ic_star
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_leg_n
import com.trackr.feature.route.resources.route_minutes
import com.trackr.feature.route.resources.route_scheme_or
import com.trackr.ui.screens.favoritesgroup.components.legLineColor
import com.trackr.ui.screens.routeplan.SchemeTabUi
import com.trackr.ui.screens.routeplan.departArriveText
import com.trackr.ui.screens.routeplan.planSegments
import com.trackr.ui.screens.routeplan.schemeSummaryText
import com.trackr.ui.screens.routeplan.schemeTransferText
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 底部方案面板：最多 3 个方案标签（一直可见，点一下切换）+ 可左右滑动的详情 + 收藏 / 开始追踪。
 * 正在追踪当前方案时，按钮行换成追踪状态条。
 */
@Composable
internal fun SchemePanel(
    tabs: List<SchemeTabUi>,
    pagerState: PagerState,
    trackingSession: GroupTrackingSessionState?,
    onSelectScheme: (Int) -> Unit,
    onLineClick: (CandidateLine) -> Unit,
    onSaveFavorite: () -> Unit,
    onStartTracking: (AmapTransitSchemeResult) -> Unit,
    onConfirmBoarded: () -> Unit,
    onStopTracking: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    // 出发时间按拿到方案的那一刻算（和原来一样，方案变了才重算）
    val planNowMillis = remember(tabs) { nowMillis() }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEachIndexed { index, tab ->
                SchemeTab(
                    tab = tab,
                    selected = pagerState.currentPage == index,
                    onClick = { onSelectScheme(index) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scheme_tab_$index")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scheme_pager"),
            verticalAlignment = Alignment.Top,
            pageSpacing = 16.dp
        ) { page ->
            tabs.getOrNull(page)?.let { tab ->
                SchemeDetail(
                    page = page,
                    scheme = tab.scheme,
                    nowMillis = planNowMillis,
                    onLineClick = onLineClick
                )
            }
        }

        // 分页点已删：上面那排页签本身就是选中指示器，同一件事不再放两个入口
        Spacer(modifier = Modifier.height(14.dp))

        val current = tabs.getOrNull(pagerState.currentPage)?.scheme
        val isTrackingCurrent = trackingSession != null && !trackingSession.isCompleted &&
            current != null && trackingSession.groupId == current.group.id
        if (isTrackingCurrent) {
            TrackingStatusBar(
                session = trackingSession,
                onConfirmBoarded = onConfirmBoarded,
                onStopTracking = onStopTracking
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveFavorite,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_save_favorite")
                ) {
                    Icon(vectorResource(Res.drawable.ic_star), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.action_favorite), fontWeight = FontWeight.Bold)
                }
                // 只追踪这一次，不收藏（规划出来的多半是临时走一趟）
                Button(
                    onClick = { current?.let(onStartTracking) },
                    enabled = current != null,
                    modifier = Modifier
                        .weight(2f)
                        .height(48.dp)
                        .testTag("btn_start_monitor")
                ) {
                    Icon(vectorResource(Res.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.action_start_tracking), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 一个方案标签：名字（最快 / 少换乘 / 少走路）+ 分钟 + 换乘 */
@Composable
private fun SchemeTab(
    tab: SchemeTabUi,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = tab.scheme
    val subColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("scheme_tab_label")
            )
            Text(
                text = stringResource(RouteRes.string.route_minutes, scheme.durationMinutes),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = schemeTransferText(scheme),
                style = MaterialTheme.typography.labelSmall,
                color = subColor,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchemeDetail(
    page: Int,
    scheme: AmapTransitSchemeResult,
    nowMillis: Long,
    onLineClick: (CandidateLine) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 分钟只在上面那排页签写一次，这里只写页签里没有的「出发 · 到」
        Text(
            text = departArriveText(nowMillis, scheme.durationMinutes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("scheme_depart_arrive_$page")
        )

        SegmentBar(segments = remember(scheme) { planSegments(scheme) })

        Text(
            text = schemeSummaryText(scheme),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("scheme_summary_$page")
        )

        scheme.group.legs.forEachIndexed { legIndex, leg ->
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(RouteRes.string.route_leg_n, legIndex + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                leg.candidateLines.forEachIndexed { lineIndex, line ->
                    val isMain = lineIndex == 0
                    Surface(
                        onClick = { onLineClick(line) },
                        shape = MaterialTheme.shapes.small,
                        color = if (isMain) legLineColor(legIndex) else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.testTag("scheme_line_chip_${line.lineName}")
                    ) {
                        Text(
                            text = if (isMain) line.lineName else stringResource(RouteRes.string.route_scheme_or, line.lineName),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = when {
                                !isMain -> MaterialTheme.colorScheme.onSurfaceVariant
                                legIndex == 0 -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
