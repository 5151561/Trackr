package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.TransitLeg
import com.trackr.core.designsystem.theme.extendedColors

/** 时间轴上一程的状态 */
enum class LegTimelineState {
    /** 还没到的程 */
    UPCOMING,

    /** 当前程，等车中 */
    WAITING,

    /** 当前程，车上 */
    ON_BOARD,

    /** 已经走过的程 */
    DONE
}

/**
 * 换乘卡片时间轴中的一程：左侧圆点 + 乘车实线，右侧上车站名与乘车信息。
 * 竖线高度跟随右侧内容（Row + IntrinsicSize.Min），不写死。
 *
 * @param rideNote 乘车信息小字，如"坐 4 站 · 在 深大北门 下车"、"坐 5 站 · 预计 07:58 上车"
 * @param walkMetersBefore 上车前的步行距离；第 2 程起为 0 时标"同站换乘"
 */
@Composable
fun LegTimelineItem(
    legIndex: Int,
    leg: TransitLeg,
    state: LegTimelineState,
    rideNote: String,
    selectedLineId: String?,
    realtimeInfoOf: (CandidateLine) -> CandidateLineRealtimeInfo?,
    onSelectCandidateLine: (lineId: String) -> Unit,
    onAddCandidateLine: (lineName: String) -> Unit,
    onLineClick: (CandidateLine) -> Unit,
    modifier: Modifier = Modifier,
    walkMetersBefore: Int = 0
) {
    val legColor = legLineColor(legIndex)
    val isDone = state == LegTimelineState.DONE
    // 乘车中：上车站已经走过，变灰
    val isStopPassed = isDone || state == LegTimelineState.ON_BOARD
    val isActive = state == LegTimelineState.WAITING || state == LegTimelineState.ON_BOARD
    val activeLineId = selectedLineId ?: leg.primaryLine?.lineId
    val selectedCandidate = leg.candidateLines.find { it.lineId == activeLineId } ?: leg.primaryLine

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("leg_timeline_item_$legIndex")
    ) {
        TimelineRail(
            dotStyle = if (isStopPassed) TimelineDotStyle.DONE else TimelineDotStyle.RING,
            dotColor = legColor,
            lineColor = if (isDone) MaterialTheme.colorScheme.outlineVariant else legColor
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 12.dp)
        ) {
            // 上车站
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = leg.fromStopName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isStopPassed) FontWeight.SemiBold else FontWeight.Bold
                    ),
                    color = if (isStopPassed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("timeline_stop_$legIndex")
                )
                if (legIndex > 0) {
                    Text(
                        text = if (walkMetersBefore > 0) "换乘" else "同站换乘",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state == LegTimelineState.WAITING) {
                    HereBadge()
                }
            }

            // 乘车信息块
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            ) {
                Column(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    CandidateLineChips(
                        candidates = leg.candidateLines,
                        selectedLineId = activeLineId,
                        selectedColor = legColor,
                        realtimeInfoOf = realtimeInfoOf,
                        chipTagPrefix = "leg_chip_",
                        addButtonTag = "btn_add_line_leg_$legIndex",
                        addDialogTitle = "添加换乘线路",
                        addPlaceholder = "例如：M375、204",
                        onSelect = onSelectCandidateLine,
                        onAdd = onAddCandidateLine
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = rideNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.testTag("leg_ride_note_$legIndex")
                            )
                            if (state == LegTimelineState.ON_BOARD) {
                                HereBadge()
                            }
                        }
                        TextButton(
                            onClick = { selectedCandidate?.let(onLineClick) },
                            enabled = selectedCandidate != null,
                            modifier = Modifier.testTag("btn_track_trajectory_$legIndex")
                        ) {
                            Text("实时轨迹", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/** 两程之间的步行段：虚线竖线 + "步行 X 米" */
@Composable
fun TimelineWalkSegment(
    walkMeters: Int,
    modifier: Modifier = Modifier
) {
    val walkColor = MaterialTheme.extendedColors.walk
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(22.dp)
                .fillMaxHeight()
                .heightIn(min = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
            ) {
                drawLine(
                    color = walkColor,
                    start = Offset(size.width / 2, 0f),
                    end = Offset(size.width / 2, size.height),
                    strokeWidth = size.width,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "步行 $walkMeters 米",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(vertical = 4.dp)
        )
    }
}

/** 时间轴终点 */
@Composable
fun TimelineEndStop(
    stopName: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.extendedColors.success)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stopName,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("timeline_end_stop")
        )
    }
}

