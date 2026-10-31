package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.LegPhase
import com.trackr.domain.model.TrackingSessionState
import com.trackr.domain.model.TransitGroup
import com.trackr.core.designsystem.theme.extendedColors

/**
 * 收藏页路线卡片（单程直达 / 多程换乘）。
 *
 * 一张卡只有一个主数字：未追踪 / 等车时是"N 分钟后到 上车站"，乘车时是"N 站后在 下车站 下车"。
 * 不常用的操作（收藏、返程、编辑、删除）收进右上角 ⋮ 菜单。
 */
@Composable
fun TransitGroupCard(
    group: TransitGroup,
    activeSession: GroupTrackingSessionState?,
    realtimeSummaries: Map<String, String>,
    candidateRealtimeMap: Map<String, CandidateLineRealtimeInfo> = emptyMap(),
    selectedLineKeys: Map<String, String> = emptyMap(),
    onSelectCandidateLine: (legIndex: Int, lineId: String) -> Unit = { _, _ -> },
    onAddCandidateLine: (legIndex: Int, lineName: String) -> Unit = { _, _ -> },
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConfirmBoarded: () -> Unit = {},
    onSwitchToNextLeg: () -> Unit = {},
    onReverseClick: () -> Unit = {},
    onSaveTemporary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLineClick: (CandidateLine) -> Unit,
    modifier: Modifier = Modifier,
    isTemporaryTracking: Boolean = false
) {
    val tracking = activeSession?.takeIf { it.groupId == group.id && !it.isCompleted }
    val isTracking = tracking != null
    val activeLegIndex = tracking?.activeLegIndex?.coerceIn(0, (group.legs.size - 1).coerceAtLeast(0)) ?: 0
    // 只采用属于当前程的两阶段状态；刚切程时旧状态还没刷新，按"等车 · 连接中"显示
    val session = tracking?.trackingSessionState?.takeIf {
        it.legPhase != LegPhase.SINGLE && it.currentLegIndex == tracking.activeLegIndex
    }
    val phase: LegPhase? = if (isTracking) session?.legPhase ?: LegPhase.WAITING_BOARD else null
    val isMultiLeg = group.legs.size > 1

    val selectedLines: List<CandidateLine?> = group.legs.mapIndexed { index, leg ->
        val chosenId = selectedLineKeys["${group.id}_$index"] ?: leg.primaryLine?.lineId
        leg.candidateLines.find { it.lineId == chosenId } ?: leg.primaryLine
    }
    val infoOf: (Int, CandidateLine) -> CandidateLineRealtimeInfo? = { index, line ->
        candidateRealtimeMap["${group.id}_${index}_${line.lineId}"]
            ?: candidateRealtimeMap["${line.lineId}_${line.toStopOrder}"]
    }
    // 实时数据刷新时顺便刷新"现在"，用于估算预计上车时间
    val nowMillis = remember(candidateRealtimeMap, session) { System.currentTimeMillis() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("transit_group_card_${group.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (isTracking) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CardHeader(
                group = group,
                summary = groupSummaryText(group, selectedLines),
                phaseLabel = if (isTracking) trackingPhaseLabel(activeLegIndex, group.legs.size, phase) else null,
                isTemporaryTracking = isTemporaryTracking,
                onSaveTemporary = onSaveTemporary,
                onReverseClick = onReverseClick,
                onEdit = onEdit,
                onDelete = onDelete
            )

            if (isTracking) {
                LegStepper(
                    fractions = legProgressFractions(group.legs.size, activeLegIndex, phase),
                    modifier = Modifier.testTag("leg_stepper_${group.id}")
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            val headlineLeg = group.legs.getOrNull(activeLegIndex)
            val headlineLine = selectedLines.getOrNull(activeLegIndex)
            val headlineInfo = headlineLine?.let { infoOf(activeLegIndex, it) }

            // 单程：候选线路胶囊放在主数字上方
            if (!isMultiLeg && headlineLeg != null) {
                CandidateLineChips(
                    candidates = headlineLeg.candidateLines,
                    selectedLineId = headlineLine?.lineId,
                    selectedColor = MaterialTheme.colorScheme.primary,
                    realtimeInfoOf = { infoOf(0, it) },
                    chipTagPrefix = "candidate_chip_",
                    addButtonTag = "btn_add_candidate_direct",
                    addDialogTitle = "添加同达线路",
                    addPlaceholder = "例如：311、M194",
                    onSelect = { lineId -> onSelectCandidateLine(0, lineId) },
                    onAdd = { lineName -> onAddCandidateLine(0, lineName) }
                )
            }

            if (headlineLeg != null) {
                val nextLine = selectedLines.getOrNull(activeLegIndex + 1)
                val headline = cardHeadline(
                    phase = phase,
                    session = session,
                    boardStopName = headlineLeg.fromStopName,
                    dropStopName = headlineLeg.toStopName,
                    info = headlineInfo,
                    fallbackEta = headlineLine?.let { realtimeSummaries["${it.lineId}_${it.toStopOrder}"] },
                    nextLineName = nextLine?.lineName
                )
                HeadlineSection(
                    groupId = group.id,
                    legIndex = activeLegIndex,
                    line = headlineLine,
                    info = headlineInfo,
                    phase = phase,
                    headline = headline,
                    isSignalLost = phase == LegPhase.ON_BOARD && session?.isSignalLost == true,
                    actions = {
                        CardActions(
                            groupId = group.id,
                            phase = phase,
                            isLastLeg = activeLegIndex >= group.legs.lastIndex,
                            showTrajectory = !isMultiLeg,
                            onStartTracking = onStartTracking,
                            onStopTracking = onStopTracking,
                            onConfirmBoarded = onConfirmBoarded,
                            onSwitchToNextLeg = onSwitchToNextLeg,
                            onTrajectoryClick = { headlineLine?.let(onLineClick) }
                        )
                    }
                )
            }

            if (isMultiLeg) {
                val rideStops = selectedLines.map { reliableRideStops(it) }
                val walks = group.legs.map { it.walkingDistanceMeters }
                Column(modifier = Modifier.fillMaxWidth()) {
                    group.legs.forEachIndexed { index, leg ->
                        if (index > 0 && leg.walkingDistanceMeters > 0) {
                            TimelineWalkSegment(walkMeters = leg.walkingDistanceMeters)
                        }
                        val state = when {
                            !isTracking -> LegTimelineState.UPCOMING
                            index < activeLegIndex -> LegTimelineState.DONE
                            index == activeLegIndex ->
                                if (phase == LegPhase.ON_BOARD) LegTimelineState.ON_BOARD else LegTimelineState.WAITING
                            else -> LegTimelineState.UPCOMING
                        }
                        val line = selectedLines[index]
                        val base = rideStopsText(line)
                        val rideNote = when (state) {
                            LegTimelineState.DONE -> "$base · 已下车"
                            LegTimelineState.WAITING -> "$base · 在等车"
                            LegTimelineState.ON_BOARD -> onBoardRideNote(rideStops[index], session)
                            LegTimelineState.UPCOMING -> {
                                val extra = if (index == 0) {
                                    "在 ${leg.toStopName} 下车"
                                } else if (
                                    isTracking && index == activeLegIndex + 1 && session != null &&
                                    shouldShowNextLegLive(phase, session.stopsRemaining)
                                ) {
                                    nextLegLiveText(leg.fromStopName, line?.let { infoOf(index, it) })
                                } else {
                                    val anchorMinutes = when {
                                        !isTracking -> selectedLines.firstOrNull()?.let { infoOf(0, it) }?.knownMinutes()
                                        session == null || session.stopsRemaining < 0 -> null
                                        else -> etaMinutesOf(session.etaSeconds, session.stopsRemaining)
                                    }
                                    boardTimeTextForLeg(
                                        targetLegIndex = index,
                                        anchorLegIndex = if (isTracking) activeLegIndex else 0,
                                        anchorIsDropStop = phase == LegPhase.ON_BOARD,
                                        anchorMinutes = anchorMinutes,
                                        rideStopsPerLeg = rideStops,
                                        walkMetersPerLeg = walks,
                                        nowMillis = nowMillis
                                    )
                                }
                                if (extra != null) "$base · $extra" else base
                            }
                        }
                        LegTimelineItem(
                            legIndex = index,
                            leg = leg,
                            state = state,
                            rideNote = rideNote,
                            selectedLineId = line?.lineId,
                            realtimeInfoOf = { infoOf(index, it) },
                            onSelectCandidateLine = { lineId -> onSelectCandidateLine(index, lineId) },
                            onAddCandidateLine = { lineName -> onAddCandidateLine(index, lineName) },
                            onLineClick = onLineClick,
                            walkMetersBefore = leg.walkingDistanceMeters
                        )
                    }
                    TimelineEndStop(stopName = group.destinationStopName)
                }
            }
        }
    }
}

/** 乘车中这一程的小字："已坐 2 / 4 站 · 车上" */
private fun onBoardRideNote(totalStops: Int?, session: TrackingSessionState?): String {
    if (totalStops == null || session == null || session.boardStopOrder <= 0) return "车上"
    val ridden = (session.currentBusOrder - session.boardStopOrder).coerceIn(0, totalStops)
    return "已坐 $ridden / $totalStops 站 · 车上"
}

@Composable
private fun CardHeader(
    group: TransitGroup,
    summary: String,
    phaseLabel: String?,
    isTemporaryTracking: Boolean,
    onSaveTemporary: () -> Unit,
    onReverseClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = groupTitleText(group),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("group_title_${group.id}")
            )
            if (phaseLabel != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = phaseLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("phase_badge_${group.id}")
                    )
                }
            } else {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("group_summary_${group.id}")
                )
            }
        }
        CardMenu(
            groupId = group.id,
            isTemporary = group.isTemporary,
            onlySave = isTemporaryTracking,
            onSaveTemporary = onSaveTemporary,
            onReverseClick = onReverseClick,
            onEdit = onEdit,
            onDelete = onDelete
        )
    }
}

