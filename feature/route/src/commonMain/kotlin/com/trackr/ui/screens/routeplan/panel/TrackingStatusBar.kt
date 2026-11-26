package com.trackr.ui.screens.routeplan.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_end
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.core.model.LegPhase
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_status_boarded
import com.trackr.ui.screens.routeplan.trackingPhaseOf
import com.trackr.ui.screens.routeplan.trackingStatusText
import org.jetbrains.compose.resources.stringResource

/** 规划页追踪状态条："第 1/2 程 · 等车 · 4 分钟" + 我已上车 / 结束 */
@Composable
internal fun TrackingStatusBar(
    session: GroupTrackingSessionState,
    onConfirmBoarded: () -> Unit,
    onStopTracking: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tracking_status_bar"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = trackingStatusText(session),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .testTag("tracking_status_text")
            )
            if (trackingPhaseOf(session) == LegPhase.WAITING_BOARD) {
                FilledTonalButton(
                    onClick = onConfirmBoarded,
                    modifier = Modifier.testTag("btn_plan_confirm_boarded")
                ) {
                    Text(stringResource(RouteRes.string.route_status_boarded))
                }
            }
            Button(
                onClick = onStopTracking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.testTag("btn_plan_stop_tracking")
            ) {
                Text(stringResource(Res.string.action_end))
            }
        }
    }
}