/** 第 1 程用 primary，第 2 程及以后用 legSecondary */
@Composable
internal fun legLineColor(legIndex: Int): Color =
    if (legIndex == 0) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.legSecondary

internal enum class TimelineDotStyle { RING, DONE }

@Composable
private fun TimelineRail(
    dotStyle: TimelineDotStyle,
    dotColor: Color,
    lineColor: Color
) {
    Column(
        modifier = Modifier
            .width(22.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        val dotModifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
        if (dotStyle == TimelineDotStyle.DONE) {
            Box(modifier = dotModifier.background(MaterialTheme.colorScheme.outline))
        } else {
            Box(
                modifier = dotModifier
                    .background(MaterialTheme.colorScheme.surface)
                    .border(3.dp, dotColor, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(4.dp)
                .weight(1f)
                .heightIn(min = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(lineColor)
        )
    }
}

@Composable
private fun HereBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = "你在这里",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .testTag("badge_you_are_here")
        )
    }
}

/**
 * 候选线路胶囊：放不下自动换行，选中实心，最快加"最快"小标，末尾"+ 添加"。
 * 每个胶囊使用 Surface(onClick)，自带 48dp 最小点击区域。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CandidateLineChips(
    candidates: List<CandidateLine>,
    selectedLineId: String?,
    selectedColor: Color,
    realtimeInfoOf: (CandidateLine) -> CandidateLineRealtimeInfo?,
    chipTagPrefix: String,
    addButtonTag: String,
    addDialogTitle: String,
    addPlaceholder: String,
    onSelect: (lineId: String) -> Unit,
    onAdd: (lineName: String) -> Unit,
    modifier: Modifier = Modifier,
    selectedContentColor: Color = if (selectedColor == MaterialTheme.colorScheme.primary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.surface
    }
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newLineName by remember { mutableStateOf("") }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        candidates.forEach { candidate ->
            val isSelected = candidate.lineId == selectedLineId
            val info = realtimeInfoOf(candidate)
            val shortEta = info?.shortChipEta?.takeIf { it.isNotBlank() }
            Surface(
                onClick = { onSelect(candidate.lineId) },
                modifier = Modifier.testTag("$chipTagPrefix${candidate.lineName}"),
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) selectedColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (shortEta != null) "${candidate.lineName} · $shortEta" else candidate.lineName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isSelected) selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (info?.isFastest == true) {
                        FastestBadge()
                    }
                }
            }
        }

        Surface(
            onClick = { showAddDialog = true },
            modifier = Modifier.testTag(addButtonTag),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "添加",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                newLineName = ""
            },
            title = { Text(addDialogTitle) },
            text = {
                OutlinedTextField(
                    value = newLineName,
                    onValueChange = { newLineName = it },
                    label = { Text("线路名称") },
                    placeholder = { Text(addPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newLineName.isNotBlank()) {
                            onAdd(newLineName.trim())
                            showAddDialog = false
                            newLineName = ""
                        }
                    },
                    enabled = newLineName.isNotBlank()
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    newLineName = ""
                }) {
                    Text("取消")
                }
            }
        )
    }
}

/** success 色"最快"小标 */
@Composable
internal fun FastestBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.extendedColors.successContainer
    ) {
        Text(
            text = "最快",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.extendedColors.success,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}