@Composable
private fun CardMenu(
    groupId: String,
    isTemporary: Boolean,
    onlySave: Boolean,
    onSaveTemporary: () -> Unit,
    onReverseClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("btn_more_$groupId")
        ) {
            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isTemporary || onlySave) {
                DropdownMenuItem(
                    text = { Text("收藏") },
                    leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSaveTemporary()
                    },
                    modifier = Modifier.testTag("btn_save_temp_$groupId")
                )
            }
            // 临时追踪（未保存）的卡片只留"收藏"
            if (onlySave) return@DropdownMenu
            DropdownMenuItem(
                text = { Text("生成返程") },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                onClick = {
                    expanded = false
                    onReverseClick()
                },
                modifier = Modifier.testTag("btn_reverse_$groupId")
            )
            DropdownMenuItem(
                text = { Text("编辑") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
                modifier = Modifier.testTag("btn_edit_$groupId")
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
                modifier = Modifier.testTag("btn_delete_$groupId")
            )
        }
    }
}

/** 分程进度条：每程一格，按 [fractions] 填充 */
@Composable
private fun LegStepper(fractions: List<Float>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadlineSection(
    groupId: String,
    legIndex: Int,
    line: CandidateLine?,
    info: CandidateLineRealtimeInfo?,
    phase: LegPhase?,
    headline: CardHeadline,
    isSignalLost: Boolean,
    actions: @Composable () -> Unit
) {
    val accent = if (headline.isSuccess) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.primary
    val content: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(modifier = contentModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (line != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = legLineColor(legIndex)) {
                        Text(
                            text = line.lineName,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (legIndex == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("selected_candidate_name")
                        )
                    }
                    val direction = info?.directionText?.ifBlank { null }
                        ?: line.endStopName.takeIf { it.isNotBlank() }?.let { "${it}方向" }
                    if (direction != null) {
                        Text(
                            text = "开往 $direction",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            val headlineText = buildAnnotatedString {
                if (headline.number != null) {
                    withStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Black, color = accent)) {
                        append(headline.number)
                    }
                    append(" ")
                    withStyle(
                        SpanStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        append(headline.text)
                    }
                } else {
                    withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent)) {
                        append(headline.text)
                    }
                }
            }
            Text(
                text = headlineText,
                modifier = Modifier.testTag("eta_headline_$groupId")
            )
            if (headline.detail != null) {
                Text(
                    text = headline.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("eta_detail_$groupId")
                )
            }
            if (isSignalLost) {
                Text(
                    text = "信号丢失，显示的是上次位置",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.extendedColors.warning,
                    modifier = Modifier.testTag("signal_lost_$groupId")
                )
            }
        }
    }

    if (phase == null) {
        // 未追踪：主数字在左，按钮在右
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content(Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            actions()
        }
    } else {
        // 追踪中：按钮放在主数字下面一行
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content(Modifier.fillMaxWidth())
            actions()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardActions(
    groupId: String,
    phase: LegPhase?,
    isLastLeg: Boolean,
    showTrajectory: Boolean,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConfirmBoarded: () -> Unit,
    onSwitchToNextLeg: () -> Unit,
    onTrajectoryClick: () -> Unit
) {
    if (phase == null) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onStartTracking,
                modifier = Modifier.testTag("btn_track_$groupId")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("开始追踪")
            }
            if (showTrajectory) {
                OutlinedButton(
                    onClick = onTrajectoryClick,
                    modifier = Modifier.testTag("btn_candidate_trajectory")
                ) {
                    Text("看轨迹")
                }
            }
        }
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (phase == LegPhase.WAITING_BOARD) {
            FilledTonalButton(
                onClick = onConfirmBoarded,
                modifier = Modifier.testTag("btn_confirm_boarded_$groupId")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("我已上车")
            }
        } else if (phase == LegPhase.ON_BOARD && !isLastLeg) {
            FilledTonalButton(
                onClick = onSwitchToNextLeg,
                modifier = Modifier.testTag("btn_next_leg_$groupId")
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("跳到下一程")
            }
        }
        Button(
            onClick = onStopTracking,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.testTag("btn_track_$groupId")
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("结束")
        }
        if (showTrajectory) {
            OutlinedButton(
                onClick = onTrajectoryClick,
                modifier = Modifier.testTag("btn_candidate_trajectory")
            ) {
                Text("看轨迹")
            }
        }
    }
}
